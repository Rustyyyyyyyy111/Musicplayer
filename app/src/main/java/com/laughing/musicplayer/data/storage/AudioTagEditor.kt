package com.laughing.musicplayer.data.storage

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.AudioFormatDetector
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.data.local.TrackDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class TrackTechnicalInfo(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val releaseYear: String,
    val filePath: String,
    val formatAndCodec: String,
    val bitrate: String,
    val sampleRate: String,
    val channelDisplay: String,
    val fileSize: String,
    val isOfflineFile: Boolean,
    val isFileWritableForTagEdit: Boolean,
    val sourceLabel: String
)

data class AudioTagEditRequest(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val genre: GenreType,
    val releaseYear: Int?
)

sealed interface AudioTagEditResult {
    data class Success(
        val updatedTrack: Track,
        val wroteEmbeddedFileTags: Boolean,
        val updatedMediaStore: Boolean,
        val messageVi: String
    ) : AudioTagEditResult

    data class NotOfflineTrack(val messageVi: String) : AudioTagEditResult
    data class Error(val messageVi: String) : AudioTagEditResult
}

object TrackMetadataInspector {

    suspend fun inspect(context: Context, track: Track): TrackTechnicalInfo = withContext(Dispatchers.IO) {
        val localFile = track.localPath
            ?.takeIf { !it.startsWith("content://") && !it.startsWith("tg://") }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.isFile }

        val contentUri = track.localPath
            ?.takeIf { it.startsWith("content://") }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: track.streamUri?.takeIf { it.startsWith("content://") }?.let { runCatching { Uri.parse(it) }.getOrNull() }

        val probed = localFile?.let { AudioFormatDetector.probeAudioSpecs(it, track.format) }
        var uriAlbumArtist: String? = null
        var uriYear: Int? = null
        var uriCodec: String? = null
        var uriChannels = track.channelCount
        var uriSampleRate = track.sampleRateHz
        var uriBitrate = track.bitrateKbps

