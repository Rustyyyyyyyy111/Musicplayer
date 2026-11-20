package com.laughing.musicplayer.core.util

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.laughing.musicplayer.core.model.AudioFormat
import java.io.File

data class AudioSpecProbeResult(
    val sampleRateHz: Int = 0,
    val bitDepth: Int? = null,
    val bitrateKbps: Int = 0,
    val durationMs: Long = 0L,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val releaseYear: Int? = null,
    val genreName: String? = null,
    val channelCount: Int = 2,
    val audioCodec: String? = null,
    val embeddedArtwork: ByteArray? = null,
    val detectedFormat: AudioFormat? = null
)

object AudioFormatDetector {
    private val supportedAudioExtensions = mapOf(
        "mp3" to AudioFormat.MP3,
        "flac" to AudioFormat.FLAC,
        "wav" to AudioFormat.WAV,
        "wave" to AudioFormat.WAV,
        "m4a" to AudioFormat.M4A,
        "aac" to AudioFormat.AAC,
        "alac" to AudioFormat.ALAC,
        "ogg" to AudioFormat.OGG,
        "oga" to AudioFormat.OGG,
        "opus" to AudioFormat.OPUS,
        "ape" to AudioFormat.APE
    )

    private val rejectedNonMusicExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "gif",
        "jpg", "jpeg", "png", "webp", "bmp", "heic",
        "pdf", "zip", "rar", "7z", "apk", "txt", "doc", "docx", "json", "lrc"
    )

    private val audioMimeMap = mapOf(
        "audio/mpeg" to AudioFormat.MP3,
        "audio/mp3" to AudioFormat.MP3,
        "audio/flac" to AudioFormat.FLAC,
        "audio/x-flac" to AudioFormat.FLAC,
        "audio/wav" to AudioFormat.WAV,
        "audio/x-wav" to AudioFormat.WAV,
        "audio/wave" to AudioFormat.WAV,
        "audio/mp4" to AudioFormat.M4A,
        "audio/m4a" to AudioFormat.M4A,
        "audio/x-m4a" to AudioFormat.M4A,
        "audio/aac" to AudioFormat.AAC,
        "audio/aacp" to AudioFormat.AAC,
        "audio/alac" to AudioFormat.ALAC,
        "audio/ogg" to AudioFormat.OGG,
        "application/ogg" to AudioFormat.OGG,
        "audio/opus" to AudioFormat.OPUS,
        "audio/ape" to AudioFormat.APE,
        "audio/x-ape" to AudioFormat.APE
    )

    fun detectFromMetadata(
        fileName: String?,
        mimeType: String?,
        isVoiceNote: Boolean = false,
        isVideoNote: Boolean = false,
        includeVoiceMessages: Boolean = false
    ): AudioFormat? {
        if (isVideoNote) return null
        if (isVoiceNote && !includeVoiceMessages) return null

        val cleanMime = mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        if (cleanMime.startsWith("video/") || cleanMime.startsWith("image/")) {
            return null
        }

        val ext = fileName?.substringAfterLast('.', "")?.lowercase()?.trim().orEmpty()
        if (ext in rejectedNonMusicExtensions) return null

        supportedAudioExtensions[ext]?.let { return it }
        audioMimeMap[cleanMime]?.let { return it }

        if (isVoiceNote && includeVoiceMessages) {
            return AudioFormat.OGG
        }

        return null
    }

    fun detectFromSignature(headerBytes: ByteArray): AudioFormat? {
        if (headerBytes.size < 4) return null
        // FLAC: "fLaC"
        if (headerBytes[0] == 0x66.toByte() && headerBytes[1] == 0x4C.toByte() &&
            headerBytes[2] == 0x61.toByte() && headerBytes[3] == 0x43.toByte()
        ) {
            return AudioFormat.FLAC
        }
        // WAV: "RIFF....WAVE"
        if (headerBytes.size >= 12 &&
            headerBytes[0] == 0x52.toByte() && headerBytes[1] == 0x49.toByte() &&
            headerBytes[2] == 0x46.toByte() && headerBytes[3] == 0x46.toByte() &&
            headerBytes[8] == 0x57.toByte() && headerBytes[9] == 0x41.toByte() &&
            headerBytes[10] == 0x56.toByte() && headerBytes[11] == 0x45.toByte()
        ) {
            return AudioFormat.WAV
        }
        // MP3 ID3v2 or MPEG frame sync
        if ((headerBytes[0] == 0x49.toByte() && headerBytes[1] == 0x44.toByte() && headerBytes[2] == 0x33.toByte()) ||
            (headerBytes[0] == 0xFF.toByte() && (headerBytes[1].toInt() and 0xE0) == 0xE0)
        ) {
            return AudioFormat.MP3
        }
        // OGG / OPUS
        if (headerBytes[0] == 0x4F.toByte() && headerBytes[1] == 0x67.toByte() &&
            headerBytes[2] == 0x67.toByte() && headerBytes[3] == 0x53.toByte()
        ) {
            val headerStr = String(headerBytes, Charsets.US_ASCII)
            return if (headerStr.contains("OpusHead")) AudioFormat.OPUS else AudioFormat.OGG
        }
        // M4A / ALAC ISO BMFF ("ftyp")
        if (headerBytes.size >= 12 &&
            headerBytes[4] == 0x66.toByte() && headerBytes[5] == 0x74.toByte() &&
            headerBytes[6] == 0x79.toByte() && headerBytes[7] == 0x70.toByte()
        ) {
            return AudioFormat.M4A
        }
        // APE: "MAC "
        if (headerBytes[0] == 0x4D.toByte() && headerBytes[1] == 0x41.toByte() &&
            headerBytes[2] == 0x43.toByte() && headerBytes[3] == 0x20.toByte()
        ) {
            return AudioFormat.APE
        }
        // ADTS AAC
        if (headerBytes[0] == 0xFF.toByte() && (headerBytes[1].toInt() and 0xF6) == 0xF0) {
            return AudioFormat.AAC
        }
        return null
    }

    fun detectFromFile(file: File, mimeType: String? = null): AudioFormat? {
        detectFromMetadata(file.name, mimeType)?.let { return it }
        if (!file.exists() || !file.canRead()) return null
        return runCatching {
            val buffer = ByteArray(64)
            val read = file.inputStream().use { it.read(buffer) }
            if (read > 0) detectFromSignature(buffer.copyOf(read)) else null
        }.getOrNull()
    }

    fun defaultSampleRateFor(format: AudioFormat): Int = when (format) {
        AudioFormat.OPUS -> 48000
        AudioFormat.FLAC, AudioFormat.WAV, AudioFormat.ALAC, AudioFormat.APE -> 48000
        else -> 44100
    }

    fun probeAudioSpecs(file: File, format: AudioFormat): AudioSpecProbeResult {
        if (!file.exists() || !file.canRead() || file.length() <= 0L) {
            return AudioSpecProbeResult(
                sampleRateHz = defaultSampleRateFor(format),
                bitDepth = if (format.isLossless) 16 else null,
                audioCodec = defaultCodecLabelFor(format),
                detectedFormat = format
            )
        }

        val flacHeader = if (format == AudioFormat.FLAC) parseFlacStreamInfo(file) else null
        val retriever = MediaMetadataRetriever()
        val retrieverResult = try {
            runCatching {
                retriever.setDataSource(file.absolutePath)
                extractFromRetriever(retriever)
            }.getOrNull()
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        val extractorResult = try {
            runCatching {
                extractor.setDataSource(file.absolutePath)
                extractFromExtractor(extractor, format)
            }.getOrNull()
        } finally {
            runCatching { extractor.release() }
        }

        return mergeProbeResults(
            format = format,
            knownFileSizeBytes = file.length(),
            flacHeader = flacHeader,
            retriever = retrieverResult,
            extractor = extractorResult
        )
    }

    fun probeAudioSpecsFromUri(
        context: Context,
        uri: Uri,
        format: AudioFormat,
        knownFileSizeBytes: Long = 0L
    ): AudioSpecProbeResult {
        val flacHeader = if (format == AudioFormat.FLAC) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buf = ByteArray(42)
                    val read = stream.read(buf)
                    if (read >= 26) parseFlacHeaderBytes(buf, read) else null
                }
            }.getOrNull()
        } else null

        val retriever = MediaMetadataRetriever()
        val retrieverResult = try {
            runCatching {
                retriever.setDataSource(context, uri)
                extractFromRetriever(retriever)
            }.getOrNull()
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        val extractorResult = try {
            runCatching {
                extractor.setDataSource(context, uri, null)
                extractFromExtractor(extractor, format)
            }.getOrNull()
        } finally {
            runCatching { extractor.release() }
        }

        return mergeProbeResults(
            format = format,
            knownFileSizeBytes = knownFileSizeBytes,
            flacHeader = flacHeader,
            retriever = retrieverResult,
            extractor = extractorResult
        )
    }

    private data class FlacStreamInfo(
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitDepth: Int
    )

    private data class RetrieverProbe(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val genreName: String? = null,
        val releaseYear: Int? = null,
        val durationMs: Long = 0L,
        val bitrateKbps: Int = 0,
        val sampleRateHz: Int = 0,
        val bitDepth: Int? = null,
        val embeddedArtwork: ByteArray? = null
    )

    private data class ExtractorProbe(
        val sampleRateHz: Int = 0,
        val channelCount: Int = 2,
        val audioCodec: String? = null,
        val bitrateKbps: Int = 0,
        val bitDepth: Int? = null,
        val detectedFormat: AudioFormat? = null
    )

    private fun parseFlacStreamInfo(file: File): FlacStreamInfo? = runCatching {
        val buf = ByteArray(42)
        val read = file.inputStream().use { it.read(buf) }
        parseFlacHeaderBytes(buf, read)
    }.getOrNull()

    private fun parseFlacHeaderBytes(buf: ByteArray, read: Int): FlacStreamInfo? {
        if (read < 26) return null
        if (buf[0] != 0x66.toByte() || buf[1] != 0x4C.toByte() ||
            buf[2] != 0x61.toByte() || buf[3] != 0x43.toByte() ||
            (buf[4].toInt() and 0x7F) != 0
        ) {
            return null
        }
        val b18 = buf[18].toInt() and 0xFF
        val b19 = buf[19].toInt() and 0xFF
        val b20 = buf[20].toInt() and 0xFF
        val b21 = buf[21].toInt() and 0xFF
        val sr = (b18 shl 12) or (b19 shl 4) or (b20 ushr 4)
        val channels = ((b20 ushr 1) and 0x07) + 1
        val bps = (((b20 and 0x01) shl 4) or (b21 ushr 4)) + 1
        return if (sr in 8000..384000) {
            FlacStreamInfo(
                sampleRateHz = sr,
                channelCount = channels.coerceIn(1, 8),
                bitDepth = bps.coerceIn(8, 32)
            )
        } else null
    }

    private fun extractFromRetriever(retriever: MediaMetadataRetriever): RetrieverProbe {
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
        val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
        val albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.takeIf { it.isNotBlank() }
        val genreName = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() }
        val yearRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        val releaseYear = yearRaw?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val bitrateKbps = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
        var sampleRateHz = 0
        var bitDepth: Int? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sampleRateHz = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            val bits = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
            if (bits != null && bits in 8..32) bitDepth = bits
        }
        val artwork = runCatching { retriever.embeddedPicture }.getOrNull()
        return RetrieverProbe(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genreName = genreName,
            releaseYear = releaseYear,
            durationMs = durationMs,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            embeddedArtwork = artwork
        )
    }

    private fun extractFromExtractor(extractor: MediaExtractor, format: AudioFormat): ExtractorProbe? {
        for (i in 0 until extractor.trackCount) {
            val mf = extractor.getTrackFormat(i)
            val mime = mf.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                val sr = if (mf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) mf.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                val ch = if (mf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    mf.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 16)
                } else 2
                val br = if (mf.containsKey(MediaFormat.KEY_BIT_RATE)) mf.getInteger(MediaFormat.KEY_BIT_RATE) / 1000 else 0
                val depth = if (mf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    when (mf.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                        AndroidAudioFormat.ENCODING_PCM_8BIT -> 8
                        AndroidAudioFormat.ENCODING_PCM_16BIT -> 16
                        AndroidAudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                        AndroidAudioFormat.ENCODING_PCM_32BIT,
                        AndroidAudioFormat.ENCODING_PCM_FLOAT -> 32
                        else -> null
                    }
                } else null
                val refinedFormat = if (format == AudioFormat.M4A && mime.lowercase().contains("alac")) {
                    AudioFormat.ALAC
                } else {
                    format
                }
                return ExtractorProbe(
                    sampleRateHz = sr,
                    channelCount = ch,
                    audioCodec = mapMimeToCodecLabel(mime, refinedFormat),
                    bitrateKbps = br,
                    bitDepth = depth,
                    detectedFormat = refinedFormat
                )
            }
        }
        return null
    }

    private fun mergeProbeResults(
        format: AudioFormat,
        knownFileSizeBytes: Long,
        flacHeader: FlacStreamInfo?,
        retriever: RetrieverProbe?,
        extractor: ExtractorProbe?
    ): AudioSpecProbeResult {
        val effectiveFormat = extractor?.detectedFormat ?: format
        var sampleRate = flacHeader?.sampleRateHz?.takeIf { it > 0 }
            ?: retriever?.sampleRateHz?.takeIf { it > 0 }
            ?: extractor?.sampleRateHz?.takeIf { it > 0 }
            ?: 0
        val bitDepth = flacHeader?.bitDepth
            ?: retriever?.bitDepth
            ?: extractor?.bitDepth
        val durationMs = retriever?.durationMs ?: 0L
        var bitrateKbps = retriever?.bitrateKbps?.takeIf { it > 0 }
            ?: extractor?.bitrateKbps?.takeIf { it > 0 }
            ?: 0

        if (bitrateKbps <= 0 && durationMs > 1000L && knownFileSizeBytes > 0L) {
            bitrateKbps = ((knownFileSizeBytes * 8L) / durationMs).toInt().coerceIn(32, 9216)
        }
        if (sampleRate <= 0) {
            sampleRate = defaultSampleRateFor(effectiveFormat)
        }
        val codec = when {
            flacHeader != null -> "FLAC (Lossless)"
            extractor?.audioCodec != null -> extractor.audioCodec
            else -> defaultCodecLabelFor(effectiveFormat)
        }

        return AudioSpecProbeResult(
            sampleRateHz = sampleRate,
            bitDepth = bitDepth ?: if (effectiveFormat.isLossless) 16 else null,
            bitrateKbps = bitrateKbps,
            durationMs = durationMs,
            title = retriever?.title,
            artist = retriever?.artist,
            album = retriever?.album,
            albumArtist = retriever?.albumArtist,
            releaseYear = retriever?.releaseYear,
            genreName = retriever?.genreName,
            channelCount = flacHeader?.channelCount ?: extractor?.channelCount ?: 2,
            audioCodec = codec,
            embeddedArtwork = retriever?.embeddedArtwork,
            detectedFormat = effectiveFormat
        )
    }

    fun mapMimeToCodecLabel(mime: String?, format: AudioFormat): String {
        val m = mime.orEmpty().lowercase()
        return when {
            m.contains("flac") -> "FLAC (Lossless)"
            m.contains("alac") -> "ALAC (Apple Lossless)"
            (m.contains("mp4a-latm") || m.contains("aac")) && format == AudioFormat.M4A -> "M4A / AAC"
            m.contains("mp4a-latm") || m.contains("aac") -> "AAC"
            m.contains("mpeg") -> "MPEG Audio Layer III (MP3)"
            m.contains("opus") -> "Opus"
            m.contains("vorbis") -> "Ogg Vorbis"
            m.contains("raw") -> "PCM (WAV)"
            m.contains("ape") -> "Monkey's Audio (APE)"
            else -> defaultCodecLabelFor(format)
        }
    }

    fun defaultCodecLabelFor(format: AudioFormat): String = when (format) {
        AudioFormat.FLAC -> "FLAC (Lossless)"
        AudioFormat.ALAC -> "ALAC (Apple Lossless)"
        AudioFormat.WAV -> "PCM (WAV)"
        AudioFormat.MP3 -> "MPEG Audio Layer III (MP3)"
        AudioFormat.M4A -> "M4A / AAC"
        AudioFormat.AAC -> "AAC (ADTS)"
        AudioFormat.OGG -> "Ogg Vorbis"
        AudioFormat.OPUS -> "Opus"
        AudioFormat.APE -> "Monkey's Audio (APE)"
        AudioFormat.OTHER -> "Audio"
    }
}
