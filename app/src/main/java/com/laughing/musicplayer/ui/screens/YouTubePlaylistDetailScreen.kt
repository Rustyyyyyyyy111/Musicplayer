package com.laughing.musicplayer.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laughing.musicplayer.data.youtube.YouTubeCollectionResult
import com.laughing.musicplayer.data.youtube.YouTubeSearchTab
import com.laughing.musicplayer.ui.components.AppleCard
import com.laughing.musicplayer.ui.components.ArtworkImage
import com.laughing.musicplayer.ui.components.TrackRowItem
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel

@Composable
fun YouTubePlaylistDetailScreen(
    viewModel: MusicPlayerViewModel,
    collection: YouTubeCollectionResult,
    onBack: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val detailState by viewModel.playlistDetailState.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()

    LaunchedEffect(collection.id) {
        if (detailState.collection?.id != collection.id) {
            viewModel.openYouTubePlaylist(collection)
        }
    }

    val activeCollection = detailState.collection ?: collection

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("youtube_playlist_detail_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("playlist_detail_back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Quay lại",
                    tint = colors.accent
                )
            }
            Text(
                text = when (activeCollection.kind) {
                    YouTubeSearchTab.ALBUMS -> "Album YouTube Music"
                    YouTubeSearchTab.ARTISTS -> "Nghệ sĩ YouTube Music"
                    else -> "Playlist YouTube Music"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { viewModel.openYouTubePlaylist(activeCollection) },
                enabled = !detailState.isLoading,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Làm mới playlist",
                    tint = colors.textSecondary
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item(key = "playlist_hero") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ArtworkImage(
                        artworkUri = activeCollection.artworkUrl,
                        contentDescription = activeCollection.title,
                        modifier = Modifier.size(168.dp),
                        cornerRadius = 16.dp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = activeCollection.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metaText = buildString {
                        if (activeCollection.subtitle.isNotBlank()) {
                            append(activeCollection.subtitle)
                        }
                        if (detailState.tracks.isNotEmpty()) {
                            if (isNotEmpty()) append(" • ")
                            append("${detailState.tracks.size} bài hát • OPUS 48kHz")
                        }
                    }
                    if (metaText.isNotBlank()) {
                        Text(
                            text = metaText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (detailState.tracks.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.playTrack(detailState.tracks.first(), detailState.tracks)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("playlist_play_all")
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Phát tất cả", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { viewModel.downloadPlaylistTracks(detailState.tracks) },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("playlist_download_all")
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Tải cả playlist", maxLines = 1)
                            }
                        }
                    }
                }
            }

            if (detailState.isLoading) {
                item(key = "playlist_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(color = colors.accent)
                            Text(
                                text = "Đang tải danh sách bài hát...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            } else if (detailState.errorMessage != null && detailState.tracks.isEmpty()) {
                item(key = "playlist_error") {
                    AppleCard(
                        cornerRadius = 18.dp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = detailState.errorMessage ?: "Không thể tải playlist",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textPrimary,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { viewModel.openYouTubePlaylist(activeCollection) },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Thử lại")
                            }
                        }
                    }
                }
            } else {
                items(detailState.tracks, key = { "pl_${it.id}" }) { track ->
                    TrackRowItem(
                        track = track,
                        isCurrent = playback.currentTrack?.id == track.id,
                        onPlay = { viewModel.playTrack(track, detailState.tracks) },
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
