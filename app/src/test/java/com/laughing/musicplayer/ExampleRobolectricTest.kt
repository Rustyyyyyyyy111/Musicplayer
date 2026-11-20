package com.laughing.musicplayer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.laughing.musicplayer.core.model.SleepTimerMode
import com.laughing.musicplayer.core.model.SmartPlaylistType
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.SecureCredentialStore
import com.laughing.musicplayer.data.local.CacheEntryEntity
import com.laughing.musicplayer.data.local.MusicPlayerDatabase
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.data.local.TrackEntity
import com.laughing.musicplayer.data.storage.CacheManager
import com.laughing.musicplayer.data.storage.UnifiedStorageManager
import com.laughing.musicplayer.integrations.PlaylistAndSmartMixManager
import com.laughing.musicplayer.lyrics.LyricsEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals("com.laughing.musicplayer", context.packageName)
    }

    private fun dismissOnboardingIfShown() {
        composeRule.waitForIdle()
        val hasLaterBtn = composeRule.onAllNodes(hasTestTag("onboarding_later_btn"))
            .fetchSemanticsNodes()
            .isNotEmpty()
        if (hasLaterBtn) {
            composeRule.onNodeWithTag("onboarding_later_btn").performClick()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun listenNowScreenIsCleanAndNavigationWorks() {
        dismissOnboardingIfShown()
        // Home screen keeps Listen Now & discovery only (no AI Smart Mix or Telegram setup card)
        composeRule.onNodeWithTag("listen_now_screen").assertIsDisplayed()
        composeRule.onNodeWithText("AI Smart Mix").assertDoesNotExist()
        composeRule.onNodeWithTag("tg_setup_btn").assertDoesNotExist()
        composeRule.onNodeWithTag("quick_eq_btn").assertDoesNotExist()
        composeRule.onNodeWithTag("quick_car_mode_btn").assertDoesNotExist()

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

        // Verify "Nguồn", "Đồng bộ", and "Chọn nguồn nhạc Telegram" were moved out of Library
        composeRule.onNodeWithText("Nguồn").assertDoesNotExist()
        composeRule.onNodeWithText("Đồng bộ").assertDoesNotExist()
        composeRule.onNodeWithText("Chọn nguồn nhạc Telegram").assertDoesNotExist()
        composeRule.onNodeWithText("Mở Cài đặt > Nguồn nhạc để đăng nhập Telegram, chọn kênh/nhóm lấy nhạc và nhấn Đồng bộ ngay.")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("source_tab_youtube_music").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Tìm trên YouTube Music").assertIsDisplayed()

        composeRule.onNodeWithTag("source_tab_offline").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Quét nhạc trong máy").assertIsDisplayed()
    }

    @Test
    fun settingsScreenDisplaysConsolidatedSourcesYouTubeLoginCrossfadeAndRemovesScrobbleAndUpdate() {
        dismissOnboardingIfShown()
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

        // Consolidated Nguồn nhạc items: Telegram Login, Sources, Sync Now, YouTube Login
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasText("Nguồn nhạc"))
        composeRule.onNodeWithText("Nguồn nhạc").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("setting_tg_login"))
        composeRule.onNodeWithTag("setting_tg_login").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("setting_tg_sources"))
        composeRule.onNodeWithTag("setting_tg_sources").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("setting_tg_sync_now"))
        composeRule.onNodeWithTag("setting_tg_sync_now").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("setting_yt_login"))
        composeRule.onNodeWithTag("setting_yt_login").assertIsDisplayed()
        composeRule.onNodeWithText("Đăng nhập YouTube").assertIsDisplayed()
        composeRule.onNodeWithText("Chưa đăng nhập").assertIsDisplayed()

        // Crossfade slider & Car Mode in Settings
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasText("Nhạc và phát lại"))
        composeRule.onNodeWithText("Nhạc và phát lại").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("setting_crossfade_slider"))
        composeRule.onNodeWithTag("setting_crossfade_slider").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("setting_car_mode"))
        composeRule.onNodeWithTag("setting_car_mode").assertIsDisplayed()
        composeRule.onNodeWithText("Chế độ lái xe").assertIsDisplayed()

        // Storage path & Manage Downloads
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasText("Bộ nhớ"))
        composeRule.onNodeWithText("Bộ nhớ").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("setting_manage_offline"))
        composeRule.onNodeWithText("Quản lý bài tải").assertIsDisplayed()

        // Hệ thống without Scrobble or App Update
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasText("Hệ thống"))
        composeRule.onNodeWithText("Hệ thống").assertIsDisplayed()
        composeRule.onNodeWithTag("setting_scrobble").assertDoesNotExist()
        composeRule.onNodeWithTag("setting_app_update").assertDoesNotExist()
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
        val storage = UnifiedStorageManager(context, settings, logger)
        val engine = LyricsEngine(storage, { OkHttpClient() }, logger)

        val lrc = """
            [00:12.50]Em ơi đừng khóc nữa
            [00:18.00]사랑해
        """.trimIndent()
        val parsed = engine.parseLrc(lrc)
        assertEquals(2, parsed.size)
        assertEquals(12_500L, parsed[0].timestampMs)
        assertEquals("Em ơi đừng khóc nữa", parsed[0].text)
        assertEquals(18_000L, parsed[1].timestampMs)
        assertNotNull(parsed[1].romanization)
        db.close()
    }

    @Test
    fun telegramAuthScreenOpensFromSettingsAndShowsPhoneAndCodeSteps() {
        dismissOnboardingIfShown()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("setting_tg_login"))
        composeRule.onNodeWithTag("setting_tg_login").performClick()
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

            val trackNeverPlayed = TrackEntity(
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

            val manager = PlaylistAndSmartMixManager(
                db.playlistDao(),
                db.trackDao(),
                ActivityLogger(db.activityLogDao())
            )
            val unplayed = db.trackDao().observeNotPlayedIn30Days(thirtyDaysAgo).first()
            assertTrue(unplayed.any { it.id == "t_never" })
            assertTrue(unplayed.any { it.id == "t_old" })
            assertFalse(unplayed.any { it.id == "t_hot_fast" })

            val highBpm = manager.observeSmartPlaylist(SmartPlaylistType.HIGH_ENERGY_130_BPM).first()
            assertEquals(1, highBpm.size)
            assertEquals("t_hot_fast", highBpm.first().id)

            val mostPlayed = manager.observeSmartPlaylist(SmartPlaylistType.MOST_PLAYED_THIS_MONTH).first()
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
        assertEquals(SleepTimerMode.END_OF_CURRENT_TRACK, controller.playbackState.value.sleepTimerMode)
        assertTrue(controller.consumeTrackSleepTimerOnEnd())
        assertEquals(SleepTimerMode.OFF, controller.playbackState.value.sleepTimerMode)

        controller.setSleepTimerAfterTracks(2)
        assertEquals(SleepTimerMode.AFTER_N_TRACKS, controller.playbackState.value.sleepTimerMode)
        assertEquals(2, controller.playbackState.value.sleepTimerRemainingTracks)
        assertFalse(controller.consumeTrackSleepTimerOnEnd())
        assertEquals(1, controller.playbackState.value.sleepTimerRemainingTracks)
        assertTrue(controller.consumeTrackSleepTimerOnEnd())
        assertEquals(SleepTimerMode.OFF, controller.playbackState.value.sleepTimerMode)
    }

    @Test
    fun unifiedSearchTabsAndSmartPlaylistsUiAreInteractive() {
        dismissOnboardingIfShown()
        // 1. Unified Search Tabs
        composeRule.onNodeWithTag("nav_search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("search_tab_all").assertIsDisplayed()
        composeRule.onNodeWithTag("search_tab_offline").assertIsDisplayed()
        composeRule.onNodeWithTag("search_tab_telegram").assertIsDisplayed()
        composeRule.onNodeWithTag("search_input").assertIsDisplayed()
        composeRule.onNodeWithTag("search_input").performTextInput("đúng lúc")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("đúng lúc").assertIsDisplayed()

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

    @Test
    fun youtubeSessionCookieManagerPersistsCookiesTxtAndScopesDomains() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, MusicPlayerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val logger = ActivityLogger(db.activityLogDao())
            val creds = SecureCredentialStore(context)
            val settings = SettingsDataStore(context, creds)
            val manager = com.laughing.musicplayer.data.youtube.YouTubeSessionCookieManager(
                context = context,
                credentialStore = creds,
                settingsDataStore = settings,
                logger = logger
            )

            val saved = manager.saveRawCookieHeaderForTesting("PREF=tz=Asia.Ho_Chi_Minh; SAPISID=test_sapisid_123; SID=test_sid_456")
            assertTrue(saved)
            assertTrue(manager.hasActiveCookies())
            assertTrue(settings.preferencesFlow.first().isYouTubeLoggedIn)

            val cookiesFile = manager.getCookiesTxtFile()
            assertTrue(cookiesFile.exists())
            val content = cookiesFile.readText()
            assertTrue(content.contains("# Netscape HTTP Cookie File"))
            assertTrue(content.contains(".music.youtube.com"))
            assertTrue(content.contains("SAPISID\ttest_sapisid_123"))

            assertNotNull(manager.getCookieHeaderForUrl("https://music.youtube.com/youtubei/v1/browse"))
            assertNotNull(manager.getCookieHeaderForUrl("https://rr3---sn-test.googlevideo.com/videoplayback"))
            assertEquals(null, manager.getCookieHeaderForUrl("https://example.com/private"))

            manager.clearSession()
            assertFalse(manager.hasActiveCookies())
            assertFalse(cookiesFile.exists())
            assertFalse(settings.preferencesFlow.first().isYouTubeLoggedIn)
            db.close()
        }
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun media3CompactNotificationButtonsAndCommandsAlwaysExposeNextAndPrevious() {
        val augmented = com.laughing.musicplayer.audio.MusicLibraryService.augmentAvailableCommands(
            androidx.media3.common.Player.Commands.EMPTY
        )
        assertTrue(augmented.contains(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(augmented.contains(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        assertTrue(augmented.contains(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(augmented.contains(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))

        val buttons = com.laughing.musicplayer.audio.MusicLibraryService.buildCompactTransportButtons(
            showPauseButton = true
        )
        assertEquals(3, buttons.size)
        assertEquals(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS, buttons[0].playerCommand)
        assertEquals(androidx.media3.common.Player.COMMAND_PLAY_PAUSE, buttons[1].playerCommand)
        assertEquals(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT, buttons[2].playerCommand)
        assertEquals(
            0,
            buttons[0].extras.getInt(
                androidx.media3.session.DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX,
                -1
            )
        )
        assertEquals(
            1,
            buttons[1].extras.getInt(
                androidx.media3.session.DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX,
                -1
            )
        )
        assertEquals(
            2,
            buttons[2].extras.getInt(
                androidx.media3.session.DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX,
                -1
            )
        )
    }

    @Test
    fun libraryLocalSearchFiltersAcrossAllSourcesAndExploreShowsMoodsOnEmptySearch() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = (context.applicationContext as MusicPlayerApplication).container.database
            val now = System.currentTimeMillis()
            db.trackDao().upsertTracks(
                listOf(
                    TrackEntity(
                        id = "lib_tg_1",
                        title = "Đúng Lúc Gặp Người",
                        artist = "Vũ",
                        album = "Telegram Album",
                        source = "TELEGRAM",
                        durationMs = 210_000L,
                        format = "FLAC",
                        bitrateKbps = 1411,
                        sampleRateHz = 48000,
                        bitDepth = 24,
                        fileSizeBytes = 32_000_000L,
                        fileHash = null,
                        fileName = "dung_luc.flac",
                        artworkUri = null,
                        streamUri = null,
                        localPath = null,
                        telegramChatId = 1L,
                        telegramChatTitle = "Nhạc Việt",
                        telegramMessageId = 10L,
                        telegramFileId = 100,
                        telegramThreadId = null,
                        telegramTopicName = null,
                        youtubeVideoId = null,
                        genre = "BALLAD",
                        genreManuallyEdited = false,
                        isVoiceMessage = false,
                        downloadStatus = "NONE",
                        downloadProgress = 0f,
                        isFavorite = false,
                        dateAddedEpochMs = now,
                        lastPlayedEpochMs = 0L,
                        playCount = 0,
                        loudnessGainDb = 0f,
                        bpm = 92,
                        normalizedSearchBlob = "dung luc gap nguoi vu"
                    ),
                    TrackEntity(
                        id = "lib_yt_1",
                        title = "Nơi Này Có Anh",
                        artist = "Sơn Tùng M-TP",
                        album = "YouTube Music",
                        source = "YOUTUBE_MUSIC",
                        durationMs = 240_000L,
                        format = "OPUS",
                        bitrateKbps = 160,
                        sampleRateHz = 48000,
                        bitDepth = null,
                        fileSizeBytes = 5_000_000L,
                        fileHash = null,
                        fileName = "noi_nay_co_anh.opus",
                        artworkUri = null,
                        streamUri = "https://www.youtube.com/watch?v=test1",
                        localPath = "/tmp/test1.opus",
                        telegramChatId = null,
                        telegramChatTitle = null,
                        telegramMessageId = null,
                        telegramFileId = null,
                        telegramThreadId = null,
                        telegramTopicName = null,
                        youtubeVideoId = "test1",
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
                        bpm = 110,
                        normalizedSearchBlob = "noi nay co anh son tung mtp"
                    )
                )
            )
        }

        dismissOnboardingIfShown()
        // 1. Explore feed & Mood chips when Search tab is opened with empty input
        composeRule.onNodeWithTag("nav_search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("explore_feed_list").assertIsDisplayed()
        composeRule.onNodeWithText("Tâm trạng & Thể loại").assertIsDisplayed()
        composeRule.onNodeWithTag("explore_mood_vpop").assertIsDisplayed()
        composeRule.onNodeWithTag("explore_mood_chill").assertIsDisplayed()

        // 2. Library local search filters across Telegram + YouTube Music + Offline
        composeRule.onNodeWithTag("nav_library").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("library_search_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("library_search_input").assertIsDisplayed()
        composeRule.onNodeWithTag("library_search_input").performTextInput("noi nay")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("track_row_lib_yt_1").assertIsDisplayed()
        composeRule.onNodeWithTag("track_row_lib_tg_1").assertDoesNotExist()

        // Clear and test Vietnamese query matching Telegram track
        composeRule.onNodeWithTag("library_search_clear").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("library_search_input").performTextInput("đúng lúc")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("track_row_lib_tg_1").assertIsDisplayed()

        // Cancel collapses local search bar
        composeRule.onNodeWithTag("library_search_cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("library_search_input").assertDoesNotExist()
    }

    @Test
    fun telegramRoomMigrationDeduplicatesTracksAndCountsDistinctPerChatAndTopic() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbName = "migration_v2_v3_test.db"
            context.deleteDatabase(dbName)

            // 1. Seed a v2 SQLite database directly with duplicate (telegramChatId, telegramMessageId) rows
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `tracks` (
                                    `id` TEXT NOT NULL,
                                    `title` TEXT NOT NULL,
                                    `artist` TEXT NOT NULL,
                                    `album` TEXT NOT NULL,
                                    `source` TEXT NOT NULL,
                                    `durationMs` INTEGER NOT NULL,
                                    `format` TEXT NOT NULL,
                                    `bitrateKbps` INTEGER NOT NULL,
                                    `sampleRateHz` INTEGER NOT NULL,
                                    `bitDepth` INTEGER,
                                    `fileSizeBytes` INTEGER NOT NULL,
                                    `fileHash` TEXT,
                                    `fileName` TEXT NOT NULL,
                                    `artworkUri` TEXT,
                                    `streamUri` TEXT,
                                    `localPath` TEXT,
                                    `telegramChatId` INTEGER,
                                    `telegramChatTitle` TEXT,
                                    `telegramMessageId` INTEGER,
                                    `telegramFileId` INTEGER,
                                    `telegramThreadId` INTEGER,
                                    `telegramTopicName` TEXT,
                                    `youtubeVideoId` TEXT,
                                    `genre` TEXT NOT NULL,
                                    `genreManuallyEdited` INTEGER NOT NULL,
                                    `isVoiceMessage` INTEGER NOT NULL,
                                    `downloadStatus` TEXT NOT NULL,
                                    `downloadProgress` REAL NOT NULL,
                                    `isFavorite` INTEGER NOT NULL,
                                    `dateAddedEpochMs` INTEGER NOT NULL,
                                    `lastPlayedEpochMs` INTEGER NOT NULL,
                                    `playCount` INTEGER NOT NULL,
                                    `loudnessGainDb` REAL NOT NULL,
                                    `bpm` INTEGER NOT NULL,
                                    `normalizedSearchBlob` TEXT NOT NULL,
                                    PRIMARY KEY(`id`)
                                )
                                """.trimIndent()
                            )
                            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_telegramChatId_telegramMessageId` ON `tracks` (`telegramChatId`, `telegramMessageId`)")
                            db.execSQL(
                                """
                                INSERT INTO tracks VALUES (
                                    'dup_old_1', 'Song A', 'Artist', 'Album', 'TELEGRAM', 200000, 'FLAC', 1411, 44100, 16,
                                    25000000, NULL, 'song_a.flac', NULL, 'tg://file/11?size=25000000', NULL,
                                    -1001, 'Forum Chat', 77, 11, 900, 'Ballad Topic', NULL, 'BALLAD', 0, 0,
                                    'NONE', 0.0, 0, 1000, 0, 0, 0.0, 90, 'song a'
                                )
                                """.trimIndent()
                            )
                            db.execSQL(
                                """
                                INSERT INTO tracks VALUES (
                                    'tg_-1001_77', 'Song A', 'Artist', 'Album', 'TELEGRAM', 200000, 'FLAC', 1411, 44100, 16,
                                    25000000, NULL, 'song_a.flac', NULL, 'tg://file/12?size=25000000', '/tmp/song_a.flac',
                                    -1001, 'Forum Chat', 77, 12, 900, 'Ballad Topic', NULL, 'BALLAD', 0, 0,
                                    'COMPLETED', 1.0, 1, 2000, 0, 5, 0.0, 90, 'song a'
                                )
                                """.trimIndent()
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build()
            )
            helper.writableDatabase.close()
            helper.close()

            // 2. Open with Room + MIGRATION_2_3 — create remaining tables or test MIGRATION_2_3 directly via SupportSQLite + verify queries
            val migratedHelper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            if (oldVersion == 2 && newVersion == 3) {
                                MusicPlayerDatabase.MIGRATION_2_3.migrate(db)
                            }
                        }
                    })
                    .build()
            )
            val sdb = migratedHelper.writableDatabase
            sdb.query("SELECT id, telegramRemoteFileId FROM tracks WHERE telegramChatId = -1001 AND telegramMessageId = 77").use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("tg_-1001_77", cursor.getString(0))
            }
            sdb.close()
            migratedHelper.close()
            context.deleteDatabase(dbName)

            // 3. Verify distinct count DAO helpers never double-count after repeated upserts
            val memDb = Room.inMemoryDatabaseBuilder(context, MusicPlayerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val track1 = TrackEntity(
                id = "tg_-2001_101",
                title = "Track 1",
                artist = "Artist",
                album = "Forum",
                source = "TELEGRAM",
                durationMs = 180_000L,
                format = "FLAC",
                bitrateKbps = 1411,
                sampleRateHz = 44100,
                bitDepth = 16,
                fileSizeBytes = 10_000L,
                fileHash = null,
                fileName = "t1.flac",
                artworkUri = null,
                streamUri = "tg://file/101?size=10000",
                localPath = null,
                telegramChatId = -2001L,
                telegramChatTitle = "Forum",
                telegramMessageId = 101L,
                telegramFileId = 101,
                telegramRemoteFileId = "remote_101",
                telegramThreadId = 50L,
                telegramTopicName = "Pop",
                youtubeVideoId = null,
                genre = "POP",
                genreManuallyEdited = false,
                isVoiceMessage = false,
                downloadStatus = "NONE",
                downloadProgress = 0f,
                isFavorite = false,
                dateAddedEpochMs = 1_000L,
                lastPlayedEpochMs = 0L,
                playCount = 0,
                loudnessGainDb = 0f,
                bpm = 0,
                normalizedSearchBlob = "track 1"
            )
            val track2 = track1.copy(
                id = "tg_-2001_102",
                title = "Track 2",
                telegramMessageId = 102L,
                telegramFileId = 102,
                telegramRemoteFileId = "remote_102",
                normalizedSearchBlob = "track 2"
            )
            memDb.trackDao().upsertTracks(listOf(track1, track2))
            memDb.trackDao().upsertTracks(listOf(track1, track2)) // Re-sync upsert
            assertEquals(2, memDb.trackDao().countDistinctTelegramTracksForChat(-2001L))
            assertEquals(2, memDb.trackDao().countDistinctTelegramTracksForTopic(-2001L, 50L))
            memDb.close()
        }
    }

    @Test
    fun telegramFileResolverRecoversViaRemoteFileIdAndGetMessageFallback() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val memDb = Room.inMemoryDatabaseBuilder(context, MusicPlayerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val logger = ActivityLogger(memDb.activityLogDao())

            val staleRemoteTrack = TrackEntity(
                id = "tg_10_500",
                title = "Restart Track",
                artist = "Artist",
                album = "Chat",
                source = "TELEGRAM",
                durationMs = 200_000L,
                format = "MP3",
                bitrateKbps = 320,
                sampleRateHz = 44100,
                bitDepth = null,
                fileSizeBytes = 8_000_000L,
                fileHash = null,
                fileName = "restart.mp3",
                artworkUri = null,
                streamUri = "tg://file/5310?size=8000000",
                localPath = null,
                telegramChatId = 10L,
                telegramChatTitle = "Chat",
                telegramMessageId = 500L,
                telegramFileId = 5310,
                telegramRemoteFileId = "perm_remote_500",
                telegramThreadId = null,
                telegramTopicName = null,
                youtubeVideoId = null,
                genre = "POP",
                genreManuallyEdited = false,
                isVoiceMessage = false,
                downloadStatus = "NONE",
                downloadProgress = 0f,
                isFavorite = false,
                dateAddedEpochMs = 1_000L,
                lastPlayedEpochMs = 0L,
                playCount = 0,
                loudnessGainDb = 0f,
                bpm = 0,
                normalizedSearchBlob = "restart track"
            )
            val legacyNoRemoteTrack = staleRemoteTrack.copy(
                id = "tg_10_501",
                telegramMessageId = 501L,
                telegramFileId = 6310,
                telegramRemoteFileId = null,
                streamUri = "tg://file/6310?size=8000000"
            )
            memDb.trackDao().upsertTracks(listOf(staleRemoteTrack, legacyNoRemoteTrack))

            val fakeClient = object : com.laughing.musicplayer.data.telegram.TelegramFileClient {
                override suspend fun getFile(fileId: Int, timeoutMs: Long): Result<org.drinkless.tdlib.TdApi.File> {
                    return Result.failure(IllegalStateException("File not found #$fileId"))
                }

                override suspend fun getRemoteFile(
                    remoteFileId: String,
                    fileType: org.drinkless.tdlib.TdApi.FileType,
                    timeoutMs: Long
                ): Result<org.drinkless.tdlib.TdApi.File> {
                    if (remoteFileId == "perm_remote_500") {
                        return Result.success(
                            org.drinkless.tdlib.TdApi.File().apply {
                                id = 9901
                                size = 8_000_000L
                                remote = org.drinkless.tdlib.TdApi.RemoteFile().apply { id = "perm_remote_500" }
                                local = org.drinkless.tdlib.TdApi.LocalFile()
                            }
                        )
                    }
                    return Result.failure(IllegalStateException("Unknown remote"))
                }

                override suspend fun getMessage(
                    chatId: Long,
                    messageId: Long,
                    timeoutMs: Long
                ): Result<org.drinkless.tdlib.TdApi.Message> {
                    if (chatId == 10L && messageId == 501L) {
                        val audio = org.drinkless.tdlib.TdApi.Audio().apply {
                            this.audio = org.drinkless.tdlib.TdApi.File().apply {
                                id = 8802
                                size = 8_000_000L
                                remote = org.drinkless.tdlib.TdApi.RemoteFile().apply { id = "recovered_remote_501" }
                                local = org.drinkless.tdlib.TdApi.LocalFile()
                            }
                        }
                        return Result.success(
                            org.drinkless.tdlib.TdApi.Message().apply {
                                id = 501L
                                this.chatId = 10L
                                content = org.drinkless.tdlib.TdApi.MessageAudio().apply { this.audio = audio }
                            }
                        )
                    }
                    return Result.failure(IllegalStateException("Message not found"))
                }

                override suspend fun downloadFile(
                    fileId: Int,
                    priority: Int,
                    offset: Long,
                    limit: Long,
                    synchronous: Boolean,
                    timeoutMs: Long
                ): Result<org.drinkless.tdlib.TdApi.File> {
                    return if (fileId == 9901 || fileId == 8802) {
                        Result.success(
                            org.drinkless.tdlib.TdApi.File().apply {
                                id = fileId
                                size = 8_000_000L
                                remote = org.drinkless.tdlib.TdApi.RemoteFile().apply {
                                    id = if (fileId == 9901) "perm_remote_500" else "recovered_remote_501"
                                }
                                local = org.drinkless.tdlib.TdApi.LocalFile()
                            }
                        )
                    } else {
                        Result.failure(IllegalStateException("Không tìm thấy tệp Telegram #$fileId"))
                    }
                }
            }

            val resolver = com.laughing.musicplayer.data.telegram.TelegramFileResolver(
                client = fakeClient,
                trackDao = memDb.trackDao(),
                storageManager = null,
                logger = logger
            )

            // Case A: Stale fileId recovered via GetRemoteFile
            val resA = resolver.resolveAndStartDownload(
                target = com.laughing.musicplayer.data.telegram.TelegramFileTarget(
                    fileId = 5310,
                    expectedSize = 8_000_000L
                ),
                priority = 32,
                offset = 0L,
                limit = 0L
            )
            assertTrue(resA.isSuccess)
            assertEquals(9901, resA.getOrThrow().fileId)
            val updatedA = memDb.trackDao().getTrackById("tg_10_500")
            assertEquals(9901, updatedA?.telegramFileId)
            assertEquals("perm_remote_500", updatedA?.telegramRemoteFileId)

            // Case B: Stale legacy row without remoteFileId recovered via GetMessage fallback
            val resB = resolver.resolveAndStartDownload(
                target = com.laughing.musicplayer.data.telegram.TelegramFileTarget(
                    fileId = 6310,
                    expectedSize = 8_000_000L
                ),
                priority = 32,
                offset = 0L,
                limit = 0L
            )
            assertTrue(resB.isSuccess)
            assertEquals(8802, resB.getOrThrow().fileId)
            val updatedB = memDb.trackDao().getTrackById("tg_10_501")
            assertEquals(8802, updatedB?.telegramFileId)
            assertEquals("recovered_remote_501", updatedB?.telegramRemoteFileId)

            memDb.close()
        }
    }

    @Test
    fun telegramSourcePickerRendersSelectAllAndDeselectOnSingleHorizontalLine() {
        dismissOnboardingIfShown()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("setting_tg_sources"))
        composeRule.onNodeWithTag("setting_tg_sources").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("telegram_source_picker_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("tg_select_all_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("tg_deselect_all_btn").assertIsDisplayed()

        val deselectTextBounds = composeRule.onNodeWithText("Bỏ chọn").assertIsDisplayed().getBoundsInRoot()
        assertTrue(
            "Bỏ chọn label must stay horizontal (width=${deselectTextBounds.width}, height=${deselectTextBounds.height})",
            deselectTextBounds.width > deselectTextBounds.height
        )
    }

    @Test
    fun telegramFileResolverEnsureVerifiedLocalAudioFileRecoversViaGetMessageAndUpdateFile() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val memDb = Room.inMemoryDatabaseBuilder(context, MusicPlayerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val logger = ActivityLogger(memDb.activityLogDao())
            val settings = SettingsDataStore(context, SecureCredentialStore(context))
            val storage = UnifiedStorageManager(context, settings, logger)

            val tdDownloadDir = File(context.cacheDir, "fake_td_files").apply { mkdirs() }
            val validMp3Bytes = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00) + ByteArray(2048) { 0x55 }
            val downloadedTempFile = File(tdDownloadDir, "recovered_777.mp3").apply {
                writeBytes(validMp3Bytes)
            }

            val staleEntity = TrackEntity(
                id = "tg_77_9001",
                title = "Lossless Restart Test",
                artist = "Artist",
                album = "Channel",
                source = "TELEGRAM",
                durationMs = 180_000L,
                format = "MP3",
                bitrateKbps = 320,
                sampleRateHz = 44100,
                bitDepth = null,
                fileSizeBytes = validMp3Bytes.size.toLong(),
                fileHash = null,
                fileName = "recovered_777.mp3",
                artworkUri = null,
                streamUri = "tg://file/999?size=${validMp3Bytes.size}",
                localPath = "/nonexistent/deleted_after_force_stop.mp3",
                telegramChatId = 77L,
                telegramChatTitle = "Channel",
                telegramMessageId = 9001L,
                telegramFileId = 999,
                telegramRemoteFileId = "stale_remote_999",
                telegramRemoteUniqueId = "uniq_old",
                telegramThreadId = null,
                telegramTopicName = null,
                youtubeVideoId = null,
                genre = "POP",
                genreManuallyEdited = false,
                isVoiceMessage = false,
                mimeType = "audio/mpeg",
                downloadStatus = "NONE",
                downloadProgress = 0f,
                lastVerifiedAtEpochMs = 0L,
                isFavorite = false,
                dateAddedEpochMs = 1_000L,
                lastPlayedEpochMs = 0L,
                playCount = 0,
                loudnessGainDb = 0f,
                bpm = 0,
                normalizedSearchBlob = "lossless restart test"
            )
            val missingMessageEntity = staleEntity.copy(
                id = "tg_77_9002",
                title = "Deleted Message Track",
                telegramMessageId = 9002L,
                telegramFileId = 1002,
                telegramRemoteFileId = null
            )
            memDb.trackDao().upsertTracks(listOf(staleEntity, missingMessageEntity))

            val updatesFlow = MutableSharedFlow<org.drinkless.tdlib.TdApi.File>(extraBufferCapacity = 8)
            val fakeClient = object : com.laughing.musicplayer.data.telegram.TelegramFileClient {
                override suspend fun getFile(fileId: Int, timeoutMs: Long): Result<org.drinkless.tdlib.TdApi.File> {
                    return if (fileId == 7701) {
                        Result.success(
                            org.drinkless.tdlib.TdApi.File().apply {
                                id = 7701
                                size = validMp3Bytes.size.toLong()
                                remote = org.drinkless.tdlib.TdApi.RemoteFile().apply {
                                    id = "fresh_remote_7701"
                                    uniqueId = "fresh_unique_7701"
                                }
                                local = org.drinkless.tdlib.TdApi.LocalFile().apply {
                                    path = downloadedTempFile.absolutePath
                                    downloadedSize = validMp3Bytes.size.toLong()
                                    isDownloadingCompleted = true
                                }
                            }
                        )
                    } else {
                        Result.failure(IllegalStateException("File not found #$fileId"))
                    }
                }

                override suspend fun getRemoteFile(
                    remoteFileId: String,
                    fileType: org.drinkless.tdlib.TdApi.FileType,
                    timeoutMs: Long
                ): Result<org.drinkless.tdlib.TdApi.File> =
                    Result.failure(IllegalStateException("Remote file expired"))

                override suspend fun getMessage(
                    chatId: Long,
                    messageId: Long,
                    timeoutMs: Long
                ): Result<org.drinkless.tdlib.TdApi.Message> {
                    if (chatId == 77L && messageId == 9001L) {
                        val audio = org.drinkless.tdlib.TdApi.Audio().apply {
                            this.audio = org.drinkless.tdlib.TdApi.File().apply {
                                id = 7701
                                size = validMp3Bytes.size.toLong()
                                remote = org.drinkless.tdlib.TdApi.RemoteFile().apply {
                                    id = "fresh_remote_7701"
                                    uniqueId = "fresh_unique_7701"
                                }
                                local = org.drinkless.tdlib.TdApi.LocalFile()
                            }
                        }
                        return Result.success(
                            org.drinkless.tdlib.TdApi.Message().apply {
                                id = 9001L
                                this.chatId = 77L
                                content = org.drinkless.tdlib.TdApi.MessageAudio().apply { this.audio = audio }
                            }
                        )
                    }
                    return Result.failure(IllegalStateException("Message not found (404)"))
                }

                override suspend fun downloadFile(
                    fileId: Int,
                    priority: Int,
                    offset: Long,
                    limit: Long,
                    synchronous: Boolean,
                    timeoutMs: Long
                ): Result<org.drinkless.tdlib.TdApi.File> {
                    if (fileId == 7701) {
                        return Result.success(
                            org.drinkless.tdlib.TdApi.File().apply {
                                id = 7701
                                size = validMp3Bytes.size.toLong()
                                remote = org.drinkless.tdlib.TdApi.RemoteFile().apply {
                                    id = "fresh_remote_7701"
                                    uniqueId = "fresh_unique_7701"
                                }
                                local = org.drinkless.tdlib.TdApi.LocalFile().apply {
                                    downloadedSize = 512L
                                    isDownloadingCompleted = false
                                }
                            }
                        )
                    }
                    return Result.failure(IllegalStateException("Download failed #$fileId"))
                }
            }

            val resolver = com.laughing.musicplayer.data.telegram.TelegramFileResolver(
                client = fakeClient,
                trackDao = memDb.trackDao(),
                storageManager = storage,
                logger = logger,
                fileUpdates = updatesFlow,
                waitForReady = { true }
            )

            // Emit TDLib UpdateFile completion shortly after download starts
            val emitterJob = launch {
                delay(80L)
                updatesFlow.emit(
                    org.drinkless.tdlib.TdApi.File().apply {
                        id = 7701
                        size = validMp3Bytes.size.toLong()
                        remote = org.drinkless.tdlib.TdApi.RemoteFile().apply {
                            id = "fresh_remote_7701"
                            uniqueId = "fresh_unique_7701"
                        }
                        local = org.drinkless.tdlib.TdApi.LocalFile().apply {
                            path = downloadedTempFile.absolutePath
                            downloadedSize = validMp3Bytes.size.toLong()
                            isDownloadingCompleted = true
                        }
                    }
                )
            }

            val verifiedResult = resolver.ensureVerifiedLocalAudioFile(staleEntity.toDomain())
            emitterJob.cancel()

            assertTrue("Should recover and persist verified local audio", verifiedResult.isSuccess)
            val verifiedTrack = verifiedResult.getOrThrow()
            assertNotNull(verifiedTrack.localPath)
            assertTrue(File(verifiedTrack.localPath!!).exists())
            assertTrue(
                "Must copy into persistent cache dir",
                verifiedTrack.localPath!!.contains("music_cache")
            )
            assertEquals(7701, verifiedTrack.telegramFileId)
            assertEquals("fresh_unique_7701", verifiedTrack.telegramRemoteUniqueId)
            val savedEntity = memDb.trackDao().getTrackById("tg_77_9001")
            assertEquals("COMPLETED", savedEntity?.downloadStatus)
            assertTrue((savedEntity?.lastVerifiedAtEpochMs ?: 0L) > 0L)

            val missingResult = resolver.ensureVerifiedLocalAudioFile(missingMessageEntity.toDomain())
            assertTrue("Deleted message should fail explicitly", missingResult.isFailure)
            assertTrue(missingResult.exceptionOrNull()?.message?.isNotBlank() == true)

            memDb.close()
            tdDownloadDir.deleteRecursively()
        }
    }

    @Test
    fun databaseMigration3To4And4To5RemovesPublicChannelsAndPreservesPersonalTelegramTracks() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbName = "test_migration_4_to_5.db"
            context.deleteDatabase(dbName)
            val factory = FrameworkSQLiteOpenHelperFactory()
            val config = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE tracks (
                                id TEXT PRIMARY KEY NOT NULL,
                                title TEXT NOT NULL,
                                artist TEXT NOT NULL,
                                album TEXT NOT NULL,
                                source TEXT NOT NULL,
                                durationMs INTEGER NOT NULL,
                                format TEXT NOT NULL,
                                bitrateKbps INTEGER NOT NULL,
                                sampleRateHz INTEGER NOT NULL,
                                bitDepth INTEGER,
                                fileSizeBytes INTEGER NOT NULL,
                                fileHash TEXT,
                                fileName TEXT NOT NULL,
                                artworkUri TEXT,
                                streamUri TEXT,
                                localPath TEXT,
                                telegramChatId INTEGER,
                                telegramChatTitle TEXT,
                                telegramMessageId INTEGER,
                                telegramFileId INTEGER,
                                telegramThreadId INTEGER,
                                telegramTopicName TEXT,
                                youtubeVideoId TEXT,
                                genre TEXT NOT NULL,
                                genreManuallyEdited INTEGER NOT NULL,
                                isVoiceMessage INTEGER NOT NULL,
                                downloadStatus TEXT NOT NULL,
                                downloadProgress REAL NOT NULL,
                                isFavorite INTEGER NOT NULL,
                                dateAddedEpochMs INTEGER NOT NULL,
                                lastPlayedEpochMs INTEGER NOT NULL,
                                playCount INTEGER NOT NULL,
                                loudnessGainDb REAL NOT NULL,
                                bpm INTEGER NOT NULL,
                                normalizedSearchBlob TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE playlist_tracks (
                                playlistId INTEGER NOT NULL,
                                trackId TEXT NOT NULL,
                                position INTEGER NOT NULL,
                                PRIMARY KEY(playlistId, trackId)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE download_tasks (
                                id TEXT PRIMARY KEY NOT NULL,
                                trackId TEXT NOT NULL,
                                title TEXT NOT NULL,
                                artist TEXT NOT NULL,
                                source TEXT NOT NULL,
                                status TEXT NOT NULL,
                                bytesDownloaded INTEGER NOT NULL,
                                totalBytes INTEGER NOT NULL,
                                localPath TEXT,
                                errorMessage TEXT,
                                updatedAtEpochMs INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO tracks VALUES (
                                'tg_1_2', 'Personal Track', 'Artist', 'Album', 'TELEGRAM',
                                180000, 'FLAC', 1411, 44100, 16, 10000, NULL, 'old.flac',
                                NULL, 'tg://file/1?size=10000', NULL, 1, 'Chat', 2, 1, NULL, NULL,
                                NULL, 'POP', 0, 0, 'NONE', 0.0, 0, 1000, 0, 0, 0.0, 0, 'personal track'
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()

            val helper = factory.create(config)
            val sqliteDb = helper.writableDatabase
            MusicPlayerDatabase.MIGRATION_2_3.migrate(sqliteDb)
            MusicPlayerDatabase.MIGRATION_3_4.migrate(sqliteDb)

            // Seed v4 public channel artifacts prior to v5 cleanup migration
            sqliteDb.execSQL(
                """
                INSERT INTO public_channels VALUES (
                    'lossless_vn', 'Lossless VN', NULL, NULL, NULL, 1, 'IDLE', 0, 0, 0, 1000, NULL, 'BACKEND_API'
                )
                """.trimIndent()
            )
            sqliteDb.execSQL(
                """
                INSERT INTO tracks (
                    id, title, artist, album, source, telegramOrigin, publicChannelUsername,
                    durationMs, format, bitrateKbps, sampleRateHz, bitDepth, fileSizeBytes, fileHash,
                    fileName, artworkUri, streamUri, localPath, telegramChatId, telegramChatTitle,
                    telegramMessageId, telegramFileId, telegramRemoteFileId, telegramRemoteUniqueId,
                    telegramThreadId, telegramTopicName, youtubeVideoId, genre, genreManuallyEdited,
                    isVoiceMessage, mimeType, downloadStatus, downloadProgress, lastVerifiedAtEpochMs,
                    isFavorite, dateAddedEpochMs, lastPlayedEpochMs, playCount, loudnessGainDb, bpm,
                    normalizedSearchBlob
                ) VALUES (
                    'tgpub_lossless_vn_401', 'Public Song', 'Artist', '@lossless_vn', 'TELEGRAM',
                    'PUBLIC_TELEGRAM', 'lossless_vn', 200000, 'FLAC', 1411, 44100, 16, 20000000, NULL,
                    'pub.flac', NULL, 'https://example.com/stream', NULL, NULL, '@lossless_vn',
                    401, NULL, NULL, NULL, NULL, NULL, NULL, 'POP', 0, 0, 'audio/flac',
                    'NONE', 0.0, 0, 0, 2000, 0, 0, 0.0, 0, 'public song'
                )
                """.trimIndent()
            )
            sqliteDb.execSQL("INSERT INTO playlist_tracks VALUES (1, 'tgpub_lossless_vn_401', 0)")
            sqliteDb.execSQL("INSERT INTO playlist_tracks VALUES (1, 'tg_1_2', 1)")
            sqliteDb.execSQL(
                """
                INSERT INTO download_tasks VALUES (
                    'dl_pub_1', 'tgpub_lossless_vn_401', 'Public Song', 'Artist', 'TELEGRAM',
                    'QUEUED', 0, 20000000, NULL, NULL, 2000
                )
                """.trimIndent()
            )

            // Run v4 -> v5 migration
            MusicPlayerDatabase.MIGRATION_4_5.migrate(sqliteDb)

            // Verify public_channels table dropped and columns removed from tracks
            sqliteDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='public_channels'").use { c ->
                assertEquals(0, c.count)
            }
            val columnNames = mutableSetOf<String>()
            sqliteDb.query("PRAGMA table_info(`tracks`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    columnNames.add(c.getString(nameIdx))
                }
            }
            assertFalse(columnNames.contains("telegramOrigin"))
            assertFalse(columnNames.contains("publicChannelUsername"))
            sqliteDb.query("SELECT COUNT(*) FROM playlist_tracks WHERE trackId = 'tgpub_lossless_vn_401'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            sqliteDb.query("SELECT COUNT(*) FROM playlist_tracks WHERE trackId = 'tg_1_2'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            sqliteDb.query("SELECT COUNT(*) FROM download_tasks WHERE trackId = 'tgpub_lossless_vn_401'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            sqliteDb.close()

            val migratedDb = Room.databaseBuilder(context, MusicPlayerDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .build()
            val preserved = migratedDb.trackDao().getTrackById("tg_1_2")
            assertNotNull(preserved)
            assertEquals("Personal Track", preserved?.title)
            assertEquals(null, migratedDb.trackDao().getTrackById("tgpub_lossless_vn_401"))

            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun telegramFileResolverProgressiveStreamReturnsTgUriWithoutBlockingFullDownload() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val memDb = Room.inMemoryDatabaseBuilder(context, MusicPlayerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val logger = ActivityLogger(memDb.activityLogDao())
            val settings = SettingsDataStore(context, SecureCredentialStore(context))
            val storage = UnifiedStorageManager(context, settings, logger)

            val trackEntity = TrackEntity(
                id = "tg_88_3001",
                title = "Progressive Stream Song",
                artist = "Artist",
                album = "Channel",
                source = "TELEGRAM",
                durationMs = 240_000L,
                format = "FLAC",
                bitrateKbps = 1411,
                sampleRateHz = 48000,
                bitDepth = 24,
                fileSizeBytes = 30_000_000L,
                fileHash = null,
                fileName = "progressive.flac",
                artworkUri = null,
                streamUri = "tg://file/111?size=30000000",
                localPath = null,
                telegramChatId = 88L,
                telegramChatTitle = "Channel",
                telegramMessageId = 3001L,
                telegramFileId = 111,
                telegramRemoteFileId = "old_remote_111",
                telegramRemoteUniqueId = "uniq_111",
                telegramThreadId = null,
                telegramTopicName = null,
                youtubeVideoId = null,
                genre = "POP",
                genreManuallyEdited = false,
                isVoiceMessage = false,
                mimeType = "audio/flac",
                downloadStatus = "NONE",
                downloadProgress = 0f,
                lastVerifiedAtEpochMs = 0L,
                isFavorite = false,
                dateAddedEpochMs = 1_000L,
                lastPlayedEpochMs = 0L,
                playCount = 0,
                loudnessGainDb = 0f,
                bpm = 0,
                normalizedSearchBlob = "progressive stream song"
            )
            memDb.trackDao().upsertTracks(listOf(trackEntity))

            var downloadFileCalled = false
            val fakeClient = object : com.laughing.musicplayer.data.telegram.TelegramFileClient {
                override suspend fun getFile(fileId: Int, timeoutMs: Long): Result<org.drinkless.tdlib.TdApi.File> =
                    Result.failure(IllegalStateException("Stale fileId #$fileId"))

                override suspend fun getRemoteFile(
                    remoteFileId: String,
                    fileType: org.drinkless.tdlib.TdApi.FileType,
                    timeoutMs: Long
                ): Result<org.drinkless.tdlib.TdApi.File> =
                    Result.failure(IllegalStateException("Not needed"))

                override suspend fun getMessage(
                    chatId: Long,
                    messageId: Long,
                    timeoutMs: Long
                ): Result<org.drinkless.tdlib.TdApi.Message> {
                    val audio = org.drinkless.tdlib.TdApi.Audio().apply {
                        this.audio = org.drinkless.tdlib.TdApi.File().apply {
                            id = 5502
                            size = 30_000_000L
                            remote = org.drinkless.tdlib.TdApi.RemoteFile().apply {
                                id = "fresh_remote_5502"
                                uniqueId = "fresh_uniq_5502"
                            }
                            local = org.drinkless.tdlib.TdApi.LocalFile()
                        }
                    }
                    return Result.success(
                        org.drinkless.tdlib.TdApi.Message().apply {
                            id = 3001L
                            this.chatId = 88L
                            content = org.drinkless.tdlib.TdApi.MessageAudio().apply { this.audio = audio }
                        }
                    )
                }

                override suspend fun downloadFile(
                    fileId: Int,
                    priority: Int,
                    offset: Long,
                    limit: Long,
                    synchronous: Boolean,
                    timeoutMs: Long
                ): Result<org.drinkless.tdlib.TdApi.File> {
                    downloadFileCalled = true
                    return Result.failure(AssertionError("Progressive resolution must not block on full download"))
                }
            }

            val resolver = com.laughing.musicplayer.data.telegram.TelegramFileResolver(
                client = fakeClient,
                trackDao = memDb.trackDao(),
                storageManager = storage,
                logger = logger,
                waitForReady = { true }
            )

            val res = resolver.resolveForProgressiveStream(trackEntity.toDomain())
            assertTrue("Progressive stream resolution must succeed immediately", res.isSuccess)
            assertFalse("Must not call full blocking downloadFile during URI resolution", downloadFileCalled)
            val resolved = res.getOrThrow()
            assertFalse(resolved.isLocalFile)
            assertTrue(resolved.playUri.startsWith("tg://file/5502?"))
            assertEquals(5502, resolved.track.telegramFileId)
            assertEquals("fresh_remote_5502", resolved.track.telegramRemoteFileId)

            memDb.close()
        }
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun publicChannelUiIsRemovedAndNetworkTimeoutsAreBounded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = (context.applicationContext as MusicPlayerApplication).container
        val proxyManager = container.networkProxyManager

        val apiClient = proxyManager.getHttpClient()
        assertEquals(6_000, apiClient.connectTimeoutMillis)
        assertEquals(8_000, apiClient.readTimeoutMillis)
        assertEquals(8_000, apiClient.writeTimeoutMillis)
        assertEquals(8_000, apiClient.callTimeoutMillis)

        val streamClient = proxyManager.getStreamHttpClient()
        assertEquals(6_000, streamClient.connectTimeoutMillis)
        assertEquals(8_000, streamClient.readTimeoutMillis)
        assertEquals(0, streamClient.callTimeoutMillis)

        // Verify cancelActivePlaybackLoad cleanly resets buffering/playing state
        container.playerController.cancelActivePlaybackLoad()
        assertFalse(container.playerController.playbackState.value.isBuffering)
        assertFalse(container.playerController.playbackState.value.isPlaying)

        dismissOnboardingIfShown()
        // 1. Library Telegram tab must NOT show removed Public Channel filter chips
        composeRule.onNodeWithTag("nav_library").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("telegram_origin_filter_row").assertDoesNotExist()
        composeRule.onNodeWithTag("tg_origin_public").assertDoesNotExist()

        // 2. Settings must NOT show removed Kênh Telegram công khai row
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setting_public_tg_channels").assertDoesNotExist()
        composeRule.onNodeWithText("Kênh Telegram công khai").assertDoesNotExist()
    }
}

