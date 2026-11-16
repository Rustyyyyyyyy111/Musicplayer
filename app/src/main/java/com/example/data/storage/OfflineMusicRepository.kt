package com.example.data.storage

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.core.model.AudioFormat
import com.example.core.model.DownloadStatus
import com.example.core.model.MusicSource
import com.example.core.model.Track
import com.example.core.util.ActivityLogger
import com.example.core.util.AudioFormatDetector
import com.example.core.util.AutoGenreClassifier
import com.example.core.util.DuplicateDetector
import com.example.core.util.VietnameseTextNormalizer
import com.example.data.local.SettingsDataStore
import com.example.data.local.TrackDao
import com.example.data.local.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class OfflineMusicRepository(
    private val context: Context,
    private val trackDao: TrackDao,
    private val storageManager: UnifiedStorageManager,
    private val settingsDataStore: SettingsDataStore,
    private val logger: ActivityLogger
) {
    suspend fun indexLocalLibrary(): Int = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<Track>()

        // 1. Index MediaStore Audio (works 100% offline, covers /sdcard/Music Player/ and device music)
        runCatching {
            val projection = buildList {
                add(MediaStore.Audio.Media._ID)
                add(MediaStore.Audio.Media.DISPLAY_NAME)
                add(MediaStore.Audio.Media.TITLE)
                add(MediaStore.Audio.Media.ARTIST)
                add(MediaStore.Audio.Media.ALBUM)
                add(MediaStore.Audio.Media.ALBUM_ID)
                add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.SIZE)
                add(MediaStore.Audio.Media.MIME_TYPE)
                add(MediaStore.Audio.Media.DATE_ADDED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(MediaStore.Audio.Media.BITRATE)
                }
            }.toTypedArray()

            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.DURATION} > 1000",
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val bitrateCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                } else -1

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "unknown"
                    val mime = cursor.getString(mimeCol)
                    val format = AudioFormatDetector.detectFromMetadata(displayName, mime) ?: continue
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
                    val albumId = cursor.getLong(albumIdCol)
                    val artUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    ).toString()
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: displayName.substringBeforeLast('.')
                    val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Nghệ sĩ không rõ"
                    val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() }
                        ?: "Offline"
                    val bitrate = if (bitrateCol >= 0) (cursor.getInt(bitrateCol) / 1000) else 0
                    val genre = AutoGenreClassifier.classify(title, artist, album, displayName)

                    val track = Track(
                        id = "offline_ms_$mediaId",
                        title = title,
                        artist = artist,
                        album = album,
                        source = MusicSource.OFFLINE,
                        durationMs = cursor.getLong(durCol),
                        format = format,
                        bitrateKbps = bitrate,
                        sampleRateHz = if (format.isLossless) 48000 else 44100,
                        bitDepth = if (format.isLossless) 24 else 16,
                        fileSizeBytes = cursor.getLong(sizeCol),
                        fileName = displayName,
                        artworkUri = artUri,
                        streamUri = contentUri.toString(),
                        localPath = contentUri.toString(),
                        genre = genre,
                        downloadStatus = DownloadStatus.COMPLETED,
                        downloadProgress = 1f,
                        dateAddedEpochMs = cursor.getLong(dateCol) * 1000L
                    )
                    discovered += track
                }
            }
        }.onFailure { err ->
            logger.log("OFFLINE", "WARN", "MediaStore scan skipped/partial: ${err.message}")
        }

        // 2. Index App-managed unified directory (/sdcard/Music Player fallback)
        listOf(StorageSubdir.TELEGRAM, StorageSubdir.YOUTUBE).forEach { sub ->
            val dir = storageManager.getAppManagedDir(sub)
            dir.listFiles()?.forEach { file ->
                val format = AudioFormatDetector.detectFromFile(file) ?: return@forEach
                discovered += buildTrackFromFile(file, format)
            }
        }

        // 3. Index SAF directory if granted by user
        val safUriStr = settingsDataStore.preferencesFlow.first().safTreeUri
        if (!safUriStr.isNullOrBlank()) {
            runCatching {
                val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(safUriStr))
                rootDoc?.listFiles()?.forEach { child ->
                    if (child.isDirectory) {
                        child.listFiles().forEach { audioDoc ->
                            val name = audioDoc.name ?: return@forEach
                            val format = AudioFormatDetector.detectFromMetadata(name, audioDoc.type) ?: return@forEach
                            val title = name.substringBeforeLast('.')
                            discovered += Track(
                                id = "offline_saf_${audioDoc.uri.toString().hashCode()}",
                                title = title,
                                artist = "Nghệ sĩ không rõ",
                                album = child.name ?: "Music Player",
                                source = MusicSource.OFFLINE,
                                durationMs = 0L,
                                format = format,
                                fileSizeBytes = audioDoc.length(),
                                fileName = name,
                                streamUri = audioDoc.uri.toString(),
                                localPath = audioDoc.uri.toString(),
                                genre = AutoGenreClassifier.classify(title, "", "", name),
                                downloadStatus = DownloadStatus.COMPLETED,
                                downloadProgress = 1f
                            )
                        }
                    }
                }
            }
        }

        val existingMap = trackDao.getAllTracksOnce().associateBy { it.id }
        val entities = discovered.distinctBy { it.id }.map { track ->
            val prev = existingMap[track.id]
            val merged = if (prev != null) {
                track.copy(
                    isFavorite = prev.isFavorite,
                    playCount = prev.playCount,
                    lastPlayedEpochMs = prev.lastPlayedEpochMs,
                    genre = if (prev.genreManuallyEdited) {
                        runCatching { com.example.core.model.GenreType.valueOf(prev.genre) }
                            .getOrDefault(track.genre)
                    } else track.genre,
                    genreManuallyEdited = prev.genreManuallyEdited
                )
            } else track
            val blob = VietnameseTextNormalizer.normalize("${merged.title} ${merged.artist} ${merged.album} ${merged.fileName}")
            merged.toEntity(blob)
        }
        if (entities.isNotEmpty()) {
            trackDao.upsertTracks(entities)
        }
        logger.log("OFFLINE", "INFO", "Đã lập chỉ mục ${entities.size} bài hát Offline")
        entities.size
    }

    private fun buildTrackFromFile(file: File, format: AudioFormat): Track {
        val retriever = MediaMetadataRetriever()
        var title = file.nameWithoutExtension
        var artist = "Nghệ sĩ không rõ"
        var album = "Music Player"
        var duration = 0L
        var bitrate = 0
        runCatching {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            bitrate = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
        }
        runCatching { retriever.release() }

        val hash = if (file.length() <= 60L * 1024 * 1024) DuplicateDetector.computeSha256(file) else null
        return Track(
            id = "offline_file_${file.absolutePath.hashCode()}",
            title = title,
            artist = artist,
            album = album,
            source = MusicSource.OFFLINE,
            durationMs = duration,
            format = format,
            bitrateKbps = bitrate,
            sampleRateHz = if (format.isLossless) 48000 else 44100,
            bitDepth = if (format.isLossless) 24 else 16,
            fileSizeBytes = file.length(),
            fileHash = hash,
            fileName = file.name,
            streamUri = Uri.fromFile(file).toString(),
            localPath = file.absolutePath,
            genre = AutoGenreClassifier.classify(title, artist, album, file.name),
            downloadStatus = DownloadStatus.COMPLETED,
            downloadProgress = 1f,
            dateAddedEpochMs = file.lastModified()
        )
    }
}
