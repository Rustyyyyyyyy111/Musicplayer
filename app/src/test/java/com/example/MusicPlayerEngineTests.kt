package com.example

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.example.audio.BiquadEqAudioProcessor
import com.example.core.model.AudioFormat
import com.example.core.model.EqPreset
import com.example.core.model.EqState
import com.example.core.model.GenreType
import com.example.core.model.MusicSource
import com.example.core.model.Track
import com.example.core.util.AudioFormatDetector
import com.example.core.util.AutoGenreClassifier
import com.example.core.util.DuplicateDetector
import com.example.core.util.VietnameseTextNormalizer
import com.example.data.telegram.TelegramAuthentication
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
}
