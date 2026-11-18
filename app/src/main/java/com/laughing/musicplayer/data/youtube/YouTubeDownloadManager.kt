package com.laughing.musicplayer.data.youtube

import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.AudioFormatDetector
import com.laughing.musicplayer.data.local.DownloadCacheDao
import com.laughing.musicplayer.data.local.DownloadTaskEntity
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.storage.UnifiedStorageManager
import com.laughing.musicplayer.integrations.NetworkProxyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class YouTubeDownloadManager(
    private val youTubeRepository: YouTubeMusicRepository,
    private val proxyManager: NetworkProxyManager,
    private val trackDao: TrackDao,
    private val downloadCacheDao: DownloadCacheDao,
    private val storageManager: UnifiedStorageManager,
    private val logger: ActivityLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val downloadSemaphore = Semaphore(3)

    fun downloadPlaylist(tracks: List<Track>): Int {
        val eligible = tracks.filter {
            it.source == MusicSource.YOUTUBE_MUSIC &&
                it.downloadStatus != DownloadStatus.COMPLETED &&
                it.downloadStatus != DownloadStatus.DOWNLOADING
        }
        eligible.forEach { track -> downloadTrack(track) }
        return eligible.size
    }

    fun downloadTrack(track: Track) {
        val taskId = "dl_${track.id}"
        activeJobs[taskId]?.cancel()

        val job = scope.launch {
            trackDao.updateDownloadState(track.id, DownloadStatus.QUEUED.name, 0f, null)
            downloadSemaphore.withPermit {
                trackDao.updateDownloadState(track.id, DownloadStatus.DOWNLOADING.name, 0f, null)
                val resolved = youTubeRepository.resolveAudioStreamUrl(
                    videoUrlOrId = track.youtubeVideoId ?: track.streamUri ?: return@withPermit,
                    allowRetry = true
                ).getOrElse { err ->
                    markFailed(taskId, track, err.message ?: "Không lấy được luồng âm thanh")
                    return@withPermit
                }

                val ext = resolved.format.extension
                val targetName = storageManager.sanitizeFileName("${track.artist} - ${track.title}.$ext")
                val tempFile = File.createTempFile("yt_dl_", ".$ext")

                val downloadResult = runDownloadWithSingleRetry(
                    taskId = taskId,
                    track = track,
                    streamUrl = resolved.streamUrl,
                    targetName = targetName,
                    tempFile = tempFile
                )

                downloadResult.onSuccess { downloaded ->
                    val savedPath = storageManager.saveAudioToUnifiedStorage(
                        source = MusicSource.YOUTUBE_MUSIC,
                        fileName = targetName,
                        mimeType = resolved.mimeType
                    ) { tempFile.inputStream() }

                    if (savedPath != null) {
                        val probed = AudioFormatDetector.probeAudioSpecs(tempFile, resolved.format)
                        trackDao.updateDownloadState(track.id, DownloadStatus.COMPLETED.name, 1f, savedPath)
                        trackDao.updateTrackAudioSpecs(
                            trackId = track.id,
                            format = resolved.format.name,
                            fileName = targetName,
                            artworkUri = resolved.artworkUrl,
                            bitrateKbps = probed.bitrateKbps.takeIf { it > 0 } ?: resolved.bitrateKbps,
                            sampleRateHz = probed.sampleRateHz.takeIf { it > 0 } ?: resolved.sampleRateHz,
                            bitDepth = probed.bitDepth,
                            durationMs = probed.durationMs.takeIf { it > 0 } ?: resolved.durationMs
                        )
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
                        logger.log("YT_DL", "INFO", "Tải về thành công: ${track.title}")
                    } else {
                        markFailed(taskId, track, "Không thể ghi vào /sdcard/Music/Musicplayer/")
                    }
                }.onFailure { err ->
                    if (err !is kotlinx.coroutines.CancellationException) {
                        markFailed(taskId, track, err.message ?: "Lỗi tải về")
                    }
                }
                tempFile.delete()
            }
        }
        activeJobs[taskId] = job
    }

    private suspend fun runDownloadWithSingleRetry(
        taskId: String,
        track: Track,
        streamUrl: String,
        targetName: String,
        tempFile: File
    ): Result<Long> {
        val firstAttempt = executeStreamDownload(taskId, track, streamUrl, targetName, tempFile)
        val firstErr = firstAttempt.exceptionOrNull() ?: return firstAttempt
        if (firstErr is kotlinx.coroutines.CancellationException) return firstAttempt
        logger.log("YT_DL", "WARN", "Luồng tải '${track.title}' bị gián đoạn, đang thử lại 1 lần...")
        delay(250L)
        return executeStreamDownload(taskId, track, streamUrl, targetName, tempFile)
    }

    private suspend fun executeStreamDownload(
        taskId: String,
        track: Track,
        streamUrl: String,
        targetName: String,
        tempFile: File
    ): Result<Long> = runCatching {
        val job = currentCoroutineContext()[Job]
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", YouTubeSessionCookieManager.DEFAULT_USER_AGENT)
            .build()
        val call = proxyManager.getStreamHttpClient().newCall(request)
        val registration = job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("Empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: 1L
                var downloaded = 0L
                val buffer = ByteArray(64 * 1024)

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { out ->
                        var read: Int
                        var lastNotify = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            if (!currentCoroutineContext().isActive) {
                                throw kotlinx.coroutines.CancellationException()
                            }
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
                                        destinationPath = "${storageManager.getDisplayRootPath()}$targetName",
                                        errorMessage = null,
                                        updatedAtEpochMs = now
                                    )
                                )
                            }
                        }
                    }
                }
                downloaded
            }
        } finally {
            registration?.dispose()
        }
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
        logger.log("STORAGE", "INFO", "Đã xóa tệp đã lưu: ${track.title}")
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