        if (probed == null && contentUri != null) {
            val mmr = MediaMetadataRetriever()
            runCatching {
                mmr.setDataSource(context, contentUri)
                uriAlbumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.takeIf { it.isNotBlank() }
                val y = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                uriYear = y?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
                if (uriBitrate <= 0) {
                    uriBitrate = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
                }
            }
            runCatching { mmr.release() }

            val extractor = MediaExtractor()
            runCatching {
                extractor.setDataSource(context, contentUri, null)
                for (i in 0 until extractor.trackCount) {
                    val mf = extractor.getTrackFormat(i)
                    val mime = mf.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        if (mf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            uriChannels = mf.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 16)
                        }
                        if (mf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            uriSampleRate = mf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (uriBitrate <= 0 && mf.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            uriBitrate = mf.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                        }
                        uriCodec = AudioFormatDetector.mapMimeToCodecLabel(mime, track.format)
                        break
                    }
                }
            }
            runCatching { extractor.release() }
        }

        val effectiveAlbumArtist = track.albumArtist?.takeIf { it.isNotBlank() }
            ?: probed?.albumArtist
            ?: uriAlbumArtist
            ?: track.artist.ifBlank { "Chưa có thông tin" }
        val effectiveYear = track.releaseYear ?: probed?.releaseYear ?: uriYear
        val effectiveSampleRate = probed?.sampleRateHz?.takeIf { it > 0 }
            ?: uriSampleRate.takeIf { it > 0 }
            ?: track.sampleRateHz
        val effectiveBitrate = probed?.bitrateKbps?.takeIf { it > 0 }
            ?: uriBitrate.takeIf { it > 0 }
            ?: track.bitrateKbps
        val effectiveChannels = probed?.channelCount ?: uriChannels.coerceAtLeast(1)
        val effectiveCodec = probed?.audioCodec
            ?: uriCodec
            ?: track.audioCodec
            ?: AudioFormatDetector.defaultCodecLabelFor(track.format)
        val effectiveSize = localFile?.length()?.takeIf { it > 0L } ?: track.fileSizeBytes

        val isOfflineLocal = localFile != null || contentUri != null || track.source == MusicSource.OFFLINE
        val pathDisplay = when {
            localFile != null -> localFile.absolutePath
            contentUri != null -> contentUri.toString()
            !track.localPath.isNullOrBlank() -> track.localPath
            else -> "Phát trực tuyến (${track.source.labelVi})"
        }
        val formatSummary = buildString {
            append(track.format.extension.uppercase(Locale.US))
            if (effectiveCodec.isNotBlank() && !effectiveCodec.equals(track.format.extension, ignoreCase = true)) {
                append(" • ").append(effectiveCodec)
            }
            if (track.format.isLossless && track.bitDepth != null) {
                append(" • ${track.bitDepth}-bit")
            }
        }
        val srDisplay = if (effectiveSampleRate > 0) {
            if (effectiveSampleRate % 1000 == 0) "${effectiveSampleRate / 1000} kHz (${effectiveSampleRate} Hz)"
            else String.format(Locale.US, "%.1f kHz (%d Hz)", effectiveSampleRate / 1000f, effectiveSampleRate)
        } else {
            "Chưa có thông tin"
        }
        val chDisplay = when (effectiveChannels) {
            1 -> "Mono (1 kênh)"
            2 -> "Stereo (2 kênh)"
            else -> "$effectiveChannels kênh"
        }
        val bitrateDisplay = if (effectiveBitrate > 0) "$effectiveBitrate kbps" else "Chưa có thông tin"
        val sizeDisplay = if (effectiveSize > 0L) formatFileSizeBytes(effectiveSize) else "Phát trực tuyến (chưa tải)"

        TrackTechnicalInfo(
            title = track.title.ifBlank { "Không rõ tên bài" },
            artist = track.artist.ifBlank { "Nghệ sĩ không rõ" },
            album = track.album.ifBlank { "Chưa có thông tin" },
            albumArtist = effectiveAlbumArtist,
            genre = track.genre.displayNameVi,
            releaseYear = effectiveYear?.toString() ?: "Chưa có thông tin",
            filePath = pathDisplay,
            formatAndCodec = formatSummary,
            bitrate = bitrateDisplay,
            sampleRate = srDisplay,
            channelDisplay = chDisplay,
            fileSize = sizeDisplay,
            isOfflineFile = isOfflineLocal,
            isFileWritableForTagEdit = localFile?.canWrite() == true,
            sourceLabel = track.source.labelVi
        )
    }

    private fun formatFileSizeBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

