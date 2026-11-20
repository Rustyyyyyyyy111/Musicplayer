package com.laughing.musicplayer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.data.local.ListeningBehaviorSnapshot
import com.laughing.musicplayer.data.local.MusicPlayerDatabase
import com.laughing.musicplayer.data.local.TimeOfDayBucket
import com.laughing.musicplayer.data.local.TrackListeningSignal
import com.laughing.musicplayer.data.local.toEntity
import com.laughing.musicplayer.integrations.AiSmartMixEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiSmartPlaylistAndYouTubeRadioRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun dismissOnboardingIfShown() {
        val laterNodes = composeRule.onAllNodesWithTag("onboarding_later_btn").fetchSemanticsNodes()
        if (laterNodes.isNotEmpty()) {
            composeRule.onNodeWithTag("onboarding_later_btn").performClick()
            composeRule.waitForIdle()
        }
    }

    private fun sampleTrack(
        id: String,
        title: String,
        artist: String,
        genre: GenreType = GenreType.POP,
        playCount: Int = 0,
        isFavorite: Boolean = false,
        lastPlayedMs: Long = 0L,
        source: MusicSource = MusicSource.OFFLINE
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = "Personal Album",
            source = source,
            durationMs = 210_000L,
            format = AudioFormat.M4A,
            bitrateKbps = 256,
            sampleRateHz = 44100,
            bitDepth = 16,
            fileName = "$title.m4a",
            genre = genre,
            playCount = playCount,
            isFavorite = isFavorite,
            lastPlayedEpochMs = lastPlayedMs
        )
    }

    @Test
    fun aiSmartMixEngineBalancesFamiliarAndDiscoveryTracksAndPenalizesSkips() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as MusicPlayerApplication
            val engine = AiSmartMixEngine(app.container.youTubeMusicRepository, app.container.activityLogger)
            val now = System.currentTimeMillis()

            val favoriteCompleted = sampleTrack(
                id = "t_fav",
                title = "Em Của Ngày Hôm Qua (Ballad)",
                artist = "Sơn Tùng M-TP",
                genre = GenreType.BALLAD,
                playCount = 14,
                isFavorite = true,
                lastPlayedMs = now - 3_600_000L
            )
            val heavilySkipped = sampleTrack(
                id = "t_skip",
                title = "Bài Bị Bỏ Qua",
                artist = "Random Artist",
                genre = GenreType.ROCK,
                playCount = 2,
                isFavorite = false,
                lastPlayedMs = now - 7_200_000L
            )
            val familiarTracks = (1..12).map { i ->
                sampleTrack(
                    id = "fam_$i",
                    title = "Bản Ballad Quen Thuộc $i",
                    artist = if (i % 2 == 0) "Sơn Tùng M-TP" else "Hoàng Dũng",
                    genre = GenreType.BALLAD,
                    playCount = 5 + i,
                    lastPlayedMs = now - (i * 86_400_000L)
                )
            }
            val freshDiscoveries = (1..8).map { i ->
                sampleTrack(
                    id = "disc_$i",
                    title = "Lofi Chill Đêm Khuya Mới $i",
                    artist = "Nghệ Sĩ Mới $i",
                    genre = GenreType.ACOUSTIC,
                    playCount = 0,
                    lastPlayedMs = 0L
                )
            }

            val snapshot = ListeningBehaviorSnapshot(
                trackSignals = mapOf(
                    "t_fav" to TrackListeningSignal(playStarts = 14, completions = 12, earlySkips = 0, lastUpdatedMs = now),
                    "t_skip" to TrackListeningSignal(playStarts = 6, completions = 0, earlySkips = 6, lastUpdatedMs = now)
                ),
                artistAffinity = mapOf("sơn tùng m-tp" to 14f, "hoàng dũng" to 8f),
                keywordAffinity = mapOf("Ballad" to 10f, "Lofi" to 6f),
                timeBucketGenres = mapOf(
                    TimeOfDayBucket.LATE_NIGHT.name to mapOf(GenreType.BALLAD.name to 9, GenreType.ACOUSTIC.name to 5)
                )
            )

            val mix = engine.generatePersonalMix(
                allTracks = listOf(favoriteCompleted, heavilySkipped) + familiarTracks + freshDiscoveries,
                snapshot = snapshot,
                hourOfDay = 23, // LATE_NIGHT
                refreshSeed = 0,
                allowOnlineDiscovery = false,
                targetSize = 18
            )

            assertEquals(18, mix.tracks.size)
            assertEquals(TimeOfDayBucket.LATE_NIGHT, mix.timeBucket)
            assertTrue("Expected familiar tracks (~70%)", mix.familiarCount >= 10)
            assertTrue("Expected fresh discovery tracks (~30%)", mix.discoveryCount in 4..7)
            assertTrue("Favorite track should appear in familiar selection", mix.tracks.any { it.id == "t_fav" })
            assertFalse("Heavily skipped track should be excluded when better candidates exist", mix.tracks.any { it.id == "t_skip" })
            assertEquals("No duplicate track IDs allowed", mix.tracks.size, mix.tracks.map { it.id }.distinct().size)
        }
    }

    @Test
    fun appendTracksToQueueAppendsUpNextContinuouslyAndRejectsStaleSeeds() {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as MusicPlayerApplication
        val controller = app.container.playerController
        val seed = sampleTrack("yt_seed", "Mưa Tháng Sáu", "Văn Mai Hương", source = MusicSource.YOUTUBE_MUSIC)
        val related1 = sampleTrack("yt_rel1", "Đại Minh Tinh", "Văn Mai Hương", source = MusicSource.YOUTUBE_MUSIC)
        val related2 = sampleTrack("yt_rel2", "Hương", "Văn Mai Hương", source = MusicSource.YOUTUBE_MUSIC)
        val dupOfSeed = sampleTrack("yt_seed_dup", "Mưa Tháng Sáu", "Văn Mai Hương", source = MusicSource.YOUTUBE_MUSIC)

        controller.playQueue(listOf(seed), 0)
        assertEquals(listOf("yt_seed"), controller.playbackState.value.queue.map { it.id })

        // Stale seed must be ignored cleanly
        val staleAdded = controller.appendTracksToQueue(
            tracks = listOf(related1),
            expectedCurrentTrackId = "yt_other_song"
        )
        assertEquals(0, staleAdded)

        // Matching seed appends non-duplicate Up Next tracks without interrupting current track
        val added = controller.appendTracksToQueue(
            tracks = listOf(related1, related2, dupOfSeed),
            expectedCurrentTrackId = "yt_seed"
        )
        assertEquals(2, added)
        assertEquals(listOf("yt_seed", "yt_rel1", "yt_rel2"), controller.playbackState.value.queue.map { it.id })
        assertEquals("yt_seed", controller.playbackState.value.currentTrack?.id)
    }

    @Test
    fun listenNowDisplaysAiSmartPlaylistHeroCard5PreviewTracksSheetAndPlayAll() {
        dismissOnboardingIfShown()
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as MusicPlayerApplication
        val now = System.currentTimeMillis()
        runBlocking {
            val seedTracks = (1..10).map { idx ->
                val t = sampleTrack(
                    id = "ui_mix_$idx",
                    title = "Lofi Chill Session $idx",
                    artist = "Nghệ sĩ $idx",
                    genre = GenreType.ACOUSTIC,
                    playCount = if (idx <= 7) 10 - idx else 0,
                    isFavorite = idx == 1,
                    lastPlayedMs = if (idx <= 7) now - idx * 60_000L else 0L
                )
                t.toEntity(VietnameseTextNormalizer.normalize("${t.title} ${t.artist}"))
            }
            app.container.database.trackDao().upsertTracks(seedTracks)
        }

        composeRule.onNodeWithTag("listen_now_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_smart_playlist_card").assertIsDisplayed()
        composeRule.onNodeWithTag("ai_mix_refresh_btn").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("ai_mix_preview_row_4").fetchSemanticsNodes().isNotEmpty()
        }

        // Only 5 inline preview rows on hero card (index 0..4), row 5 is inside the sheet
        composeRule.onNodeWithTag("ai_mix_preview_row_0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ai_mix_preview_row_4").performScrollTo().assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("ai_mix_preview_row_5").fetchSemanticsNodes().size)

        // Open full mix sheet via "Xem tất cả"
        composeRule.onNodeWithTag("ai_mix_view_all_btn").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ai_smart_playlist_sheet").assertIsDisplayed()

        // Tap Play All inside the sheet -> loads full generated list into player queue
        composeRule.onNodeWithTag("ai_mix_sheet_play_all_btn").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(10, app.container.playerController.playbackState.value.queue.size)
    }
}
