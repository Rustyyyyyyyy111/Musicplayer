package com.laughing.musicplayer

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.laughing.musicplayer.audio.CastPlaybackCoordinator
import com.laughing.musicplayer.audio.DualPlayerCrossfadeController
import com.laughing.musicplayer.audio.MusicLibraryService
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.SecureCredentialStore
import com.laughing.musicplayer.data.local.ListeningBehaviorStore
import com.laughing.musicplayer.data.local.MusicPlayerDatabase
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.data.storage.AudioTagEditor
import com.laughing.musicplayer.data.storage.CacheManager
import com.laughing.musicplayer.data.storage.OfflineMusicRepository
import com.laughing.musicplayer.data.storage.UnifiedStorageManager
import com.laughing.musicplayer.data.telegram.TelegramAuthentication
import com.laughing.musicplayer.data.telegram.TelegramChatBrowser
import com.laughing.musicplayer.data.telegram.TelegramCore
import com.laughing.musicplayer.data.telegram.TelegramFileResolver
import com.laughing.musicplayer.data.telegram.TelegramForumTopics
import com.laughing.musicplayer.data.telegram.TelegramMessageScanner
import com.laughing.musicplayer.data.telegram.TelegramMusicRepository
import com.laughing.musicplayer.data.youtube.YouTubeDownloadManager
import com.laughing.musicplayer.data.youtube.YouTubeMusicRepository
import com.laughing.musicplayer.data.youtube.YouTubeSessionCookieManager
import com.laughing.musicplayer.integrations.AiSmartMixEngine
import com.laughing.musicplayer.integrations.NetworkProxyManager
import com.laughing.musicplayer.integrations.PlaylistAndSmartMixManager
import com.laughing.musicplayer.lyrics.LyricsEngine
import com.laughing.musicplayer.widget.WidgetStateManager
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database = MusicPlayerDatabase.getInstance(appContext)
    val credentialStore = SecureCredentialStore(appContext)
    val settingsDataStore = SettingsDataStore(appContext, credentialStore)
    val activityLogger = ActivityLogger(database.activityLogDao())

    val storageManager = UnifiedStorageManager(appContext, settingsDataStore, activityLogger)
    val cacheManager = CacheManager(appContext, database.downloadCacheDao(), settingsDataStore, activityLogger)
    val youTubeCookieManager = YouTubeSessionCookieManager(
        appContext,
        credentialStore,
        settingsDataStore,
        activityLogger
    )
    val networkProxyManager = NetworkProxyManager(settingsDataStore, youTubeCookieManager, activityLogger)

    val offlineMusicRepository = OfflineMusicRepository(
        appContext,
        database.trackDao(),
        storageManager,
        settingsDataStore,
        activityLogger
    )

    val telegramCore = TelegramCore(appContext, credentialStore, activityLogger)
    val telegramAuthentication = TelegramAuthentication(
        telegramCore,
        settingsDataStore,
        activityLogger
    ) {
        database.trackDao().deletePersonalTelegramTracks()
        database.telegramDao().clearAllSources()
        database.telegramDao().clearAllTopics()
        database.telegramDao().clearAllCheckpoints()
        activityLogger.log("TG_AUTH", "INFO", "Đã dọn dữ liệu Telegram khi đăng xuất")
    }
    val telegramFileResolver = TelegramFileResolver(
        telegramCore,
        database.trackDao(),
        storageManager,
        activityLogger
    )
    val telegramForumTopics = TelegramForumTopics(
        telegramCore,
        database.telegramDao(),
        database.trackDao(),
        activityLogger
    )
    val telegramChatBrowser = TelegramChatBrowser(
        telegramCore,
        telegramForumTopics,
        database.telegramDao(),
        database.trackDao(),
        activityLogger
    )
    val telegramMessageScanner = TelegramMessageScanner(
        telegramCore,
        telegramForumTopics,
        database.trackDao(),
        database.telegramDao(),
        settingsDataStore,
        storageManager,
        activityLogger
    )
    val telegramMusicRepository = TelegramMusicRepository(
        appContext,
        telegramCore,
        telegramFileResolver,
        telegramChatBrowser,
        telegramMessageScanner,
        database.trackDao(),
        database.downloadCacheDao(),
        storageManager,
        activityLogger
    )

    val youTubeMusicRepository = YouTubeMusicRepository(networkProxyManager, database.trackDao(), activityLogger)
    val youTubeDownloadManager = YouTubeDownloadManager(
        appContext,
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

    val listeningBehaviorStore = ListeningBehaviorStore(appContext)
    val aiSmartMixEngine = AiSmartMixEngine(youTubeMusicRepository, activityLogger)

    val lyricsEngine = LyricsEngine(
        storageManager,
        { networkProxyManager.getHttpClient() },
        activityLogger
    )

    val playerController = DualPlayerCrossfadeController(
        appContext,
        telegramCore,
        telegramFileResolver,
        cacheManager,
        youTubeMusicRepository,
        database.trackDao(),
        settingsDataStore,
        playlistAndSmartMixManager,
        networkProxyManager,
        activityLogger,
        ioScope = appScope,
        behaviorStore = listeningBehaviorStore
    )

    val audioTagEditor = AudioTagEditor(
        appContext,
        database.trackDao(),
        activityLogger
    )

    val castPlaybackCoordinator = CastPlaybackCoordinator(
        appContext,
        playerController,
        activityLogger
    )

    @Volatile
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private val hasTriggeredSessionAutoSync = AtomicBoolean(false)
    private val postFrameInitDone = AtomicBoolean(false)

    init {
        playerController.onEnsureMediaSessionConnected = { connectMediaSessionService() }
        appScope.launch {
            playerController.playbackState
                .map { state ->
                    Triple(
                        state.currentTrack?.id to state.isPlaying,
                        state.currentTrack,
                        state.queue
                    )
                }
                .distinctUntilChanged { a, b ->
                    a.first == b.first &&
                        a.second?.artworkUri == b.second?.artworkUri &&
                        a.third.size == b.third.size
                }
                .collect { (_, track, queue) ->
                    WidgetStateManager.publishPlaybackSnapshot(
                        context = appContext,
                        currentTrack = track,
                        queue = queue,
                        isPlaying = playerController.playbackState.value.isPlaying
                    )
                }
        }
    }

    fun onFirstFrameRendered() {
        if (!postFrameInitDone.compareAndSet(false, true)) return
        playerController.restoreLastPlaybackCheckpointIfNeeded()
        appScope.launch(Dispatchers.IO) {
            youTubeCookieManager.warmUp()
            networkProxyManager.warmUp()
            telegramAuthentication.ensureStarted()
            observeTelegramStartupAutoSync()
        }
        castPlaybackCoordinator.ensureInitialized()
    }

    private fun observeTelegramStartupAutoSync() {
        if (isRobolectricEnvironment()) return
        appScope.launch {
            telegramCore.authState.collect { state ->
                if (state is TdApi.AuthorizationStateReady) {
                    if (!hasTriggeredSessionAutoSync.getAndSet(true)) {
                        val prefs = settingsDataStore.preferencesFlow.first()
                        if (prefs.autoSyncTelegram) {
                            runCatching {
                                val synced = telegramMusicRepository.syncSelectedSources(fullRescan = false)
                                activityLogger.log(
                                    "TG_SYNC",
                                    "INFO",
                                    "Đồng bộ tự động khi khởi động hoàn tất: $synced bài hát"
                                )
                            }.onFailure { err ->
                                activityLogger.log(
                                    "TG_SYNC",
                                    "WARN",
                                    "Đồng bộ tự động khi khởi động bỏ qua: ${err.message}"
                                )
                            }
                        }
                    }
                } else if (state !is TdApi.AuthorizationStateReady) {
                    hasTriggeredSessionAutoSync.set(false)
                }
            }
        }
    }

    private fun isRobolectricEnvironment(): Boolean {
        return Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
            Build.HARDWARE.equals("robolectric", ignoreCase = true)
    }

    fun connectMediaSessionService() {
        if (mediaControllerFuture != null) return
        if (isRobolectricEnvironment()) {
            return
        }
        runCatching {
            val sessionToken = SessionToken(
                appContext,
                ComponentName(appContext, MusicLibraryService::class.java)
            )
            val future = MediaController.Builder(appContext, sessionToken).buildAsync()
            mediaControllerFuture = future
            future.addListener(
                { runCatching { future.get() } },
                ContextCompat.getMainExecutor(appContext)
            )
        }.onFailure { err ->
            activityLogger.log("MEDIA_SESSION", "WARN", "Không thể kết nối MediaLibraryService: ${err.message}")
        }
    }
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
