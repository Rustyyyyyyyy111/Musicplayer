package com.laughing.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.laughing.musicplayer.core.model.TelegramChatType
import com.laughing.musicplayer.core.model.UnifiedSearchTab
import com.laughing.musicplayer.ui.components.AppleMusicSurfaceBackground
import com.laughing.musicplayer.ui.components.FloatingMiniPlayer
import com.laughing.musicplayer.ui.screens.ActivityLogsScreen
import com.laughing.musicplayer.ui.screens.CarModeScreen
import com.laughing.musicplayer.ui.screens.DownloadsManagerScreen
import com.laughing.musicplayer.ui.screens.EqualizerScreen
import com.laughing.musicplayer.ui.screens.LibraryScreen
import com.laughing.musicplayer.ui.screens.ListenNowScreen
import com.laughing.musicplayer.ui.screens.NowPlayingSheet
import com.laughing.musicplayer.ui.screens.SearchScreen
import com.laughing.musicplayer.ui.screens.SettingsScreen
import com.laughing.musicplayer.ui.screens.TelegramAuthScreen
import com.laughing.musicplayer.ui.screens.TelegramSourcePickerScreen
import com.laughing.musicplayer.ui.screens.TopicTracksScreen
import com.laughing.musicplayer.ui.screens.YouTubeAuthScreen
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.theme.MusicPlayerTheme
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel
import kotlinx.serialization.Serializable

@Serializable object ListenNowRoute
@Serializable object LibraryRoute
@Serializable object SearchRoute
@Serializable object SettingsRoute
@Serializable object TelegramAuthRoute
@Serializable object YouTubeAuthRoute
@Serializable data class TelegramSourcePickerRoute(val filterTypeName: String? = null)
@Serializable data class TopicTracksRoute(val chatId: Long, val threadId: Long, val topicTitle: String)
@Serializable object EqualizerRoute
@Serializable object DownloadsRoute
@Serializable object CarModeRoute
@Serializable object ActivityLogsRoute

