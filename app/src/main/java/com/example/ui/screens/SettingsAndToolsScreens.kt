package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.core.model.CacheLimitOption
import com.example.core.model.CrossfadeOption
import com.example.core.model.DownloadStatus
import com.example.core.model.EqPreset
import com.example.core.model.ProxyConfig
import com.example.core.model.ProxyType
import com.example.core.model.TelegramChatType
import com.example.core.model.ThemeMode
import com.example.data.telegram.TelegramAuthUiState
import com.example.ui.components.AppleCard
import com.example.ui.components.ArtworkImage
import com.example.ui.theme.LocalMusicPlayerColors
import com.example.ui.viewmodel.MusicPlayerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: MusicPlayerViewModel,
    onOpenTelegramAuth: () -> Unit,
    onOpenSourcePicker: (TelegramChatType?) -> Unit,
    onOpenYouTubeSearch: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenCarMode: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenActivityLogs: () -> Unit,
    onOpenNowPlaying: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalMusicPlayerColors.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()
    val tgAuth by viewModel.telegramAuthState.collectAsStateWithLifecycle()
    val proxyTestResult by viewModel.proxyTestResult.collectAsStateWithLifecycle()

    var showCacheLimitDialog by remember { mutableStateOf(false) }
    var showProxyDialog by remember { mutableStateOf(false) }
    var showScrobbleDialog by remember { mutableStateOf(false) }
    var showVersionDialog by remember { mutableStateOf(false) }
    var showCrossfadeDialog by remember { mutableStateOf(false) }

    val safTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            viewModel.setSafTreeUri(uri)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text(
                text = "Cài đặt",
                style = MaterialTheme.typography.displayLarge,
                color = colors.textPrimary
            )
        }

        // Section 1: Giao diện
        item {
            SettingsSectionHeader("Giao diện")
            AppleCard(cornerRadius = 22.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsNavRow(
                        icon = Icons.Filled.Palette,
                        title = "Đổi giao diện",
                        subtitle = prefs.themeMode.labelVi,
                        onClick = {
                            val next = when (prefs.themeMode) {
                                ThemeMode.LIGHT -> ThemeMode.DARK
                                ThemeMode.DARK -> ThemeMode.LIQUID_GLASS
                                ThemeMode.LIQUID_GLASS -> ThemeMode.LIGHT
                            }
                            viewModel.setThemeMode(next)
                        },
                        testTag = "setting_theme_cycle"
                    )
                    SettingsDivider()
                    ThemeChoiceRow(
                        label = "Chế độ sáng",
                        selected = prefs.themeMode == ThemeMode.LIGHT,
                        onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                        testTag = "setting_theme_light"
                    )
                    SettingsDivider()
                    ThemeChoiceRow(
                        label = "Chế độ tối",
                        selected = prefs.themeMode == ThemeMode.DARK,
                        onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                        testTag = "setting_theme_dark"
                    )
                    SettingsDivider()
                    ThemeChoiceRow(
                        label = "Liquid Glass",
                        selected = prefs.themeMode == ThemeMode.LIQUID_GLASS,
                        onClick = { viewModel.setThemeMode(ThemeMode.LIQUID_GLASS) },
                        testTag = "setting_theme_liquid_glass"
                    )
                }
            }
        }

        // Section 2: Nguồn nhạc
        item {
            SettingsSectionHeader("Nguồn nhạc")
            AppleCard(cornerRadius = 22.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val tgSubtitle = when (val st = tgAuth) {
                        is TelegramAuthUiState.Authenticated -> "Đã đăng nhập: ${st.displayName}"
                        is TelegramAuthUiState.Unconfigured -> "Chưa cấu hình khóa API"
                        else -> "Chưa đăng nhập"
                    }
                    SettingsNavRow(
                        icon = Icons.Filled.Send,
                        title = "Tài khoản Telegram",
                        subtitle = tgSubtitle,
                        onClick = onOpenTelegramAuth,
                        testTag = "setting_tg_account"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.CloudSync,
                        title = "Chọn kênh lấy nhạc",
                        subtitle = "Kênh công khai & Kênh riêng tư",
                        onClick = { onOpenSourcePicker(TelegramChatType.PUBLIC_CHANNEL) },
                        testTag = "setting_tg_channels"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.Group,
                        title = "Chọn nhóm lấy nhạc",
                        subtitle = "Nhóm, Siêu nhóm & Forum Topics",
                        onClick = { onOpenSourcePicker(TelegramChatType.FORUM_GROUP) },
                        testTag = "setting_tg_groups"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.Group,
                        title = "Chọn cuộc trò chuyện",
                        subtitle = "Trò chuyện riêng & Tin nhắn đã lưu",
                        onClick = { onOpenSourcePicker(TelegramChatType.PRIVATE_CHAT) },
                        testTag = "setting_tg_chats"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.PlayArrow,
                        title = "YouTube Music",
                        subtitle = "Tìm kiếm & tải xuống MP3/M4A/OPUS",
                        onClick = onOpenYouTubeSearch,
                        testTag = "setting_yt_music"
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Đồng bộ tự động",
                        subtitle = "Tự động quét bài hát mới từ Telegram bằng WorkManager",
                        checked = prefs.autoSyncTelegram,
                        onCheckedChange = { viewModel.setAutoSync(it) },
                        testTag = "setting_auto_sync"
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Bao gồm tin nhắn thoại",
                        subtitle = "Lập chỉ mục cả Voice Note / tin nhắn thoại Telegram",
                        checked = prefs.includeVoiceMessages,
                        onCheckedChange = { viewModel.setIncludeVoice(it) }
                    )
                }
            }
        }

        // Section 3: Nhạc và phát lại
        item {
            SettingsSectionHeader("Nhạc và phát lại")
            AppleCard(cornerRadius = 22.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsNavRow(
                        icon = Icons.Filled.GraphicEq,
                        title = "Crossfade",
                        subtitle = prefs.crossfadeOption.labelVi,
                        onClick = { showCrossfadeDialog = true },
                        testTag = "setting_crossfade"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.GraphicEq,
                        title = "Equalizer",
                        subtitle = if (prefs.eqState.enabled) "Bật • ${prefs.eqState.preset.displayNameVi}" else "Đang tắt",
                        onClick = onOpenEqualizer,
                        testTag = "setting_equalizer"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.Lyrics,
                        title = "Lyrics",
                        subtitle = "Đồng bộ LRC, LRCLIB & căn chỉnh độ trễ",
                        onClick = onOpenNowPlaying,
                        testTag = "setting_lyrics"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.DirectionsCar,
                        title = "Chế độ xe",
                        subtitle = "Giao diện tương phản cao, nút điều khiển lớn",
                        onClick = onOpenCarMode,
                        testTag = "setting_car_mode"
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Lời bài hát trên Màn hình khóa & Thông báo",
                        subtitle = "Hiển thị trực tiếp từng câu lời bài hát (.lrc) theo thời gian thực trên MediaStyle Notification",
                        checked = prefs.showLyricsOnLockScreen,
                        onCheckedChange = { viewModel.setShowLyricsOnLockScreen(it) },
                        testTag = "setting_lockscreen_lyrics"
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Tự động phát bài tiếp theo",
                        subtitle = "Phát liên tục hoặc tự gợi ý bài phù hợp khi hết hàng đợi",
                        checked = prefs.autoPlayNext,
                        onCheckedChange = { viewModel.setAutoPlayNext(it) },
                        testTag = "setting_auto_next"
                    )
                }
            }
        }

        // Section 4: Bộ nhớ
        item {
            SettingsSectionHeader("Bộ nhớ")
            AppleCard(cornerRadius = 22.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsNavRow(
                        icon = Icons.Filled.Folder,
                        title = "Thư mục tải nhạc",
                        subtitle = if (prefs.safTreeUri != null) "Đã chọn thư mục SAF tùy chỉnh" else "/sdcard/Music Player/",
                        onClick = { safTreeLauncher.launch(null) },
                        testTag = "setting_download_dir"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.Storage,
                        title = "Dung lượng cache",
                        subtitle = formatBytesReadable(cacheSizeBytes),
                        onClick = { showCacheLimitDialog = true },
                        testTag = "setting_cache_size"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.Storage,
                        title = "Giới hạn cache",
                        subtitle = formatBytesReadable(prefs.cacheLimitBytes),
                        onClick = { showCacheLimitDialog = true },
                        testTag = "setting_cache_limit"
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Tự tải trước",
                        subtitle = "Tải trước bài kế tiếp để chuyển bài không giật lag",
                        checked = prefs.preloadNextTrack,
                        onCheckedChange = { viewModel.setPreloadNext(it) },
                        testTag = "setting_preload_next"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.CleaningServices,
                        title = "Dọn bộ đệm",
                        subtitle = "Chỉ xóa cache tạm, giữ nguyên nhạc tải offline",
                        onClick = { viewModel.clearTemporaryCache() },
                        testTag = "setting_clear_cache"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.Download,
                        title = "Quản lý bài tải offline",
                        subtitle = "Xem tiến độ, tạm dừng, thử lại hoặc xóa tệp cục bộ",
                        onClick = onOpenDownloads,
                        testTag = "setting_manage_offline"
                    )
                }
            }
        }

        // Section 5: Hệ thống
        item {
            SettingsSectionHeader("Hệ thống")
            AppleCard(cornerRadius = 22.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsNavRow(
                        icon = Icons.Filled.BugReport,
                        title = "Nhật ký hoạt động",
                        subtitle = "Ghi nhận tối đa 1.000 sự kiện đã lọc thông tin nhạy cảm",
                        onClick = onOpenActivityLogs,
                        testTag = "setting_activity_logs"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.Share,
                        title = "Xuất log",
                        subtitle = "Chia sẻ tệp nhật ký lỗi (.txt)",
                        onClick = {
                            val share = viewModel.buildExportLogsIntent()
                            context.startActivity(Intent.createChooser(share, "Xuất nhật ký Music Player"))
                        },
                        testTag = "setting_export_logs"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.VpnLock,
                        title = "Proxy HTTP / SOCKS5",
                        subtitle = if (prefs.proxyConfig.type == ProxyType.NONE) "Không dùng Proxy" else "${prefs.proxyConfig.type.name}: ${prefs.proxyConfig.host}:${prefs.proxyConfig.port}",
                        onClick = { showProxyDialog = true },
                        testTag = "setting_proxy"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.CloudSync,
                        title = "Scrobble (Last.fm / ListenBrainz)",
                        subtitle = if (prefs.lastFmEnabled || prefs.listenBrainzEnabled) "Đang bật" else "Chưa kích hoạt",
                        onClick = { showScrobbleDialog = true },
                        testTag = "setting_scrobble"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.Info,
                        title = "Thông tin phiên bản",
                        subtitle = "Music Player v${BuildConfig.VERSION_NAME} (arm64-v8a)",
                        onClick = { showVersionDialog = true },
                        testTag = "setting_version_info"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon = Icons.Filled.SystemUpdate,
                        title = "Cập nhật ứng dụng",
                        subtitle = "Kiểm tra bản phát hành mới nhất",
                        onClick = { showVersionDialog = true },
                        testTag = "setting_app_update"
                    )
                }
            }
        }
    }

    if (showCrossfadeDialog) {
        AlertDialog(
            onDismissRequest = { showCrossfadeDialog = false },
            title = { Text("Thời lượng Crossfade") },
            text = {
                Column {
                    CrossfadeOption.entries.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setCrossfade(opt)
                                    showCrossfadeDialog = false
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(opt.labelVi, color = MaterialTheme.colorScheme.onSurface)
                            if (prefs.crossfadeOption == opt) {
                                Text("Đang chọn", color = colors.accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCrossfadeDialog = false }) { Text("Đóng") } }
        )
    }

    if (showCacheLimitDialog) {
        var customMb by remember { mutableStateOf("4096") }
        AlertDialog(
            onDismissRequest = { showCacheLimitDialog = false },
            title = { Text("Giới hạn bộ nhớ đệm (Cache)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        CacheLimitOption.GB_1,
                        CacheLimitOption.GB_2,
                        CacheLimitOption.GB_3,
                        CacheLimitOption.GB_5
                    ).forEach { opt ->
                        TextButton(onClick = {
                            viewModel.setCacheLimitBytes(opt.bytes)
                            showCacheLimitDialog = false
                        }) { Text(opt.labelVi) }
                    }
                    OutlinedTextField(
                        value = customMb,
                        onValueChange = { customMb = it },
                        label = { Text("Tùy chỉnh (MB)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Button(onClick = {
                        val mb = customMb.toLongOrNull()?.coerceAtLeast(128L) ?: 2048L
                        viewModel.setCacheLimitBytes(mb * 1024L * 1024L)
                        showCacheLimitDialog = false
                    }) { Text("Áp dụng tùy chỉnh") }
                }
            },
            confirmButton = { TextButton(onClick = { showCacheLimitDialog = false }) { Text("Đóng") } }
        )
    }

    if (showProxyDialog) {
        var pType by remember { mutableStateOf(prefs.proxyConfig.type) }
        var host by remember { mutableStateOf(prefs.proxyConfig.host) }
        var port by remember { mutableStateOf(prefs.proxyConfig.port.toString()) }
        var user by remember { mutableStateOf(prefs.proxyConfig.username) }
        var pass by remember { mutableStateOf(prefs.proxyConfig.password) }

        AlertDialog(
            onDismissRequest = { showProxyDialog = false },
            title = { Text("Cấu hình Proxy") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProxyType.entries.forEach { t ->
                            FilterChip(
                                selected = pType == t,
                                onClick = { pType = t },
                                label = { Text(t.name) }
                            )
                        }
                    }
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Máy chủ (Host)") }, singleLine = true)
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Cổng (Port)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Tên đăng nhập (Tùy chọn)") }, singleLine = true)
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Mật khẩu (Tùy chọn)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    proxyTestResult?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.accent)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        viewModel.testProxyConnection(
                            ProxyConfig(pType, host.trim(), port.toIntOrNull() ?: 1080, user.trim(), pass)
                        )
                    }) { Text("Kiểm tra") }
                    Button(onClick = {
                        viewModel.updateProxyConfig(
                            ProxyConfig(pType, host.trim(), port.toIntOrNull() ?: 1080, user.trim(), pass)
                        )
                        showProxyDialog = false
                    }) { Text("Lưu") }
                }
            },
            dismissButton = { TextButton(onClick = { showProxyDialog = false }) { Text("Đóng") } }
        )
    }

    if (showScrobbleDialog) {
        var lfmEnabled by remember { mutableStateOf(prefs.lastFmEnabled) }
        var lfmUser by remember { mutableStateOf(prefs.lastFmUsername) }
        var lfmSession by remember { mutableStateOf("") }
        var lbEnabled by remember { mutableStateOf(prefs.listenBrainzEnabled) }
        var lbToken by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showScrobbleDialog = false },
            title = { Text("Scrobble Last.fm & ListenBrainz") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Bật Last.fm")
                        Switch(checked = lfmEnabled, onCheckedChange = { lfmEnabled = it })
                    }
                    OutlinedTextField(value = lfmUser, onValueChange = { lfmUser = it }, label = { Text("Tên người dùng Last.fm") }, singleLine = true)
                    OutlinedTextField(value = lfmSession, onValueChange = { lfmSession = it }, label = { Text("Session Key Last.fm mới") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Bật ListenBrainz")
                        Switch(checked = lbEnabled, onCheckedChange = { lbEnabled = it })
                    }
                    OutlinedTextField(value = lbToken, onValueChange = { lbToken = it }, label = { Text("User Token ListenBrainz") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveScrobbleSettings(lfmUser.trim(), lfmEnabled, lfmSession.trim(), lbEnabled, lbToken.trim())
                    showScrobbleDialog = false
                }) { Text("Lưu") }
            },
            dismissButton = { TextButton(onClick = { showScrobbleDialog = false }) { Text("Đóng") } }
        )
    }

    if (showVersionDialog) {
        AlertDialog(
            onDismissRequest = { showVersionDialog = false },
            title = { Text("Music Player v${BuildConfig.VERSION_NAME}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ngôn ngữ mặc định: Tiếng Việt (vi-VN)")
                    Text("Kiến trúc bản dựng: arm64-v8a (không tương thích máy 32-bit hoặc giả lập x86/x86_64)")
                    Text("Động cơ âm thanh: Media3 ExoPlayer + 10-Band Biquad EQ + Dual-Player Crossfade + Telegram TDLib")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ianshulyadav/PixelMusic/releases")))
                    }
                    showVersionDialog = false
                }) { Text("Xem bản phát hành") }
            },
            dismissButton = {
                TextButton(onClick = { showVersionDialog = false }) { Text("Đóng") }
            }
        )
    }
}

