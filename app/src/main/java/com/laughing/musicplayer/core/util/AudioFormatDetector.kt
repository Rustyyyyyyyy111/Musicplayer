package com.laughing.musicplayer.core.util

import android.media.AudioFormat as AndroidAudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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
    val embeddedArtwork: ByteArray? = null
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
                bitDepth = if (format.isLossless) 16 else null
            )
        }

        var sampleRate = 0
        var bitDepth: Int? = null
        var bitrateKbps = 0
        var durationMs = 0L
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var releaseYear: Int? = null
        var genreName: String? = null
        var channelCount = 2
        var audioCodec: String? = null
        var artwork: ByteArray? = null

        // 1. Parse FLAC STREAMINFO directly if FLAC (gives exact sample rate, bit depth, channels)
        if (format == AudioFormat.FLAC) {
            runCatching {
                val buf = ByteArray(42)
                val read = file.inputStream().use { it.read(buf) }
                if (read >= 26 &&
                    buf[0] == 0x66.toByte() && buf[1] == 0x4C.toByte() &&
                    buf[2] == 0x61.toByte() && buf[3] == 0x43.toByte() &&
                    (buf[4].toInt() and 0x7F) == 0
                ) {
                    val b18 = buf[18].toInt() and 0xFF
                    val b19 = buf[19].toInt() and 0xFF
                    val b20 = buf[20].toInt() and 0xFF
                    val b21 = buf[21].toInt() and 0xFF
                    val sr = (b18 shl 12) or (b19 shl 4) or (b20 ushr 4)
                    val channels = ((b20 ushr 1) and 0x07) + 1
                    val bps = (((b20 and 0x01) shl 4) or (b21 ushr 4)) + 1
                    if (sr in 8000..384000) sampleRate = sr
                    if (channels in 1..8) channelCount = channels
                    if (bps in 8..32) bitDepth = bps
                    audioCodec = "FLAC (Lossless)"
                }
            }
        }

        // 2. Inspect MediaMetadataRetriever for duration, bitrate, tags, and embedded album art
        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(file.absolutePath)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.takeIf { it.isNotBlank() }
            genreName = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() }
            val yearRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            releaseYear = yearRaw?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            bitrateKbps = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sampleRate == 0) {
                sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                val bits = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
                if (bits != null && bits in 8..32 && bitDepth == null) bitDepth = bits
            }
            artwork = retriever.embeddedPicture
        }
        runCatching { retriever.release() }

        // 3. Fallback MediaExtractor for sample rate, channels, codec MIME, and pcm bit depth
        val extractor = MediaExtractor()
        runCatching {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val mf = extractor.getTrackFormat(i)
                val mime = mf.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    if (sampleRate == 0 && mf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = mf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (mf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = mf.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 16)
                    }
                    if (audioCodec == null) {
                        audioCodec = mapMimeToCodecLabel(mime, format)
                    }
                    if (bitrateKbps == 0 && mf.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        bitrateKbps = mf.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                    }
                    if (bitDepth == null && mf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        bitDepth = when (mf.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                            AndroidAudioFormat.ENCODING_PCM_8BIT -> 8
                            AndroidAudioFormat.ENCODING_PCM_16BIT -> 16
                            AndroidAudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                            AndroidAudioFormat.ENCODING_PCM_32BIT,
                            AndroidAudioFormat.ENCODING_PCM_FLOAT -> 32
                            else -> null
                        }
                    }
                    break
                }
            }
        }
        runCatching { extractor.release() }

        // 4. Estimate bitrate from file size & duration when container omits it
        if (bitrateKbps <= 0 && durationMs > 1000L && file.length() > 0L) {
            bitrateKbps = ((file.length() * 8L) / durationMs).toInt().coerceIn(32, 9216)
        }

        return AudioSpecProbeResult(
            sampleRateHz = if (sampleRate > 0) sampleRate else defaultSampleRateFor(format),
            bitDepth = bitDepth ?: if (format.isLossless) 16 else null,
            bitrateKbps = bitrateKbps,
            durationMs = durationMs,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            releaseYear = releaseYear,
            genreName = genreName,
            channelCount = channelCount,
            audioCodec = audioCodec ?: defaultCodecLabelFor(format),
            embeddedArtwork = artwork
        )
    }

    fun mapMimeToCodecLabel(mime: String?, format: AudioFormat): String {
        val m = mime.orEmpty().lowercase()
        return when {
            m.contains("flac") -> "FLAC (Lossless)"
            m.contains("alac") -> "ALAC (Apple Lossless)"
            m.contains("mp4a-latm") || m.contains("aac") -> "AAC (MP4A)"
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
        AudioFormat.M4A -> "AAC / M4A"
        AudioFormat.AAC -> "AAC (ADTS)"
        AudioFormat.OGG -> "Ogg Vorbis"
        AudioFormat.OPUS -> "Opus"
        AudioFormat.APE -> "Monkey's Audio (APE)"
        AudioFormat.OTHER -> "Audio"
    }
}