private data class BottomNavTab(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialTrackId = intent?.getStringExtra("widget_play_track_id")
        setContent {
            val vm: MusicPlayerViewModel = viewModel()
            LaunchedEffect(initialTrackId) {
                if (!initialTrackId.isNullOrBlank()) {
                    vm.playTrackByIdFromWidget(initialTrackId)
                }
            }
            MusicPlayerRoot(viewModel = vm)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
fun MusicPlayerRoot(viewModel: MusicPlayerViewModel) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val liquidPalette by viewModel.liquidGlassPalette.collectAsStateWithLifecycle()
    val toastMsg by viewModel.statusToast.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(toastMsg) {
        toastMsg?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearToast()
        }
    }

    MusicPlayerTheme(
        themeMode = prefs.themeMode,
        liquidGlassPalette = liquidPalette
    ) {
        val colors = LocalMusicPlayerColors.current
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val isCarMode = currentDestination?.hasRoute<CarModeRoute>() == true

        val primaryTabs = remember {
            listOf(
                BottomNavTab("Nghe nhạc", Icons.Filled.PlayCircle, ListenNowRoute, "nav_listen_now"),
                BottomNavTab("Thư viện", Icons.Filled.LibraryMusic, LibraryRoute, "nav_library"),
                BottomNavTab("Tìm kiếm", Icons.Filled.Search, SearchRoute, "nav_search"),
                BottomNavTab("Cài đặt", Icons.Filled.Settings, SettingsRoute, "nav_settings")
            )
        }

        fun navigateTopTab(route: Any) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        AppleMusicSurfaceBackground(artworkUri = playback.currentTrack?.artworkUri) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavRail = maxWidth >= 600.dp && !isCarMode

                Row(modifier = Modifier.fillMaxSize()) {
                    if (useNavRail) {
                        NavigationRail(
                            containerColor = colors.navBarSurface,
                            modifier = Modifier.statusBarsPadding()
                        ) {
                            primaryTabs.forEach { tab ->
                                val selected = when (tab.route) {
                                    is ListenNowRoute -> currentDestination?.hasRoute<ListenNowRoute>() == true
                                    is LibraryRoute -> currentDestination?.hasRoute<LibraryRoute>() == true
                                    is SearchRoute -> currentDestination?.hasRoute<SearchRoute>() == true
                                    is SettingsRoute -> currentDestination?.hasRoute<SettingsRoute>() == true
                                    else -> false
                                }
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTopTab(tab.route) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = colors.accent,
                                        selectedTextColor = colors.accent,
                                        unselectedIconColor = colors.textSecondary,
                                        unselectedTextColor = colors.textSecondary,
                                        indicatorColor = colors.accent.copy(alpha = 0.14f)
                                    ),
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }

                    Scaffold(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        snackbarHost = {
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier.padding(bottom = if (playback.currentTrack != null) 84.dp else 12.dp)
                            )
                        },
                        bottomBar = {
                            if (!isCarMode) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val progress = if (playback.durationMs > 0L) {
                                        playback.positionMs.toFloat() / playback.durationMs.toFloat()
                                    } else 0f

                                    if (playback.currentTrack != null) {
                                        FloatingMiniPlayer(
                                            track = playback.currentTrack,
                                            isPlaying = playback.isPlaying,
                                            progress = progress,
                                            onTap = { showNowPlaying = true },
                                            onTogglePlayPause = { viewModel.togglePlayPause() },
                                            onSkipNext = { viewModel.skipNext() },
                                            modifier = Modifier
                                                .widthIn(max = 680.dp)
                                                .padding(bottom = if (useNavRail) 12.dp else 8.dp)
                                        )
                                    }

                                    if (!useNavRail) {
                                        NavigationBar(
                                            containerColor = colors.navBarSurface,
                                            tonalElevation = 0.dp
                                        ) {
                                            primaryTabs.forEach { tab ->
                                                val selected = when (tab.route) {
                                                    is ListenNowRoute -> currentDestination?.hasRoute<ListenNowRoute>() == true
                                                    is LibraryRoute -> currentDestination?.hasRoute<LibraryRoute>() == true
                                                    is SearchRoute -> currentDestination?.hasRoute<SearchRoute>() == true
                                                    is SettingsRoute -> currentDestination?.hasRoute<SettingsRoute>() == true
                                                    else -> false
                                                }
                                                NavigationBarItem(
                                                    selected = selected,
                                                    onClick = { navigateTopTab(tab.route) },
                                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                                    label = { Text(tab.label, maxLines = 1) },
                                                    colors = NavigationBarItemDefaults.colors(
                                                        selectedIconColor = colors.accent,
                                                        selectedTextColor = colors.accent,
                                                        unselectedIconColor = colors.textSecondary,
                                                        unselectedTextColor = colors.textSecondary,
                                                        indicatorColor = colors.accent.copy(alpha = 0.14f)
                                                    ),
                                                    modifier = Modifier.testTag(tab.testTag)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (!isCarMode) Modifier.statusBarsPadding() else Modifier)
                                .padding(innerPadding)
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = ListenNowRoute
                            ) {
                                composable<ListenNowRoute> {
                                    ListenNowScreen(
                                        viewModel = viewModel,
                                        onOpenCarMode = { navController.navigate(CarModeRoute) },
                                        onOpenEqualizer = { navController.navigate(EqualizerRoute) }
                                    )
                                }

                                composable<LibraryRoute> {
                                    LibraryScreen(
                                        viewModel = viewModel,
                                        onOpenSearch = { navigateTopTab(SearchRoute) }
                                    )
                                }

                                composable<SearchRoute> {
                                    SearchScreen(viewModel = viewModel)
                                }

                                composable<SettingsRoute> {
                                    SettingsScreen(
                                        viewModel = viewModel,
                                        onOpenTelegramAuth = { navController.navigate(TelegramAuthRoute) },
                                        onOpenYouTubeAuth = { navController.navigate(YouTubeAuthRoute) },
                                        onOpenSourcePicker = { chatType ->
                                            navController.navigate(TelegramSourcePickerRoute(chatType?.name))
                                        },
                                        onOpenYouTubeSearch = {
                                            viewModel.selectSearchTab(UnifiedSearchTab.YOUTUBE_MUSIC)
                                            navigateTopTab(SearchRoute)
                                        },
                                        onOpenEqualizer = { navController.navigate(EqualizerRoute) },
                                        onOpenCarMode = { navController.navigate(CarModeRoute) },
                                        onOpenDownloads = { navController.navigate(DownloadsRoute) },
                                        onOpenActivityLogs = { navController.navigate(ActivityLogsRoute) },
                                        onOpenNowPlaying = {
                                            if (playback.currentTrack != null) {
                                                showNowPlaying = true
                                            } else {
                                                navigateTopTab(LibraryRoute)
                                            }
                                        }
                                    )
                                }

                                composable<TelegramAuthRoute> {
                                    TelegramAuthScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() },
                                        onOpenSourcePicker = { navController.navigate(TelegramSourcePickerRoute()) }
                                    )
                                }

                                composable<YouTubeAuthRoute> {
                                    YouTubeAuthScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable<TelegramSourcePickerRoute> { entry ->
                                    val args = entry.toRoute<TelegramSourcePickerRoute>()
                                    val filter = args.filterTypeName?.let {
                                        runCatching { TelegramChatType.valueOf(it) }.getOrNull()
                                    }
                                    TelegramSourcePickerScreen(
                                        viewModel = viewModel,
                                        initialFilterType = filter,
                                        onBack = { navController.popBackStack() },
                                        onOpenTopicTracks = { chatId, threadId, title ->
                                            navController.navigate(TopicTracksRoute(chatId, threadId, title))
                                        }
                                    )
                                }

                                composable<TopicTracksRoute> { entry ->
                                    val args = entry.toRoute<TopicTracksRoute>()
                                    TopicTracksScreen(
                                        viewModel = viewModel,
                                        chatId = args.chatId,
                                        threadId = args.threadId,
                                        topicTitle = args.topicTitle,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable<EqualizerRoute> {
                                    EqualizerScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable<DownloadsRoute> {
                                    DownloadsManagerScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable<CarModeRoute> {
                                    CarModeScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }

                                composable<ActivityLogsRoute> {
                                    ActivityLogsScreen(
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }
                        }
                    }
                }

                // Full-screen Now Playing Overlay
                AnimatedVisibility(
                    visible = showNowPlaying && playback.currentTrack != null,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    NowPlayingSheet(
                        viewModel = viewModel,
                        onDismiss = { showNowPlaying = false },
                        onOpenEqualizer = {
                            showNowPlaying = false
                            navController.navigate(EqualizerRoute)
                        }
                    )
                }
            }
        }
    }
}
