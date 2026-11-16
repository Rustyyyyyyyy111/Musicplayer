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
}
