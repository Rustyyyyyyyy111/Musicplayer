package com.laughing.musicplayer.data.youtube

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.laughing.musicplayer.MusicPlayerApplication
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.util.AudioFormatDetector
import com.laughing.musicplayer.data.local.DownloadTaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object YouTubeDownloadNotificationHelper {
    const val DOWNLOAD_CHANNEL_ID = "youtube_downloads"
    private const val DOWNLOAD_CHANNEL_NAME = "Tải nhạc YouTube"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(DOWNLOAD_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    DOWNLOAD_CHANNEL_ID,
                    DOWNLOAD_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Hiển thị tiến độ tải xuống âm thanh từ YouTube"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun progressNotificationIdFor(trackId: String): Int {
        return 10_000 + (trackId.hashCode() and 0x7FFF)
    }

    fun completionNotificationIdFor(trackId: String): Int {
        return 110_000 + (trackId.hashCode() and 0x7FFF)
    }

    fun buildProgressNotification(
        context: Context,
        title: String,
        progressPercent: Int,
        indeterminate: Boolean = false
    ): Notification {
        ensureChannel(context)
        val clamped = progressPercent.coerceIn(0, 100)
        return NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Đang chuẩn bị tải..." else "Đang tải về • $clamped%")
            .setSubText("$clamped%")
            .setProgress(100, clamped, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun buildCompletedNotification(
        context: Context,
        title: String
    ): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("Download complete")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    fun postCompletedNotification(context: Context, trackId: String, title: String) {
        if (!canPostNotifications(context)) return
        val notification = buildCompletedNotification(context, title)
        runCatching {
            NotificationManagerCompat.from(context).notify(completionNotificationIdFor(trackId), notification)
        }
    }

    fun cancelNotifications(context: Context, trackId: String) {
        runCatching {
            val nm = NotificationManagerCompat.from(context)
            nm.cancel(progressNotificationIdFor(trackId))
            nm.cancel(completionNotificationIdFor(trackId))
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}

class YouTubeDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trackId = inputData.getString(KEY_TRACK_ID)?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE)?.takeIf { it.isNotBlank() } ?: "YouTube Audio"
        val artist = inputData.getString(KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: "YouTube"
        val album = inputData.getString(KEY_ALBUM)?.takeIf { it.isNotBlank() } ?: "YouTube"
        val durationHintMs = inputData.getLong(KEY_DURATION_MS, 0L)
        val videoUrlOrId = inputData.getString(KEY_VIDEO_URL_OR_ID)?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure()

        val app = applicationContext as? MusicPlayerApplication
            ?: return@withContext Result.failure()
        val container = app.container
        val trackDao = container.database.trackDao()
        val downloadCacheDao = container.database.downloadCacheDao()
        val storageManager = container.storageManager
        val youTubeRepository = container.youTubeMusicRepository
        val offlineRepository = container.offlineMusicRepository
        val proxyManager = container.networkProxyManager
        val logger = container.activityLogger

        val taskId = "dl_$trackId"
        val notificationId = YouTubeDownloadNotificationHelper.progressNotificationIdFor(trackId)

        var tempFile: File? = null
        try {
            updateForegroundSafely(
                notificationId = notificationId,
                notification = YouTubeDownloadNotificationHelper.buildProgressNotification(
                    context = applicationContext,
                    title = title,
                    progressPercent = 0,
                    indeterminate = true
                )
            )
            trackDao.updateDownloadState(trackId, DownloadStatus.DOWNLOADING.name, 0f, null)

            val resolved = youTubeRepository.resolveAudioStreamUrl(
                videoUrlOrId = videoUrlOrId,
                allowRetry = true
            ).getOrElse { err ->
                markFailed(taskId, trackId, title, artist, err.message ?: "Không lấy được luồng âm thanh", container)
                return@withContext Result.failure()
            }

            val ext = resolved.format.extension
            val targetName = storageManager.sanitizeFileName("$artist - $title.$ext")
            val scratch = File.createTempFile("yt_dl_", ".$ext", applicationContext.cacheDir)
            tempFile = scratch

            val streamResult = runDownloadWithSingleRetry(
                taskId = taskId,
                trackId = trackId,
                title = title,
                artist = artist,
                streamUrl = resolved.streamUrl,
                targetName = targetName,
                tempFile = scratch,
                notificationId = notificationId,
                container = container
            )

            val downloadedBytes = streamResult.getOrElse { err ->
                if (err is CancellationException || isStopped) throw CancellationException()
                markFailed(taskId, trackId, title, artist, err.message ?: "Lỗi tải về", container)
                return@withContext Result.failure()
            }

            if (isStopped || !currentCoroutineContext().isActive) {
                throw CancellationException()
            }

            val probed = AudioFormatDetector.probeAudioSpecs(scratch, resolved.format)
            val finalDurationMs = probed.durationMs.takeIf { it > 0L }
                ?: resolved.durationMs.takeIf { it > 0L }
                ?: durationHintMs

            val savedPath = storageManager.saveYouTubeAudioToStorage(
                fileName = targetName,
                mimeType = resolved.mimeType,
                title = title,
                artist = artist,
                album = album,
                durationMs = finalDurationMs
            ) { scratch.inputStream() }

            if (savedPath == null) {
                markFailed(
                    taskId = taskId,
                    trackId = trackId,
                    title = title,
                    artist = artist,
                    message = "Không thể ghi vào ${storageManager.getYouTubeDisplayPath()}",
                    container = container
                )
                return@withContext Result.failure()
            }

            trackDao.updateDownloadState(trackId, DownloadStatus.COMPLETED.name, 1f, savedPath)
            trackDao.updateTrackAudioSpecs(
                trackId = trackId,
                format = resolved.format.name,
                fileName = targetName,
                artworkUri = resolved.artworkUrl,
                bitrateKbps = probed.bitrateKbps.takeIf { it > 0 } ?: resolved.bitrateKbps,
                sampleRateHz = probed.sampleRateHz.takeIf { it > 0 } ?: resolved.sampleRateHz,
                bitDepth = probed.bitDepth,
                durationMs = finalDurationMs
            )
            downloadCacheDao.upsertDownload(
                DownloadTaskEntity(
                    id = taskId,
                    trackId = trackId,
                    title = title,
                    artist = artist,
                    source = MusicSource.YOUTUBE_MUSIC.name,
                    status = DownloadStatus.COMPLETED.name,
                    bytesDownloaded = downloadedBytes,
                    totalBytes = downloadedBytes,
                    destinationPath = savedPath,
                    errorMessage = null,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )

            // Automatically sync Offline tab once saved + scanned
            runCatching { offlineRepository.indexLocalLibrary() }

            YouTubeDownloadNotificationHelper.postCompletedNotification(
                context = applicationContext,
                trackId = trackId,
                title = title
            )
            logger.log("YT_DL", "INFO", "Tải về thành công: $title -> $savedPath")
            Result.success()
        } catch (cancel: CancellationException) {
            YouTubeDownloadNotificationHelper.cancelNotifications(applicationContext, trackId)
            runCatching {
                trackDao.updateDownloadState(trackId, DownloadStatus.NONE.name, 0f, null)
                downloadCacheDao.deleteDownload(taskId)
            }
            logger.log("YT_DL", "INFO", "Đã hủy tải về: $title")
            throw cancel
        } finally {
            tempFile?.delete()
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun runDownloadWithSingleRetry(
        taskId: String,
        trackId: String,
        title: String,
        artist: String,
        streamUrl: String,
        targetName: String,
        tempFile: File,
        notificationId: Int,
        container: com.laughing.musicplayer.AppContainer
    ): kotlin.Result<Long> {
        val first = executeStreamDownload(
            taskId = taskId,
            trackId = trackId,
            title = title,
            artist = artist,
            streamUrl = streamUrl,
            targetName = targetName,
            tempFile = tempFile,
            notificationId = notificationId,
            container = container
        )
        val firstErr = first.exceptionOrNull() ?: return first
        if (firstErr is CancellationException || isStopped) return first
        container.activityLogger.log("YT_DL", "WARN", "Luồng tải '$title' bị gián đoạn, đang thử lại 1 lần...")
        delay(250L)
        return executeStreamDownload(
            taskId = taskId,
            trackId = trackId,
            title = title,
            artist = artist,
            streamUrl = streamUrl,
            targetName = targetName,
            tempFile = tempFile,
            notificationId = notificationId,
            container = container
        )
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun executeStreamDownload(
        taskId: String,
        trackId: String,
        title: String,
        artist: String,
        streamUrl: String,
        targetName: String,
        tempFile: File,
        notificationId: Int,
        container: com.laughing.musicplayer.AppContainer
    ): kotlin.Result<Long> = runCatching {
        val job = currentCoroutineContext()[Job]
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", YouTubeSessionCookieManager.DEFAULT_USER_AGENT)
            .build()
        val call = container.networkProxyManager.getStreamHttpClient().newCall(request)
        val registration = job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("Empty body")
                val total = body.contentLength().takeIf { it > 0L } ?: 1L
                var downloaded = 0L
                val buffer = ByteArray(64 * 1024)

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { out ->
                        var read: Int
                        var lastNotifyMs = 0L
                        var lastPercent = -1
                        while (input.read(buffer).also { read = it } != -1) {
                            if (isStopped || !currentCoroutineContext().isActive) {
                                call.cancel()
                                throw CancellationException()
                            }
                            out.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (now - lastNotifyMs >= 250L || (percent - lastPercent >= 2 && now - lastNotifyMs >= 100L)) {
                                lastNotifyMs = now
                                lastPercent = percent
                                val progressFloat = (percent / 100f).coerceIn(0f, 1f)
                                container.database.trackDao().updateDownloadState(
                                    trackId,
                                    DownloadStatus.DOWNLOADING.name,
                                    progressFloat,
                                    null
                                )
                                container.database.downloadCacheDao().upsertDownload(
                                    DownloadTaskEntity(
                                        id = taskId,
                                        trackId = trackId,
                                        title = title,
                                        artist = artist,
                                        source = MusicSource.YOUTUBE_MUSIC.name,
                                        status = DownloadStatus.DOWNLOADING.name,
                                        bytesDownloaded = downloaded,
                                        totalBytes = total,
                                        destinationPath = "${container.storageManager.getYouTubeDisplayPath()}$targetName",
                                        errorMessage = null,
                                        updatedAtEpochMs = now
                                    )
                                )
                                updateForegroundSafely(
                                    notificationId = notificationId,
                                    notification = YouTubeDownloadNotificationHelper.buildProgressNotification(
                                        context = applicationContext,
                                        title = title,
                                        progressPercent = percent,
                                        indeterminate = total <= 1L
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

    private suspend fun updateForegroundSafely(notificationId: Int, notification: Notification) {
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(notificationId, notification)
            }
            setForeground(info)
        } catch (err: Exception) {
            if (err is CancellationException) throw err
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun markFailed(
        taskId: String,
        trackId: String,
        title: String,
        artist: String,
        message: String,
        container: com.laughing.musicplayer.AppContainer
    ) {
        YouTubeDownloadNotificationHelper.cancelNotifications(applicationContext, trackId)
        container.database.trackDao().updateDownloadState(trackId, DownloadStatus.FAILED.name, 0f, null)
        container.database.downloadCacheDao().upsertDownload(
            DownloadTaskEntity(
                id = taskId,
                trackId = trackId,
                title = title,
                artist = artist,
                source = MusicSource.YOUTUBE_MUSIC.name,
                status = DownloadStatus.FAILED.name,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                destinationPath = "",
                errorMessage = message,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        container.activityLogger.log("YT_DL", "ERROR", "Tải YouTube thất bại '$title': $message")
    }

    companion object {
        const val KEY_TRACK_ID = "track_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_VIDEO_URL_OR_ID = "video_url_or_id"
        const val KEY_DURATION_MS = "duration_ms"

        fun uniqueWorkName(trackId: String): String = "yt_download_$trackId"

        fun buildInputData(
            trackId: String,
            title: String,
            artist: String,
            album: String,
            videoUrlOrId: String,
            durationMs: Long
        ): Data {
            return Data.Builder()
                .putString(KEY_TRACK_ID, trackId)
                .putString(KEY_TITLE, title)
                .putString(KEY_ARTIST, artist)
                .putString(KEY_ALBUM, album)
                .putString(KEY_VIDEO_URL_OR_ID, videoUrlOrId)
                .putLong(KEY_DURATION_MS, durationMs)
                .build()
        }
    }
}
