package com.laughing.musicplayer

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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

    @Test
    fun listenNowScreenIsCleanAndNavigationWorks() {
        composeRule.waitForIdle()
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
        composeRule.onNodeWithText("Quét nhạc Offline ngay").assertIsDisplayed()
    }

    @Test
    fun settingsScreenDisplaysConsolidatedSourcesYouTubeLoginCrossfadeAndRemovesScrobbleAndUpdate() {
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
        composeRule.waitForIdle()
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
        composeRule.waitForIdle()
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

        composeRule.waitForIdle()
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
}
