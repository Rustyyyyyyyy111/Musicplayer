package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.util.ActivityLogger
import com.example.core.util.SecureCredentialStore
import com.example.data.local.CacheEntryEntity
import com.example.data.local.MusicPlayerDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.storage.CacheManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun readVietnameseAppNameFromContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Music Player", appName)
    }

    @Test
    fun mainNavigationAndLibrarySourceSwitcherWork() {
        composeRule.waitForIdle()
        // Primary Vietnamese bottom navigation tabs are visible
        composeRule.onNodeWithTag("nav_listen_now").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_library").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_search").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_settings").assertIsDisplayed()

        // Navigate to Thư viện and verify segmented switcher [Telegram | YouTube Music | Offline]
        composeRule.onNodeWithTag("nav_library").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("library_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("source_tab_telegram").assertIsDisplayed()
        composeRule.onNodeWithTag("source_tab_youtube_music").assertIsDisplayed()
        composeRule.onNodeWithTag("source_tab_offline").assertIsDisplayed()

        composeRule.onNodeWithTag("source_tab_youtube_music").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tìm trên YouTube Music").assertIsDisplayed()

        composeRule.onNodeWithTag("source_tab_offline").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Quét nhạc Offline ngay").assertIsDisplayed()
    }

    @Test
    fun settingsScreenDisplaysAllFiveVietnameseSectionsAndThemeOptions() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Giao diện").assertIsDisplayed()
        composeRule.onNodeWithTag("setting_theme_light").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setting_theme_dark").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setting_theme_liquid_glass").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasText("Nguồn nhạc"))
        composeRule.onNodeWithText("Nguồn nhạc").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasText("Nhạc và phát lại"))
        composeRule.onNodeWithText("Nhạc và phát lại").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasText("Bộ nhớ"))
        composeRule.onNodeWithText("Bộ nhớ").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasText("Hệ thống"))
        composeRule.onNodeWithText("Hệ thống").assertIsDisplayed()
    }

    @Test
    fun cacheManagerEvictsOldTemporaryFilesWithoutDeletingPermanentOfflineFiles() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, MusicPlayerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val settings = SettingsDataStore(context, SecureCredentialStore(context))
            settings.setCacheLimitBytes(1000L)
            val logger = ActivityLogger(db.activityLogDao())
            val cacheManager = CacheManager(context, db.downloadCacheDao(), settings, logger)

            val tempDir = File(context.cacheDir, "test_cache_eviction").apply { mkdirs() }
            val oldTemp = File(tempDir, "old_stream.cache").apply { writeBytes(ByteArray(800)) }
            val newTemp = File(tempDir, "new_stream.cache").apply { writeBytes(ByteArray(600)) }
            val permanentOffline = File(tempDir, "saved_offline.flac").apply { writeBytes(ByteArray(2000)) }

            db.downloadCacheDao().upsertCacheEntry(
                CacheEntryEntity("k1", "t1", oldTemp.absolutePath, oldTemp.length(), false, 100L)
            )
            db.downloadCacheDao().upsertCacheEntry(
                CacheEntryEntity("k2", "t2", newTemp.absolutePath, newTemp.length(), false, 200L)
            )
            db.downloadCacheDao().upsertCacheEntry(
                CacheEntryEntity("k3", "t3", permanentOffline.absolutePath, permanentOffline.length(), true, 50L)
            )

            cacheManager.enforceCacheLimit()

            assertFalse("Old temporary cache entry should be evicted", oldTemp.exists())
            assertTrue("Newer temporary cache within 1000B limit should remain", newTemp.exists())
            assertTrue("Permanent offline file must NEVER be evicted", permanentOffline.exists())

            db.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun lyricsEngineParsesLrcTimestampsAndRomanizesHangul() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MusicPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val logger = ActivityLogger(db.activityLogDao())
        val settings = SettingsDataStore(context, SecureCredentialStore(context))
        val storage = com.example.data.storage.UnifiedStorageManager(context, settings, logger)
        val engine = com.example.lyrics.LyricsEngine(storage, { okhttp3.OkHttpClient() }, logger)

        val lrc = """
            [00:12.50]Em ơi đừng khóc nữa
            [00:18.00]사랑해
        """.trimIndent()
        val parsed = engine.parseLrc(lrc)
        assertEquals(2, parsed.size)
        assertEquals(12_500L, parsed[0].timestampMs)
        assertEquals("Em ơi đừng khóc nữa", parsed[0].text)
        assertEquals(18_000L, parsed[1].timestampMs)
        org.junit.Assert.assertNotNull(parsed[1].romanization)
        db.close()
    }

    @Test
    fun telegramAuthScreenShowsPhoneAndCodeStepsAndApiCredentialForm() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("listen_now_screen").performScrollToNode(hasTestTag("tg_setup_btn"))
        composeRule.onNodeWithTag("tg_setup_btn").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("telegram_auth_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Tài khoản Telegram").assertIsDisplayed()
        composeRule.onNodeWithText("Số điện thoại").assertIsDisplayed()
        composeRule.onNodeWithText("Mã Telegram").assertIsDisplayed()
        composeRule.onNodeWithText("Hoàn tất").assertIsDisplayed()

        // When Secrets API ID/Hash are unset in Robolectric, the encrypted API setup card appears without blocking
        composeRule.onNodeWithTag("telegram_auth_screen").performScrollToNode(hasTestTag("tg_api_id_input"))
        composeRule.onNodeWithTag("tg_api_id_input").assertIsDisplayed()
        composeRule.onNodeWithTag("tg_api_id_input").performTextInput("123456")
        composeRule.onNodeWithTag("telegram_auth_screen").performScrollToNode(hasTestTag("tg_api_hash_input"))
        composeRule.onNodeWithTag("tg_api_hash_input").assertIsDisplayed()
        composeRule.onNodeWithTag("tg_api_hash_input").performTextInput("0123456789abcdef0123456789abcdef")
        composeRule.waitForIdle()
    }

    @Test
    fun smartPlaylistDaoQueriesReturnCorrectFilteredTracks() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, MusicPlayerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val now = System.currentTimeMillis()
            val fortyFiveDaysAgo = now - (45L * 24L * 60L * 60L * 1000L)
            val thirtyDaysAgo = now - (30L * 24L * 60L * 60L * 1000L)

            val trackNeverPlayed = com.example.data.local.TrackEntity(
                id = "t_never",
                title = "Bài Chưa Nghe",
                artist = "A",
                album = "Alb",
                source = "OFFLINE",
                durationMs = 180_000L,
                format = "FLAC",
                bitrateKbps = 1411,
                sampleRateHz = 44100,
                bitDepth = 16,
                fileSizeBytes = 10_000L,
                fileHash = null,
                fileName = "a.flac",
                artworkUri = null,
                streamUri = null,
                localPath = "/tmp/a.flac",
                telegramChatId = null,
                telegramChatTitle = null,
                telegramMessageId = null,
                telegramFileId = null,
                telegramThreadId = null,
                telegramTopicName = null,
                youtubeVideoId = null,
                genre = "BALLAD",
                genreManuallyEdited = false,
                isVoiceMessage = false,
                downloadStatus = "COMPLETED",
                downloadProgress = 1f,
                isFavorite = false,
                dateAddedEpochMs = now - 5_000L,
                lastPlayedEpochMs = 0L,
                playCount = 0,
                loudnessGainDb = 0f,
                bpm = 96,
                normalizedSearchBlob = "bai chua nghe a"
            )
            val trackOldPlayed = trackNeverPlayed.copy(
                id = "t_old",
                title = "Bài Lâu Rồi",
                lastPlayedEpochMs = fortyFiveDaysAgo,
                playCount = 2,
                bpm = 118
            )
            val trackHotFast = trackNeverPlayed.copy(
                id = "t_hot_fast",
                title = "Vinahouse Nonstop 142 BPM",
                lastPlayedEpochMs = now - 3_600_000L,
                playCount = 25,
                bpm = 142
            )

            db.trackDao().upsertTracks(listOf(trackNeverPlayed, trackOldPlayed, trackHotFast))

            val manager = com.example.integrations.PlaylistAndSmartMixManager(
                db.playlistDao(),
                db.trackDao(),
                ActivityLogger(db.activityLogDao())
            )
            val unplayed = db.trackDao().observeNotPlayedIn30Days(thirtyDaysAgo).first()
            assertTrue(unplayed.any { it.id == "t_never" })
            assertTrue(unplayed.any { it.id == "t_old" })
            assertFalse(unplayed.any { it.id == "t_hot_fast" })

            val highBpm = manager.observeSmartPlaylist(com.example.core.model.SmartPlaylistType.HIGH_ENERGY_130_BPM).first()
            assertEquals(1, highBpm.size)
            assertEquals("t_hot_fast", highBpm.first().id)

            val mostPlayed = manager.observeSmartPlaylist(com.example.core.model.SmartPlaylistType.MOST_PLAYED_THIS_MONTH).first()
            assertEquals("t_hot_fast", mostPlayed.firstOrNull()?.id)

            db.close()
        }
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun advancedSleepTimerTrackModesDecrementAndStopProperly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = (context.applicationContext as MusicPlayerApplication).container.playerController

        controller.setSleepTimerEndOfTrack()
        assertEquals(com.example.core.model.SleepTimerMode.END_OF_CURRENT_TRACK, controller.playbackState.value.sleepTimerMode)
        assertTrue(controller.consumeTrackSleepTimerOnEnd())
        assertEquals(com.example.core.model.SleepTimerMode.OFF, controller.playbackState.value.sleepTimerMode)

        controller.setSleepTimerAfterTracks(2)
        assertEquals(com.example.core.model.SleepTimerMode.AFTER_N_TRACKS, controller.playbackState.value.sleepTimerMode)
        assertEquals(2, controller.playbackState.value.sleepTimerRemainingTracks)
        assertFalse(controller.consumeTrackSleepTimerOnEnd())
        assertEquals(1, controller.playbackState.value.sleepTimerRemainingTracks)
        assertTrue(controller.consumeTrackSleepTimerOnEnd())
        assertEquals(com.example.core.model.SleepTimerMode.OFF, controller.playbackState.value.sleepTimerMode)
    }

    @Test
    fun unifiedSearchTabsAndSmartPlaylistsUiAreInteractive() {
        composeRule.waitForIdle()
        // 1. Unified Search Tabs
        composeRule.onNodeWithTag("nav_search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("search_tab_all").assertIsDisplayed()
        composeRule.onNodeWithTag("search_tab_offline").assertIsDisplayed()
        composeRule.onNodeWithTag("search_tab_telegram").assertIsDisplayed()
        composeRule.onNodeWithTag("unified_search_tabs").performScrollToNode(hasTestTag("search_tab_youtube_music"))
        composeRule.onNodeWithTag("search_tab_youtube_music").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("yt_search_submit").assertIsDisplayed()

        // 2. Library Smart Playlists
        composeRule.onNodeWithTag("nav_library").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("library_category_row").performScrollToNode(hasTestTag("lib_cat_playlists"))
        composeRule.onNodeWithTag("lib_cat_playlists").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("library_content_list").performScrollToNode(hasTestTag("smart_playlist_not_played_30_days"))
        composeRule.onNodeWithTag("smart_playlist_not_played_30_days").assertIsDisplayed()
        composeRule.onNodeWithTag("library_content_list").performScrollToNode(hasTestTag("smart_playlist_most_played_this_month"))
        composeRule.onNodeWithTag("smart_playlist_most_played_this_month").assertIsDisplayed()
        composeRule.onNodeWithTag("library_content_list").performScrollToNode(hasTestTag("smart_playlist_high_energy_130_bpm"))
        composeRule.onNodeWithTag("smart_playlist_high_energy_130_bpm").assertIsDisplayed()
    }
}
