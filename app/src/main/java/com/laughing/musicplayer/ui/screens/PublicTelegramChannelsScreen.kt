package com.laughing.musicplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.PublicChannelAccessMode
import com.laughing.musicplayer.core.model.PublicChannelItem
import com.laughing.musicplayer.core.model.PublicSyncStatus
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.ui.components.AppleCard
import com.laughing.musicplayer.ui.components.ArtworkImage
import com.laughing.musicplayer.ui.components.TrackRowItem
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PublicFormatFilter(val label: String, val matchingFormats: Set<AudioFormat>) {
    ALL("Tất cả", emptySet()),
    FLAC("FLAC", setOf(AudioFormat.FLAC)),
    ALAC("ALAC", setOf(AudioFormat.ALAC)),
    WAV("WAV", setOf(AudioFormat.WAV)),
    MP3("MP3", setOf(AudioFormat.MP3)),
    AAC("AAC / M4A", setOf(AudioFormat.AAC, AudioFormat.M4A)),
    OPUS_OGG("OPUS / OGG", setOf(AudioFormat.OPUS, AudioFormat.OGG))
}

@Composable
fun PublicTelegramChannelsScreen(
    viewModel: MusicPlayerViewModel,
    onBack: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val channels by viewModel.publicChannels.collectAsStateWithLifecycle()
    val isAdding by viewModel.isAddingPublicChannel.collectAsStateWithLifecycle()
    val addError by viewModel.publicChannelError.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()

    var selectedChannel by remember { mutableStateOf<PublicChannelItem?>(null) }

    var channelInput by remember { mutableStateOf(TextFieldValue("")) }
    var backendUrlInput by remember(prefs.publicBackendBaseUrl) {
        mutableStateOf(TextFieldValue(prefs.publicBackendBaseUrl))
    }
    var backendTokenInput by remember {
        mutableStateOf(TextFieldValue(viewModel.getPublicBackendTokenSnapshot()))
    }
    var botTokenInput by remember {
        mutableStateOf(TextFieldValue(viewModel.getPublicBotTokenSnapshot()))
    }

    var trackSearchInput by remember { mutableStateOf(TextFieldValue("")) }
    var selectedFormatFilter by remember { mutableStateOf(PublicFormatFilter.ALL) }

    BackHandler(enabled = selectedChannel != null) {
        selectedChannel = null
        trackSearchInput = TextFieldValue("")
        selectedFormatFilter = PublicFormatFilter.ALL
    }

    if (selectedChannel != null) {
        val currentChannel = channels.firstOrNull { it.channelKey == selectedChannel?.channelKey } ?: selectedChannel!!
        val channelTracks by viewModel.observePublicChannelTracks(currentChannel.username)
            .collectAsStateWithLifecycle(initialValue = emptyList())

        val filteredTracks = remember(channelTracks, trackSearchInput.text, selectedFormatFilter) {
            val q = trackSearchInput.text.trim()
            channelTracks.filter { t ->
                val matchesFormat = selectedFormatFilter == PublicFormatFilter.ALL ||
                    t.format in selectedFormatFilter.matchingFormats
                val matchesQuery = q.isEmpty() || VietnameseTextNormalizer.matches(
                    "${t.title} ${t.artist} ${t.album} ${t.fileName}",
                    q
                )
                matchesFormat && matchesQuery
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("public_channel_tracks_screen")
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = {
                                selectedChannel = null
                                trackSearchInput = TextFieldValue("")
                                selectedFormatFilter = PublicFormatFilter.ALL
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = colors.accent)
                        }
                        ArtworkImage(
                            artworkUri = currentChannel.avatarUrl,
                            contentDescription = currentChannel.displayTitle,
                            modifier = Modifier.size(38.dp),
                            cornerRadius = 19.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentChannel.displayTitle,
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "@${currentChannel.username} • ${channelTracks.size} bài",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        }
                    }
                    if (filteredTracks.isNotEmpty()) {
                        TextButton(
                            onClick = { viewModel.playTrack(filteredTracks.first(), filteredTracks) },
                            modifier = Modifier.testTag("public_channel_play_all")
                        ) {
                            Text("Phát tất cả", color = colors.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                OutlinedTextField(
                    value = trackSearchInput,
                    onValueChange = { trackSearchInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("public_channel_track_search"),
                    placeholder = { Text("Tìm trong @${currentChannel.username}...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (trackSearchInput.text.isNotEmpty()) {
                            IconButton(onClick = { trackSearchInput = TextFieldValue("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Xóa")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("public_channel_format_filters")
                ) {
                    items(PublicFormatFilter.entries) { filter ->
                        FilterChip(
                            selected = selectedFormatFilter == filter,
                            onClick = { selectedFormatFilter = filter },
                            label = { Text(filter.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accent,
                                selectedLabelColor = Color.White,
                                labelColor = colors.textPrimary
                            ),
                            modifier = Modifier.testTag("public_fmt_${filter.name.lowercase()}")
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp)
            ) {
                if (filteredTracks.isEmpty()) {
                    item {
                        AppleCard(
                            cornerRadius = 20.dp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Chưa có bài nhạc phù hợp",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = currentChannel.lastError
                                        ?: "Nhấn Đồng bộ để tìm thêm tệp âm thanh công khai từ @${currentChannel.username}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary
                                )
                                Button(
                                    onClick = { viewModel.syncPublicChannel(currentChannel.channelKey) },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Đồng bộ kênh này")
                                }
                            }
                        }
                    }
                } else {
                    items(filteredTracks, key = { it.id }) { track ->
                        TrackRowItem(
                            track = track,
                            isCurrent = playback.currentTrack?.id == track.id,
                            onPlay = { viewModel.playTrack(track, filteredTracks) },
                            onToggleFavorite = { viewModel.toggleFavorite(track) },
                            onDownload = { viewModel.downloadTrack(track) },
                            onAddToQueue = { viewModel.addToQueueNext(track) },
                            onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) }
                        )
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("public_channels_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = colors.accent)
                    }
                    Text(
                        text = "Kênh Telegram công khai",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { viewModel.syncAllPublicChannels() },
                    enabled = prefs.publicChannelSyncEnabled && channels.isNotEmpty(),
                    modifier = Modifier.size(48.dp).testTag("public_sync_all_btn")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Đồng bộ tất cả", tint = colors.accent)
                }
            }
        }

        // Enable / Disable Toggle Card
        item {
            AppleCard(cornerRadius = 20.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Public, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.padding(end = 10.dp)) {
                            Text(
                                text = "Bật đồng bộ Kênh công khai",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Không yêu cầu đăng nhập tài khoản Telegram cá nhân",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        }
                    }
                    Switch(
                        checked = prefs.publicChannelSyncEnabled,
                        onCheckedChange = { viewModel.setPublicChannelSyncEnabled(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accent),
                        modifier = Modifier.testTag("public_channel_enable_switch")
                    )
                }
            }
        }

        // Access Mode Configuration Card (Official Companion Backend API or Bot API Token)
        item {
            AppleCard(cornerRadius = 20.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Phương thức kết nối chính thức",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PublicChannelAccessMode.entries.forEach { mode ->
                            FilterChip(
                                selected = prefs.publicChannelAccessMode == mode,
                                onClick = { viewModel.setPublicChannelAccessMode(mode) },
                                label = { Text(mode.labelVi, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.accent,
                                    selectedLabelColor = Color.White,
                                    labelColor = colors.textPrimary
                                ),
                                modifier = Modifier.testTag("public_mode_${mode.name.lowercase()}")
                            )
                        }
                    }

                    if (prefs.publicChannelAccessMode == PublicChannelAccessMode.BACKEND_API) {
                        Text(
                            text = "Companion Backend Service cung cấp API phân trang toàn bộ lịch sử âm thanh kênh công khai và hỗ trợ tua/stream Range HTTP.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                        OutlinedTextField(
                            value = backendUrlInput,
                            onValueChange = { backendUrlInput = it },
                            label = { Text("URL Backend API (VD: https://music-api.domain.com)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().testTag("public_backend_url_input")
                        )
                        OutlinedTextField(
                            value = backendTokenInput,
                            onValueChange = { backendTokenInput = it },
                            label = { Text("Bearer Token (Tùy chọn)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().testTag("public_backend_token_input")
                        )
                        Button(
                            onClick = {
                                viewModel.savePublicBackendConfig(backendUrlInput.text, backendTokenInput.text)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().testTag("public_backend_save_btn")
                        ) {
                            Text("Lưu cấu hình Backend API")
                        }
                    } else {
                        Text(
                            text = "Bot API chính thức chỉ đọc được bài đăng mới từ thời điểm Bot làm quản trị viên kênh (không lấy được lịch sử cũ và tệp >20 MB).",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                        OutlinedTextField(
                            value = botTokenInput,
                            onValueChange = { botTokenInput = it },
                            label = { Text("Telegram Bot Token (từ @BotFather)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().testTag("public_bot_token_input")
                        )
                        Button(
                            onClick = { viewModel.savePublicBotToken(botTokenInput.text) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().testTag("public_bot_save_btn")
                        ) {
                            Text("Lưu Bot Token")
                        }
                    }
                }
            }
        }

        // Add Channel Card
        item {
            AppleCard(cornerRadius = 20.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Thêm kênh công khai",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    OutlinedTextField(
                        value = channelInput,
                        onValueChange = {
                            channelInput = it
                            if (addError != null) viewModel.clearPublicChannelError()
                        },
                        label = { Text("Nhập @username hoặc https://t.me/channel") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (!isAdding) viewModel.addPublicChannel(channelInput.text)
                        }),
                        modifier = Modifier.fillMaxWidth().testTag("public_channel_input")
                    )
                    AnimatedVisibility(visible = !addError.isNullOrBlank()) {
                        Text(
                            text = addError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.accent,
                            modifier = Modifier.testTag("public_channel_error")
                        )
                    }
                    Button(
                        onClick = { viewModel.addPublicChannel(channelInput.text) },
                        enabled = !isAdding && prefs.publicChannelSyncEnabled,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("public_channel_add_btn")
                    ) {
                        if (isAdding) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Đang kiểm tra kênh...")
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Thêm kênh")
                        }
                    }
                }
            }
        }

        // Channel List
        if (channels.isEmpty()) {
            item {
                AppleCard(cornerRadius = 20.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.CloudQueue, contentDescription = null, tint = colors.accent)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Chưa có kênh công khai nào. Hãy cấu hình Backend API hoặc Bot Token rồi thêm @username kênh để bắt đầu.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        } else {
            items(channels, key = { it.channelKey }) { ch ->
                PublicChannelCard(
                    channel = ch,
                    onOpen = { selectedChannel = ch },
                    onSync = { viewModel.syncPublicChannel(ch.channelKey) },
                    onTogglePause = {
                        if (ch.isPaused) viewModel.resumePublicChannel(ch.channelKey)
                        else viewModel.pausePublicChannel(ch.channelKey)
                    },
                    onRemove = { viewModel.removePublicChannel(ch.channelKey) }
                )
            }
        }
    }
}

@Composable
private fun PublicChannelCard(
    channel: PublicChannelItem,
    onOpen: () -> Unit,
    onSync: () -> Unit,
    onTogglePause: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val statusColor = when (channel.syncStatus) {
        PublicSyncStatus.SYNCING -> colors.accent
        PublicSyncStatus.PAUSED -> colors.textSecondary
        PublicSyncStatus.ERROR -> Color(0xFFFF453A)
        PublicSyncStatus.IDLE -> Color(0xFF30D158)
    }

    AppleCard(
        cornerRadius = 20.dp,
        onClick = onOpen,
        modifier = Modifier.testTag("public_channel_card_${channel.username}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(
                    artworkUri = channel.avatarUrl,
                    contentDescription = channel.displayTitle,
                    modifier = Modifier.size(52.dp),
                    cornerRadius = 26.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${channel.username} • ${channel.discoveredCount} bài nhạc",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    Text(
                        text = formatSyncTimeLabel(channel.lastSyncedEpochMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = statusColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = channel.syncStatus.labelVi,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (!channel.lastError.isNullOrBlank()) {
                Text(
                    text = channel.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (channel.syncStatus == PublicSyncStatus.ERROR) Color(0xFFFF453A) else colors.textSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onOpen,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Mở nhạc", maxLines = 1)
                }
                IconButton(
                    onClick = onSync,
                    enabled = !channel.isPaused && channel.syncStatus != PublicSyncStatus.SYNCING,
                    modifier = Modifier.size(48.dp).testTag("public_sync_btn_${channel.username}")
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = "Đồng bộ ngay", tint = colors.accent)
                }
                IconButton(
                    onClick = onTogglePause,
                    modifier = Modifier.size(48.dp).testTag("public_pause_btn_${channel.username}")
                ) {
                    Icon(
                        imageVector = if (channel.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (channel.isPaused) "Tiếp tục" else "Tạm dừng",
                        tint = colors.textPrimary
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(48.dp).testTag("public_remove_btn_${channel.username}")
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Xóa kênh", tint = colors.textSecondary)
                }
            }
        }
    }
}

private fun formatSyncTimeLabel(epochMs: Long): String {
    if (epochMs <= 0L) return "Chưa đồng bộ"
    val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return "Cập nhật: ${fmt.format(Date(epochMs))}"
}
