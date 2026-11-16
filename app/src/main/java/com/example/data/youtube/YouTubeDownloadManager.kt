package com.example.data.youtube

import com.example.core.model.DownloadStatus
import com.example.core.model.MusicSource
import com.example.core.model.Track
import com.example.core.util.ActivityLogger
import com.example.data.local.DownloadCacheDao
import com.example.data.local.DownloadTaskEntity
import com.example.data.local.TrackDao
import com.example.data.storage.UnifiedStorageManager
import com.example.integrations.ScrobbleAndProxyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class YouTubeDownloadManager(
    private val youTubeRepository: YouTubeMusicRepository,
    private val proxyManager: ScrobbleAndProxyManager,
    private val trackDao: TrackDao,
    private val downloadCacheDao: DownloadCacheDao,
    private val storageManager: UnifiedStorageManager,
    private val logger: ActivityLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun downloadTrack(track: Track) {
        val taskId = "dl_${track.id}"
        activeJobs[taskId]?.cancel()

        val job = scope.launch {
            trackDao.updateDownloadState(track.id, DownloadStatus.DOWNLOADING.name, 0f, null)
            val resolved = youTubeRepository.resolveAudioStreamUrl(
                track.youtubeVideoId ?: track.streamUri ?: return@launch
            ).getOrElse { err ->
                markFailed(taskId, track, err.message ?: "Không lấy được luồng âm thanh")
                return@launch
            }

            val ext = resolved.format.extension
            val targetName = storageManager.sanitizeFileName("${track.artist} - ${track.title}.$ext")
            val tempFile = File.createTempFile("yt_dl_", ".$ext")

            runCatching {
                val request = Request.Builder()
                    .url(resolved.streamUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                proxyManager.getHttpClient().newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("Empty body")
                    val total = body.contentLength().takeIf { it > 0 } ?: 1L
                    var downloaded = 0L
                    val buffer = ByteArray(16 * 1024)

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { out ->
                            var read: Int
                            var lastNotify = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                if (!isActive) throw kotlinx.coroutines.CancellationException()
                                out.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastNotify > 250L) {
                                    lastNotify = now
                                    val progress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                                    trackDao.updateDownloadState(track.id, DownloadStatus.DOWNLOADING.name, progress, null)
                                    downloadCacheDao.upsertDownload(
                                        DownloadTaskEntity(
                                            id = taskId,
                                            trackId = track.id,
                                            title = track.title,
                                            artist = track.artist,
                                            source = MusicSource.YOUTUBE_MUSIC.name,
                                            status = DownloadStatus.DOWNLOADING.name,
                                            bytesDownloaded = downloaded,
                                            totalBytes = total,
                                            destinationPath = "${storageManager.getDisplayRootPath()}YouTube/$targetName",
                                            errorMessage = null,
                                            updatedAtEpochMs = now
                                        )
                                    )
                                }
                            }
                        }
                    }

                    val savedPath = storageManager.saveAudioToUnifiedStorage(
                        source = MusicSource.YOUTUBE_MUSIC,
                        fileName = targetName,
                        mimeType = resolved.mimeType
                    ) { tempFile.inputStream() }

                    if (savedPath != null) {
                        trackDao.updateDownloadState(track.id, DownloadStatus.COMPLETED.name, 1f, savedPath)
                        downloadCacheDao.upsertDownload(
                            DownloadTaskEntity(
                                id = taskId,
                                trackId = track.id,
                                title = track.title,
                                artist = track.artist,
                                source = MusicSource.YOUTUBE_MUSIC.name,
                                status = DownloadStatus.COMPLETED.name,
                                bytesDownloaded = downloaded,
                                totalBytes = downloaded,
                                destinationPath = savedPath,
                                errorMessage = null,
                                updatedAtEpochMs = System.currentTimeMillis()
                            )
                        )
                        logger.log("YT_DL", "INFO", "Tải YouTube Music thành công: ${track.title}")
                    } else {
                        markFailed(taskId, track, "Không thể ghi vào Music Player/YouTube")
                    }
                }
            }.onFailure { err ->
                if (err !is kotlinx.coroutines.CancellationException) {
                    markFailed(taskId, track, err.message ?: "Lỗi tải xuống")
                }
            }
            tempFile.delete()
        }
        activeJobs[taskId] = job
    }

    fun cancelDownload(track: Track) {
        val taskId = "dl_${track.id}"
        activeJobs.remove(taskId)?.cancel()
        scope.launch {
            trackDao.updateDownloadState(track.id, DownloadStatus.NONE.name, 0f, null)
            downloadCacheDao.deleteDownload(taskId)
        }
    }

    suspend fun deleteLocalCopy(track: Track) {
        storageManager.deleteLocalFile(track.localPath)
        trackDao.updateDownloadState(track.id, DownloadStatus.NONE.name, 0f, null)
        downloadCacheDao.deleteDownload("dl_${track.id}")
        logger.log("STORAGE", "INFO", "Đã xóa tệp YouTube offline: ${track.title}")
    }

    private suspend fun markFailed(taskId: String, track: Track, message: String) {
        trackDao.updateDownloadState(track.id, DownloadStatus.FAILED.name, 0f, null)
        downloadCacheDao.upsertDownload(
            DownloadTaskEntity(
                id = taskId,
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                source = MusicSource.YOUTUBE_MUSIC.name,
                status = DownloadStatus.FAILED.name,
                bytesDownloaded = 0L,
                totalBytes = track.fileSizeBytes,
                destinationPath = "",
                errorMessage = message,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        logger.log("YT_DL", "ERROR", "Tải YouTube thất bại '${track.title}': $message")
    }
}
