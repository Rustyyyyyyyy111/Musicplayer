package com.laughing.musicplayer.data.storage

import android.content.ContentUris
import android.content.Context
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
import com.laughing.musicplayer.core.util.AudioTagAnalysis
import com.laughing.musicplayer.core.util.AutoGenreClassifier
import com.laughing.musicplayer.core.util.DuplicateDetector
import com.laughing.musicplayer.core.util.ReplayGainExtractor
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.local.TrackEntity
import com.laughing.musicplayer.data.local.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

enum class OfflineScanMode(val labelVi: String) {
    ENTIRE_DEVICE("Quét toàn bộ thiết bị"),
    SELECTED_FOLDER("Chọn thư mục để quét")
}

data class OfflineScanProgress(
    val scannedCount: Int = 0,
    val currentTitle: String = ""
)

class OfflineMusicRepository(
    private val context: Context,
    private val trackDao: TrackDao,
    private val storageManager: UnifiedStorageManager,
    private val settingsDataStore: SettingsDataStore,
    private val logger: ActivityLogger
) {
    companion object {
        const val MIN_DEVICE_SCAN_DURATION_MS = 30_000L
        private const val LOCAL_ID_PREFIX = "local_"
    }

    suspend fun indexLocalLibrary(): Int = scanEntireDevice()

    suspend fun scanEntireDevice(
        onProgress: (OfflineScanProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<Track>()
        val seenFilePaths = mutableSetOf<String>()
        val seenUris = mutableSetOf<String>()

        // 1. Scan MediaStore.Audio.Media across BOTH external and internal storage (filtering < 30s ringtones/notifications)
        listOf(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.INTERNAL_CONTENT_URI
        ).forEach { collectionUri ->
            queryMediaStoreCollection(
                collectionUri = collectionUri,
                discovered = discovered,
                seenFilePaths = seenFilePaths,
                seenUris = seenUris,
                onProgress = onProgress
            )
        }

        // 2. Scan readable app/public directories (/sdcard/Music/Musicplayer/, /sdcard/Music Player/YouTube/, app-managed dirs)
        listOf(
            storageManager.getDirectPublicMusicDir(),
            storageManager.getDirectYouTubeMusicDir(),
            storageManager.getMediaStoreYouTubeDir(),
            storageManager.getAppManagedDir(StorageSubdir.TELEGRAM),
            storageManager.getAppManagedDir(StorageSubdir.YOUTUBE)
        ).forEach { dir ->
            if (dir.exists() && dir.canRead()) {
                scanDirectoryFiles(
                    dir = dir,
                    discovered = discovered,
                    seenFilePaths = seenFilePaths,
                    seenUris = seenUris,
                    onProgress = onProgress
                )
            }
        }

        // 3. Re-scan any persisted SAF folders previously authorized by the user
        val prefs = settingsDataStore.preferencesFlow.first()
        val persistedSafUris = buildSet {
            addAll(prefs.scannedFolderUris)
            prefs.safTreeUri?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        persistedSafUris.forEach { uriStr ->
            runCatching {
                val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
                if (rootDoc != null && rootDoc.exists()) {
                    scanDocumentTreeRecursive(
                        rootDoc = rootDoc,
                        discovered = discovered,
                        seenUris = seenUris,
                        onProgress = onProgress
                    )
                }
            }
        }

        val count = upsertAndMergeOfflineTracks(discovered)
        logger.log("OFFLINE", "INFO", "Đã quét xong toàn bộ thiết bị: tìm thấy $count bài hát")
        count
    }

    suspend fun scanSelectedFolder(
        treeUri: Uri,
        onProgress: (OfflineScanProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        settingsDataStore.addScannedFolderUri(treeUri.toString())
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
        if (rootDoc == null || !rootDoc.exists()) {
            logger.log("OFFLINE", "WARN", "Không thể truy cập thư mục SAF: $treeUri")
            return@withContext 0
        }
        val count = scanDocumentTreeAndUpsert(rootDoc, onProgress)
        logger.log("OFFLINE", "INFO", "Đã quét thư mục ($treeUri): tìm thấy $count bài hát")
        count
    }

    internal suspend fun scanDocumentTreeAndUpsert(
        rootDoc: DocumentFile,
        onProgress: (OfflineScanProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<Track>()
        val seenUris = mutableSetOf<String>()
        scanDocumentTreeRecursive(
            rootDoc = rootDoc,
            discovered = discovered,
            seenUris = seenUris,
            onProgress = onProgress
        )
        upsertAndMergeOfflineTracks(discovered)
    }

    private suspend fun queryMediaStoreCollection(
        collectionUri: Uri,
        discovered: MutableList<Track>,
        seenFilePaths: MutableSet<String>,
        seenUris: MutableSet<String>,
        onProgress: (OfflineScanProgress) -> Unit
    ) {
        runCatching {
            val isExternal = collectionUri == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
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
                add(MediaStore.Audio.Media.IS_MUSIC)
                add(MediaStore.Audio.Media.IS_RINGTONE)
                add(MediaStore.Audio.Media.IS_NOTIFICATION)
                add(MediaStore.Audio.Media.IS_ALARM)
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(MediaStore.Audio.Media.BITRATE)
                }
            }.toTypedArray()

            // Skip short ringtones/notifications (< 30 seconds) directly at query level when duration is indexed;
            // items with 0/NULL duration are still checked post-probe so newly saved M4A/OPUS are not missed.
            val selection =
                "${MediaStore.Audio.Media.DURATION} >= $MIN_DEVICE_SCAN_DURATION_MS OR " +
                    "${MediaStore.Audio.Media.DURATION} IS NULL OR ${MediaStore.Audio.Media.DURATION} = 0"

            context.contentResolver.query(
                collectionUri,
                projection,
                selection,
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
                val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
                val isRingtoneCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_RINGTONE)
                val isNotifCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_NOTIFICATION)
                val isAlarmCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_ALARM)
                @Suppress("DEPRECATION")
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val bitrateCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                } else -1

                while (cursor.moveToNext()) {
                    val isMusic = if (isMusicCol >= 0) cursor.getInt(isMusicCol) == 1 else true
                    val isSystemTone = (isRingtoneCol >= 0 && cursor.getInt(isRingtoneCol) == 1) ||
                        (isNotifCol >= 0 && cursor.getInt(isNotifCol) == 1) ||
                        (isAlarmCol >= 0 && cursor.getInt(isAlarmCol) == 1)
                    if (isSystemTone && !isMusic) continue

                    val rawPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val canonical = rawPath?.let { runCatching { File(it).canonicalPath }.getOrNull() }
                    if (canonical != null && canonical in seenFilePaths) continue

                    val mediaId = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(collectionUri, mediaId)
                    val contentUriStr = contentUri.toString()
                    if (contentUriStr in seenUris) continue

                    val displayName = cursor.getString(nameCol) ?: "unknown"
                    val mime = cursor.getString(mimeCol)
                    val initialFormat = AudioFormatDetector.detectFromMetadata(displayName, mime) ?: continue

                    val cursorDurationMs = cursor.getLong(durCol)
                    if (cursorDurationMs in 1L until MIN_DEVICE_SCAN_DURATION_MS) continue

                    val sizeBytes = cursor.getLong(sizeCol)
                    val trackId = "$LOCAL_ID_PREFIX$contentUriStr"

                    val readableFile = rawPath?.let { File(it) }?.takeIf { it.exists() && it.canRead() }
                    val probed = if (readableFile != null) {
                        AudioFormatDetector.probeAudioSpecs(readableFile, initialFormat)
                    } else {
                        AudioFormatDetector.probeAudioSpecsFromUri(context, contentUri, initialFormat, sizeBytes)
                    }

                    val finalDurationMs = probed.durationMs.takeIf { it > 0L } ?: cursorDurationMs
                    if (finalDurationMs < MIN_DEVICE_SCAN_DURATION_MS) continue

                    val finalFormat = probed.detectedFormat ?: initialFormat
                    val fallbackParts = parseArtistTitleFromFilename(displayName)
                    val mediaArtist = cursor.getString(artistCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    val mediaAlbum = cursor.getString(albumCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    val mediaTitle = cursor.getString(titleCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }

                    val title = probed.title ?: mediaTitle ?: fallbackParts.second
                    val artist = probed.artist ?: mediaArtist ?: fallbackParts.first
                    val album = probed.album ?: mediaAlbum ?: "Offline"

                    val albumId = cursor.getLong(albumIdCol)
                    val embeddedArtPath = probed.embeddedArtwork?.let { bytes ->
                        storageManager.saveArtworkFile(trackId, bytes)
                    }
                    val artUri = embeddedArtPath ?: if (isExternal && albumId > 0L) {
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            albumId
                        ).toString()
                    } else null

                    var bitrate = probed.bitrateKbps.takeIf { it > 0 }
                        ?: if (bitrateCol >= 0) (cursor.getInt(bitrateCol) / 1000) else 0
                    if (bitrate <= 0 && finalDurationMs > 1000L && sizeBytes > 0L) {
                        bitrate = ((sizeBytes * 8L) / finalDurationMs).toInt().coerceIn(32, 9216)
                    }

                    val tagAnalysis = analyzeStreamHeaderBytes(contentUri, title, displayName)
                    val genre = AutoGenreClassifier.classify(title, artist, album, displayName)

                    val track = Track(
                        id = trackId,
                        title = title,
                        artist = artist,
                        album = album,
                        source = MusicSource.OFFLINE,
                        durationMs = finalDurationMs,
                        format = finalFormat,
                        bitrateKbps = bitrate,
                        sampleRateHz = probed.sampleRateHz.takeIf { it > 0 }
                            ?: AudioFormatDetector.defaultSampleRateFor(finalFormat),
                        bitDepth = probed.bitDepth ?: if (finalFormat.isLossless) 16 else null,
                        fileSizeBytes = sizeBytes,
                        fileName = displayName,
                        artworkUri = artUri,
                        streamUri = contentUriStr,
                        localPath = rawPath ?: contentUriStr,
                        genre = genre,
                        downloadStatus = DownloadStatus.COMPLETED,
                        downloadProgress = 1f,
                        dateAddedEpochMs = (cursor.getLong(dateCol) * 1000L).takeIf { it > 0L }
                            ?: System.currentTimeMillis(),
                        loudnessGainDb = tagAnalysis?.replayGainDb ?: 0f,
                        bpm = tagAnalysis?.bpm ?: ReplayGainExtractor.extractBpmFromText(title, displayName),
                        albumArtist = probed.albumArtist,
                        releaseYear = probed.releaseYear,
                        channelCount = probed.channelCount,
                        audioCodec = probed.audioCodec ?: AudioFormatDetector.defaultCodecLabelFor(finalFormat)
                    )
                    if (canonical != null) seenFilePaths += canonical
                    seenUris += contentUriStr
                    discovered += track
                    onProgress(OfflineScanProgress(discovered.size, title))
                }
            }
        }.onFailure { err ->
            logger.log("OFFLINE", "WARN", "MediaStore ($collectionUri) bỏ qua một phần: ${err.message}")
        }
    }

    private suspend fun scanDirectoryFiles(
        dir: File,
        discovered: MutableList<Track>,
        seenFilePaths: MutableSet<String>,
        seenUris: MutableSet<String>,
        onProgress: (OfflineScanProgress) -> Unit
    ) {
        val children = dir.listFiles() ?: return
        for (file in children) {
            if (file.isDirectory) {
                scanDirectoryFiles(file, discovered, seenFilePaths, seenUris, onProgress)
                continue
            }
            if (!file.isFile) continue
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (canonical in seenFilePaths) continue
            val fileUriStr = Uri.fromFile(file).toString()
            if (fileUriStr in seenUris) continue
            val format = AudioFormatDetector.detectFromFile(file) ?: continue
            val track = buildTrackFromFile(file, format)
            seenFilePaths += canonical
            seenUris += fileUriStr
            discovered += track
            onProgress(OfflineScanProgress(discovered.size, track.title))
        }
    }

    private suspend fun scanDocumentTreeRecursive(
        rootDoc: DocumentFile,
        discovered: MutableList<Track>,
        seenUris: MutableSet<String>,
        onProgress: (OfflineScanProgress) -> Unit,
        visitedDirs: MutableSet<String> = mutableSetOf(),
        depth: Int = 0
    ) {
        if (depth > 16) return
        val dirKey = rootDoc.uri.toString()
        if (!visitedDirs.add(dirKey)) return

        val parentFolderName = rootDoc.name?.takeIf { it.isNotBlank() } ?: "Offline"
        val children = runCatching { rootDoc.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            if (child.isDirectory) {
                scanDocumentTreeRecursive(
                    rootDoc = child,
                    discovered = discovered,
                    seenUris = seenUris,
                    onProgress = onProgress,
                    visitedDirs = visitedDirs,
                    depth = depth + 1
                )
            } else if (child.isFile) {
                val uriStr = child.uri.toString()
                if (uriStr in seenUris) continue
                val name = child.name ?: continue
                val format = AudioFormatDetector.detectFromMetadata(name, child.type) ?: continue
                val track = buildTrackFromDocumentFile(child, format, parentFolderName)
                seenUris += uriStr
                discovered += track
                onProgress(OfflineScanProgress(discovered.size, track.title))
            }
        }
    }

    private suspend fun buildTrackFromDocumentFile(
        docFile: DocumentFile,
        initialFormat: AudioFormat,
        parentFolderName: String
    ): Track {
        val uri = docFile.uri
        val uriStr = uri.toString()
        val trackId = "$LOCAL_ID_PREFIX$uriStr"
        val fileName = docFile.name ?: uri.lastPathSegment ?: "audio"
        val sizeBytes = docFile.length()

        val probed = AudioFormatDetector.probeAudioSpecsFromUri(
            context = context,
            uri = uri,
            format = initialFormat,
            knownFileSizeBytes = sizeBytes
        )
        val finalFormat = probed.detectedFormat ?: initialFormat
        val fallbackParts = parseArtistTitleFromFilename(fileName)
        val title = probed.title ?: fallbackParts.second
        val artist = probed.artist ?: fallbackParts.first
        val album = probed.album ?: parentFolderName
        val artworkPath = probed.embeddedArtwork?.let { bytes ->
            storageManager.saveArtworkFile(trackId, bytes)
        }
        val tagAnalysis = analyzeStreamHeaderBytes(uri, title, fileName)
        val dateAdded = docFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()

        return Track(
            id = trackId,
            title = title,
            artist = artist,
            album = album,
            source = MusicSource.OFFLINE,
            durationMs = probed.durationMs,
            format = finalFormat,
            bitrateKbps = probed.bitrateKbps,
            sampleRateHz = probed.sampleRateHz.takeIf { it > 0 }
                ?: AudioFormatDetector.defaultSampleRateFor(finalFormat),
            bitDepth = probed.bitDepth ?: if (finalFormat.isLossless) 16 else null,
            fileSizeBytes = sizeBytes,
            fileName = fileName,
            artworkUri = artworkPath,
            streamUri = uriStr,
            localPath = uriStr,
            genre = AutoGenreClassifier.classify(title, artist, album, fileName),
            downloadStatus = DownloadStatus.COMPLETED,
            downloadProgress = 1f,
            dateAddedEpochMs = dateAdded,
            loudnessGainDb = tagAnalysis?.replayGainDb ?: 0f,
            bpm = tagAnalysis?.bpm ?: ReplayGainExtractor.extractBpmFromText(title, fileName),
            albumArtist = probed.albumArtist,
            releaseYear = probed.releaseYear,
            channelCount = probed.channelCount,
            audioCodec = probed.audioCodec ?: AudioFormatDetector.defaultCodecLabelFor(finalFormat)
        )
    }

    private suspend fun buildTrackFromFile(file: File, initialFormat: AudioFormat): Track {
        val fileUriStr = Uri.fromFile(file).toString()
        val trackId = "$LOCAL_ID_PREFIX$fileUriStr"
        val probed = AudioFormatDetector.probeAudioSpecs(file, initialFormat)
        val finalFormat = probed.detectedFormat ?: initialFormat
        val fallbackParts = parseArtistTitleFromFilename(file.name)
        val title = probed.title ?: fallbackParts.second
        val artist = probed.artist ?: fallbackParts.first
        val defaultAlbum = if (file.parentFile?.name.equals("YouTube", ignoreCase = true)) {
            "YouTube"
        } else {
            file.parentFile?.name?.takeIf { it.isNotBlank() } ?: "Musicplayer"
        }
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
            format = finalFormat,
            bitrateKbps = probed.bitrateKbps,
            sampleRateHz = probed.sampleRateHz,
            bitDepth = probed.bitDepth,
            fileSizeBytes = file.length(),
            fileHash = hash,
            fileName = file.name,
            artworkUri = artworkPath,
            streamUri = fileUriStr,
            localPath = file.absolutePath,
            genre = AutoGenreClassifier.classify(title, artist, album, file.name),
            downloadStatus = DownloadStatus.COMPLETED,
            downloadProgress = 1f,
            dateAddedEpochMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
            loudnessGainDb = analysis.replayGainDb ?: 0f,
            bpm = analysis.bpm,
            albumArtist = probed.albumArtist,
            releaseYear = probed.releaseYear,
            channelCount = probed.channelCount,
            audioCodec = probed.audioCodec ?: AudioFormatDetector.defaultCodecLabelFor(finalFormat)
        )
    }

    private suspend fun upsertAndMergeOfflineTracks(discovered: List<Track>): Int {
        if (discovered.isEmpty()) return 0

        val existing = trackDao.getAllTracksOnce()
        val existingById = existing.associateBy { it.id }
        val existingOfflineByStreamUri = existing
            .filter { it.source == MusicSource.OFFLINE.name && !it.streamUri.isNullOrBlank() }
            .associateBy { it.streamUri!! }
        val existingOfflineByLocalPath = existing
            .filter { it.source == MusicSource.OFFLINE.name && !it.localPath.isNullOrBlank() }
            .associateBy { it.localPath!! }

        val legacyIdsToDelete = mutableSetOf<String>()

        val entities = discovered.distinctBy { it.id }.map { track ->
            val prevDirect = existingById[track.id]
            val prevLegacy = if (prevDirect == null) {
                val match = track.streamUri?.let { existingOfflineByStreamUri[it] }
                    ?: track.localPath?.let { existingOfflineByLocalPath[it] }
                if (match != null && match.id != track.id && match.id.startsWith("offline_")) {
                    legacyIdsToDelete += match.id
                }
                match
            } else null

            val prev: TrackEntity? = prevDirect ?: prevLegacy
            val merged = if (prev != null) {
                track.copy(
                    isFavorite = prev.isFavorite,
                    playCount = prev.playCount,
                    lastPlayedEpochMs = prev.lastPlayedEpochMs,
                    artworkUri = track.artworkUri ?: prev.artworkUri,
                    genre = if (prev.genreManuallyEdited) {
                        runCatching { GenreType.valueOf(prev.genre) }.getOrDefault(track.genre)
                    } else track.genre,
                    genreManuallyEdited = prev.genreManuallyEdited,
                    albumArtist = track.albumArtist ?: prev.albumArtist,
                    releaseYear = track.releaseYear ?: prev.releaseYear,
                    channelCount = track.channelCount.takeIf { it > 0 } ?: prev.channelCount,
                    audioCodec = track.audioCodec ?: prev.audioCodec
                )
            } else track

            val blob = VietnameseTextNormalizer.normalize(
                "${merged.title} ${merged.artist} ${merged.album} ${merged.fileName}"
            )
            merged.toEntity(blob)
        }

        if (legacyIdsToDelete.isNotEmpty()) {
            trackDao.deleteTracksByIds(legacyIdsToDelete.toList())
        }
        if (entities.isNotEmpty()) {
            trackDao.upsertTracks(entities)
        }
        return entities.size
    }

    private fun parseArtistTitleFromFilename(fileName: String): Pair<String, String> {
        val base = fileName.substringBeforeLast('.')
        val parts = base.split(" - ", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0].trim() to parts[1].trim()
        } else {
            "Nghệ sĩ không rõ" to base.ifBlank { fileName }
        }
    }

    private fun analyzeStreamHeaderBytes(uri: Uri, title: String, fileName: String): AudioTagAnalysis? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(128 * 1024)
                var totalRead = 0
                while (totalRead < buf.size) {
                    val r = stream.read(buf, totalRead, buf.size - totalRead)
                    if (r <= 0) break
                    totalRead += r
                }
                if (totalRead > 0) {
                    ReplayGainExtractor.analyzeBytes(buf.copyOf(totalRead), title, fileName)
                } else null
            }
        }.getOrNull()
    }
}
