package com.laughing.musicplayer.data.storage

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.AudioFormatDetector
import com.laughing.musicplayer.core.util.AutoGenreClassifier
import com.laughing.musicplayer.core.util.DuplicateDetector
import com.laughing.musicplayer.core.util.ReplayGainExtractor
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.local.toEntity
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
        val seenFilePaths = mutableSetOf<String>()

        // 1. Index direct public directories (/sdcard/Music/Musicplayer/, /sdcard/Music Player/YouTube/, Music/Music Player/YouTube/) if readable
        listOf(
            storageManager.getDirectPublicMusicDir(),
            storageManager.getDirectYouTubeMusicDir(),
            storageManager.getMediaStoreYouTubeDir()
        ).forEach { dir ->
            if (dir.exists() && dir.canRead()) {
                dir.listFiles()?.forEach { file ->
                    if (!file.isFile) return@forEach
                    val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                    if (canonical in seenFilePaths) return@forEach
                    val format = AudioFormatDetector.detectFromFile(file) ?: return@forEach
                    seenFilePaths += canonical
                    discovered += buildTrackFromFile(file, format)
                }
            }
        }

        // 2. Index MediaStore Audio (covers /sdcard/Music/Musicplayer/ and device music)
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
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
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
                @Suppress("DEPRECATION")
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val bitrateCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                } else -1

                while (cursor.moveToNext()) {
                    val rawPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val canonical = rawPath?.let { runCatching { File(it).canonicalPath }.getOrNull() }
                    if (canonical != null && canonical in seenFilePaths) {
                        continue
                    }

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
                    val durationMs = cursor.getLong(durCol)
                    val sizeBytes = cursor.getLong(sizeCol)
                    var bitrate = if (bitrateCol >= 0) (cursor.getInt(bitrateCol) / 1000) else 0
                    if (bitrate <= 0 && durationMs > 1000L && sizeBytes > 0L) {
                        bitrate = ((sizeBytes * 8L) / durationMs).toInt().coerceIn(32, 9216)
                    }
                    val probed = rawPath?.let { File(it) }?.takeIf { it.exists() && it.canRead() }?.let {
                        AudioFormatDetector.probeAudioSpecs(it, format)
                    }
                    val genre = AutoGenreClassifier.classify(title, artist, album, displayName)
                    val tagAnalysis = runCatching {
                        context.contentResolver.openInputStream(contentUri)?.use { stream ->
                            val buf = ByteArray(128 * 1024)
                            var totalRead = 0
                            while (totalRead < buf.size) {
                                val r = stream.read(buf, totalRead, buf.size - totalRead)
                                if (r <= 0) break
                                totalRead += r
                            }
                            if (totalRead > 0) {
                                ReplayGainExtractor.analyzeBytes(buf.copyOf(totalRead), title, displayName)
                            } else null
                        }
                    }.getOrNull()

                    val track = Track(
                        id = "offline_ms_$mediaId",
                        title = title,
                        artist = artist,
                        album = album,
                        source = MusicSource.OFFLINE,
                        durationMs = durationMs,
                        format = format,
                        bitrateKbps = probed?.bitrateKbps?.takeIf { it > 0 } ?: bitrate,
                        sampleRateHz = probed?.sampleRateHz?.takeIf { it > 0 }
                            ?: AudioFormatDetector.defaultSampleRateFor(format),
                        bitDepth = probed?.bitDepth ?: if (format.isLossless) 16 else null,
                        fileSizeBytes = sizeBytes,
                        fileName = displayName,
                        artworkUri = artUri,
                        streamUri = contentUri.toString(),
                        localPath = rawPath ?: contentUri.toString(),
                        genre = genre,
                        downloadStatus = DownloadStatus.COMPLETED,
                        downloadProgress = 1f,
                        dateAddedEpochMs = cursor.getLong(dateCol) * 1000L,
                        loudnessGainDb = tagAnalysis?.replayGainDb ?: 0f,
                        bpm = tagAnalysis?.bpm ?: ReplayGainExtractor.extractBpmFromText(title, displayName)
                    )
                    discovered += track
                }
            }
        }.onFailure { err ->
            logger.log("OFFLINE", "WARN", "MediaStore scan skipped/partial: ${err.message}")
        }

        // 3. Index App-managed unified directory fallback
        listOf(StorageSubdir.TELEGRAM, StorageSubdir.YOUTUBE).forEach { sub ->
            val dir = storageManager.getAppManagedDir(sub)
            dir.listFiles()?.forEach { file ->
                val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (canonical in seenFilePaths) return@forEach
                val format = AudioFormatDetector.detectFromFile(file) ?: return@forEach
                seenFilePaths += canonical
                discovered += buildTrackFromFile(file, format)
            }
        }

        // 4. Index SAF directory if granted by user
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
                                album = child.name ?: "Musicplayer",
                                source = MusicSource.OFFLINE,
                                durationMs = 0L,
                                format = format,
                                sampleRateHz = AudioFormatDetector.defaultSampleRateFor(format),
                                bitDepth = if (format.isLossless) 16 else null,
                                fileSizeBytes = audioDoc.length(),
                                fileName = name,
                                streamUri = audioDoc.uri.toString(),
                                localPath = audioDoc.uri.toString(),
                                genre = AutoGenreClassifier.classify(title, "", "", name),
                                downloadStatus = DownloadStatus.COMPLETED,
                                downloadProgress = 1f
                            )
                        }
                    } else if (child.isFile) {
                        val name = child.name ?: return@forEach
                        val format = AudioFormatDetector.detectFromMetadata(name, child.type) ?: return@forEach
                        val title = name.substringBeforeLast('.')
                        discovered += Track(
                            id = "offline_saf_${child.uri.toString().hashCode()}",
                            title = title,
                            artist = "Nghệ sĩ không rõ",
                            album = "Musicplayer",
                            source = MusicSource.OFFLINE,
                            durationMs = 0L,
                            format = format,
                            sampleRateHz = AudioFormatDetector.defaultSampleRateFor(format),
                            bitDepth = if (format.isLossless) 16 else null,
                            fileSizeBytes = child.length(),
                            fileName = name,
                            streamUri = child.uri.toString(),
                            localPath = child.uri.toString(),
                            genre = AutoGenreClassifier.classify(title, "", "", name),
                            downloadStatus = DownloadStatus.COMPLETED,
                            downloadProgress = 1f
                        )
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
                    artworkUri = track.artworkUri ?: prev.artworkUri,
                    genre = if (prev.genreManuallyEdited) {
                        runCatching { GenreType.valueOf(prev.genre) }
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

    private suspend fun buildTrackFromFile(file: File, format: AudioFormat): Track {
        val trackId = "offline_file_${file.absolutePath.hashCode()}"
        val probed = AudioFormatDetector.probeAudioSpecs(file, format)
        val parsedNameParts = file.nameWithoutExtension.split(" - ", limit = 2)
        val fallbackArtist = if (parsedNameParts.size == 2 && parsedNameParts[0].isNotBlank()) {
            parsedNameParts[0].trim()
        } else {
            "Nghệ sĩ không rõ"
        }
        val fallbackTitle = if (parsedNameParts.size == 2 && parsedNameParts[1].isNotBlank()) {
            parsedNameParts[1].trim()
        } else {
            file.nameWithoutExtension
        }
        val title = probed.title ?: fallbackTitle
        val artist = probed.artist ?: fallbackArtist
        val defaultAlbum = if (file.parentFile?.name.equals("YouTube", ignoreCase = true)) "YouTube" else "Musicplayer"
        val album = probed.album ?: defaultAlbum
        val artworkPath = probed.embeddedArtwork?.let { bytes ->
            storageManager.saveArtworkFile(trackId, bytes)
        }

        val hash = if (file.length() <= 60L * 1024 * 1024) DuplicateDetector.computeSha256(file) else null
        val analysis = ReplayGainExtractor.analyzeFile(file, title, file.name)
        return Track(
            id = trackId,
            title = title,
            artist = artist,
            album = album,
            source = MusicSource.OFFLINE,
            durationMs = probed.durationMs,
            format = format,
            bitrateKbps = probed.bitrateKbps,
            sampleRateHz = probed.sampleRateHz,
            bitDepth = probed.bitDepth,
            fileSizeBytes = file.length(),
            fileHash = hash,
            fileName = file.name,
            artworkUri = artworkPath,
            streamUri = Uri.fromFile(file).toString(),
            localPath = file.absolutePath,
            genre = AutoGenreClassifier.classify(title, artist, album, file.name),
            downloadStatus = DownloadStatus.COMPLETED,
            downloadProgress = 1f,
            dateAddedEpochMs = file.lastModified(),
            loudnessGainDb = analysis.replayGainDb ?: 0f,
            bpm = analysis.bpm
        )
    }
}
