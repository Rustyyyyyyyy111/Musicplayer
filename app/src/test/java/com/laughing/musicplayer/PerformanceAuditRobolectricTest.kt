package com.laughing.musicplayer

import android.app.Application
import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.SleepTimerMode
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.data.local.MusicPlayerDatabase
import com.laughing.musicplayer.data.local.TrackEntity
import com.laughing.musicplayer.ui.theme.ArtworkEffectsCache
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PerformanceAuditRobolectricTest {

    @Test
    fun `ArtworkEffectsCache generates blurred ImageBitmap palette and caches default artwork`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()

        val sourceBmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFD9385A.toInt())
        }
        val visuals = ArtworkEffectsCache.buildVisualsFromBitmap(sourceBmp)
        assertNotNull(visuals.blurredBitmap)
        assertTrue(visuals.blurredBitmap!!.width <= 128)
        assertTrue(visuals.blurredBitmap!!.height <= 128)

        val cachedFirst = ArtworkEffectsCache.getDefaultBlurredArtwork(
            app,
            R.drawable.img_default_artwork_1794853244685
        )
        val cachedSecond = ArtworkEffectsCache.getDefaultBlurredArtwork(
            app,
            R.drawable.img_default_artwork_1794853244685
        )
        assertNotNull(cachedFirst)
        assertSame(cachedFirst, cachedSecond)
    }

    @Test
    fun `DualPlayerCrossfadeController syncs playbackProgress for sleep timer and seek without blocking`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val controller = (app as MusicPlayerApplication).container.playerController

        controller.setSleepTimerMinutes(10)
        assertEquals(SleepTimerMode.AFTER_MINUTES, controller.playbackState.value.sleepTimerMode)
        assertEquals(600_000L, controller.playbackProgress.value.sleepTimerRemainingMs)
        assertEquals(600_000L, controller.playbackState.value.sleepTimerRemainingMs)

        controller.cancelSleepTimer()
        assertEquals(SleepTimerMode.OFF, controller.playbackState.value.sleepTimerMode)
        assertEquals(0L, controller.playbackProgress.value.sleepTimerRemainingMs)

        controller.seekTo(42_500L)
        assertEquals(42_500L, controller.playbackProgress.value.positionMs)
    }

    @Test
    fun `TrackDao bulk lookup queries return exact subsets for sync merge optimization`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, MusicPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val base = TrackEntity(
                id = "yt_1",
                title = "Track 1",
                artist = "Artist 1",
                album = "Album 1",
                source = "YOUTUBE_MUSIC",
                durationMs = 200_000L,
                format = "OPUS",
                bitrateKbps = 128,
                sampleRateHz = 48000,
                bitDepth = null,
                fileSizeBytes = 0L,
                fileHash = null,
                fileName = "yt_1.opus",
                artworkUri = null,
                streamUri = "https://youtube.com/watch?v=1",
                localPath = null,
                telegramChatId = null,
                telegramChatTitle = null,
                telegramMessageId = null,
                telegramFileId = null,
                telegramThreadId = null,
                telegramTopicName = null,
                youtubeVideoId = "1",
                genre = "POP",
                genreManuallyEdited = false,
                isVoiceMessage = false,
                downloadStatus = "NONE",
                downloadProgress = 0f,
                isFavorite = true,
                dateAddedEpochMs = 1000L,
                lastPlayedEpochMs = 5000L,
                playCount = 3,
                loudnessGainDb = 0f,
                bpm = 110,
                normalizedSearchBlob = "track 1 artist 1"
            )
            val yt2 = base.copy(id = "yt_2", title = "Track 2", youtubeVideoId = "2")
            val tgChat10 = base.copy(
                id = "tg_10_1",
                source = "TELEGRAM",
                youtubeVideoId = null,
                telegramChatId = 10L,
                telegramMessageId = 1L,
                telegramFileId = 101
            )
            val tgChat20 = base.copy(
                id = "tg_20_1",
                source = "TELEGRAM",
                youtubeVideoId = null,
                telegramChatId = 20L,
                telegramMessageId = 1L,
                telegramFileId = 201
            )
            db.trackDao().upsertTracks(listOf(base, yt2, tgChat10, tgChat20))

            val byIds = db.trackDao().getTracksByIds(listOf("yt_1", "tg_20_1"))
            assertEquals(setOf("yt_1", "tg_20_1"), byIds.map { it.id }.toSet())

            val chat10 = db.trackDao().getTelegramTracksForChat(10L)
            assertEquals(1, chat10.size)
            assertEquals("tg_10_1", chat10.first().id)
        } finally {
            db.close()
        }
    }

    @Test
    fun `Search filters local offline and telegram tracks immediately on query update`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>() as MusicPlayerApplication
        val now = System.currentTimeMillis()
        app.container.database.trackDao().upsertTracks(
            listOf(
                TrackEntity(
                    id = "perf_off_1",
                    title = "Cắt Đôi Nỗi Sầu",
                    artist = "Tăng Duy Tân",
                    album = "Offline",
                    source = MusicSource.OFFLINE.name,
                    durationMs = 210_000L,
                    format = AudioFormat.MP3.name,
                    bitrateKbps = 320,
                    sampleRateHz = 44100,
                    bitDepth = null,
                    fileSizeBytes = 7_000_000L,
                    fileHash = null,
                    fileName = "cat_doi_noi_sau.mp3",
                    artworkUri = null,
                    streamUri = null,
                    localPath = "/sdcard/Music Player/cat_doi.mp3",
                    telegramChatId = null,
                    telegramChatTitle = null,
                    telegramMessageId = null,
                    telegramFileId = null,
                    telegramThreadId = null,
                    telegramTopicName = null,
                    youtubeVideoId = null,
                    genre = "POP",
                    genreManuallyEdited = false,
                    isVoiceMessage = false,
                    downloadStatus = "COMPLETED",
                    downloadProgress = 1f,
                    isFavorite = false,
                    dateAddedEpochMs = now,
                    lastPlayedEpochMs = 0L,
                    playCount = 0,
                    loudnessGainDb = 0f,
                    bpm = 120,
                    normalizedSearchBlob = "cat doi noi sau tang duy tan"
                )
            )
        )

        val vm = MusicPlayerViewModel(app)
        vm.updateSearchQuery("cat doi")

        val matchedState = withTimeout(3_000L) {
            vm.unifiedSearchState.first { state ->
                state.offlineResults.any { it.id == "perf_off_1" }
            }
        }
        assertTrue(matchedState.offlineResults.any { it.id == "perf_off_1" })
    }
}
