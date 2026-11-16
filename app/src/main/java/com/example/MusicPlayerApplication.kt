package com.example

import android.app.Application
import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.example.audio.DualPlayerCrossfadeController
import com.example.core.util.ActivityLogger
import com.example.core.util.SecureCredentialStore
import com.example.data.local.MusicPlayerDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.storage.CacheManager
import com.example.data.storage.OfflineMusicRepository
import com.example.data.storage.UnifiedStorageManager
import com.example.data.telegram.TelegramAuthentication
import com.example.data.telegram.TelegramChatBrowser
import com.example.data.telegram.TelegramCore
import com.example.data.telegram.TelegramForumTopics
import com.example.data.telegram.TelegramMessageScanner
import com.example.data.telegram.TelegramMusicRepository
import com.example.data.youtube.YouTubeDownloadManager
import com.example.data.youtube.YouTubeMusicRepository
import com.example.integrations.PlaylistAndSmartMixManager
import com.example.integrations.ScrobbleAndProxyManager
import com.example.lyrics.LyricsEngine

@UnstableApi
class AppContainer(context: Context) {
    val database = MusicPlayerDatabase.getInstance(context)
    val credentialStore = SecureCredentialStore(context)
    val settingsDataStore = SettingsDataStore(context, credentialStore)
    val activityLogger = ActivityLogger(database.activityLogDao())

    val storageManager = UnifiedStorageManager(context, settingsDataStore, activityLogger)
    val cacheManager = CacheManager(context, database.downloadCacheDao(), settingsDataStore, activityLogger)
    val scrobbleAndProxyManager = ScrobbleAndProxyManager(settingsDataStore, credentialStore, activityLogger)

    val offlineMusicRepository = OfflineMusicRepository(
        context,
        database.trackDao(),
        storageManager,
        settingsDataStore,
        activityLogger
    )

    val telegramCore = TelegramCore(context, credentialStore, activityLogger)
    val telegramAuthentication = TelegramAuthentication(telegramCore, settingsDataStore, activityLogger)
    val telegramForumTopics = TelegramForumTopics(telegramCore, database.telegramDao(), activityLogger)
    val telegramChatBrowser = TelegramChatBrowser(telegramCore, telegramForumTopics, database.telegramDao(), activityLogger)
    val telegramMessageScanner = TelegramMessageScanner(
        telegramCore,
        telegramForumTopics,
        database.trackDao(),
        database.telegramDao(),
        settingsDataStore,
        activityLogger
    )
    val telegramMusicRepository = TelegramMusicRepository(
        context,
        telegramCore,
        telegramChatBrowser,
        telegramMessageScanner,
        database.trackDao(),
        database.downloadCacheDao(),
        storageManager,
        activityLogger
    )

    val youTubeMusicRepository = YouTubeMusicRepository(scrobbleAndProxyManager, database.trackDao(), activityLogger)
    val youTubeDownloadManager = YouTubeDownloadManager(
        youTubeMusicRepository,
        scrobbleAndProxyManager,
        database.trackDao(),
        database.downloadCacheDao(),
        storageManager,
        activityLogger
    )

    val playlistAndSmartMixManager = PlaylistAndSmartMixManager(
        database.playlistDao(),
        database.trackDao(),
        activityLogger
    )

    val lyricsEngine = LyricsEngine(
        storageManager,
        { scrobbleAndProxyManager.getHttpClient() },
        activityLogger
    )

    val playerController = DualPlayerCrossfadeController(
        context,
        telegramCore,
        cacheManager,
        youTubeMusicRepository,
        database.trackDao(),
        settingsDataStore,
        playlistAndSmartMixManager,
        scrobbleAndProxyManager,
        activityLogger
    )
}

class MusicPlayerApplication : Application(), androidx.work.Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
