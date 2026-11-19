package com.laughing.musicplayer.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class CastRouteUi(
    val id: String,
    val name: String,
    val description: String,
    val isSelected: Boolean,
    val isConnecting: Boolean
)

data class LocalAudioOutputUi(
    val id: String,
    val name: String,
    val typeLabel: String,
    val isBluetooth: Boolean,
    val isWired: Boolean,
    val isActive: Boolean
)

data class CastUiState(
    val isCastFrameworkAvailable: Boolean = false,
    val isDiscovering: Boolean = false,
    val connectedDeviceName: String? = null,
    val castRoutes: List<CastRouteUi> = emptyList(),
    val localAudioOutputs: List<LocalAudioOutputUi> = emptyList(),
    val statusNotice: String? = null
)

@UnstableApi
class CastPlaybackCoordinator(
    private val context: Context,
    private val playerController: DualPlayerCrossfadeController,
    private val logger: ActivityLogger
) {
    private val appContext = context.applicationContext
    private val _uiState = MutableStateFlow(CastUiState())
    val uiState: StateFlow<CastUiState> = _uiState.asStateFlow()

    private val mediaRouter: MediaRouter? = runCatching {
        if (isRobolectricEnvironment()) null else MediaRouter.getInstance(appContext)
    }.getOrNull()

    private val routeSelector: MediaRouteSelector? = runCatching {
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                )
            )
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()
    }.getOrNull()

    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null
    private var callbackRegistered = false

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
            refreshRoutes()
            if (!route.isDefault) {
                logger.log("CAST", "INFO", "Đã chọn thiết bị phát: ${route.name}")
            }
        }
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
    }

    init {
        initializeCastContextSafely()
        refreshAudioOutputs()
    }

    private fun isRobolectricEnvironment(): Boolean {
        return Build.FINGERPRINT.equals("robolectric", ignoreCase = true) ||
            Build.HARDWARE.equals("robolectric", ignoreCase = true)
    }

    private fun initializeCastContextSafely() {
        if (isRobolectricEnvironment()) {
            _uiState.value = _uiState.value.copy(
                isCastFrameworkAvailable = false,
                statusNotice = "Dịch vụ Chromecast không khả dụng trong môi trường kiểm thử — có thể chọn đầu ra Bluetooth hoặc âm thanh hệ thống."
            )
            return
        }
        val playStatus = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        }.getOrDefault(ConnectionResult.SERVICE_MISSING)

        if (playStatus != ConnectionResult.SUCCESS) {
            _uiState.value = _uiState.value.copy(
                isCastFrameworkAvailable = false,
                statusNotice = "Thiết bị chưa sẵn sàng Google Play Services cho Chromecast. Bạn vẫn có thể chọn Bluetooth hoặc đầu ra âm thanh hệ thống."
            )
            return
        }

        runCatching {
            val executor = ContextCompat.getMainExecutor(appContext)
            CastContext.getSharedInstance(appContext, executor)
                .addOnSuccessListener { ctx ->
                    castContext = ctx
                    val player = runCatching { CastPlayer(ctx) }.getOrNull()
                    castPlayer = player
                    player?.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                        override fun onCastSessionAvailable() {
                            val deviceName = ctx.sessionManager.currentCastSession?.castDevice?.friendlyName
                                ?: "Chromecast"
                            _uiState.value = _uiState.value.copy(
                                connectedDeviceName = deviceName,
                                statusNotice = "Đã kết nối Chromecast: $deviceName"
                            )
                            castCurrentTrackIfPossible()
                            refreshRoutes()
                        }

                        override fun onCastSessionUnavailable() {
                            _uiState.value = _uiState.value.copy(
                                connectedDeviceName = null,
                                statusNotice = "Đã ngắt kết nối Chromecast, tiếp tục phát trên thiết bị."
                            )
                            refreshRoutes()
                        }
                    })
                    _uiState.value = _uiState.value.copy(isCastFrameworkAvailable = true)
                    refreshRoutes()
                }
                .addOnFailureListener { err ->
                    logger.log("CAST", "WARN", "CastContext không khả dụng: ${err.message}")
                    _uiState.value = _uiState.value.copy(
                        isCastFrameworkAvailable = false,
                        statusNotice = "Không thể khởi tạo Google Cast trên thiết bị này. Hãy dùng Bluetooth hoặc Đầu ra hệ thống."
                    )
                }
        }.onFailure { err ->
            logger.log("CAST", "WARN", "Bỏ qua CastContext: ${err.message}")
            _uiState.value = _uiState.value.copy(
                isCastFrameworkAvailable = false,
                statusNotice = "Không thể khởi tạo Google Cast trên thiết bị này. Hãy dùng Bluetooth hoặc Đầu ra hệ thống."
            )
        }
    }

    fun startDiscovery() {
        val router = mediaRouter
        val selector = routeSelector
        if (router != null && selector != null && !callbackRegistered) {
            runCatching {
                router.addCallback(
                    selector,
                    routerCallback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
                )
                callbackRegistered = true
            }
        }
        _uiState.value = _uiState.value.copy(isDiscovering = true)
        refreshRoutes()
        refreshAudioOutputs()
    }

    fun stopDiscovery() {
        if (callbackRegistered) {
            runCatching { mediaRouter?.removeCallback(routerCallback) }
            callbackRegistered = false
        }
        _uiState.value = _uiState.value.copy(isDiscovering = false)
    }

    fun refreshRoutes() {
        val router = mediaRouter
        val selector = routeSelector
        val routes = if (router != null && selector != null) {
            runCatching {
                router.routes
                    .filter { !it.isDefault && it.matchesSelector(selector) }
                    .map { route ->
                        CastRouteUi(
                            id = route.id,
                            name = route.name,
                            description = route.description?.takeIf { it.isNotBlank() } ?: "Thiết bị Chromecast",
                            isSelected = route.isSelected,
                            isConnecting = route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING
                        )
                    }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val activeCastName = runCatching {
            castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
        }.getOrNull() ?: routes.firstOrNull { it.isSelected }?.name

        val notice = when {
            activeCastName != null -> "Đang kết nối tới $activeCastName"
            !_uiState.value.isCastFrameworkAvailable ->
                "Dịch vụ Google Cast chưa sẵn sàng trên thiết bị. Hãy chọn loa Bluetooth hoặc đầu ra hệ thống bên dưới."
            routes.isEmpty() ->
                "Chưa tìm thấy thiết bị Chromecast cùng mạng Wi-Fi. Bạn có thể chuyển sang Bluetooth hoặc đầu ra âm thanh hệ thống."
            else -> null
        }

        _uiState.value = _uiState.value.copy(
            connectedDeviceName = activeCastName,
            castRoutes = routes,
            statusNotice = notice
        )
    }

    fun refreshAudioOutputs() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val outputs = if (audioManager != null) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val mapped = devices.mapNotNull { info ->
                when (info.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_BLE_BROADCAST -> LocalAudioOutputUi(
                        id = "bt_${info.id}",
                        name = info.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Thiết bị Bluetooth",
                        typeLabel = "Bluetooth Audio",
                        isBluetooth = true,
                        isWired = false,
                        isActive = true
                    )
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> LocalAudioOutputUi(
                        id = "wired_${info.id}",
                        name = info.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Tai nghe / USB Audio",
                        typeLabel = "Kết nối có dây / DAC",
                        isBluetooth = false,
                        isWired = true,
                        isActive = true
                    )
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> LocalAudioOutputUi(
                        id = "speaker_${info.id}",
                        name = "Loa ngoài thiết bị",
                        typeLabel = "Âm thanh mặc định",
                        isBluetooth = false,
                        isWired = false,
                        isActive = false
                    )
                    else -> null
                }
            }.distinctBy { "${it.name}_${it.typeLabel}" }

            val hasExternal = mapped.any { it.isBluetooth || it.isWired }
            mapped.map { item ->
                if (!item.isBluetooth && !item.isWired) item.copy(isActive = !hasExternal) else item
            }
        } else {
            emptyList()
        }

        _uiState.value = _uiState.value.copy(
            localAudioOutputs = outputs.ifEmpty {
                listOf(
                    LocalAudioOutputUi(
                        id = "speaker_default",
                        name = "Loa ngoài thiết bị",
                        typeLabel = "Âm thanh mặc định",
                        isBluetooth = false,
                        isWired = false,
                        isActive = true
                    )
                )
            }
        )
    }

    fun selectCastRoute(routeId: String): String {
        val router = mediaRouter
            ?: return "MediaRouter không khả dụng trên thiết bị này."
        val target = router.routes.firstOrNull { it.id == routeId }
            ?: return "Không tìm thấy thiết bị Chromecast đã chọn."
        runCatching { target.select() }.onFailure { err ->
            logger.log("CAST", "WARN", "Không thể chọn tuyến Cast '${target.name}': ${err.message}")
            return "Không thể kết nối tới '${target.name}': ${err.message}"
        }
        refreshRoutes()
        return "Đang kết nối tới '${target.name}'..."
    }

    fun disconnectCast(): String {
        runCatching {
            castContext?.sessionManager?.endCurrentSession(true)
            mediaRouter?.defaultRoute?.select()
        }
        _uiState.value = _uiState.value.copy(connectedDeviceName = null)
        refreshRoutes()
        return "Đã ngắt kết nối Chromecast và quay về loa thiết bị."
    }

    private fun castCurrentTrackIfPossible() {
        val remote = castPlayer ?: return
        val state = playerController.playbackState.value
        val track = state.currentTrack ?: return
        val remoteUrl = track.streamUri?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (remoteUrl == null) {
            _uiState.value = _uiState.value.copy(
                statusNotice = "Đã kết nối Chromecast, nhưng tệp nội bộ/${track.source.labelVi} chưa có URL HTTP công khai để phát trực tiếp lên TV. Hãy dùng Bluetooth hoặc đầu ra hệ thống."
            )
            logger.log("CAST", "INFO", "Track '${track.title}' không có HTTP URI để Cast trực tiếp")
            return
        }
        runCatching {
            val artUri = track.artworkUri?.let { raw ->
                if (raw.startsWith("http://") || raw.startsWith("https://")) Uri.parse(raw) else null
            }
            val item = MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(remoteUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setArtworkUri(artUri)
                        .build()
                )
                .build()
            if (playerController.activePlayer.isPlaying) {
                playerController.activePlayer.pause()
            }
            remote.setMediaItem(item, state.positionMs.coerceAtLeast(0L))
            remote.prepare()
            remote.play()
        }.onFailure { err ->
            logger.log("CAST", "WARN", "Lỗi gửi luồng lên CastPlayer: ${err.message}")
        }
    }

    fun openSystemAudioOutputSwitcher(callerContext: Context): Boolean {
        val pkg = callerContext.packageName
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG").apply {
                        putExtra("package_name", pkg)
                        putExtra("extra_package_name", pkg)
                    }
                )
            }
            add(Intent(Settings.ACTION_SOUND_SETTINGS))
        }
        return launchFirstResolvableIntent(callerContext, candidates)
    }

    fun openBluetoothSettings(callerContext: Context): Boolean {
        return launchFirstResolvableIntent(
            callerContext,
            listOf(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                Intent(Settings.ACTION_SOUND_SETTINGS)
            )
        )
    }

    private fun launchFirstResolvableIntent(callerContext: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (callerContext !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolved = runCatching {
                intent.resolveActivity(callerContext.packageManager) != null
            }.getOrDefault(false)
            if (resolved) {
                val started = runCatching { callerContext.startActivity(intent) }.isSuccess
                if (started) return true
            }
        }
        return false
    }
}
