package com.laughing.musicplayer

import android.app.Notification
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.SecureCredentialStore
import com.laughing.musicplayer.data.local.MusicPlayerDatabase
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.data.storage.UnifiedStorageManager
import com.laughing.musicplayer.data.youtube.YouTubeDownloadManager
import com.laughing.musicplayer.data.youtube.YouTubeDownloadNotificationHelper
import com.laughing.musicplayer.data.youtube.YouTubeDownloadWorker
import com.laughing.musicplayer.data.youtube.YouTubeMusicRepository
import com.laughing.musicplayer.data.youtube.YouTubeSearchTab
import com.laughing.musicplayer.ui.screens.NowPlayingDownloadActionButton
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YouTubeUpgradeRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun standardYouTubeTabsAndSearchResultsAllowAllPlayableDurations() {
        assertEquals("videos", YouTubeSearchTab.VIDEOS.filterKey)
        assertEquals("channels", YouTubeSearchTab.CHANNELS.filterKey)
        assertEquals("playlists", YouTubeSearchTab.PLAYLISTS.filterKey)

        val liveItem = StreamInfoItem(0, "https://youtube.com/watch?v=live1", "Lofi Live Radio", StreamType.LIVE_STREAM).apply {
            duration = -1L
        }
        val shortItem = StreamInfoItem(0, "https://youtube.com/watch?v=short1", "Short Clip 45s", StreamType.VIDEO_STREAM).apply {
            duration = 45L
        }
        val standardItem = StreamInfoItem(0, "https://youtube.com/watch?v=ok240", "Cover Acoustic", StreamType.VIDEO_STREAM).apply {
            duration = 240L
        }
        val djMixItem = StreamInfoItem(0, "https://youtube.com/watch?v=dj2h", "Vinahouse Nonstop DJ Mix 2h", StreamType.VIDEO_STREAM).apply {
            duration = 7_200L
        }
        val fullAlbumItem = StreamInfoItem(0, "https://youtube.com/watch?v=album90m", "Full Album Live Set 90m", StreamType.VIDEO_STREAM).apply {
            duration = 5_400L
        }
        val invalidBlankUrl = StreamInfoItem(0, "", "Broken Entry", StreamType.VIDEO_STREAM).apply {
            duration = 180L
        }

        val filtered = YouTubeMusicRepository.filterSearchStreamItems(
            listOf(liveItem, shortItem, standardItem, djMixItem, fullAlbumItem, invalidBlankUrl)
        )
        assertEquals(
            listOf("Lofi Live Radio", "Short Clip 45s", "Cover Acoustic", "Vinahouse Nonstop DJ Mix 2h", "Full Album Live Set 90m"),
            filtered.map { it.name }
        )

        val query = YouTubeMusicRepository.buildRelatedSearchQuery(
            Track(
                id = "yt_demo",
                title = "Chạy Ngay Đi (Official MV)",
                artist = "Sơn Tùng M-TP - Topic",
                album = "YouTube",
                source = MusicSource.YOUTUBE_MUSIC,
                durationMs = 240_000L,
                format = AudioFormat.OPUS,
                bitrateKbps = 160,
                sampleRateHz = 48000,
                bitDepth = null,
                fileName = "demo.opus"
            )
        )
        assertTrue(query.contains("Sơn Tùng M-TP"))
    }

    @Test
    fun audioStreamSelectorPrioritizesOpusThenM4aAndIgnoresBlankUrls() {
        val blankOpus = AudioStream.Builder()
            .setId("blank_opus")
            .setContent("", true)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setMediaFormat(MediaFormat.WEBMA_OPUS)
            .setAverageBitrate(256)
            .build()

        val highM4a = AudioStream.Builder()
            .setId("m4a_192")
            .setContent("https://example.com/audio.m4a", true)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setMediaFormat(MediaFormat.M4A)
            .setAverageBitrate(192)
            .build()

        val opus128 = AudioStream.Builder()
            .setId("opus_128")
            .setContent("https://example.com/audio_128.webm", true)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setMediaFormat(MediaFormat.WEBMA_OPUS)
            .setAverageBitrate(128)
            .build()

        val opus160 = AudioStream.Builder()
            .setId("opus_160")
            .setContent("https://example.com/audio_160.webm", true)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setMediaFormat(MediaFormat.WEBMA_OPUS)
            .setAverageBitrate(160)
            .build()

        val selectedOpus = YouTubeMusicRepository.selectBestOpusFirstStream(
            listOf(blankOpus, highM4a, opus128, opus160)
        )
        assertNotNull(selectedOpus)
        assertEquals("https://example.com/audio_160.webm", selectedOpus?.content)

        // Fallback to M4A when no OPUS stream exists
        val mp3Low = AudioStream.Builder()
            .setId("mp3_96")
            .setContent("https://example.com/audio.mp3", true)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setMediaFormat(MediaFormat.MP3)
            .setAverageBitrate(96)
            .build()

        val selectedM4a = YouTubeMusicRepository.selectBestOpusFirstStream(listOf(mp3Low, highM4a))
        assertEquals("https://example.com/audio.m4a", selectedM4a?.content)
    }

    @Test
    fun downloadNotificationsDisplayProgressAndDownloadCompleteAndManagerGuardsDuplicates() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, MusicPlayerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val logger = ActivityLogger(db.activityLogDao())
            val settings = SettingsDataStore(context, SecureCredentialStore(context))
            val storage = UnifiedStorageManager(context, settings, logger)

            // Scoped storage fallback when All Files Access is disabled in Robolectric
            assertEquals("/sdcard/Music/Music Player/YouTube/", storage.getYouTubeDisplayPath())

            val progressNotif = YouTubeDownloadNotificationHelper.buildProgressNotification(
                context = context,
                title = "Bài Hát Remix Lofi",
                progressPercent = 45,
                indeterminate = false
            )
            assertEquals("Bài Hát Remix Lofi", progressNotif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals(100, progressNotif.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertEquals(45, progressNotif.extras.getInt(Notification.EXTRA_PROGRESS))
            assertFalse(progressNotif.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertTrue((progressNotif.flags and Notification.FLAG_ONGOING_EVENT) != 0)

            val doneNotif = YouTubeDownloadNotificationHelper.buildCompletedNotification(
                context = context,
                title = "Bài Hát Remix Lofi"
            )
            assertEquals("Bài Hát Remix Lofi", doneNotif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals("Download complete", doneNotif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals(0, doneNotif.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertEquals(0, doneNotif.extras.getInt(Notification.EXTRA_PROGRESS))
            assertTrue((doneNotif.flags and Notification.FLAG_ONGOING_EVENT) == 0)

            val manager = YouTubeDownloadManager(
                context = context,
                trackDao = db.trackDao(),
                downloadCacheDao = db.downloadCacheDao(),
                storageManager = storage,
                logger = logger
            )
            val alreadyDownloaded = Track(
                id = "yt_done_1",
                title = "Already Downloaded",
                artist = "Artist",
                album = "YouTube",
                source = MusicSource.YOUTUBE_MUSIC,
                durationMs = 180_000L,
                format = AudioFormat.OPUS,
                streamUri = "https://www.youtube.com/watch?v=done1",
                youtubeVideoId = "done1",
                downloadStatus = DownloadStatus.COMPLETED,
                downloadProgress = 1f
            )
            manager.downloadTrack(alreadyDownloaded)
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(YouTubeDownloadWorker.uniqueWorkName(alreadyDownloaded.id))
                .get()
            assertTrue("Completed track must not enqueue duplicate download work", infos.isEmpty() || infos.none {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            })

            db.close()
        }
    }

    @Test
    fun nowPlayingDownloadActionButtonTransitionsBetweenIdleProgressCancelAndCompletedStates() {
        var startedCount = 0
        var cancelledCount = 0
        var currentStatus by androidx.compose.runtime.mutableStateOf(DownloadStatus.NONE)
        var currentProgress by androidx.compose.runtime.mutableFloatStateOf(0f)

        composeRule.setContent {
            NowPlayingDownloadActionButton(
                status = currentStatus,
                progress = currentProgress,
                onStartDownload = { startedCount++ },
                onCancelDownload = { cancelledCount++ }
            )
        }

        // 1. Not downloaded -> enabled download icon triggers start
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("now_playing_download_btn").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, startedCount)

        // 2. Downloading -> CircularProgressIndicator + "45%", tap cancels download
        composeRule.runOnUiThread {
            currentStatus = DownloadStatus.DOWNLOADING
            currentProgress = 0.45f
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("now_playing_download_progress").assertIsDisplayed()
        composeRule.onNodeWithText("45%").assertIsDisplayed()
        composeRule.onNodeWithTag("now_playing_download_progress").performClick()
        assertEquals(1, cancelledCount)

        // 3. Downloaded -> OfflinePin icon shown and disabled to block duplicate downloads
        composeRule.runOnUiThread {
            currentStatus = DownloadStatus.COMPLETED
            currentProgress = 1f
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("now_playing_downloaded_btn").assertIsDisplayed().assertIsNotEnabled()
    }
}
