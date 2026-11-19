package com.laughing.musicplayer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laughing.musicplayer.audio.CastUiState
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastPickerSheet(
    castState: CastUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelectCastRoute: (String) -> Unit,
    onDisconnectCast: () -> Unit,
    onOpenSystemAudioOutput: () -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.groupedSurface,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (castState.connectedDeviceName != null) {
                            Icons.Filled.CastConnected
                        } else {
                            Icons.Filled.Cast
                        },
                        contentDescription = "Chromecast và Thiết bị phát",
                        tint = colors.accent
                    )
                    Column {
                        Text(
                            text = "Thiết bị phát & Chromecast",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = castState.connectedDeviceName?.let { "Đang phát qua $it" }
                                ?: "Chọn Chromecast, Bluetooth hoặc đầu ra âm thanh",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("cast_picker_refresh_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Quét lại thiết bị",
                        tint = colors.textPrimary
                    )
                }
            }

            if (!castState.statusNotice.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.cardSurface,
                    border = BorderStroke(0.75.dp, colors.divider),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cast_status_notice")
                ) {
                    Text(
                        text = castState.statusNotice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            // Chromecast section
            Text(
                text = "THIẾT BỊ CHROMECAST",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colors.cardSurface,
                border = BorderStroke(0.75.dp, colors.divider),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (castState.castRoutes.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (castState.isCastFrameworkAvailable) {
                                "Không tìm thấy thiết bị Chromecast khả dụng"
                            } else {
                                "Dịch vụ Google Cast không khả dụng trên thiết bị này"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Hãy đảm bảo điện thoại và loa/TV Chromecast dùng chung mạng Wi-Fi, hoặc chuyển sang loa Bluetooth bên dưới.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        castState.castRoutes.forEachIndexed { idx, route ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clickable { onSelectCastRoute(route.id) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                    .testTag("cast_route_${route.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = if (route.isSelected) Icons.Filled.CastConnected else Icons.Filled.Cast,
                                    contentDescription = route.name,
                                    tint = if (route.isSelected) colors.accent else colors.textSecondary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = route.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (route.isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (route.isConnecting) "Đang kết nối..." else route.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (route.isSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = "Đang chọn",
                                        tint = colors.accent
                                    )
                                }
                            }
                            if (idx < castState.castRoutes.lastIndex) {
                                androidx.compose.material3.HorizontalDivider(color = colors.divider)
                            }
                        }
                    }
                }
            }

            if (castState.connectedDeviceName != null) {
                TextButton(
                    onClick = onDisconnectCast,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("cast_disconnect_btn")
                ) {
                    Text("Ngắt kết nối Chromecast", color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
            }

            // Local Audio Outputs section
            Text(
                text = "ĐẦU RA ÂM THANH TRÊN MÁY",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colors.cardSurface,
                border = BorderStroke(0.75.dp, colors.divider),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    castState.localAudioOutputs.forEachIndexed { idx, out ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 54.dp)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    out.isBluetooth -> Icons.Filled.Bluetooth
                                    out.isWired -> Icons.Filled.Headphones
                                    else -> Icons.Filled.Speaker
                                },
                                contentDescription = out.name,
                                tint = if (out.isActive) colors.accent else colors.textSecondary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = out.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (out.isActive) FontWeight.Bold else FontWeight.Medium,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = out.typeLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary
                                )
                            }
                            if (out.isActive) {
                                Text(
                                    text = "Đang dùng",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.accent
                                )
                            }
                        }
                        if (idx < castState.localAudioOutputs.lastIndex) {
                            androidx.compose.material3.HorizontalDivider(color = colors.divider)
                        }
                    }
                }
            }

            // Fallback buttons: System Audio Output Switcher + Bluetooth Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenSystemAudioOutput,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("cast_system_output_btn")
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Đầu ra hệ thống", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = onOpenBluetoothSettings,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("cast_bluetooth_settings_btn")
                ) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Bluetooth", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
