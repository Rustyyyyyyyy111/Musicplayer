package com.laughing.musicplayer

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.laughing.musicplayer.audio.DualPlayerCrossfadeController
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.data.telegram.TelegramAudioDataSource
import com.laughing.musicplayer.data.telegram.TelegramFileResolver
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TelegramStreamingBoundsRobolectricTest {

    @Test
    fun `computeAvailableBytesAtPosition clamps against sparse disk size and TDLib downloadedEnd`() {
        // Reproduces seeking/reading at exact boundary 219244 while sparse file is preallocated to 5MB
        val atBoundary = TelegramAudioDataSource.computeAvailableBytesAtPosition(
            readPosition = 219_244L,
            downloadStart = 0L,
            downloadedEnd = 219_244L,
            diskLength = 5_000_000L,
            totalFileLength = 5_000_000L,
            isCompleted = false
        )
        assertEquals(0L, atBoundary)

        // Seeking ahead into M4A moov offset before TDLib updates downloadOffset must yield 0 available bytes
        val aheadSeek = TelegramAudioDataSource.computeAvailableBytesAtPosition(
            readPosition = 4_800_000L,
            downloadStart = 0L,
            downloadedEnd = 219_244L,
            diskLength = 5_000_000L,
            totalFileLength = 5_000_000L,
            isCompleted = false
        )
        assertEquals(0L, aheadSeek)

        // Reading near boundary only exposes exact remaining contiguous bytes (44 bytes)
        val nearBoundary = TelegramAudioDataSource.computeAvailableBytesAtPosition(
            readPosition = 219_200L,
            downloadStart = 0L,
            downloadedEnd = 219_244L,
            diskLength = 5_000_000L,
            totalFileLength = 5_000_000L,
            isCompleted = false
        )
        assertEquals(44L, nearBoundary)

        // Completed file still stops at EOF
        val atCompletedEof = TelegramAudioDataSource.computeAvailableBytesAtPosition(
            readPosition = 219_244L,
            downloadStart = 0L,
            downloadedEnd = 219_244L,
            diskLength = 219_244L,
            totalFileLength = 219_244L,
            isCompleted = true
        )
        assertEquals(0L, atCompletedEof)
    }

    @Test
    fun `clampReadLength never exceeds buffer bounds or available downloaded bytes`() {
        // Exact boundary index == array.size (length=219244, offset=219244) must return 0
        assertEquals(
            0,
            TelegramAudioDataSource.clampReadLength(
                requestedLength = 4096,
                bufferSize = 219_244,
                bufferOffset = 219_244,
                availableStreamBytes = 10_000L,
                dataSpecRemaining = C.LENGTH_UNSET.toLong(),
                totalFileLength = 5_000_000L,
                currentReadPosition = 0L
            )
        )

        // Partial tail inside buffer clamped strictly to buffer.size - offset
        assertEquals(
            44,
            TelegramAudioDataSource.clampReadLength(
                requestedLength = 4096,
                bufferSize = 219_244,
                bufferOffset = 219_200,
                availableStreamBytes = 500_000L,
                dataSpecRemaining = C.LENGTH_UNSET.toLong(),
                totalFileLength = 5_000_000L,
                currentReadPosition = 100_000L
            )
        )

        // Large buffer clamped strictly to available progressive TDLib bytes
        assertEquals(
            44,
            TelegramAudioDataSource.clampReadLength(
                requestedLength = 65_536,
                bufferSize = 219_244,
                bufferOffset = 0,
                availableStreamBytes = 44L,
                dataSpecRemaining = C.LENGTH_UNSET.toLong(),
                totalFileLength = 5_000_000L,
                currentReadPosition = 219_200L
            )
        )

        // Clamped by remaining DataSpec byte count
        assertEquals(
            20,
            TelegramAudioDataSource.clampReadLength(
                requestedLength = 4096,
                bufferSize = 65_536,
                bufferOffset = 0,
                availableStreamBytes = 10_000L,
                dataSpecRemaining = 20L,
                totalFileLength = 5_000_000L,
                currentReadPosition = 0L
            )
        )
    }

    @Test
    fun `search and library Telegram tracks normalize to identical MediaItem extras`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MusicPlayerViewModel(app)

        val expectedUri = TelegramFileResolver.buildStreamUri(
            fileId = 7788,
            fileSize = 8_219_244L,
            chatId = -1001234567L,
            messageId = 9001L,
            remoteFileId = "remote_abc",
            trackId = "tg_-1001234567_9001"
        )

        val libraryTrack = Track(
            id = "tg_-1001234567_9001",
            title = "M4A Stream Test",
            artist = "Artist",
            album = "Telegram",
            source = MusicSource.TELEGRAM,
            durationMs = 210_000L,
            format = AudioFormat.M4A,
            fileSizeBytes = 8_219_244L,
            streamUri = expectedUri,
            telegramChatId = -1001234567L,
            telegramMessageId = 9001L,
            telegramFileId = 7788,
            telegramRemoteFileId = "remote_abc",
            mimeType = "audio/mp4"
        )

        // Simulate a Search item missing cached streamUri string (or only carrying IDs)
        val searchTrackWithMissingUri = libraryTrack.copy(streamUri = null)

        val normalizedFromSearch = vm.normalizeTelegramTrackForPlayback(searchTrackWithMissingUri)
        val normalizedFromLibrary = vm.normalizeTelegramTrackForPlayback(libraryTrack)

        assertEquals(normalizedFromLibrary.streamUri, normalizedFromSearch.streamUri)
        assertEquals(normalizedFromLibrary.telegramFileId, normalizedFromSearch.telegramFileId)
        assertEquals(normalizedFromLibrary.telegramChatId, normalizedFromSearch.telegramChatId)
        assertEquals(normalizedFromLibrary.telegramMessageId, normalizedFromSearch.telegramMessageId)
        assertEquals(normalizedFromLibrary.fileSizeBytes, normalizedFromSearch.fileSizeBytes)

        val searchExtras = DualPlayerCrossfadeController.buildMediaItemExtras(
            normalizedFromSearch,
            normalizedFromSearch.streamUri!!
        )
        val libraryExtras = DualPlayerCrossfadeController.buildMediaItemExtras(
            normalizedFromLibrary,
            normalizedFromLibrary.streamUri!!
        )

        assertEquals(libraryExtras.getString(DualPlayerCrossfadeController.EXTRA_URI), searchExtras.getString(DualPlayerCrossfadeController.EXTRA_URI))
        assertEquals(7788, searchExtras.getInt(DualPlayerCrossfadeController.EXTRA_FILE_ID))
        assertEquals(-1001234567L, searchExtras.getLong(DualPlayerCrossfadeController.EXTRA_CHAT_ID))
        assertEquals(9001L, searchExtras.getLong(DualPlayerCrossfadeController.EXTRA_MESSAGE_ID))
        assertEquals(8_219_244L, searchExtras.getLong(DualPlayerCrossfadeController.EXTRA_TOTAL_FILE_SIZE))
        assertNotNull(Uri.parse(searchExtras.getString(DualPlayerCrossfadeController.EXTRA_URI)))
    }
}
