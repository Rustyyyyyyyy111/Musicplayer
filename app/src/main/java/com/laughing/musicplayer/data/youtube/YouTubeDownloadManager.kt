package com.laughing.musicplayer.data.youtube

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.data.local.DownloadCacheDao
import com.laughing.musicplayer.data.local.DownloadTaskEntity
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.local.toEntity
import com.laughing.musicplayer.data.storage.UnifiedStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YouTubeDownloadManager(
    private val context: Context,
    private val trackDao: TrackDao,
    private val downloadCacheDao: DownloadCacheDao,
    private val storageManager: UnifiedStorageManager,
    private val logger: ActivityLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun downloadPlaylist(tracks: List<Track>): Int {
        val eligible = tracks.filter {
            it.source == MusicSource.YOUTUBE_MUSIC &&
                it.downloadStatus != DownloadStatus.COMPLETED &&
                it.downloadStatus != DownloadStatus.DOWNLOADING &&
                it.downloadStatus != DownloadStatus.QUEUED
        }
        eligible.forEach { track -> downloadTrack(track) }
        return eligible.size
    }

    fun downloadTrack(track: Track) {
        if (track.source != MusicSource.YOUTUBE_MUSIC) return
        if (track.downloadStatus == DownloadStatus.COMPLETED ||
            track.downloadStatus == DownloadStatus.DOWNLOADING ||
            track.downloadStatus == DownloadStatus.QUEUED
        ) {
            return
        }
        val videoUrlOrId = track.youtubeVideoId ?: track.streamUri ?: return
        val taskId = "dl_${track.id}"

        scope.launch {
            if (trackDao.getTrackById(track.id) == null) {
                val blob = VietnameseTextNormalizer.normalize("${track.title} ${track.artist} ${track.album}")
                trackDao.upsertTracks(listOf(track.toEntity(blob)))
            }
            trackDao.updateDownloadState(track.id, DownloadStatus.QUEUED.name, 0f, null)
            downloadCacheDao.upsertDownload(
                DownloadTaskEntity(
                    id = taskId,
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    source = MusicSource.YOUTUBE_MUSIC.name,
                    status = DownloadStatus.QUEUED.name,
                    bytesDownloaded = 0L,
                    totalBytes = track.fileSizeBytes,
                    destinationPath = storageManager.getYouTubeDisplayPath(),
                    errorMessage = null,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
            YouTubeDownloadNotificationHelper.ensureChannel(context)
            val request = OneTimeWorkRequestBuilder<YouTubeDownloadWorker>()
                .setInputData(
                    YouTubeDownloadWorker.buildInputData(
                        trackId = track.id,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        videoUrlOrId = videoUrlOrId,
                        durationMs = track.durationMs
                    )
                )
                .addTag(taskId)
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    YouTubeDownloadWorker.uniqueWorkName(track.id),
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }.onFailure { err ->
                logger.log("YT_DL", "ERROR", "Không thể xếp hàng tải về '${track.title}': ${err.message}")
                trackDao.updateDownloadState(track.id, DownloadStatus.FAILED.name, 0f, null)
            }
        }
    }

    fun cancelDownload(track: Track) {
        val taskId = "dl_${track.id}"
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(YouTubeDownloadWorker.uniqueWorkName(track.id))
        }
        YouTubeDownloadNotificationHelper.cancelNotifications(context, track.id)
        scope.launch {
            trackDao.updateDownloadState(track.id, DownloadStatus.NONE.name, 0f, null)
            downloadCacheDao.deleteDownload(taskId)
            logger.log("YT_DL", "INFO", "Đã hủy tải về '${track.title}'")
        }
    }

    suspend fun deleteLocalCopy(track: Track) {
        storageManager.deleteLocalFile(track.localPath)
        trackDao.updateDownloadState(track.id, DownloadStatus.NONE.name, 0f, null)
        downloadCacheDao.deleteDownload("dl_${track.id}")
        logger.log("STORAGE", "INFO", "Đã xóa tệp đã lưu: ${track.title}")
    }
}
