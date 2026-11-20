package com.laughing.musicplayer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.laughing.musicplayer.audio.PlaybackState
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.SleepTimerMode
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.data.storage.AudioTagEditRequest
import com.laughing.musicplayer.data.storage.TrackTechnicalInfo
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingMoreSheet(
    track: Track,
    playback: PlaybackState,
    sleepCalmLockEnabled: Boolean,
    onDismiss: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenTrackInfo: () -> Unit,
    onOpenSleepMode: () -> Unit,
    onOpenEditTags: () -> Unit,
    remainingTimerMs: Long = playback.sleepTimerRemainingMs
) {
    val colors = LocalMusicPlayerColors.current
    val timerSubtitle = when (playback.sleepTimerMode) {
        SleepTimerMode.AFTER_MINUTES -> "Đang bật • còn ${formatDurationMs(remainingTimerMs)}"
        SleepTimerMode.END_OF_CURRENT_TRACK -> "Đang bật • tắt sau bài hiện tại"
        SleepTimerMode.AFTER_N_TRACKS -> "Đang bật • tắt sau ${playback.sleepTimerRemainingTracks} bài nữa"
        SleepTimerMode.OFF -> "Chọn thời gian hoặc tắt sau bài hiện tại"
    }
    val sleepModeSubtitle = buildList {
        add(if (playback.sleepFadeOutEnabled) "Giảm dần âm lượng 30s" else "Tắt ngay")
        if (sleepCalmLockEnabled) add("Đang khóa dịu mắt")
    }.joinToString(" • ")

    val isEditableOffline = track.source == MusicSource.OFFLINE || !track.localPath.isNullOrBlank()

    AppleModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Track summary header
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colors.modalCardSurface,
                border = BorderStroke(1.dp, colors.modalBorder),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ArtworkImage(
                        artworkUri = track.artworkUri,
                        contentDescription = track.title,
                        modifier = Modifier.size(52.dp),
                        cornerRadius = 12.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${track.artist} • ${track.source.labelVi}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Grouped menu actions
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colors.modalCardSurface,
                border = BorderStroke(1.dp, colors.modalBorder),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    MoreMenuRow(
                        icon = Icons.Filled.Timer,
                        title = "Hẹn giờ",
                        subtitle = timerSubtitle,
                        accentBadge = playback.sleepTimerMode != SleepTimerMode.OFF,
                        testTag = "more_menu_sleep_timer",
                        onClick = onOpenSleepTimer
                    )
                    HorizontalDivider(color = colors.divider)
                    MoreMenuRow(
                        icon = Icons.Filled.Info,
                        title = "Thông tin bài hát",
                        subtitle = "Xem siêu dữ liệu, codec, bitrate, sample rate & tệp",
                        accentBadge = false,
                        testTag = "more_menu_track_info",
                        onClick = onOpenTrackInfo
                    )
                    HorizontalDivider(color = colors.divider)
                    MoreMenuRow(
                        icon = Icons.Filled.Bedtime,
                        title = "Chế độ ngủ",
                        subtitle = sleepModeSubtitle,
                        accentBadge = sleepCalmLockEnabled || playback.sleepFadeOutActive,
                        testTag = "more_menu_sleep_mode",
                        onClick = onOpenSleepMode
                    )
                    HorizontalDivider(color = colors.divider)
                    MoreMenuRow(
                        icon = Icons.Filled.Edit,
                        title = "Sửa tag bài hát",
                        subtitle = if (isEditableOffline) {
                            "Chỉnh sửa tên bài, nghệ sĩ, album, năm & thể loại"
                        } else {
                            "Chỉ áp dụng cho bài hát Offline hoặc tệp đã tải về"
                        },
                        accentBadge = false,
                        testTag = "more_menu_edit_tags",
                        onClick = onOpenEditTags
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MoreMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentBadge: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (accentBadge) colors.accent else colors.textPrimary,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (accentBadge) colors.accent else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textSecondary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerAppleSheet(
    playback: PlaybackState,
    onDismiss: () -> Unit,
    onSelectMinutes: (Int) -> Unit,
    onSelectEndOfTrack: () -> Unit,
    onSelectAfterTracks: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    remainingTimerMs: Long = playback.sleepTimerRemainingMs
) {
    val colors = LocalMusicPlayerColors.current
    var customMinutes by remember { mutableIntStateOf(25) }
    var customInputText by remember { mutableStateOf("25") }
    var trackCountText by remember { mutableStateOf("3") }

    AppleModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Timer, contentDescription = "Hẹn giờ", tint = colors.accent)
                Column {
                    Text(
                        text = "Hẹn giờ tắt nhạc",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (playback.sleepFadeOutEnabled) {
                            "Âm lượng sẽ giảm dần trong 30 giây cuối"
                        } else {
                            "Sẽ tạm dừng phát khi hết thời gian"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            if (playback.sleepTimerMode != SleepTimerMode.OFF) {
                val activeText = when (playback.sleepTimerMode) {
                    SleepTimerMode.AFTER_MINUTES -> "Còn lại ${formatDurationMs(remainingTimerMs)}"
                    SleepTimerMode.END_OF_CURRENT_TRACK -> "Sẽ dừng sau khi kết thúc bài hát hiện tại"
                    SleepTimerMode.AFTER_N_TRACKS -> "Còn ${playback.sleepTimerRemainingTracks} bài nữa sẽ tắt"
                    SleepTimerMode.OFF -> ""
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.accent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sleep_timer_active_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Hẹn giờ đang chạy",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.accent,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = activeText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                        }
                        TextButton(
                            onClick = {
                                onCancelTimer()
                                onDismiss()
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("sleep_timer_cancel_btn")
                        ) {
                            Text("Hủy", color = colors.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Quick preset pills
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colors.modalCardSurface,
                border = BorderStroke(1.dp, colors.modalBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Chọn nhanh",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    val presets = listOf(15 to "15 phút", 30 to "30 phút", 45 to "45 phút", 60 to "1 tiếng")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(presets, key = { it.first }) { (minutes, label) ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    onSelectMinutes(minutes)
                                    onDismiss()
                                },
                                label = { Text(label, fontWeight = FontWeight.Medium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = colors.modalCardSurface,
                                    labelColor = colors.textPrimary
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("sleep_preset_${minutes}m")
                            )
                        }
                        item(key = "end_of_track") {
                            FilterChip(
                                selected = playback.sleepTimerMode == SleepTimerMode.END_OF_CURRENT_TRACK,
                                onClick = {
                                    onSelectEndOfTrack()
                                    onDismiss()
                                },
                                label = { Text("Hết bài hiện tại", fontWeight = FontWeight.Medium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.accent.copy(alpha = 0.2f),
                                    selectedLabelColor = colors.accent,
                                    containerColor = colors.elevatedSurface,
                                    labelColor = colors.textPrimary
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("sleep_end_of_track_btn")
                            )
                        }
                    }
                }
            }

            // Custom minutes stepper + input
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colors.modalCardSurface,
                border = BorderStroke(1.dp, colors.modalBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Tùy chỉnh thời gian",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                customMinutes = (customMinutes - 5).coerceAtLeast(1)
                                customInputText = customMinutes.toString()
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = "Giảm 5 phút", tint = colors.textPrimary)
                        }
                        OutlinedTextField(
                            value = customInputText,
                            onValueChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(3)
                                customInputText = digits
                                digits.toIntOrNull()?.let { customMinutes = it.coerceIn(1, 480) }
                            },
                            label = { Text("Số phút") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sleep_custom_minutes_input")
                        )
                        IconButton(
                            onClick = {
                                customMinutes = (customMinutes + 5).coerceAtMost(480)
                                customInputText = customMinutes.toString()
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Tăng 5 phút", tint = colors.textPrimary)
                        }
                        Button(
                            onClick = {
                                val min = customInputText.toIntOrNull()?.coerceIn(1, 480) ?: customMinutes
                                onSelectMinutes(min)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("sleep_custom_minutes_apply")
                        ) {
                            Text("Đặt")
                        }
                    }

                    HorizontalDivider(color = colors.divider)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = trackCountText,
                            onValueChange = { trackCountText = it.filter(Char::isDigit).take(2) },
                            label = { Text("Tắt sau N bài") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sleep_n_tracks_input")
                        )
                        OutlinedButton(
                            onClick = {
                                val n = trackCountText.toIntOrNull()?.coerceIn(1, 99) ?: 3
                                onSelectAfterTracks(n)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("sleep_n_tracks_apply")
                        ) {
                            Text("Áp dụng")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackInfoSheet(
    track: Track,
    info: TrackTechnicalInfo?,
    onDismiss: () -> Unit,
    onEditTagsClick: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current

    AppleModalBottomSheet(
        onDismissRequest = onDismiss
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
                    Icon(Icons.Filled.Info, contentDescription = "Thông tin bài hát", tint = colors.accent)
                    Column {
                        Text(
                            text = "Thông tin bài hát",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Nguồn: ${info?.sourceLabel ?: track.source.labelVi}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
                TextButton(
                    onClick = onEditTagsClick,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("track_info_edit_tags_shortcut")
                ) {
                    Text("Sửa tag", color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
            }

            if (info == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }
            } else {
                Text(
                    text = "SIÊU DỮ LIỆU BÀI HÁT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.cardSurface,
                    border = BorderStroke(0.75.dp, colors.divider),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("track_info_metadata_card")
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        InfoSpecRow("Tên bài hát", info.title)
                        HorizontalDivider(color = colors.divider)
                        InfoSpecRow("Nghệ sỹ", info.artist)
                        HorizontalDivider(color = colors.divider)
                        InfoSpecRow("Album", info.album)
                        HorizontalDivider(color = colors.divider)
                        InfoSpecRow("Nghệ sỹ album", info.albumArtist)
                        HorizontalDivider(color = colors.divider)
                        InfoSpecRow("Thể loại", info.genre)
                        HorizontalDivider(color = colors.divider)
                        InfoSpecRow("Năm", info.releaseYear)
                    }
                }

                Text(
                    text = "THÔNG SỐ KỸ THUẬT & TỆP",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.cardSurface,
                    border = BorderStroke(0.75.dp, colors.divider),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("track_info_tech_card")
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        InfoSpecRow("Đường dẫn file", info.filePath)
                        HorizontalDivider(color = colors.divider)
                        InfoSpecRow("Định dạng / Codec", info.formatAndCodec)
                        HorizontalDivider(color = colors.divider)
                        InfoSpecRow("Bitrate", info.bitrate)
                        HorizontalDivider(color = colors.divider)
                        InfoSpecRow("Sample rate", info.sampleRate)
                        HorizontalDivider(color = colors.divider)
                        InfoSpecRow("Số kênh", info.channelDisplay)
                        HorizontalDivider(color = colors.divider)
                        InfoSpecRow("Dung lượng file", info.fileSize)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun InfoSpecRow(label: String, value: String) {
    val colors = LocalMusicPlayerColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepModeSheet(
    playback: PlaybackState,
    sleepCalmLockEnabled: Boolean,
    onDismiss: () -> Unit,
    onToggleFadeOut: (Boolean) -> Unit,
    onToggleCalmLock: (Boolean) -> Unit,
    onStart30MinSleepMode: () -> Unit,
    onStartEndOfTrackSleepMode: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current

    AppleModalBottomSheet(
        onDismissRequest = onDismiss
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Bedtime, contentDescription = "Chế độ ngủ", tint = colors.accent)
                Column {
                    Text(
                        text = "Chế độ ngủ",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Tối ưu nghe nhạc ban đêm êm dịu & an toàn",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colors.modalCardSurface,
                border = BorderStroke(1.dp, colors.modalBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VolumeDown,
                            contentDescription = null,
                            tint = colors.accent
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Giảm dần âm lượng (30 giây)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Hạ âm lượng mượt mà trong 30 giây cuối trước khi dừng phát",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        }
                        Switch(
                            checked = playback.sleepFadeOutEnabled,
                            onCheckedChange = onToggleFadeOut,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accent),
                            modifier = Modifier.testTag("sleep_mode_fadeout_switch")
                        )
                    }

                    HorizontalDivider(color = colors.divider)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = colors.accent
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Khóa thao tác nhầm / Giao diện dịu mắt",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Làm tối màn hình Đang phát và yêu cầu nhấn giữ 1.5s để mở khóa",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        }
                        Switch(
                            checked = sleepCalmLockEnabled,
                            onCheckedChange = { enabled ->
                                onToggleCalmLock(enabled)
                                if (enabled) onDismiss()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accent),
                            modifier = Modifier.testTag("sleep_mode_calm_lock_switch")
                        )
                    }
                }
            }

            Text(
                text = "KÍCH HOẠT NHANH CHẾ ĐỘ NGỦ",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        onStart30MinSleepMode()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("sleep_mode_30m_preset_btn")
                ) {
                    Text("Ngủ sau 30 phút", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = {
                        onStartEndOfTrackSleepMode()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("sleep_mode_end_track_preset_btn")
                ) {
                    Text("Tắt sau hết bài", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTagsSheet(
    track: Track,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onDownloadOfflineFirst: () -> Unit,
    onSaveTags: (AudioTagEditRequest) -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val isEditableOffline = track.source == MusicSource.OFFLINE || !track.localPath.isNullOrBlank()

    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist) }
    var album by remember(track.id) { mutableStateOf(track.album) }
    var albumArtist by remember(track.id) { mutableStateOf(track.albumArtist.orEmpty()) }
    var yearText by remember(track.id) { mutableStateOf(track.releaseYear?.toString().orEmpty()) }
    var selectedGenre by remember(track.id) { mutableStateOf(track.genre) }

    AppleModalBottomSheet(
        onDismissRequest = onDismiss
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Edit, contentDescription = "Sửa tag bài hát", tint = colors.accent)
                Column {
                    Text(
                        text = "Sửa tag bài hát",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (isEditableOffline) {
                            "Ghi trực tiếp vào tệp (${track.format.extension.uppercase()}), MediaStore và thư viện"
                        } else {
                            "Chỉ hỗ trợ tệp Offline / đã tải xuống"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            if (!isEditableOffline) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.cardSurface,
                    border = BorderStroke(0.75.dp, colors.divider),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_tags_online_notice")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Bài hát đang phát trực tuyến (${track.source.labelVi})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Tính năng sửa tag bài hát chỉ áp dụng cho các tệp âm thanh Offline hoặc bài hát đã tải về thiết bị. Bạn có thể tải bài hát về máy trước khi chỉnh sửa tag.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                        Button(
                            onClick = {
                                onDownloadOfflineFirst()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("edit_tags_download_first_btn")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Tải xuống để sửa tag")
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.cardSurface,
                    border = BorderStroke(0.75.dp, colors.divider),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Tên bài hát") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_tag_title_input")
                        )
                        OutlinedTextField(
                            value = artist,
                            onValueChange = { artist = it },
                            label = { Text("Nghệ sỹ") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_tag_artist_input")
                        )
                        OutlinedTextField(
                            value = album,
                            onValueChange = { album = it },
                            label = { Text("Album") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_tag_album_input")
                        )
                        OutlinedTextField(
                            value = albumArtist,
                            onValueChange = { albumArtist = it },
                            label = { Text("Nghệ sỹ album") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_tag_album_artist_input")
                        )
                        OutlinedTextField(
                            value = yearText,
                            onValueChange = { yearText = it.filter(Char::isDigit).take(4) },
                            label = { Text("Năm phát hành (ví dụ 2025)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_tag_year_input")
                        )

                        Text(
                            text = "Thể loại",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GenreType.entries.forEach { genre ->
                                FilterChip(
                                    selected = selectedGenre == genre,
                                    onClick = { selectedGenre = genre },
                                    label = { Text(genre.displayNameVi) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colors.accent.copy(alpha = 0.2f),
                                        selectedLabelColor = colors.accent
                                    )
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Hủy")
                    }
                    Button(
                        enabled = title.isNotBlank() && !isSaving,
                        onClick = {
                            onSaveTags(
                                AudioTagEditRequest(
                                    title = title.trim(),
                                    artist = artist.trim(),
                                    album = album.trim(),
                                    albumArtist = albumArtist.trim().takeIf { it.isNotEmpty() },
                                    genre = selectedGenre,
                                    releaseYear = yearText.toIntOrNull()?.takeIf { it in 1900..2100 }
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.White),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("edit_tag_save_btn")
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text("Lưu thay đổi")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
