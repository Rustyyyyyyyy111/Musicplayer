package com.example.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.model.ArtistGroup
import com.example.core.model.Track
import com.example.data.telegram.TelegramAuthUiState
import com.example.ui.components.AppleCard
import com.example.ui.components.ArtworkImage
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.LocalMusicPlayerColors
import com.example.ui.viewmodel.MusicPlayerViewModel

@Composable
fun ListenNowScreen(
    viewModel: MusicPlayerViewModel,
    onOpenTelegramAuth: () -> Unit,
    onOpenSourcePicker: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenCarMode: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenTopicTracks: (Long, Long, String) -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    val recentTracks by viewModel.recentTracks.collectAsStateWithLifecycle()
    val topics by viewModel.telegramTopics.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val tgAuth by viewModel.telegramAuthState.collectAsStateWithLifecycle()

    val artistTopics: List<ArtistGroup> = viewModel.buildArtistGroups(allTracks, topics)
        .filter { it.isTelegramTopic }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("listen_now_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Nghe nhạc",
                        style = MaterialTheme.typography.displayLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Trải nghiệm âm thanh lossless & đám mây",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onOpenEqualizer)
                            .testTag("quick_eq_btn"),
                        color = colors.cardSurface,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.GraphicEq, contentDescription = "Equalizer", tint = colors.accent)
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onOpenCarMode)
                            .testTag("quick_car_mode_btn"),
                        color = colors.cardSurface,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.DirectionsCar, contentDescription = "Chế độ xe", tint = colors.textPrimary)
                        }
                    }
                }
            }
        }

        // Editorial Hero Banner
        item {
            AppleCard(cornerRadius = 24.dp) {
                Box(modifier = Modifier.fillMaxWidth().height(190.dp)) {
                    Image(
                        painter = painterResource(R.drawable.img_hero_music_1794853244231),
                        contentDescription = "Music Player Editorial",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "AI Smart Mix",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Phối danh sách phát tức thì từ Telegram, YouTube & Offline",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { viewModel.createAndPlaySmartMix(playback.currentTrack) },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.testTag("smart_mix_button")
                                ) {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Phát Smart Mix")
                                }
                                OutlinedButton(
                                    onClick = onOpenDownloads,
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Tải xuống")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Quick Connection Cards (Telegram + Offline Scan)
        item {
            AppleCard(cornerRadius = 22.dp) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val tgConnected = tgAuth is TelegramAuthUiState.Authenticated
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Send,
                            contentDescription = null,
                            tint = colors.accent
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (tgConnected) {
                                    "Telegram đã kết nối (${(tgAuth as TelegramAuthUiState.Authenticated).displayName})"
                                } else {
                                    "Kết nối Telegram cá nhân (TDLib)"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Chỉ đọc an toàn • Hỗ trợ Kênh, Nhóm, Tin nhắn riêng & Forum Topics",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = if (tgConnected) onOpenSourcePicker else onOpenTelegramAuth,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).testTag("tg_setup_btn")
                        ) {
                            Text(if (tgConnected) "Chọn nguồn nhạc" else "Đăng nhập Telegram")
                        }
                        OutlinedButton(
                            onClick = { viewModel.indexOfflineLibrary() },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).testTag("scan_offline_btn")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Quét Offline")
                        }
                    }
                }
            }
        }

        // Telegram Forum Topics mapped as Artists
        if (artistTopics.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Nghệ sĩ từ Forum Topics",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textPrimary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(artistTopics, key = { it.key }) { topic ->
                            AppleCard(
                                modifier = Modifier.width(152.dp),
                                cornerRadius = 20.dp,
                                onClick = {
                                    if (topic.chatId != null && topic.threadId != null) {
                                        onOpenTopicTracks(topic.chatId, topic.threadId, topic.name)
                                    }
                                }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    ArtworkImage(
                                        artworkUri = topic.artworkUri,
                                        contentDescription = topic.name,
                                        modifier = Modifier.fillMaxWidth().height(116.dp),
                                        cornerRadius = 14.dp
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.Forum,
                                            contentDescription = null,
                                            tint = colors.accent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = topic.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "${topic.trackCount} bài hát",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Recently Played
        val showcaseTracks: List<Track> = if (recentTracks.isNotEmpty()) recentTracks else allTracks.take(12)
        if (showcaseTracks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (recentTracks.isNotEmpty()) "Nghe gần đây" else "Khám phá thư viện",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Phát tất cả",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel.playTrack(showcaseTracks.first(), showcaseTracks) }
                            .padding(6.dp)
                    )
                }
            }
            items(showcaseTracks.take(8), key = { "recent_${it.id}" }) { track ->
                TrackRowItem(
                    track = track,
                    isCurrent = playback.currentTrack?.id == track.id,
                    onPlay = { viewModel.playTrack(track, showcaseTracks) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                    onDownload = { viewModel.downloadTrack(track) },
                    onAddToQueue = { viewModel.addToQueueNext(track) },
                    onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) }
                )
            }
        } else {
            item {
                AppleCard(cornerRadius = 22.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "Thư viện đang sẵn sàng",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Đăng nhập Telegram, tìm bài hát trên YouTube Music hoặc quét tệp âm thanh Offline để bắt đầu nghe.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}
