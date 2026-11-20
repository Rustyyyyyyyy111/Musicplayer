package com.laughing.musicplayer

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.model.toAudioQualityDisplay
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.SecureCredentialStore
import com.laughing.musicplayer.data.local.MusicPlayerDatabase
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.data.local.toEntity
import com.laughing.musicplayer.data.storage.OfflineMusicRepository
import com.laughing.musicplayer.data.storage.UnifiedStorageManager
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
class LocalMusicScannerRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun qaacM4aAndAlacDisplayAccurateQualityBadges() {
        val qaacTrack = Track(
            id = "local_qaac_1",
            title = "QAAC Song",
            artist = "Artist",
            album = "Album",
            source = MusicSource.OFFLINE,
            durationMs = 215_000L,
            format = AudioFormat.M4A,
            bitrateKbps = 256,
            sampleRateHz = 44100,
            audioCodec = "M4A / AAC"
        ).toAudioQualityDisplay()

        assertFalse(qaacTrack.isLossless)
        assertEquals("M4A / AAC", qaacTrack.formatBadge)
        assertTrue(qaacTrack.specText.contains("44.1kHz"))
        assertTrue(qaacTrack.specText.contains("256 kbps"))

        val alacInM4aTrack = Track(
            id = "local_alac_1",
            title = "ALAC Song",
            artist = "Artist",
            album = "Album",
            source = MusicSource.OFFLINE,
            durationMs = 240_000L,
            format = AudioFormat.M4A,
            bitrateKbps = 1024,
            sampleRateHz = 48000,
            bitDepth = 24,
            audioCodec = "ALAC (Apple Lossless)"
        ).toAudioQualityDisplay()

        assertTrue(alacInM4aTrack.isLossless)
        assertEquals("ALAC", alacInM4aTrack.formatBadge)
        assertTrue(alacInM4aTrack.specText.contains("24-bit / 48kHz"))
    }

    @Test
    fun recursiveFolderScanUsesDeterministicIdsDeduplicatesAndPreservesUserMetadata() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, MusicPlayerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val logger = ActivityLogger(db.activityLogDao())
            val settings = SettingsDataStore(context, SecureCredentialStore(context))
            val storage = UnifiedStorageManager(context, settings, logger)
            val repo = OfflineMusicRepository(
                context = context,
                trackDao = db.trackDao(),
                storageManager = storage,
                settingsDataStore = settings,
                logger = logger
            )

            val tempRoot = File(context.cacheDir, "scanner_tree_test").apply {
                deleteRecursively()
                mkdirs()
            }
            val m4aFile = File(tempRoot, "Sơn Tùng - Chúng Ta.m4a").apply { writeBytes(ByteArray(2048)) }
            val nestedDir = File(tempRoot, "Lossless/Album").apply { mkdirs() }
            val flacFile = File(nestedDir, "Vũ - Mùa Mưa.flac").apply { writeBytes(ByteArray(4096)) }
            // Non-audio files that must be ignored
            File(nestedDir, "cover.jpg").apply { writeBytes(ByteArray(512)) }
            File(tempRoot, "notes.txt").apply { writeText("ignore me") }

            val rootDoc = DocumentFile.fromFile(tempRoot)

            // Seed a legacy "offline_file_*" entry pointing to the same URI to confirm legacy dedupe migration
            val legacyTrack = Track(
                id = "offline_file_legacy_1",
                title = "Old Name",
                artist = "Old Artist",
                album = "Old",
                source = MusicSource.OFFLINE,
                durationMs = 190_000L,
                format = AudioFormat.M4A,
                streamUri = Uri.fromFile(m4aFile).toString(),
                localPath = Uri.fromFile(m4aFile).toString(),
                isFavorite = true,
                playCount = 7,
                genre = GenreType.BOLERO,
                genreManuallyEdited = true
            )
            db.trackDao().upsertTrack(legacyTrack.toEntity("old name"))

            val firstCount = repo.scanDocumentTreeAndUpsert(rootDoc)
            assertEquals(2, firstCount)

            val afterFirstScan = db.trackDao().getAllTracksOnce()
            assertEquals(2, afterFirstScan.size)

            val expectedM4aId = "local_${Uri.fromFile(m4aFile)}"
            val expectedFlacId = "local_${Uri.fromFile(flacFile)}"
            val scannedM4a = afterFirstScan.first { it.id == expectedM4aId }
            assertEquals("Sơn Tùng", scannedM4a.artist)
            assertEquals("Chúng Ta", scannedM4a.title)
            assertTrue("Favorite status must migrate/preserve on re-scan", scannedM4a.isFavorite)
            assertEquals(7, scannedM4a.playCount)
            assertEquals(GenreType.BOLERO.name, scannedM4a.genre)
            assertTrue(afterFirstScan.any { it.id == expectedFlacId })

            // Re-scanning the exact same folder must upsert without inflating track count
            val secondCount = repo.scanDocumentTreeAndUpsert(rootDoc)
            assertEquals(2, secondCount)
            assertEquals(2, db.trackDao().getAllTracksOnce().size)

            db.close()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun offlineTabAndSettingsOpenDualModeScannerSheet() {
        composeRule.waitForIdle()
        if (composeRule.onAllNodes(hasTestTag("onboarding_later_btn")).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("onboarding_later_btn").performClick()
            composeRule.waitForIdle()
        }

        // 1. From Library -> Offline empty state
        composeRule.onNodeWithTag("nav_library").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("source_tab_offline").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("offline_empty_scan_btn").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("offline_scan_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_option_entire_device").assertIsDisplayed()
        composeRule.onNodeWithText("Quét toàn bộ thiết bị").assertIsDisplayed()
        composeRule.onNodeWithTag("scan_option_select_folder").assertIsDisplayed()
        composeRule.onNodeWithText("Chọn thư mục để quét").assertIsDisplayed()

        // Dismiss sheet
        composeRule.onNodeWithText("Đóng").performClick()
        composeRule.waitForIdle()

        // 2. From Settings -> Nguồn nhạc -> Quét nhạc trong máy
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("setting_scan_local_music"))
        composeRule.onNodeWithTag("setting_scan_local_music").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("offline_scan_sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Quét toàn bộ thiết bị").assertIsDisplayed()
        composeRule.onNodeWithText("Chọn thư mục để quét").assertIsDisplayed()
    }
}
