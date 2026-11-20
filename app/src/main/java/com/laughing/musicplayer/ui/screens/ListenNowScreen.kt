package com.laughing.musicplayer.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laughing.musicplayer.core.model.ThemeMode
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.ui.components.AppleCard
import com.laughing.musicplayer.ui.components.AppleModalBottomSheet
import com.laughing.musicplayer.ui.components.ArtworkImage
import com.laughing.musicplayer.ui.components.TrackRowItem
import com.laughing.musicplayer.ui.components.formatDurationMs
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.viewmodel.AiSmartPlaylistUiState
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenNowScreen(viewModel: MusicPlayerViewModel) {
    val colors = LocalMusicPlayerColors.current
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    val recentTracks by viewModel.recentTracks.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val aiMix by viewModel.aiSmartPlaylistState.collectAsStateWithLifecycle()
    var showFullMixSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.ensureAiSmartPlaylistForSession()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("listen_now_screen"),
        contentPadding = PaddingValues(top = 12.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item(key = "listen_now_header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
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
        }

        // Featured AI Smart Playlist Hero Card (5 preview tracks + Full Mix Sheet)
        item(key = "ai_smart_playlist_hero") {
            AiSmartPlaylistHeroCard(
                state = aiMix,
                currentTrackId = playback.currentTrack?.id,
                onPlayAll = { viewModel.playAiSmartPlaylist(0) },
                onRefresh = { viewModel.refreshAiSmartPlaylist(forceRefresh = true) },
                onPlayPreviewTrack = { idx -> viewModel.playAiSmartPlaylist(idx) },
                onViewAll = { showFullMixSheet = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        // Recently Played / Library Discovery
        val showcaseTracks: List<Track> = if (recentTracks.isNotEmpty()) recentTracks else allTracks.take(12)
        if (showcaseTracks.isNotEmpty()) {
            item(key = "showcase_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
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
            items(showcaseTracks.take(12), key = { "recent_${it.id}" }) { track ->
                TrackRowItem(
                    track = track,
                    isCurrent = playback.currentTrack?.id == track.id,
                    onPlay = { viewModel.playTrack(track, showcaseTracks) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                    onDownload = { viewModel.downloadTrack(track) },
                    onAddToQueue = { viewModel.addToQueueNext(track) },
                    onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) },
                    modifier = Modifier.animateItem()
                )
            }
        } else if (aiMix.tracks.isEmpty() && !aiMix.isGenerating) {
            item(key = "listen_now_empty") {
                AppleCard(
                    cornerRadius = 22.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
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
                            text = "Mở Thư viện, Tìm kiếm YouTube Music hoặc Cài đặt nguồn nhạc Telegram để bắt đầu thưởng thức.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }

    if (showFullMixSheet) {
        AppleModalBottomSheet(
            onDismissRequest = { showFullMixSheet = false },
            modifier = Modifier.testTag("ai_smart_playlist_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = aiMix.mixTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "${aiMix.tracks.size} bài hát • ${aiMix.timeBucketLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                    IconButton(
                        onClick = { showFullMixSheet = false },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Đóng danh sách",
                            tint = colors.textSecondary
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.playAiSmartPlaylist(0)
                            showFullMixSheet = false
                        },
                        enabled = aiMix.tracks.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("ai_mix_sheet_play_all_btn")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Phát ngay", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.refreshAiSmartPlaylist(forceRefresh = true) },
                        enabled = !aiMix.isGenerating,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, colors.modalBorder),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tạo lại", color = colors.textPrimary)
                    }
                }

                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(top = 4.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(aiMix.tracks, key = { idx, t -> "ai_sheet_${idx}_${t.id}" }) { index, track ->
                        TrackRowItem(
                            track = track,
                            isCurrent = playback.currentTrack?.id == track.id,
                            onPlay = {
                                viewModel.playAiSmartPlaylist(index)
                                showFullMixSheet = false
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(track) },
                            onDownload = { viewModel.downloadTrack(track) },
                            onAddToQueue = { viewModel.addToQueueNext(track) },
                            onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiSmartPlaylistHeroCard(
    state: AiSmartPlaylistUiState,
    currentTrackId: String?,
    onPlayAll: () -> Unit,
    onRefresh: () -> Unit,
    onPlayPreviewTrack: (Int) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalMusicPlayerColors.current
    val shape = RoundedCornerShape(24.dp)

    val gradientColors = remember(colors.themeMode, colors.cardSurface, colors.accent, colors.elevatedSurface) {
        when (colors.themeMode) {
            ThemeMode.LIGHT -> listOf(
                colors.accent.copy(alpha = 0.11f),
                colors.cardSurface,
                colors.elevatedSurface.copy(alpha = 0.72f)
            )
            ThemeMode.DARK -> listOf(
                colors.accent.copy(alpha = 0.22f),
                colors.cardSurface,
                Color(0xFF141418)
            )
            ThemeMode.LIQUID_GLASS -> listOf(
                colors.accent.copy(alpha = 0.30f),
                Color(0xFF1E1E24).copy(alpha = 0.72f),
                Color(0xFF16161B).copy(alpha = 0.80f)
            )
        }
    }

    val borderStroke = when (colors.themeMode) {
        ThemeMode.LIQUID_GLASS -> BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
        ThemeMode.LIGHT -> BorderStroke(0.75.dp, colors.divider)
        ThemeMode.DARK -> BorderStroke(0.75.dp, Color.White.copy(alpha = 0.08f))
    }

    val previewSurfaceColor = when (colors.themeMode) {
        ThemeMode.LIGHT -> colors.elevatedSurface.copy(alpha = 0.58f)
        ThemeMode.DARK -> Color.White.copy(alpha = 0.05f)
        ThemeMode.LIQUID_GLASS -> Color.White.copy(alpha = 0.08f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai_smart_playlist_card"),
        shape = shape,
        color = Color.Transparent,
        border = borderStroke,
        shadowElevation = if (colors.themeMode == ThemeMode.LIGHT) 4.dp else 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(colors = gradientColors))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colors.accent.copy(alpha = if (colors.isDark) 0.24f else 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "AI SMART PLAYLIST • ${state.timeBucketLabel.uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (colors.isDark) Color.White else colors.accent
                            )
                        }
                    }

                    if (state.tracks.isNotEmpty()) {
                        Text(
                            text = "${state.tracks.size} bài",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.mixTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = state.mixSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (state.topTags.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.topTags, key = { "tag_$it" }) { tag ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = previewSurfaceColor
                            ) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onPlayAll,
                        enabled = state.tracks.isNotEmpty() && !state.isGenerating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("ai_mix_play_all_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Phát ngay", fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !state.isGenerating,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            1.dp,
                            if (colors.isDark) Color.White.copy(alpha = 0.22f) else colors.divider
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("ai_mix_refresh_btn")
                    ) {
                        if (state.isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.accent
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = colors.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tạo lại", color = colors.textPrimary)
                    }
                }

                if (state.tracks.isNotEmpty()) {
                    val previewTracks = state.tracks.take(5)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(previewSurfaceColor)
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        previewTracks.forEachIndexed { idx, track ->
                            val isCurrent = currentTrackId == track.id
                            val isDiscovery = track.id in state.discoveryTrackIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { onPlayPreviewTrack(idx) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .testTag("ai_mix_preview_row_$idx"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ArtworkImage(
                                    artworkUri = track.artworkUri,
                                    contentDescription = track.title,
                                    modifier = Modifier.size(40.dp),
                                    cornerRadius = 10.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCurrent) colors.accent else colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist.ifBlank { track.source.labelVi },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isDiscovery) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = colors.accent.copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            text = "Mới",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (colors.isDark) Color.White else colors.accent,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = formatDurationMs(track.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary
                                )
                            }
                            if (idx < previewTracks.lastIndex) {
                                HorizontalDivider(
                                    color = colors.divider.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(start = 60.dp, end = 10.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onViewAll,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("ai_mix_view_all_btn")
                        ) {
                            Text(
                                text = "Xem tất cả (${state.tracks.size} bài)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accent
                            )
                        }
                    }
                } else if (!state.isGenerating) {
                    Text(
                        text = "Hãy quét nhạc Offline, đồng bộ Telegram hoặc tìm trên YouTube để AI Smart Playlist phối nhạc riêng cho bạn.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }
}