class AudioTagEditor(
    private val context: Context,
    private val trackDao: TrackDao,
    private val logger: ActivityLogger
) {
    suspend fun updateOfflineTrackTags(
        track: Track,
        request: AudioTagEditRequest
    ): AudioTagEditResult = withContext(Dispatchers.IO) {
        val cleanTitle = request.title.trim()
        if (cleanTitle.isEmpty()) {
            return@withContext AudioTagEditResult.Error("Tên bài hát không được để trống.")
        }
        val cleanArtist = request.artist.trim().ifEmpty { "Nghệ sĩ không rõ" }
        val cleanAlbum = request.album.trim().ifEmpty { "Offline" }
        val cleanAlbumArtist = request.albumArtist?.trim()?.takeIf { it.isNotEmpty() }
        val cleanYear = request.releaseYear?.takeIf { it in 1900..2100 }

        val localFile = track.localPath
            ?.takeIf { !it.startsWith("content://") && !it.startsWith("tg://") }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.isFile }

        val contentUri = track.localPath
            ?.takeIf { it.startsWith("content://") }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: track.streamUri?.takeIf { it.startsWith("content://") }?.let { runCatching { Uri.parse(it) }.getOrNull() }

        if (localFile == null && contentUri == null && track.source != MusicSource.OFFLINE) {
            return@withContext AudioTagEditResult.NotOfflineTrack(
                "Tính năng sửa tag chỉ áp dụng cho bài hát Offline hoặc tệp đã tải về máy."
            )
        }

        var wroteFileTags = false
        if (localFile != null && localFile.canWrite()) {
            wroteFileTags = writeTagsToFile(localFile, track.format, cleanTitle, cleanArtist, cleanAlbum, cleanAlbumArtist, request.genre.displayNameVi, cleanYear)
        } else if (contentUri != null) {
            // Try writing directly via temp file and ParcelFileDescriptor("w") if scoped storage permits
            wroteFileTags = writeTagsThroughContentUri(contentUri, track.format, cleanTitle, cleanArtist, cleanAlbum, cleanAlbumArtist, request.genre.displayNameVi, cleanYear)
        }

        val updatedMediaStore = updateMediaStoreRow(
            track = track,
            localFile = localFile,
            contentUri = contentUri,
            title = cleanTitle,
            artist = cleanArtist,
            album = cleanAlbum,
            albumArtist = cleanAlbumArtist,
            year = cleanYear
        )

        if (localFile != null) {
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(localFile.absolutePath), null, null)
            }
        }

        val updatedTrack = track.copy(
            title = cleanTitle,
            artist = cleanArtist,
            album = cleanAlbum,
            albumArtist = cleanAlbumArtist,
            genre = request.genre,
            genreManuallyEdited = true,
            releaseYear = cleanYear
        )
        val searchBlob = VietnameseTextNormalizer.normalize(
            "${updatedTrack.title} ${updatedTrack.artist} ${updatedTrack.album} ${updatedTrack.albumArtist.orEmpty()} ${updatedTrack.fileName}"
        )
        trackDao.updateEditableMetadata(
            trackId = track.id,
            title = cleanTitle,
            artist = cleanArtist,
            album = cleanAlbum,
            albumArtist = cleanAlbumArtist,
            genre = request.genre.name,
            releaseYear = cleanYear,
            searchBlob = searchBlob
        )

        val summary = when {
            wroteFileTags && updatedMediaStore -> "Đã lưu tag vào tệp âm thanh, MediaStore và thư viện."
            wroteFileTags -> "Đã lưu tag trực tiếp vào tệp âm thanh và thư viện."
            updatedMediaStore -> "Đã cập nhật tag trong MediaStore và thư viện ứng dụng."
            else -> "Đã cập nhật siêu dữ liệu trong thư viện ứng dụng (tệp chỉ đọc theo quyền hệ thống)."
        }
        logger.log("TAG_EDITOR", "INFO", "Cập nhật tag '${cleanTitle}' (${track.format.extension}): file=$wroteFileTags, mediaStore=$updatedMediaStore")

        AudioTagEditResult.Success(
            updatedTrack = updatedTrack,
            wroteEmbeddedFileTags = wroteFileTags,
            updatedMediaStore = updatedMediaStore,
            messageVi = summary
        )
    }

    private fun writeTagsThroughContentUri(
        uri: Uri,
        format: AudioFormat,
        title: String,
        artist: String,
        album: String,
        albumArtist: String?,
        genre: String,
        year: Int?
    ): Boolean {
        return runCatching {
            val temp = File.createTempFile("tag_edit_", ".${format.extension}", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: return false
                val ok = writeTagsToFile(temp, format, title, artist, album, albumArtist, genre, year)
                if (!ok) return false
                context.contentResolver.openFileDescriptor(uri, "wt")?.use { pfd ->
                    java.io.FileOutputStream(pfd.fileDescriptor).use { out ->
                        temp.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    true
                } ?: false
            } finally {
                temp.delete()
            }
        }.getOrElse { err ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && err is RecoverableSecurityException) {
                logger.log("TAG_EDITOR", "WARN", "MediaStore yêu cầu xác nhận quyền ghi từ hệ thống: ${err.message}")
            }
            false
        }
    }

    private fun updateMediaStoreRow(
        track: Track,
        localFile: File?,
        contentUri: Uri?,
        title: String,
        artist: String,
        album: String,
        albumArtist: String?,
        year: Int?
    ): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.ARTIST, artist)
            put(MediaStore.Audio.Media.ALBUM, album)
            if (year != null) {
                put(MediaStore.Audio.Media.YEAR, year)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !albumArtist.isNullOrBlank()) {
                put(MediaStore.Audio.Media.ALBUM_ARTIST, albumArtist)
            }
        }
        return runCatching {
            val targetUri = contentUri ?: track.id.removePrefix("offline_ms_").toLongOrNull()?.let { id ->
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            }
            if (targetUri != null) {
                context.contentResolver.update(targetUri, values, null, null) > 0
            } else if (localFile != null) {
                @Suppress("DEPRECATION")
                context.contentResolver.update(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    values,
                    "${MediaStore.Audio.Media.DATA} = ?",
                    arrayOf(localFile.absolutePath)
                ) > 0
            } else {
                false
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val MAX_INLINE_TAG_EDIT_BYTES = 80L * 1024L * 1024L

        fun writeTagsToFile(
            file: File,
            format: AudioFormat,
            title: String,
            artist: String,
            album: String,
            albumArtist: String?,
            genre: String,
            year: Int?
        ): Boolean {
            if (!file.exists() || !file.isFile) return false
            val maxSafeBytes = minOf(
                MAX_INLINE_TAG_EDIT_BYTES,
                (Runtime.getRuntime().maxMemory() / 4L).coerceAtLeast(16L * 1024L * 1024L)
            )
            if (file.length() > maxSafeBytes) {
                return false
            }
            val detected = AudioFormatDetector.detectFromFile(file) ?: format
            return when (detected) {
                AudioFormat.MP3 -> writeId3v2Mp3(file, title, artist, album, albumArtist, genre, year)
                AudioFormat.FLAC -> writeFlacVorbisComments(file, title, artist, album, albumArtist, genre, year)
                AudioFormat.M4A, AudioFormat.ALAC -> writeMp4IlstTags(file, title, artist, album, albumArtist, genre, year)
                else -> false
            }
        }

        // -------------------- MP3 ID3v2.3 Writer --------------------
        private fun writeId3v2Mp3(
            file: File,
            title: String,
            artist: String,
            album: String,
            albumArtist: String?,
            genre: String,
            year: Int?
        ): Boolean = runCatching {
            val rawBytes = file.readBytes()
            val managedIds = setOf("TIT2", "TPE1", "TALB", "TPE2", "TCON", "TYER", "TDRC")
            var audioOffset = 0
            val preservedFrames = ByteArrayOutputStream()

            if (rawBytes.size >= 10 &&
                rawBytes[0] == 'I'.code.toByte() &&
                rawBytes[1] == 'D'.code.toByte() &&
                rawBytes[2] == '3'.code.toByte()
            ) {
                val major = rawBytes[3].toInt() and 0xFF
                val flags = rawBytes[5].toInt() and 0xFF
                val tagBodySize = decodeSyncSafe(rawBytes, 6)
                audioOffset = (10 + tagBodySize).coerceAtMost(rawBytes.size)
                val footerPresent = (flags and 0x10) != 0
                if (footerPresent) audioOffset = (audioOffset + 10).coerceAtMost(rawBytes.size)

                // Preserve existing non-text/artwork frames (e.g., APIC) from ID3v2.3/v2.4
                if (major == 3 || major == 4) {
                    var pos = 10
                    if ((flags and 0x40) != 0 && pos + 4 <= audioOffset) {
                        val extSize = if (major == 4) decodeSyncSafe(rawBytes, pos) else readIntBE(rawBytes, pos)
                        pos += if (major == 4) extSize else (4 + extSize)
                    }
                    val end = (10 + tagBodySize).coerceAtMost(rawBytes.size)
                    while (pos + 10 <= end) {
                        if (rawBytes[pos].toInt() == 0) break // padding
                        val frameId = String(rawBytes, pos, 4, Charsets.ISO_8859_1)
                        if (!frameId.all { it in 'A'..'Z' || it in '0'..'9' }) break
                        val frameSize = if (major == 4) decodeSyncSafe(rawBytes, pos + 4) else readIntBE(rawBytes, pos + 4)
                        if (frameSize < 0 || pos + 10 + frameSize > end) break
                        if (frameId !in managedIds) {
                            // Emit frame as ID3v2.3 header (non-syncsafe size)
                            preservedFrames.write(frameId.toByteArray(Charsets.ISO_8859_1))
                            preservedFrames.write(intToBytesBE(frameSize))
                            preservedFrames.write(rawBytes[pos + 8].toInt())
                            preservedFrames.write(rawBytes[pos + 9].toInt())
                            preservedFrames.write(rawBytes, pos + 10, frameSize)
                        }
                        pos += 10 + frameSize
                    }
                }
            }

            val framesStream = ByteArrayOutputStream()
            framesStream.write(buildId3TextFrame("TIT2", title))
            framesStream.write(buildId3TextFrame("TPE1", artist))
            framesStream.write(buildId3TextFrame("TALB", album))
            if (!albumArtist.isNullOrBlank()) {
                framesStream.write(buildId3TextFrame("TPE2", albumArtist))
            }
            framesStream.write(buildId3TextFrame("TCON", genre))
            if (year != null) {
                framesStream.write(buildId3TextFrame("TYER", year.toString()))
            }
            framesStream.write(preservedFrames.toByteArray())
            val padding = ByteArray(128)
            framesStream.write(padding)
            val tagBody = framesStream.toByteArray()

            val header = ByteArray(10)
            header[0] = 'I'.code.toByte()
            header[1] = 'D'.code.toByte()
            header[2] = '3'.code.toByte()
            header[3] = 3 // ID3v2.3
            header[4] = 0
            header[5] = 0
            encodeSyncSafe(tagBody.size, header, 6)

            val temp = File(file.parentFile, ".tmp_tag_${file.name}")
            temp.outputStream().use { out ->
                out.write(header)
                out.write(tagBody)
                out.write(rawBytes, audioOffset, rawBytes.size - audioOffset)
            }
            replaceFileSafely(temp, file)
            true
        }.getOrDefault(false)

        private fun buildId3TextFrame(id: String, value: String): ByteArray {
            val textBytes = value.toByteArray(Charsets.UTF_16) // includes BOM
            val payloadSize = 1 + textBytes.size
            val out = ByteArrayOutputStream(10 + payloadSize)
            out.write(id.toByteArray(Charsets.ISO_8859_1))
            out.write(intToBytesBE(payloadSize))
            out.write(0)
            out.write(0)
            out.write(1) // UTF-16 with BOM
            out.write(textBytes)
            return out.toByteArray()
        }

        // -------------------- FLAC Vorbis Comment Writer --------------------
        private fun writeFlacVorbisComments(
            file: File,
            title: String,
            artist: String,
            album: String,
            albumArtist: String?,
            genre: String,
            year: Int?
        ): Boolean = runCatching {
            val bytes = file.readBytes()
            if (bytes.size < 8 || bytes[0] != 'f'.code.toByte() || bytes[1] != 'L'.code.toByte() ||
                bytes[2] != 'a'.code.toByte() || bytes[3] != 'C'.code.toByte()
            ) {
                return false
            }

            data class FlacBlock(val type: Int, val payload: ByteArray)
            val blocks = mutableListOf<FlacBlock>()
            var pos = 4
            var audioOffset = bytes.size
            while (pos + 4 <= bytes.size) {
                val hdr = bytes[pos].toInt() and 0xFF
                val isLast = (hdr and 0x80) != 0
                val type = hdr and 0x7F
                val len = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                    (bytes[pos + 3].toInt() and 0xFF)
                pos += 4
                if (pos + len > bytes.size) return false
                val payload = bytes.copyOfRange(pos, pos + len)
                if (type != 1) { // drop old PADDING, we'll append fresh padding
                    blocks += FlacBlock(type, payload)
                }
                pos += len
                if (isLast) {
                    audioOffset = pos
                    break
                }
            }

            // Preserve vendor string + non-edited comments from existing VorbisComment block (type 4)
            var vendor = "MusicPlayer"
            val preservedComments = mutableListOf<String>()
            val managedKeys = setOf("TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "GENRE", "DATE", "YEAR")
            val existingVorbisIdx = blocks.indexOfFirst { it.type == 4 }
            if (existingVorbisIdx >= 0) {
                val vc = blocks[existingVorbisIdx].payload
                val bb = ByteBuffer.wrap(vc).order(ByteOrder.LITTLE_ENDIAN)
                if (bb.remaining() >= 4) {
                    val vLen = bb.int
                    if (vLen in 0..bb.remaining()) {
                        val vBytes = ByteArray(vLen)
                        bb.get(vBytes)
                        vendor = String(vBytes, Charsets.UTF_8)
                    }
                    if (bb.remaining() >= 4) {
                        val count = bb.int
                        for (i in 0 until count.coerceAtMost(512)) {
                            if (bb.remaining() < 4) break
                            val cLen = bb.int
                            if (cLen < 0 || cLen > bb.remaining()) break
                            val cBytes = ByteArray(cLen)
                            bb.get(cBytes)
                            val entry = String(cBytes, Charsets.UTF_8)
                            val key = entry.substringBefore('=').uppercase(Locale.US)
                            if (key !in managedKeys) {
                                preservedComments += entry
                            }
                        }
                    }
                }
                blocks.removeAt(existingVorbisIdx)
            }

            val allComments = buildList {
                add("TITLE=$title")
                add("ARTIST=$artist")
                add("ALBUM=$album")
                if (!albumArtist.isNullOrBlank()) add("ALBUMARTIST=$albumArtist")
                add("GENRE=$genre")
                if (year != null) add("DATE=$year")
                addAll(preservedComments)
            }

            val vcOut = ByteArrayOutputStream()
            val vBytes = vendor.toByteArray(Charsets.UTF_8)
            vcOut.write(intToBytesLE(vBytes.size))
            vcOut.write(vBytes)
            vcOut.write(intToBytesLE(allComments.size))
            allComments.forEach { entry ->
                val eb = entry.toByteArray(Charsets.UTF_8)
                vcOut.write(intToBytesLE(eb.size))
                vcOut.write(eb)
            }
            blocks.add(FlacBlock(4, vcOut.toByteArray()))
            blocks.add(FlacBlock(1, ByteArray(128))) // small padding block

            val temp = File(file.parentFile, ".tmp_tag_${file.name}")
            temp.outputStream().use { out ->
                out.write("fLaC".toByteArray(Charsets.US_ASCII))
                blocks.forEachIndexed { idx, block ->
                    val lastBit = if (idx == blocks.lastIndex) 0x80 else 0x00
                    out.write(lastBit or (block.type and 0x7F))
                    val len = block.payload.size
                    out.write((len ushr 16) and 0xFF)
                    out.write((len ushr 8) and 0xFF)
                    out.write(len and 0xFF)
                    out.write(block.payload)
                }
                out.write(bytes, audioOffset, bytes.size - audioOffset)
            }
            replaceFileSafely(temp, file)
            true
        }.getOrDefault(false)

        // -------------------- M4A / MP4 ilst Writer --------------------
        private fun writeMp4IlstTags(
            file: File,
            title: String,
            artist: String,
            album: String,
            albumArtist: String?,
            genre: String,
            year: Int?
        ): Boolean = runCatching {
            val bytes = file.readBytes()
            val topBoxes = parseMp4Boxes(bytes, 0, bytes.size) ?: return false
            val moov = topBoxes.firstOrNull { it.type == "moov" } ?: return false
            val mdat = topBoxes.firstOrNull { it.type == "mdat" }

            val moovChildren = parseMp4Boxes(bytes, moov.dataStart, moov.end) ?: return false
            val existingUdta = moovChildren.firstOrNull { it.type == "udta" }
            val existingMeta = existingUdta?.let {
                parseMp4Boxes(bytes, it.dataStart, it.end)?.firstOrNull { c -> c.type == "meta" }
            }
            val existingIlst = existingMeta?.let {
                val metaStart = it.dataStart + 4 // FullBox version + flags
                if (metaStart <= it.end) parseMp4Boxes(bytes, metaStart, it.end)?.firstOrNull { c -> c.type == "ilst" } else null
            }

            // Preserve untouched atoms (e.g. covr artwork) from existing ilst
            val managedAtoms = setOf("©nam", "©ART", "©alb", "aART", "©gen", "©day")
            val preservedIlstBytes = ByteArrayOutputStream()
            if (existingIlst != null) {
                parseMp4Boxes(bytes, existingIlst.dataStart, existingIlst.end)?.forEach { item ->
                    if (item.type !in managedAtoms) {
                        preservedIlstBytes.write(bytes, item.start, item.end - item.start)
                    }
                }
            }

            val newIlstBody = ByteArrayOutputStream().apply {
                write(buildMp4TextAtom("©nam", title))
                write(buildMp4TextAtom("©ART", artist))
                write(buildMp4TextAtom("©alb", album))
                if (!albumArtist.isNullOrBlank()) write(buildMp4TextAtom("aART", albumArtist))
                write(buildMp4TextAtom("©gen", genre))
                if (year != null) write(buildMp4TextAtom("©day", year.toString()))
                write(preservedIlstBytes.toByteArray())
            }.toByteArray()
            val newIlstBox = wrapMp4Box("ilst", newIlstBody)

            val newMetaBody = ByteArrayOutputStream().apply {
                write(ByteArray(4)) // FullBox version=0, flags=0
                val hdlr = existingMeta?.let {
                    val ms = it.dataStart + 4
                    if (ms <= it.end) parseMp4Boxes(bytes, ms, it.end)?.firstOrNull { c -> c.type == "hdlr" } else null
                }
                if (hdlr != null) {
                    write(bytes, hdlr.start, hdlr.end - hdlr.start)
                } else {
                    write(buildMp4MdirHdlrBox())
                }
                if (existingMeta != null) {
                    val ms = existingMeta.dataStart + 4
                    if (ms <= existingMeta.end) {
                        parseMp4Boxes(bytes, ms, existingMeta.end)?.forEach { child ->
                            if (child.type != "hdlr" && child.type != "ilst") {
                                write(bytes, child.start, child.end - child.start)
                            }
                        }
                    }
                }
                write(newIlstBox)
            }.toByteArray()
            val newMetaBox = wrapMp4Box("meta", newMetaBody)

            val newUdtaBody = ByteArrayOutputStream().apply {
                if (existingUdta != null) {
                    parseMp4Boxes(bytes, existingUdta.dataStart, existingUdta.end)?.forEach { child ->
                        if (child.type != "meta") {
                            write(bytes, child.start, child.end - child.start)
                        }
                    }
                }
                write(newMetaBox)
            }.toByteArray()
            val newUdtaBox = wrapMp4Box("udta", newUdtaBody)

            val newMoovBody = ByteArrayOutputStream().apply {
                moovChildren.forEach { child ->
                    if (child.type != "udta") {
                        write(bytes, child.start, child.end - child.start)
                    }
                }
                write(newUdtaBox)
            }.toByteArray()
            val newMoovBox = wrapMp4Box("moov", newMoovBody)
            val delta = newMoovBox.size - (moov.end - moov.start)

            // If moov is placed before mdat, adjust stco/co64 chunk offsets by delta
            if (mdat != null && moov.start < mdat.start && delta != 0) {
                adjustMp4ChunkOffsets(newMoovBox, 8, newMoovBox.size, delta)
            }

            val temp = File(file.parentFile, ".tmp_tag_${file.name}")
            temp.outputStream().use { out ->
                topBoxes.forEach { box ->
                    if (box.type == "moov") {
                        out.write(newMoovBox)
                    } else {
                        out.write(bytes, box.start, box.end - box.start)
                    }
                }
            }
            replaceFileSafely(temp, file)
            true
        }.getOrDefault(false)

        private data class Mp4Box(val type: String, val start: Int, val dataStart: Int, val end: Int)

        private fun parseMp4Boxes(bytes: ByteArray, start: Int, limit: Int): List<Mp4Box>? {
            val list = mutableListOf<Mp4Box>()
            var pos = start
            while (pos + 8 <= limit) {
                val size32 = readIntBE(bytes, pos).toLong() and 0xFFFFFFFFL
                val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
                val headerSize: Int
                val boxSize: Long
                if (size32 == 1L) {
                    if (pos + 16 > limit) return null
                    boxSize = ByteBuffer.wrap(bytes, pos + 8, 8).order(ByteOrder.BIG_ENDIAN).long
                    headerSize = 16
                } else if (size32 == 0L) {
                    boxSize = (limit - pos).toLong()
                    headerSize = 8
                } else {
                    boxSize = size32
                    headerSize = 8
                }
                if (boxSize < headerSize || pos + boxSize > limit) return null
                val end = (pos + boxSize).toInt()
                list += Mp4Box(type, pos, pos + headerSize, end)
                pos = end
            }
            return list
        }

        private fun adjustMp4ChunkOffsets(buf: ByteArray, start: Int, limit: Int, delta: Int) {
            val boxes = parseMp4Boxes(buf, start, limit) ?: return
            for (box in boxes) {
                when (box.type) {
                    "trak", "mdia", "minf", "stbl" -> adjustMp4ChunkOffsets(buf, box.dataStart, box.end, delta)
                    "stco" -> {
                        if (box.dataStart + 8 <= box.end) {
                            val count = readIntBE(buf, box.dataStart + 4)
                            var p = box.dataStart + 8
                            for (i in 0 until count) {
                                if (p + 4 > box.end) break
                                val off = readIntBE(buf, p)
                                writeIntBE(buf, p, off + delta)
                                p += 4
                            }
                        }
                    }
                    "co64" -> {
                        if (box.dataStart + 8 <= box.end) {
                            val count = readIntBE(buf, box.dataStart + 4)
                            var p = box.dataStart + 8
                            for (i in 0 until count) {
                                if (p + 8 > box.end) break
                                val off = ByteBuffer.wrap(buf, p, 8).order(ByteOrder.BIG_ENDIAN).long
                                ByteBuffer.wrap(buf, p, 8).order(ByteOrder.BIG_ENDIAN).putLong(off + delta.toLong())
                                p += 8
                            }
                        }
                    }
                }
            }
        }

        private fun buildMp4TextAtom(atomType: String, value: String): ByteArray {
            val utf8 = value.toByteArray(Charsets.UTF_8)
            val dataPayload = ByteArrayOutputStream().apply {
                write(intToBytesBE(1)) // type indicator: UTF-8 string
                write(ByteArray(4))    // locale = 0
                write(utf8)
            }.toByteArray()
            val dataBox = wrapMp4Box("data", dataPayload)
            val atomBytes = ByteArray(8 + dataBox.size)
            System.arraycopy(intToBytesBE(atomBytes.size), 0, atomBytes, 0, 4)
            System.arraycopy(atomType.toByteArray(Charsets.ISO_8859_1), 0, atomBytes, 4, 4)
            System.arraycopy(dataBox, 0, atomBytes, 8, dataBox.size)
            return atomBytes
        }

        private fun buildMp4MdirHdlrBox(): ByteArray {
            val payload = ByteArrayOutputStream().apply {
                write(ByteArray(8)) // version/flags + pre_defined
                write("mdir".toByteArray(Charsets.ISO_8859_1))
                write("appl".toByteArray(Charsets.ISO_8859_1))
                write(ByteArray(9)) // reserved + null name
            }.toByteArray()
            return wrapMp4Box("hdlr", payload)
        }

        private fun wrapMp4Box(type: String, payload: ByteArray): ByteArray {
            val out = ByteArray(8 + payload.size)
            writeIntBE(out, 0, out.size)
            System.arraycopy(type.toByteArray(Charsets.ISO_8859_1), 0, out, 4, 4)
            System.arraycopy(payload, 0, out, 8, payload.size)
            return out
        }

        private fun replaceFileSafely(temp: File, target: File) {
            if (!temp.renameTo(target)) {
                RandomAccessFile(target, "rw").use { raf ->
                    raf.setLength(0L)
                    temp.inputStream().use { inp ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = inp.read(buf)
                            if (r <= 0) break
                            raf.write(buf, 0, r)
                        }
                    }
                }
                temp.delete()
            }
        }

        private fun decodeSyncSafe(buf: ByteArray, offset: Int): Int {
            return ((buf[offset].toInt() and 0x7F) shl 21) or
                ((buf[offset + 1].toInt() and 0x7F) shl 14) or
                ((buf[offset + 2].toInt() and 0x7F) shl 7) or
                (buf[offset + 3].toInt() and 0x7F)
        }

        private fun encodeSyncSafe(value: Int, out: ByteArray, offset: Int) {
            out[offset] = ((value ushr 21) and 0x7F).toByte()
            out[offset + 1] = ((value ushr 14) and 0x7F).toByte()
            out[offset + 2] = ((value ushr 7) and 0x7F).toByte()
            out[offset + 3] = (value and 0x7F).toByte()
        }

        private fun readIntBE(buf: ByteArray, offset: Int): Int {
            return ((buf[offset].toInt() and 0xFF) shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
        }

        private fun writeIntBE(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = ((value ushr 24) and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            buf[offset + 3] = (value and 0xFF).toByte()
        }

        private fun intToBytesBE(value: Int): ByteArray = ByteArray(4).also { writeIntBE(it, 0, value) }

        private fun intToBytesLE(value: Int): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte()
        )
    }
}
