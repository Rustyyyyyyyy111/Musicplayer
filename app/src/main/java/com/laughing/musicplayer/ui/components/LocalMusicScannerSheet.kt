package com.laughing.musicplayer.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel
import com.laughing.musicplayer.ui.viewmodel.OfflineScanUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberLocalMusicScannerLauncher(
    viewModel: MusicPlayerViewModel
): () -> Unit {
    val context = LocalContext.current
    val colors = LocalMusicPlayerColors.current
    val scanState by viewModel.offlineScanState.collectAsStateWithLifecycle()

    var showScanSheet by remember { mutableStateOf(false) }
    var showRationaleDialog by remember { mutableStateOf(false) }

    val audioReadPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            showScanSheet = false
            viewModel.scanSelectedFolder(uri)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        showScanSheet = false
        if (granted || viewModel.hasAudioReadPermission()) {
            viewModel.scanEntireDevice()
        } else {
            showRationaleDialog = true
        }
    }

    if (showScanSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showScanSheet = false },
            sheetState = sheetState,
            containerColor = colors.cardSurface,
            contentColor = colors.textPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .testTag("offline_scan_sheet"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Quét nhạc trong máy",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Text(
                    text = "Chọn phương thức quét các tệp âm thanh (.m4a, .aac, .mp3, .flac, .opus, .wav, .ogg) trên thiết bị của bạn.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )

                if (scanState.isScanning) {
                    OfflineScanProgressBanner(scanState = scanState)
                }

                ScanOptionCard(
                    icon = Icons.Filled.LibraryMusic,
                    title = "Quét toàn bộ thiết bị",
                    subtitle = "Tìm nhạc trên cả bộ nhớ trong & ngoài qua MediaStore, tự động bỏ qua nhạc chuông / âm báo dưới 30 giây.",
                    enabled = !scanState.isScanning,
                    testTag = "scan_option_entire_device",
                    onClick = {
                        if (viewModel.hasAudioReadPermission()) {
                            showScanSheet = false
                            viewModel.scanEntireDevice()
                        } else {
                            permissionLauncher.launch(audioReadPermission)
                        }
                    }
                )

                ScanOptionCard(
                    icon = Icons.Filled.FolderOpen,
                    title = "Chọn thư mục để quét",
                    subtitle = "Mở trình chọn thư mục hệ thống và quét đệ quy toàn bộ thư mục con bên trong.",
                    enabled = !scanState.isScanning,
                    testTag = "scan_option_select_folder",
                    onClick = {
                        folderPickerLauncher.launch(null)
                    }
                )

                TextButton(
                    onClick = { showScanSheet = false },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Đóng", color = colors.accent)
                }
            }
        }
    }

    if (showRationaleDialog) {
        val activity = remember(context) { context.findActivity() }
        val canAskAgain = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, audioReadPermission)
        val permissionLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Âm thanh và nhạc (READ_MEDIA_AUDIO)"
        } else {
            "Đọc bộ nhớ (READ_EXTERNAL_STORAGE)"
        }

        AlertDialog(
            onDismissRequest = { showRationaleDialog = false },
            modifier = Modifier.testTag("audio_permission_rationale_dialog"),
            icon = {
                Icon(
                    imageVector = Icons.Filled.PermMedia,
                    contentDescription = null,
                    tint = colors.accent
                )
            },
            title = {
                Text(
                    text = "Cần quyền truy cập âm thanh",
                    color = colors.textPrimary
                )
            },
            text = {
                Text(
                    text = "Music Player cần quyền \"$permissionLabel\" để quét toàn bộ bài hát lưu trên thiết bị.\n\nNếu không muốn cấp quyền này, bạn vẫn có thể chọn từng thư mục riêng lẻ bằng tùy chọn \"Chọn thư mục để quét\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRationaleDialog = false
                        if (canAskAgain) {
                            permissionLauncher.launch(audioReadPermission)
                        } else {
                            val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(settingsIntent) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text(if (canAskAgain) "Cấp quyền ngay" else "Mở Cài đặt", color = Color.White)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            showRationaleDialog = false
                            folderPickerLauncher.launch(null)
                        }
                    ) {
                        Text("Chọn thư mục", color = colors.accent)
                    }
                    TextButton(onClick = { showRationaleDialog = false }) {
                        Text("Đóng", color = colors.textSecondary)
                    }
                }
            }
        )
    }

    return { showScanSheet = true }
}

@Composable
private fun ScanOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(18.dp),
        color = colors.elevatedSurface,
        border = BorderStroke(0.6.dp, colors.divider)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.accent.copy(alpha = 0.14f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = colors.accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textSecondary
            )
        }
    }
}

@Composable
fun OfflineScanProgressBanner(
    scanState: OfflineScanUiState,
    modifier: Modifier = Modifier
) {
    if (!scanState.isScanning) return
    val colors = LocalMusicPlayerColors.current
    AppleCard(
        cornerRadius = 18.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("offline_scan_progress_banner")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = colors.accent
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${scanState.mode?.labelVi ?: "Quét nhạc"} • ${scanState.scannedCount} bài hát",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    if (scanState.currentTitle.isNotBlank()) {
                        Text(
                            text = scanState.currentTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = colors.accent,
                trackColor = colors.divider
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
