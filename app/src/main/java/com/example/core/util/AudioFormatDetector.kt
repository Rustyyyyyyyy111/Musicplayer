package com.example.core.util

import com.example.core.model.AudioFormat
import java.io.File

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
}
