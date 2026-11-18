package com.laughing.musicplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.ui.components.AppleCard
import com.laughing.musicplayer.ui.components.TrackRowItem
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel

@Composable
fun ListenNowScreen(viewModel: MusicPlayerViewModel) {
    val colors = LocalMusicPlayerColors.current
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    val recentTracks by viewModel.recentTracks.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("listen_now_screen"),
        contentPadding = PaddingValues(top = 12.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
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

        // Recently Played / Library Discovery
        val showcaseTracks: List<Track> = if (recentTracks.isNotEmpty()) recentTracks else allTracks.take(12)
        if (showcaseTracks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
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
        } else {
            item {
                AppleCard(
                    cornerRadius = 22.dp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
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
}
