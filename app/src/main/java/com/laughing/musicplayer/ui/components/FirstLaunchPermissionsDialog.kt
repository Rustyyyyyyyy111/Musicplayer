package com.laughing.musicplayer.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.laughing.musicplayer.core.util.PermissionOnboardingHelper
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.theme.StatusSuccess
import com.laughing.musicplayer.ui.theme.StatusWarning

@Composable
fun FirstLaunchPermissionsDialog(
    onDismissLater: () -> Unit,
    onCompleted: () -> Unit,
    onPermissionsRefreshed: () -> Unit = {},
    isXiaomiDevice: Boolean = PermissionOnboardingHelper.isXiaomiOrHyperOsDevice()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = LocalMusicPlayerColors.current

    var audioGranted by remember {
        mutableStateOf(PermissionOnboardingHelper.hasAudioReadPermission(context))
    }
    var notificationsGranted by remember {
        mutableStateOf(PermissionOnboardingHelper.hasNotificationPermission(context))
    }
    var allFilesGranted by remember {
        mutableStateOf(PermissionOnboardingHelper.hasAllFilesAccessPermission(context))
    }
    var miuiSettingsOpened by remember { mutableStateOf(false) }

    fun refreshStates() {
        audioGranted = PermissionOnboardingHelper.hasAudioReadPermission(context)
        notificationsGranted = PermissionOnboardingHelper.hasNotificationPermission(context)
        allFilesGranted = PermissionOnboardingHelper.hasAllFilesAccessPermission(context)
        onPermissionsRefreshed()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshStates()
        if (audioGranted && notificationsGranted && allFilesGranted && (!isXiaomiDevice || miuiSettingsOpened)) {
            onCompleted()
        }
    }

    val singleRuntimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshStates()
    }

    val allCoreGranted = audioGranted && notificationsGranted && allFilesGranted

    AppleModalDialogSurface(
        onDismissRequest = onDismissLater,
        dismissOnClickOutside = false,
        dismissOnBackPress = true,
        maxWidth = 540.dp,
        modifier = Modifier.testTag("first_launch_permissions_dialog")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.accent.copy(alpha = 0.16f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cấp quyền cho Music Player",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Cấp quyền cần thiết để quét nhạc ngoại tuyến, quản lý tệp và điều khiển trên màn hình khóa.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            // Scrollable checklist
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PermissionStatusCard(
                    icon = Icons.Filled.LibraryMusic,
                    title = "Đọc nhạc & Âm thanh",
                    description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        "Quyền READ_MEDIA_AUDIO để quét bài hát .m4a, .flac, .mp3 trên thiết bị."
                    } else {
                        "Quyền READ_EXTERNAL_STORAGE để quét nhạc trong bộ nhớ thiết bị."
                    },
                    granted = audioGranted,
                    statusTestTag = "perm_status_audio",
                    cardTestTag = "perm_row_audio",
                    actionLabel = "Cấp",
                    onActionClick = {
                        singleRuntimeLauncher.launch(PermissionOnboardingHelper.audioReadPermission())
                    }
                )

                PermissionStatusCard(
                    icon = Icons.Filled.NotificationsActive,
                    title = "Thông báo & Màn hình khóa",
                    description = "Quyền POST_NOTIFICATIONS hiển thị trình điều khiển phát nhạc và lời bài hát.",
                    granted = notificationsGranted,
                    statusTestTag = "perm_status_notifications",
                    cardTestTag = "perm_row_notifications",
                    actionLabel = "Cấp",
                    onActionClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            singleRuntimeLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )

                PermissionStatusCard(
                    icon = Icons.Filled.FolderOpen,
                    title = "Truy cập toàn bộ tệp (All Files)",
                    description = "Giúp quét thư mục sâu, tải nhạc vào thư mục công khai và chỉnh sửa tag tệp âm thanh.",
                    granted = allFilesGranted,
                    statusTestTag = "perm_status_all_files",
                    cardTestTag = "perm_row_all_files",
                    actionLabel = "Mở cài đặt",
                    actionTestTag = "perm_action_all_files",
                    onActionClick = {
                        PermissionOnboardingHelper.openAllFilesAccessSettings(context)
                    }
                )

                if (isXiaomiDevice) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.modalCardSurface,
                        border = BorderStroke(1.dp, colors.modalBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("perm_card_xiaomi_miui")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = StatusWarning,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Quyền đặc biệt Xiaomi / MIUI / HyperOS",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = if (miuiSettingsOpened) {
                                            "Đã mở cài đặt MIUI — vui lòng kiểm tra lại 3 mục bên dưới."
                                        } else {
                                            "Mở Quyền khác và bật đủ 3 tùy chọn để trình phát hoạt động ổn định:"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.padding(start = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "• Hiển thị trên màn hình khóa",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "• Mở cửa sổ mới khi chạy nền",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "• Tạo lối tắt màn hình chính",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    miuiSettingsOpened = true
                                    PermissionOnboardingHelper.openMiuiOtherPermissionsSettings(context)
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("perm_action_miui")
                            ) {
                                Text(
                                    text = "Mở cài đặt quyền MIUI",
                                    color = colors.accent,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Bottom action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismissLater,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("onboarding_later_btn")
                ) {
                    Text(
                        text = "Để sau",
                        color = colors.textSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        val missingRuntime = PermissionOnboardingHelper.missingRuntimePermissions(context)
                        when {
                            missingRuntime.isNotEmpty() -> {
                                runtimePermissionsLauncher.launch(missingRuntime.toTypedArray())
                            }
                            !allFilesGranted -> {
                                PermissionOnboardingHelper.openAllFilesAccessSettings(context)
                            }
                            isXiaomiDevice && !miuiSettingsOpened -> {
                                miuiSettingsOpened = true
                                PermissionOnboardingHelper.openMiuiOtherPermissionsSettings(context)
                            }
                            else -> {
                                onCompleted()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("onboarding_grant_btn")
                ) {
                    Text(
                        text = if (allCoreGranted) "Hoàn tất" else "Cấp quyền",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    statusTestTag: String,
    cardTestTag: String,
    actionLabel: String,
    actionTestTag: String? = null,
    onActionClick: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.modalCardSurface,
        border = BorderStroke(1.dp, colors.modalBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(cardTestTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = (if (granted) StatusSuccess else colors.accent).copy(alpha = 0.14f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = if (granted) StatusSuccess else colors.accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.testTag(statusTestTag)
                ) {
                    Icon(
                        imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                        contentDescription = if (granted) "Đã cấp" else "Chưa cấp",
                        tint = if (granted) StatusSuccess else StatusWarning,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (granted) "Đã cấp" else "Chưa cấp",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (granted) StatusSuccess else StatusWarning
                    )
                }
            }

            if (!granted) {
                TextButton(
                    onClick = onActionClick,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .then(if (actionTestTag != null) Modifier.testTag(actionTestTag) else Modifier)
                ) {
                    Text(
                        text = actionLabel,
                        color = colors.accent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
