package com.laughing.musicplayer.data.telegram

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.laughing.musicplayer.MusicPlayerApplication
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.AudioFormatDetector
import com.laughing.musicplayer.data.local.DownloadCacheDao
import com.laughing.musicplayer.data.local.DownloadTaskEntity
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.storage.UnifiedStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TelegramMusicRepository(
    private val context: Context,
    private val core: TelegramCore,
    private val fileResolver: TelegramFileResolver,
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
        if (track.telegramFileId == null &&
            track.telegramRemoteFileId.isNullOrBlank() &&
            (track.telegramChatId == null || track.telegramMessageId == null)
        ) {
            return
        }
        val taskId = "dl_${track.id}"
        activeDownloadJobs[taskId]?.cancel()

        val job = scope.launch {
            var activeFileId = 0
            var completed = false
            try {
                trackDao.updateDownloadState(track.id, DownloadStatus.DOWNLOADING.name, 0f, null)
                val targetFileName = track.fileName.ifBlank { "${track.title}.${track.format.extension}" }
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
                        destinationPath = "${storageManager.getDisplayRootPath()}$targetFileName",
                        errorMessage = null,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )

                val resolved = fileResolver.resolveAndStartDownload(
                    target = TelegramFileTarget(
                        trackId = track.id,
                        fileId = track.telegramFileId ?: 0,
                        remoteFileId = track.telegramRemoteFileId,
                        chatId = track.telegramChatId,
                        messageId = track.telegramMessageId,
                        expectedSize = track.fileSizeBytes,
                        isVoiceMessage = track.isVoiceMessage,
                        format = track.format
                    ),
                    priority = 16,
                    offset = 0L,
                    limit = 0L
                ).getOrElse { err ->
                    markFailed(taskId, track, err.message ?: "Lỗi bắt đầu tải Telegram")
                    return@launch
                }
                activeFileId = resolved.fileId

                var lastBytes = -1L
                var lastProgressAtMs = System.currentTimeMillis()
                var retriedStall = false
                val maxStallMs = 7_500L

                while (isActive) {
                    val tdFile = core.sendQuery(TdApi.GetFile(activeFileId), timeoutMs = 4_500L).getOrNull()
                    if (tdFile == null) {
                        val elapsed = System.currentTimeMillis() - lastProgressAtMs
                        if (elapsed >= maxStallMs) {
                            markFailed(taskId, track, "Mất kết nối máy chủ Telegram (quá 7.5 giây)")
                            break
                        }
                        if (!retriedStall && elapsed >= 3_500L) {
                            retriedStall = true
                            logger.log("DOWNLOAD", "WARN", "Tải '${track.title}' mất phản hồi, đang tự thử lại 1 lần...")
                            core.sendQuery(TdApi.DownloadFile(activeFileId, 16, 0L, 0L, false), timeoutMs = 4_500L)
                        }
                        delay(350L)
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
                            destinationPath = "${storageManager.getDisplayRootPath()}$targetFileName",
                            errorMessage = null,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )

                    if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotBlank()) {
                        val srcFile = File(tdFile.local.path)
                        if (!storageManager.isValidAudioFile(srcFile, total)) {
                            if (System.currentTimeMillis() - lastProgressAtMs >= maxStallMs) {
                                markFailed(taskId, track, "Tệp Telegram tải về bị lỗi hoặc không đầy đủ")
                                break
                            }
                            delay(250L)
                            continue
                        }
                        val mime = "audio/${track.format.extension}"
                        val probed = AudioFormatDetector.probeAudioSpecs(srcFile, track.format)
                        val embeddedArtPath = probed.embeddedArtwork?.let { bytes ->
                            storageManager.saveArtworkFile(track.id, bytes)
                        }
                        val savedPath = storageManager.saveAudioToUnifiedStorage(
                            source = MusicSource.TELEGRAM,
                            fileName = targetFileName,
                            mimeType = mime
                        ) { srcFile.inputStream() }

                        if (savedPath != null) {
                            val remoteId = tdFile.remote?.id?.takeIf { it.isNotBlank() } ?: resolved.remoteFileId
                            val uniqueId = tdFile.remote?.uniqueId?.takeIf { it.isNotBlank() } ?: resolved.remoteUniqueId
                            trackDao.updateVerifiedLocalAudio(
                                trackId = track.id,
                                localPath = savedPath,
                                status = DownloadStatus.COMPLETED.name,
                                progress = 1f,
                                verifiedAtMs = System.currentTimeMillis(),
                                fileId = tdFile.id,
                                remoteFileId = remoteId,
                                remoteUniqueId = uniqueId
                            )
                            trackDao.updateTrackMetadata(
                                trackId = track.id,
                                artworkUri = embeddedArtPath,
                                bitrateKbps = probed.bitrateKbps,
                                sampleRateHz = probed.sampleRateHz,
                                bitDepth = probed.bitDepth,
                                durationMs = probed.durationMs
                            )
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
                            completed = true
                            logger.log("DOWNLOAD", "INFO", "Tải về thành công: ${track.title}")
                        } else {
                            markFailed(taskId, track, "Không thể ghi tệp vào thư mục /sdcard/Music/Musicplayer/")
                        }
                        break
                    }

                    if (downloaded > lastBytes) {
                        lastBytes = downloaded
                        lastProgressAtMs = System.currentTimeMillis()
                        retriedStall = false
                    } else {
                        val stallMs = System.currentTimeMillis() - lastProgressAtMs
                        if (stallMs >= maxStallMs) {
                            markFailed(taskId, track, "Hết thời gian tải '${track.title}' (luồng Telegram bị treo)")
                            break
                        } else if (!retriedStall && stallMs >= 3_500L) {
                            retriedStall = true
                            logger.log("DOWNLOAD", "WARN", "Tải '${track.title}' bị đứng, đang tự khôi phục 1 lần...")
                            core.sendQuery(TdApi.CancelDownloadFile(activeFileId, false), timeoutMs = 3_000L)
                            core.sendQuery(TdApi.DownloadFile(activeFileId, 16, 0L, 0L, false), timeoutMs = 4_500L)
                        }
                    }
                    delay(350L)
                }
            } catch (_: CancellationException) {
                if (activeFileId > 0 && !completed) {
                    core.cancelDownloadAsync(activeFileId, onlyIfPending = false)
                }
            } finally {
                activeDownloadJobs.remove(taskId)
            }
        }
        activeDownloadJobs[taskId] = job
    }

    fun pauseDownload(track: Track) {
        val taskId = "dl_${track.id}"
        activeDownloadJobs.remove(taskId)?.cancel()
        scope.launch {
            val fileId = trackDao.getTrackById(track.id)?.telegramFileId ?: track.telegramFileId
            if (fileId != null && fileId > 0) {
                core.sendQuery(TdApi.CancelDownloadFile(fileId, false))
            }
            trackDao.updateDownloadState(track.id, DownloadStatus.PAUSED.name, track.downloadProgress, null)
            downloadCacheDao.getDownloadById(taskId)?.let { prev ->
                downloadCacheDao.upsertDownload(prev.copy(status = DownloadStatus.PAUSED.name, updatedAtEpochMs = System.currentTimeMillis()))
            }
        }
    }

    fun cancelDownload(track: Track) {
        val taskId = "dl_${track.id}"
        activeDownloadJobs.remove(taskId)?.cancel()
        scope.launch {
            val fileId = trackDao.getTrackById(track.id)?.telegramFileId ?: track.telegramFileId
            if (fileId != null && fileId > 0) {
                core.sendQuery(TdApi.CancelDownloadFile(fileId, true))
            }
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
