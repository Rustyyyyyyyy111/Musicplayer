package com.example.data.telegram

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MusicPlayerApplication
import com.example.core.model.DownloadStatus
import com.example.core.model.MusicSource
import com.example.core.model.Track
import com.example.core.util.ActivityLogger
import com.example.data.local.DownloadCacheDao
import com.example.data.local.DownloadTaskEntity
import com.example.data.local.TrackDao
import com.example.data.storage.UnifiedStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TelegramMusicRepository(
    private val context: Context,
    private val core: TelegramCore,
    private val chatBrowser: TelegramChatBrowser,
    private val messageScanner: TelegramMessageScanner,
    private val trackDao: TrackDao,
    private val downloadCacheDao: DownloadCacheDao,
    private val storageManager: UnifiedStorageManager,
    private val logger: ActivityLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()

    fun scheduleBackgroundSync(enabled: Boolean) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(SYNC_WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<TelegramSyncWorker>(6, TimeUnit.HOURS).build()
            wm.enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }.onFailure { err ->
            logger.log("WORK_MANAGER", "WARN", "Bỏ qua lập lịch đồng bộ nền: ${err.message}")
        }
    }

    suspend fun syncSelectedSources(fullRescan: Boolean = false): Int {
        return messageScanner.scanSelectedSources(fullRescan)
    }

    suspend fun scanSingleChat(chatId: Long): Int {
        return messageScanner.scanChat(chatId, fullRescan = false)
    }

    fun startOfflineDownload(track: Track) {
        val fileId = track.telegramFileId ?: return
        val taskId = "dl_${track.id}"
        activeDownloadJobs[taskId]?.cancel()

        val job = scope.launch {
            trackDao.updateDownloadState(track.id, DownloadStatus.DOWNLOADING.name, 0f, null)
            downloadCacheDao.upsertDownload(
                DownloadTaskEntity(
                    id = taskId,
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    source = MusicSource.TELEGRAM.name,
                    status = DownloadStatus.DOWNLOADING.name,
                    bytesDownloaded = 0L,
                    totalBytes = track.fileSizeBytes,
                    destinationPath = "${storageManager.getDisplayRootPath()}Telegram/${track.fileName}",
                    errorMessage = null,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )

            core.sendQuery(TdApi.DownloadFile(fileId, 16, 0L, 0L, false)).onFailure { err ->
                markFailed(taskId, track, err.message ?: "Lỗi bắt đầu tải Telegram")
                return@launch
            }

            var stallCounter = 0
            var lastBytes = 0L
            while (true) {
                val tdFile = core.sendQuery(TdApi.GetFile(fileId)).getOrNull()
                if (tdFile == null) {
                    delay(400L)
                    continue
                }
                val total = tdFile.size.takeIf { it > 0 } ?: tdFile.expectedSize.coerceAtLeast(track.fileSizeBytes)
                val downloaded = tdFile.local.downloadedSize
                val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f

                trackDao.updateDownloadState(track.id, DownloadStatus.DOWNLOADING.name, progress, null)
                downloadCacheDao.upsertDownload(
                    DownloadTaskEntity(
                        id = taskId,
                        trackId = track.id,
                        title = track.title,
                        artist = track.artist,
                        source = MusicSource.TELEGRAM.name,
                        status = DownloadStatus.DOWNLOADING.name,
                        bytesDownloaded = downloaded,
                        totalBytes = total,
                        destinationPath = "${storageManager.getDisplayRootPath()}Telegram/${track.fileName}",
                        errorMessage = null,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )

                if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotBlank()) {
                    val srcFile = File(tdFile.local.path)
                    val mime = "audio/${track.format.extension}"
                    val savedPath = storageManager.saveAudioToUnifiedStorage(
                        source = MusicSource.TELEGRAM,
                        fileName = track.fileName.ifBlank { "${track.title}.${track.format.extension}" },
                        mimeType = mime
                    ) { srcFile.inputStream() }

                    if (savedPath != null) {
                        trackDao.updateDownloadState(track.id, DownloadStatus.COMPLETED.name, 1f, savedPath)
                        downloadCacheDao.upsertDownload(
                            DownloadTaskEntity(
                                id = taskId,
                                trackId = track.id,
                                title = track.title,
                                artist = track.artist,
                                source = MusicSource.TELEGRAM.name,
                                status = DownloadStatus.COMPLETED.name,
                                bytesDownloaded = total,
                                totalBytes = total,
                                destinationPath = savedPath,
                                errorMessage = null,
                                updatedAtEpochMs = System.currentTimeMillis()
                            )
                        )
                        logger.log("DOWNLOAD", "INFO", "Tải offline thành công: ${track.title}")
                    } else {
                        markFailed(taskId, track, "Không thể ghi tệp vào thư mục Music Player/Telegram")
                    }
                    break
                }

                if (downloaded == lastBytes) {
                    stallCounter++
                    // MelodyRide v1.2.18 auto-recovery for stalled downloads
                    if (stallCounter >= 25) {
                        logger.log("DOWNLOAD", "WARN", "Tải '${track.title}' bị đứng, đang tự khôi phục...")
                        core.sendQuery(TdApi.CancelDownloadFile(fileId, false))
                        core.sendQuery(TdApi.DownloadFile(fileId, 16, 0L, 0L, false))
                        stallCounter = 0
                    }
                } else {
                    lastBytes = downloaded
                    stallCounter = 0
                }
                delay(350L)
            }
        }
        activeDownloadJobs[taskId] = job
    }

    fun pauseDownload(track: Track) {
        val taskId = "dl_${track.id}"
        activeDownloadJobs.remove(taskId)?.cancel()
        val fileId = track.telegramFileId ?: return
        scope.launch {
            core.sendQuery(TdApi.CancelDownloadFile(fileId, false))
            trackDao.updateDownloadState(track.id, DownloadStatus.PAUSED.name, track.downloadProgress, null)
            downloadCacheDao.getDownloadById(taskId)?.let { prev ->
                downloadCacheDao.upsertDownload(prev.copy(status = DownloadStatus.PAUSED.name, updatedAtEpochMs = System.currentTimeMillis()))
            }
        }
    }

    fun cancelDownload(track: Track) {
        val taskId = "dl_${track.id}"
        activeDownloadJobs.remove(taskId)?.cancel()
        val fileId = track.telegramFileId ?: return
        scope.launch {
            core.sendQuery(TdApi.CancelDownloadFile(fileId, true))
            trackDao.updateDownloadState(track.id, DownloadStatus.NONE.name, 0f, null)
            downloadCacheDao.deleteDownload(taskId)
        }
    }

    /**
     * Deletes ONLY the local downloaded copy; never calls any Telegram deletion API.
     */
    suspend fun deleteLocalCopyOnly(track: Track) {
        storageManager.deleteLocalFile(track.localPath)
        trackDao.updateDownloadState(track.id, DownloadStatus.NONE.name, 0f, null)
        downloadCacheDao.deleteDownload("dl_${track.id}")
        logger.log("STORAGE", "INFO", "Đã xóa bản cục bộ của '${track.title}' (giữ nguyên tin nhắn Telegram)")
    }

    private suspend fun markFailed(taskId: String, track: Track, message: String) {
        trackDao.updateDownloadState(track.id, DownloadStatus.FAILED.name, 0f, null)
        downloadCacheDao.upsertDownload(
            DownloadTaskEntity(
                id = taskId,
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                source = MusicSource.TELEGRAM.name,
                status = DownloadStatus.FAILED.name,
                bytesDownloaded = 0L,
                totalBytes = track.fileSizeBytes,
                destinationPath = "",
                errorMessage = message,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        logger.log("DOWNLOAD", "ERROR", "Tải '${track.title}' thất bại: $message")
    }

    companion object {
        private const val SYNC_WORK_NAME = "telegram_periodic_sync"
    }
}

class TelegramSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? MusicPlayerApplication ?: return Result.success()
        return runCatching {
            app.container.telegramMusicRepository.syncSelectedSources(fullRescan = false)
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
