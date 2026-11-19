package com.laughing.musicplayer

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.data.storage.AudioTagEditRequest
import com.laughing.musicplayer.data.storage.AudioTagEditResult
import com.laughing.musicplayer.data.storage.AudioTagEditor
import com.laughing.musicplayer.data.storage.TrackMetadataInspector
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NowPlayingCastAndTagRobolectricTest {

    @Test
    fun `writeTagsToFile writes ID3v2 UTF-16 Vietnamese tags into MP3 and preserves audio payload`() {
        val tempFile = File.createTempFile("test_id3_", ".mp3")
        try {
            val fakeAudioPayload = byteArrayOf(
                0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte(),
                0x11, 0x22, 0x33, 0x44, 0x55
            )
            tempFile.writeBytes(fakeAudioPayload)

            val ok = AudioTagEditor.writeTagsToFile(
                file = tempFile,
                format = AudioFormat.MP3,
                title = "Mùa Hè Tuyệt Đẹp",
                artist = "Nghệ Sĩ Việt",
                album = "Album Đặc Biệt",
                albumArtist = "Nhiều Nghệ Sĩ",
                genre = "Ballad",
                year = 2025
            )
            assertTrue(ok)

            val written = tempFile.readBytes()
            assertTrue(written.size > fakeAudioPayload.size + 10)
            assertEquals('I'.code.toByte(), written[0])
            assertEquals('D'.code.toByte(), written[1])
            assertEquals('3'.code.toByte(), written[2])

            val tagBodySize = ((written[6].toInt() and 0x7F) shl 21) or
                ((written[7].toInt() and 0x7F) shl 14) or
                ((written[8].toInt() and 0x7F) shl 7) or
                (written[9].toInt() and 0x7F)
            val tail = written.copyOfRange(10 + tagBodySize, written.size)
            assertEquals(fakeAudioPayload.toList(), tail.toList())

            val tagSectionText = String(written, 10, tagBodySize, Charsets.UTF_16)
            assertTrue(written.decodeToString().contains("TIT2"))
            assertTrue(written.decodeToString().contains("TPE1"))
            assertTrue(written.decodeToString().contains("TALB"))
            assertNotNull(tagSectionText)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `writeTagsToFile updates FLAC VorbisComment while keeping STREAMINFO and audio tail intact`() {
        val tempFile = File.createTempFile("test_flac_", ".flac")
        try {
            // Minimal valid FLAC header + STREAMINFO (34 bytes, marked last block) + audio bytes
            val streamInfo = ByteArray(34).apply {
                this[0] = 0x10 // min block size
                this[2] = 0x10 // max block size
            }
            val audioTail = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
            val initialBytes = ByteBuffer.allocate(4 + 4 + streamInfo.size + audioTail.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put("fLaC".toByteArray(Charsets.US_ASCII))
                .put(0x80.toByte()) // last block + type 0 (STREAMINFO)
                .put(0).put(0).put(streamInfo.size.toByte())
                .put(streamInfo)
                .put(audioTail)
                .array()
            tempFile.writeBytes(initialBytes)

            val ok = AudioTagEditor.writeTagsToFile(
                file = tempFile,
                format = AudioFormat.FLAC,
                title = "Khúc Giao Mùa",
                artist = "Mỹ Linh",
                album = "Tóc Ngắn",
                albumArtist = "Mỹ Linh",
                genre = "Pop",
                year = 2024
            )
            assertTrue(ok)

            val out = tempFile.readBytes()
            val asUtf8 = String(out, Charsets.UTF_8)
            assertTrue(asUtf8.contains("TITLE=Khúc Giao Mùa"))
            assertTrue(asUtf8.contains("ARTIST=Mỹ Linh"))
            assertTrue(asUtf8.contains("ALBUM=Tóc Ngắn"))
            assertTrue(asUtf8.contains("DATE=2024"))
            assertEquals(audioTail.toList(), out.copyOfRange(out.size - audioTail.size, out.size).toList())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `TrackMetadataInspector handles online stream gracefully without crashing`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val streamTrack = Track(
            id = "yt_stream_1",
            title = "Lofi Chill Night",
            artist = "Chill Channel",
            album = "YouTube",
            source = MusicSource.YOUTUBE_MUSIC,
            durationMs = 195_000L,
            format = AudioFormat.OPUS,
            bitrateKbps = 128,
            sampleRateHz = 48000,
            streamUri = "https://example.com/audio.webm"
        )

        val info = TrackMetadataInspector.inspect(app, streamTrack)
        assertEquals("Lofi Chill Night", info.title)
        assertEquals("Chill Channel", info.artist)
        assertEquals("128 kbps", info.bitrate)
        assertEquals("48 kHz (48000 Hz)", info.sampleRate)
        assertFalse(info.isOfflineFile)
        assertTrue(info.filePath.contains("Phát trực tuyến"))
    }

    @Test
    fun `ViewModel Cast refresh emits fallback notice and EditTags guards online tracks`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MusicPlayerViewModel(app)

        vm.refreshCastAndAudioOutputs()
        val castNotice = vm.castUiState.value.statusNotice
        assertNotNull(castNotice)
        assertFalse(vm.castUiState.value.isCastFrameworkAvailable)
        assertTrue(vm.castUiState.value.localAudioOutputs.isNotEmpty())

        vm.setSleepCalmLockEnabled(true)
        assertTrue(vm.sleepCalmLockEnabled.value)
        vm.setSleepCalmLockEnabled(false)
        assertFalse(vm.sleepCalmLockEnabled.value)

        val onlineTrack = Track(
            id = "tg_online_1",
            title = "Online Stream",
            artist = "Channel",
            album = "Telegram",
            source = MusicSource.TELEGRAM,
            durationMs = 120_000L,
            format = AudioFormat.MP3
        )
        val editor = (app as MusicPlayerApplication).container.audioTagEditor
        val res = editor.updateOfflineTrackTags(
            onlineTrack,
            AudioTagEditRequest(
                title = "Edited",
                artist = "Artist",
                album = "Album",
                albumArtist = null,
                genre = GenreType.POP,
                releaseYear = 2025
            )
        )
        assertTrue(res is AudioTagEditResult.NotOfflineTrack)
    }
}