@Composable
fun DownloadsManagerScreen(
    viewModel: MusicPlayerViewModel,
    onBack: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    var filterStatus by remember { mutableStateOf<DownloadStatus?>(null) }

    val filtered = remember(downloads, filterStatus) {
        if (filterStatus == null) downloads else downloads.filter { it.status == filterStatus }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("downloads_manager_screen")
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = colors.accent)
                }
                Text("Quản lý tải xuống", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = filterStatus == null,
                        onClick = { filterStatus = null },
                        label = { Text("Tất cả (${downloads.size})") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accent, selectedLabelColor = Color.White)
                    )
                }
                items(
                    listOf(
                        DownloadStatus.DOWNLOADING,
                        DownloadStatus.QUEUED,
                        DownloadStatus.COMPLETED,
                        DownloadStatus.PAUSED,
                        DownloadStatus.FAILED
                    )
                ) { st ->
                    FilterChip(
                        selected = filterStatus == st,
                        onClick = { filterStatus = st },
                        label = { Text(st.labelVi) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accent, selectedLabelColor = Color.White)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filtered.isEmpty()) {
                item {
                    AppleCard(cornerRadius = 20.dp) {
                        Text(
                            text = "Không có tác vụ tải xuống nào. Tệp đã tải được lưu vào /sdcard/Music Player/.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            } else {
                items(filtered, key = { it.id }) { item ->
                    val linkedTrack = allTracks.firstOrNull { it.id == item.trackId }
                    AppleCard(cornerRadius = 18.dp) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${item.artist} • ${item.source.labelVi} • ${item.status.labelVi}", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                                }
                                Row {
                                    if (item.status == DownloadStatus.DOWNLOADING && linkedTrack != null) {
                                        IconButton(onClick = { viewModel.pauseDownload(linkedTrack) }) {
                                            Icon(Icons.Filled.Pause, contentDescription = "Tạm dừng", tint = colors.accent)
                                        }
                                    }
                                    if (item.status == DownloadStatus.FAILED || item.status == DownloadStatus.PAUSED) {
                                        IconButton(onClick = { viewModel.retryDownload(item) }) {
                                            Icon(Icons.Filled.Refresh, contentDescription = "Thử lại", tint = colors.accent)
                                        }
                                    }
                                    if (linkedTrack != null && item.status == DownloadStatus.COMPLETED) {
                                        IconButton(onClick = { viewModel.deleteLocalTrackOnly(linkedTrack) }) {
                                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Xóa bản cục bộ", tint = colors.textSecondary)
                                        }
                                    }
                                }
                            }
                            if (item.totalBytes > 0) {
                                val pct = (item.bytesDownloaded.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { pct },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = colors.accent,
                                    trackColor = colors.divider
                                )
                            }
                            if (!item.errorMessage.isNullOrBlank()) {
                                Text(item.errorMessage, style = MaterialTheme.typography.labelMedium, color = colors.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CarModeScreen(
    viewModel: MusicPlayerViewModel,
    onBack: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val track = playback.currentTrack

    Surface(
        modifier = Modifier.fillMaxSize().testTag("car_mode_screen"),
        color = Color.Black
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onBack, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Thoát Chế độ xe", color = Color.White)
                }
                OutlinedButton(
                    onClick = {
                        val targetPreset = if (prefs.eqState.preset == EqPreset.CAR_AUDIO) EqPreset.FLAT else EqPreset.CAR_AUDIO
                        viewModel.selectEqPreset(targetPreset)
                    },
                    modifier = Modifier.height(52.dp)
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = colors.accent)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (prefs.eqState.preset == EqPreset.CAR_AUDIO) "Car EQ: Bật" else "Bật Car EQ",
                        color = Color.White
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ArtworkImage(
                    artworkUri = track?.artworkUri,
                    contentDescription = track?.title ?: "Music Player",
                    modifier = Modifier.size(180.dp),
                    cornerRadius = 28.dp
                )
                Text(
                    text = track?.title ?: "Chưa phát bài hát",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track?.artist ?: "Chọn bài từ hàng đợi",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.LightGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.skipPrevious() },
                    shape = CircleShape,
                    color = Color.DarkGray
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Bài trước", tint = Color.White, modifier = Modifier.size(44.dp))
                    }
                }
                Surface(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.togglePlayPause() },
                    shape = CircleShape,
                    color = colors.accent
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playback.isPlaying) "Tạm dừng" else "Phát",
                            tint = Color.White,
                            modifier = Modifier.size(54.dp)
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.skipNext() },
                    shape = CircleShape,
                    color = Color.DarkGray
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Bài tiếp theo", tint = Color.White, modifier = Modifier.size(44.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityLogsScreen(
    viewModel: MusicPlayerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalMusicPlayerColors.current
    val logs by viewModel.activityLogs.collectAsStateWithLifecycle()
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("activity_logs_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = colors.accent)
                }
                Text("Nhật ký (${logs.size})", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
            }
            Row {
                TextButton(onClick = { viewModel.clearActivityLogs() }) { Text("Xóa", color = colors.textSecondary) }
                TextButton(onClick = {
                    val share = viewModel.buildExportLogsIntent()
                    context.startActivity(Intent.createChooser(share, "Xuất log Music Player"))
                }) { Text("Xuất log", color = colors.accent) }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(logs, key = { "${it.id}_${it.timestampMs}" }) { log ->
                AppleCard(cornerRadius = 14.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "[${log.level}] ${log.category}",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (log.level == "ERROR") colors.accent else colors.textPrimary
                            )
                            Text(
                                text = formatter.format(Date(log.timestampMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(log.message, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    val colors = LocalMusicPlayerColors.current
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = colors.textPrimary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsDivider() {
    val colors = LocalMusicPlayerColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
            .height(0.5.dp)
            .background(colors.divider)
    )
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String? = null
) {
    val colors = LocalMusicPlayerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textSecondary)
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
) {
    val colors = LocalMusicPlayerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accent)
        )
    }
}

@Composable
private fun ThemeChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val colors = LocalMusicPlayerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        if (selected) {
            Text("Đang chọn", style = MaterialTheme.typography.labelLarge, color = colors.accent)
        }
    }
}

private fun formatBytesReadable(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}
