package com.example.core.util

import com.example.core.model.AudioFormat
import com.example.core.model.MusicSource
import com.example.core.model.Track
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

data class AudioTagAnalysis(
    val replayGainDb: Float? = null,
    val replayGainPeak: Float? = null,
    val bpm: Int = 0
)

/**
 * Extracts real ReplayGain tags (REPLAYGAIN_TRACK_GAIN, REPLAYGAIN_ALBUM_GAIN,
 * R128_TRACK_GAIN) and tempo (BPM / TBPM) directly from FLAC VorbisComment,
 * MP3 ID3v2 TXXX/TBPM frames, and Ogg/Opus headers without native dependencies.
 */
object ReplayGainExtractor {

    private val dbRegex = Regex("([+-]?\\d+(?:[.,]\\d+)?)")
    private val titleBpmRegex = Regex("\\b(\\d{2,3})\\s*bpm\\b", RegexOption.IGNORE_CASE)

    fun analyzeFile(file: File, titleHint: String = "", fileNameHint: String = ""): AudioTagAnalysis {
        if (!file.exists() || !file.canRead() || file.length() < 16L) {
            return AudioTagAnalysis(bpm = extractBpmFromText(titleHint, fileNameHint))
        }
        val parsed = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(12)
                if (raf.read(header) < 4) return@use AudioTagAnalysis()
                raf.seek(0L)
                when {
                    header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                        header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte() -> parseFlac(raf)
                    header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() &&
                        header[2] == '3'.code.toByte() -> parseId3v2(raf)
                    header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
                        header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte() -> parseOggOpusOrVorbis(raf)
                    else -> AudioTagAnalysis()
                }
            }
        }.getOrDefault(AudioTagAnalysis())

        val resolvedBpm = if (parsed.bpm > 0) parsed.bpm else extractBpmFromText(titleHint, fileNameHint)
        return parsed.copy(bpm = resolvedBpm)
    }

    fun analyzeBytes(bytes: ByteArray, titleHint: String = "", fileNameHint: String = ""): AudioTagAnalysis {
        if (bytes.size < 16) return AudioTagAnalysis(bpm = extractBpmFromText(titleHint, fileNameHint))
        val parsed = when {
            bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte() -> parseFlacBlockBytes(bytes)
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() &&
                bytes[2] == '3'.code.toByte() -> parseId3v2Bytes(bytes)
            else -> AudioTagAnalysis()
        }
        val resolvedBpm = if (parsed.bpm > 0) parsed.bpm else extractBpmFromText(titleHint, fileNameHint)
        return parsed.copy(bpm = resolvedBpm)
    }

    /**
     * Resolves safe playback gain in dB (-12f..+12f) to balance volume across
     * Offline lossless files, Telegram streams/files, and YouTube Music streams.
     */
    fun resolvePlaybackLoudnessGainDb(track: Track): Float {
        if (track.loudnessGainDb != 0f) {
            return track.loudnessGainDb.coerceIn(-12f, 12f)
        }
        return when (track.source) {
            // YouTube Music AAC/OPUS streams master around -14 LUFS; normalize toward -18 LUFS target
            MusicSource.YOUTUBE_MUSIC -> -3.5f
            MusicSource.TELEGRAM -> when (track.format) {
                AudioFormat.FLAC, AudioFormat.WAV, AudioFormat.ALAC -> 0f
                else -> -1.5f
            }
            MusicSource.OFFLINE -> 0f
        }
    }

    fun parseReplayGainDbValue(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val match = dbRegex.find(raw.trim().replace(',', '.')) ?: return null
        return match.groupValues[1].toFloatOrNull()?.coerceIn(-15f, 15f)
    }

    fun parseR128GainQ78(raw: String?): Float? {
        val q78 = raw?.trim()?.toIntOrNull() ?: return null
        // R128_TRACK_GAIN targets -23 LUFS; shift +5 dB to align with -18 LUFS ReplayGain target
        return ((q78 / 256.0f) + 5.0f).coerceIn(-15f, 15f)
    }

    fun extractBpmFromText(vararg texts: String): Int {
        for (t in texts) {
            val match = titleBpmRegex.find(t) ?: continue
            val bpm = match.groupValues[1].toIntOrNull() ?: continue
            if (bpm in 40..260) return bpm
        }
        return 0
    }

    private fun parseFlac(raf: RandomAccessFile): AudioTagAnalysis {
        val maxHeaderRead = minOf(raf.length(), 512L * 1024L).toInt()
        val buf = ByteArray(maxHeaderRead)
        raf.seek(0L)
        val read = raf.read(buf)
        if (read <= 8) return AudioTagAnalysis()
        return parseFlacBlockBytes(buf.copyOf(read))
    }

    private fun parseFlacBlockBytes(bytes: ByteArray): AudioTagAnalysis {
        if (bytes.size < 8) return AudioTagAnalysis()
        var pos = 4
        while (pos + 4 <= bytes.size) {
            val headerByte = bytes[pos].toInt() and 0xFF
            val isLast = (headerByte and 0x80) != 0
            val blockType = headerByte and 0x7F
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            if (length < 0 || pos + length > bytes.size) break
            if (blockType == 4) { // VORBIS_COMMENT
                return parseVorbisCommentPayload(bytes, pos, pos + length)
            }
            pos += length
            if (isLast) break
        }
        return AudioTagAnalysis()
    }

    private fun parseVorbisCommentPayload(bytes: ByteArray, start: Int, end: Int): AudioTagAnalysis {
        var pos = start
        if (pos + 4 > end) return AudioTagAnalysis()
        val vendorLen = readIntLe(bytes, pos)
        pos += 4
        if (vendorLen < 0 || pos + vendorLen + 4 > end) return AudioTagAnalysis()
        pos += vendorLen
        val count = readIntLe(bytes, pos).coerceAtMost(512)
        pos += 4

        var trackGain: Float? = null
        var albumGain: Float? = null
        var r128Gain: Float? = null
        var trackPeak: Float? = null
        var bpm = 0

        for (i in 0 until count) {
            if (pos + 4 > end) break
            val itemLen = readIntLe(bytes, pos)
            pos += 4
            if (itemLen < 0 || pos + itemLen > end) break
            val entry = String(bytes, pos, itemLen, StandardCharsets.UTF_8)
            pos += itemLen
            val eqIdx = entry.indexOf('=')
            if (eqIdx <= 0) continue
            val key = entry.substring(0, eqIdx).trim().uppercase()
            val value = entry.substring(eqIdx + 1).trim()
            when (key) {
                "REPLAYGAIN_TRACK_GAIN" -> trackGain = parseReplayGainDbValue(value)
                "REPLAYGAIN_ALBUM_GAIN" -> albumGain = parseReplayGainDbValue(value)
                "R128_TRACK_GAIN" -> r128Gain = parseR128GainQ78(value)
                "REPLAYGAIN_TRACK_PEAK" -> trackPeak = value.toFloatOrNull()
                "BPM", "TBPM" -> bpm = value.toFloatOrNull()?.toInt()?.takeIf { it in 40..260 } ?: bpm
            }
        }
        return AudioTagAnalysis(
            replayGainDb = trackGain ?: r128Gain ?: albumGain,
            replayGainPeak = trackPeak,
            bpm = bpm
        )
    }

    private fun parseId3v2(raf: RandomAccessFile): AudioTagAnalysis {
        val header = ByteArray(10)
        raf.seek(0L)
        if (raf.read(header) < 10) return AudioTagAnalysis()
        val tagSize = decodeSynchsafeInt(header, 6)
        val total = minOf(tagSize + 10, 512 * 1024)
        val buf = ByteArray(total)
        raf.seek(0L)
        val read = raf.read(buf)
        if (read < 20) return AudioTagAnalysis()
        return parseId3v2Bytes(buf.copyOf(read))
    }

    private fun parseId3v2Bytes(bytes: ByteArray): AudioTagAnalysis {
        if (bytes.size < 20) return AudioTagAnalysis()
        val majorVersion = bytes[3].toInt() and 0xFF
        if (majorVersion !in 3..4) return AudioTagAnalysis()
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = decodeSynchsafeInt(bytes, 6)
        val limit = minOf(bytes.size, 10 + tagSize)
        var pos = 10
        if ((flags and 0x40) != 0 && pos + 4 <= limit) {
            val extSize = if (majorVersion == 4) decodeSynchsafeInt(bytes, pos) else readIntBe(bytes, pos)
            pos += extSize.coerceAtLeast(4)
        }

        var trackGain: Float? = null
        var albumGain: Float? = null
        var trackPeak: Float? = null
        var bpm = 0

        while (pos + 10 <= limit) {
            val frameId = String(bytes, pos, 4, StandardCharsets.ISO_8859_1)
            if (frameId.all { it == '\u0000' }) break
            val frameSize = if (majorVersion == 4) decodeSynchsafeInt(bytes, pos + 4) else readIntBe(bytes, pos + 4)
            pos += 10
            if (frameSize <= 0 || pos + frameSize > limit) break

            if (frameId == "TBPM" && frameSize >= 2) {
                val text = decodeId3Text(bytes, pos, frameSize)
                bpm = text.trim().toFloatOrNull()?.toInt()?.takeIf { it in 40..260 } ?: bpm
            } else if (frameId == "TXXX" && frameSize >= 3) {
                val (desc, value) = decodeId3Txxx(bytes, pos, frameSize)
                when (desc.trim().uppercase()) {
                    "REPLAYGAIN_TRACK_GAIN" -> trackGain = parseReplayGainDbValue(value)
                    "REPLAYGAIN_ALBUM_GAIN" -> albumGain = parseReplayGainDbValue(value)
                    "REPLAYGAIN_TRACK_PEAK" -> trackPeak = value.trim().toFloatOrNull()
                    "BPM", "FBPM" -> bpm = value.trim().toFloatOrNull()?.toInt()?.takeIf { it in 40..260 } ?: bpm
                }
            }
            pos += frameSize
        }

        return AudioTagAnalysis(
            replayGainDb = trackGain ?: albumGain,
            replayGainPeak = trackPeak,
            bpm = bpm
        )
    }

    private fun parseOggOpusOrVorbis(raf: RandomAccessFile): AudioTagAnalysis {
        val len = minOf(raf.length(), 256L * 1024L).toInt()
        val buf = ByteArray(len)
        raf.seek(0L)
        val read = raf.read(buf)
        if (read < 32) return AudioTagAnalysis()
        val markerOpus = "OpusTags".toByteArray(StandardCharsets.ISO_8859_1)
        val idx = indexOfBytes(buf, read, markerOpus)
        if (idx >= 0) {
            return parseVorbisCommentPayload(buf, idx + markerOpus.size, read)
        }
        val markerVorbis = byteArrayOf(0x03) + "vorbis".toByteArray(StandardCharsets.ISO_8859_1)
        val vIdx = indexOfBytes(buf, read, markerVorbis)
        if (vIdx >= 0) {
            return parseVorbisCommentPayload(buf, vIdx + markerVorbis.size, read)
        }
        return AudioTagAnalysis()
    }

    private fun decodeId3Text(bytes: ByteArray, pos: Int, size: Int): String {
        val enc = bytes[pos].toInt() and 0xFF
        val charset = id3Charset(enc)
        return String(bytes, pos + 1, size - 1, charset).trimEnd('\u0000')
    }

    private fun decodeId3Txxx(bytes: ByteArray, pos: Int, size: Int): Pair<String, String> {
        val enc = bytes[pos].toInt() and 0xFF
        val charset = id3Charset(enc)
        val isUtf16 = enc == 1 || enc == 2
        var split = -1
        if (isUtf16) {
            var i = pos + 1
            while (i + 1 < pos + size) {
                if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                    split = i
                    break
                }
                i += 2
            }
            if (split < 0) return "" to ""
            val desc = String(bytes, pos + 1, split - (pos + 1), charset)
            val valStart = (split + 2).coerceAtMost(pos + size)
            val value = String(bytes, valStart, (pos + size) - valStart, charset).trimEnd('\u0000')
            return desc to value
        } else {
            for (i in (pos + 1) until (pos + size)) {
                if (bytes[i] == 0.toByte()) {
                    split = i
                    break
                }
            }
            if (split < 0) return "" to ""
            val desc = String(bytes, pos + 1, split - (pos + 1), charset)
            val valStart = (split + 1).coerceAtMost(pos + size)
            val value = String(bytes, valStart, (pos + size) - valStart, charset).trimEnd('\u0000')
            return desc to value
        }
    }

    private fun id3Charset(enc: Int): Charset = when (enc) {
        1 -> StandardCharsets.UTF_16
        2 -> StandardCharsets.UTF_16BE
        3 -> StandardCharsets.UTF_8
        else -> StandardCharsets.ISO_8859_1
    }

    private fun decodeSynchsafeInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun readIntBe(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun indexOfBytes(haystack: ByteArray, limit: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || limit < needle.size) return -1
        outer@ for (i in 0..(limit - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
