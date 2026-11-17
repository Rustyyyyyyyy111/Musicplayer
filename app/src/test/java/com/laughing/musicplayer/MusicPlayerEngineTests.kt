package com.laughing.musicplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.laughing.musicplayer.audio.BiquadEqAudioProcessor
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.EqPreset
import com.laughing.musicplayer.core.model.EqState
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.model.toAudioQualityDisplay
import com.laughing.musicplayer.core.util.AudioFormatDetector
import com.laughing.musicplayer.core.util.AutoGenreClassifier
import com.laughing.musicplayer.core.util.DuplicateDetector
import com.laughing.musicplayer.core.util.ReplayGainExtractor
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.data.telegram.TelegramAuthentication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MusicPlayerEngineTests {

    @Test
    fun vietnameseTextNormalizerHandlesDiacriticsAndDStroke() {
        assertTrue(VietnameseTextNormalizer.matches("Đêm Trăng Tình Yêu - Lệ Quyên", "dem trang le quyen"))
        assertTrue(VietnameseTextNormalizer.matches("Sơn Tùng M-TP — Chúng Ta Của Hiện Tại", "son tung chung ta"))
        assertFalse(VietnameseTextNormalizer.matches("Bolero Trữ Tình", "edm vinahouse"))
    }

    @Test
    fun audioFormatDetectorAcceptsAudioAndDocumentsButRejectsVideoAndVoiceByDefault() {
        assertEquals(
            AudioFormat.FLAC,
            AudioFormatDetector.detectFromMetadata("lossless_track.flac", "application/octet-stream")
        )
        assertEquals(
            AudioFormat.WAV,
            AudioFormatDetector.detectFromMetadata("master.wav", "audio/wav")
        )
        assertNull(
            AudioFormatDetector.detectFromMetadata("clip.mp4", "video/mp4")
        )
        assertNull(
            AudioFormatDetector.detectFromMetadata("voice.ogg", "audio/ogg", isVoiceNote = true, includeVoiceMessages = false)
        )
        assertEquals(
            AudioFormat.OGG,
            AudioFormatDetector.detectFromMetadata("voice.ogg", "audio/ogg", isVoiceNote = true, includeVoiceMessages = true)
        )
        // Magic bytes for FLAC ("fLaC")
        assertEquals(
            AudioFormat.FLAC,
            AudioFormatDetector.detectFromSignature(byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0x00, 0x00))
        )
    }

    @Test
    fun autoGenreClassifierDetectsVietnameseAndGlobalGenres() {
        assertEquals(GenreType.BOLERO, AutoGenreClassifier.classify("Duyên Phận", "Lệ Quyên"))
        assertEquals(GenreType.REMIX, AutoGenreClassifier.classify("Ngày Mai Người Ta Lấy Chồng (Vinahouse Remix)"))
        assertEquals(GenreType.BALLAD, AutoGenreClassifier.classify("Tháng Tư Là Lời Nói Dối Của Em", "Hà Anh Tuấn"))
        assertEquals(GenreType.RAP_HIPHOP, AutoGenreClassifier.classify("Mang Tiền Về Cho Mẹ", "Đen Vâu"))
        assertEquals(GenreType.CLASSICAL, AutoGenreClassifier.classify("Moonlight Sonata", "Beethoven"))
    }

    @Test
    fun duplicateDetectorMergesIdenticalTracksAndKeepsDistinctRemixesSeparate() {
        val mp3Copy = Track(
            id = "t_mp3",
            title = "Chúng Ta Của Hiện Tại",
            artist = "Sơn Tùng M-TP",
            album = "Single",
            source = MusicSource.TELEGRAM,
            durationMs = 301_000L,
            format = AudioFormat.MP3,
            bitrateKbps = 320,
            fileSizeBytes = 12_000_000L
        )
        val flacCopy = mp3Copy.copy(
            id = "t_flac",
            format = AudioFormat.FLAC,
            bitrateKbps = 1411,
            bitDepth = 24,
            fileSizeBytes = 42_000_000L
        )
        val remixVersion = mp3Copy.copy(
            id = "t_remix",
            title = "Chúng Ta Của Hiện Tại (Vinahouse Remix)",
            durationMs = 301_000L
        )

        val groups = DuplicateDetector.findDuplicates(listOf(mp3Copy, flacCopy, remixVersion))
        assertEquals(1, groups.size)
        assertEquals("t_flac", groups.first().recommendedKeepId)
        assertFalse(groups.first().tracks.any { it.id == "t_remix" })
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun biquadEqAudioProcessorProcessesPcm16WithSoftClippingProtection() {
        val eqProcessor = BiquadEqAudioProcessor()
        eqProcessor.updateSettings(
            EqState(
                enabled = true,
                preset = EqPreset.BASS_BOOST,
                bandGainsDb = EqPreset.BASS_BOOST.gainsDb,
                preampDb = 6.0f,
                clippingProtection = true
            )
        )
        val inputFmt = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        val outFmt = eqProcessor.configure(inputFmt)
        assertEquals(inputFmt, outFmt)
        eqProcessor.flush()
        assertTrue(eqProcessor.isActive)

        // Feed 128 stereo PCM16 frames near full scale to verify clipping protection stays bounded
        val pcmIn = ByteBuffer.allocateDirect(128 * 4).order(ByteOrder.nativeOrder())
        repeat(128 * 2) { idx ->
            val valSample = if (idx % 2 == 0) 26000.toShort() else (-26000).toShort()
            pcmIn.putShort(valSample)
        }
        pcmIn.flip()
        eqProcessor.queueInput(pcmIn)
        val out = eqProcessor.output
        assertTrue(out.remaining() == 128 * 4)
        while (out.remaining() >= 2) {
            val s = out.short.toInt()
            assertTrue("Sample $s must be within 16-bit PCM bounds", s in Short.MIN_VALUE..Short.MAX_VALUE)
        }
    }

    @Test
    fun telegramPhoneNormalizationAndErrorTranslationWorkForVietnameseNumbers() {
        assertEquals("+84901234567", TelegramAuthentication.normalizePhone("0901234567"))
        assertEquals("+84901234567", TelegramAuthentication.normalizePhone("+84 901-234-567"))
        assertEquals("+84901234567", TelegramAuthentication.normalizePhone("84901234567"))

        assertTrue(
            TelegramAuthentication.translateTdLibAuthError("PHONE_NUMBER_INVALID")
                .contains("+84")
        )
        assertTrue(
            TelegramAuthentication.translateTdLibAuthError("PHONE_CODE_INVALID")
                .contains("không chính xác")
        )
        assertTrue(
            TelegramAuthentication.translateTdLibAuthError("PHONE_CODE_EXPIRED")
                .contains("hết hạn")
        )
        assertTrue(
            TelegramAuthentication.translateTdLibAuthError("PASSWORD_HASH_INVALID")
                .contains("2FA")
        )
    }

    @Test
    fun replayGainExtractorParsesFlacVorbisCommentAndMp3Id3v2Tags() {
        // 1. Synthesize minimal FLAC stream with VORBIS_COMMENT metadata block (type 4, last-block bit set)
        val comments = listOf("REPLAYGAIN_TRACK_GAIN=-6.25 dB", "BPM=138")
        val vendorBytes = "MusicPlayer".toByteArray(Charsets.UTF_8)
        val commentPayload = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(vendorBytes.size)
            put(vendorBytes)
            putInt(comments.size)
            comments.forEach { c ->
                val b = c.toByteArray(Charsets.UTF_8)
                putInt(b.size)
                put(b)
            }
            flip()
        }
        val payloadBytes = ByteArray(commentPayload.remaining()).also { commentPayload.get(it) }
        val flacBytes = ByteBuffer.allocate(8 + payloadBytes.size).apply {
            put("fLaC".toByteArray(Charsets.US_ASCII))
            put((0x80 or 0x04).toByte()) // last metadata block = true, type = 4 (VORBIS_COMMENT)
            put(((payloadBytes.size ushr 16) and 0xFF).toByte())
            put(((payloadBytes.size ushr 8) and 0xFF).toByte())
            put((payloadBytes.size and 0xFF).toByte())
            put(payloadBytes)
        }.array()

        val flacAnalysis = ReplayGainExtractor.analyzeBytes(flacBytes)
        assertEquals(-6.25f, flacAnalysis.replayGainDb!!, 0.01f)
        assertEquals(138, flacAnalysis.bpm)

        // 2. Synthesize minimal ID3v2.3 header with TXXX (REPLAYGAIN_TRACK_GAIN) + TBPM
        val txxxPayload = byteArrayOf(0x00) +
            "REPLAYGAIN_TRACK_GAIN\u0000+2.50 dB".toByteArray(Charsets.ISO_8859_1)
        val tbpmPayload = byteArrayOf(0x00) + "134".toByteArray(Charsets.ISO_8859_1)
        val totalFramesSize = 10 + txxxPayload.size + 10 + tbpmPayload.size
        val id3Bytes = ByteBuffer.allocate(10 + totalFramesSize).order(ByteOrder.BIG_ENDIAN).apply {
            put("ID3".toByteArray(Charsets.US_ASCII))
            put(0x03.toByte()) // ID3v2.3
            put(0x00.toByte())
            put(0x00.toByte())
            // synchsafe size
            put(((totalFramesSize ushr 21) and 0x7F).toByte())
            put(((totalFramesSize ushr 14) and 0x7F).toByte())
            put(((totalFramesSize ushr 7) and 0x7F).toByte())
            put((totalFramesSize and 0x7F).toByte())
            // TXXX frame
            put("TXXX".toByteArray(Charsets.US_ASCII))
            putInt(txxxPayload.size)
            putShort(0)
            put(txxxPayload)
            // TBPM frame
            put("TBPM".toByteArray(Charsets.US_ASCII))
            putInt(tbpmPayload.size)
            putShort(0)
            put(tbpmPayload)
        }.array()

        val mp3Analysis = ReplayGainExtractor.analyzeBytes(id3Bytes)
        assertEquals(2.50f, mp3Analysis.replayGainDb!!, 0.01f)
        assertEquals(134, mp3Analysis.bpm)

        // 3. Verify cross-source fallback normalization when no embedded tag is present
        val ytTrack = Track(
            id = "yt_1",
            title = "Test",
            artist = "Artist",
            album = "YT",
            source = MusicSource.YOUTUBE_MUSIC,
            durationMs = 200_000L,
            format = AudioFormat.M4A,
            loudnessGainDb = 0f
        )
        assertTrue(ReplayGainExtractor.resolvePlaybackLoudnessGainDb(ytTrack) < 0f)
    }

    @Test
    fun audioQualityDisplayFormatsLosslessAndLossySpecsCorrectly() {
        val flacTrack = Track(
            id = "tg_flac_1",
            title = "Lossless Song",
            artist = "Artist",
            album = "Album",
            source = MusicSource.TELEGRAM,
            durationMs = 240_000L,
            format = AudioFormat.FLAC,
            bitrateKbps = 1411,
            sampleRateHz = 48000,
            bitDepth = 24
        ).toAudioQualityDisplay()
        assertTrue(flacTrack.isLossless)
        assertEquals("FLAC", flacTrack.formatBadge)
        assertTrue(flacTrack.specText.contains("24-bit / 48kHz"))
        assertTrue(flacTrack.specText.contains("1411 kbps"))

        val opusTrack = Track(
            id = "tg_opus_1",
            title = "Opus Song",
            artist = "Artist",
            album = "Album",
            source = MusicSource.TELEGRAM,
            durationMs = 190_000L,
            format = AudioFormat.OPUS,
            bitrateKbps = 160,
            sampleRateHz = 48000
        ).toAudioQualityDisplay()
        assertFalse(opusTrack.isLossless)
        assertEquals("OPUS", opusTrack.formatBadge)
        assertTrue(opusTrack.specText.contains("48kHz"))
        assertTrue(opusTrack.specText.contains("160 kbps"))
    }

    @Test
    fun youtubeCookieParserDetectsAuthTokensAndMergesDuplicates() {
        val merged = com.laughing.musicplayer.data.youtube.YouTubeSessionCookieManager.parseAndMergeCookies(
            "PREF=f1=50000000; SID=old_sid",
            "SAPISID=abc_sapi; SID=new_sid; __Secure-3PAPISID=xyz"
        )
        assertEquals("new_sid", merged["SID"])
        assertEquals("abc_sapi", merged["SAPISID"])
        assertTrue(
            com.laughing.musicplayer.data.youtube.YouTubeSessionCookieManager.isAuthenticatedCookieHeader(
                "PREF=f1=50000000; SAPISID=abc_sapi"
            )
        )
        assertFalse(
            com.laughing.musicplayer.data.youtube.YouTubeSessionCookieManager.isAuthenticatedCookieHeader(
                "PREF=f1=50000000; VISITOR_INFO1_LIVE=anon"
            )
        )
    }
}
