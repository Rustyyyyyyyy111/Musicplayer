package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.youtube.YouTubeSearchTab
import com.example.ui.components.AppleCard
import com.example.ui.components.ArtworkImage
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.LocalMusicPlayerColors
import com.example.ui.viewmodel.MusicPlayerViewModel

@Composable
fun SearchScreen(viewModel: MusicPlayerViewModel) {
    val colors = LocalMusicPlayerColors.current
    var searchScopeOnline by rememberSaveable { mutableStateOf(false) }
    var localQuery by rememberSaveable { mutableStateOf("") }
    val ytState by viewModel.youtubeState.collectAsStateWithLifecycle()
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()

    val localResults = remember(localQuery, allTracks) {
        viewModel.searchLocalTracks(localQuery)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("search_screen")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Tìm kiếm",
                style = MaterialTheme.typography.displayLarge,
                color = colors.textPrimary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !searchScopeOnline,
                    onClick = { searchScopeOnline = false },
                    label = { Text("Trong thư viện (Không dấu)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accent,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = searchScopeOnline,
                    onClick = { searchScopeOnline = true },
                    label = { Text("YouTube Music") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accent,
                        selectedLabelColor = Color.White
                    )
                )
            }

            val currentText = if (searchScopeOnline) ytState.query else localQuery
            OutlinedTextField(
                value = currentText,
                onValueChange = {
                    if (searchScopeOnline) viewModel.updateYouTubeQuery(it) else localQuery = it
                },
                modifier = Modifier.fillMaxWidth().testTag("search_input"),
                placeholder = {
                    Text(
                        if (searchScopeOnline) "Tìm bài hát, nghệ sĩ, album YouTube Music..."
                        else "Tìm bài hát, nghệ sĩ, chủ đề Telegram, tên tệp..."
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (currentText.isNotEmpty()) {
                        IconButton(onClick = {
                            if (searchScopeOnline) viewModel.updateYouTubeQuery("") else localQuery = ""
                        }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Xóa")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (searchScopeOnline) viewModel.searchYouTubeMusic()
                })
            )

            if (searchScopeOnline) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(YouTubeSearchTab.entries) { tab ->
                            FilterChip(
                                selected = ytState.activeTab == tab,
                                onClick = { viewModel.selectYouTubeTab(tab) },
                                label = { Text(tab.labelVi) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.accent,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.searchYouTubeMusic() },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("yt_search_submit")
                    ) {
                        Text("Tìm")
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!searchScopeOnline) {
                items(localResults, key = { "loc_${it.id}" }) { track ->
                    TrackRowItem(
                        track = track,
                        isCurrent = playback.currentTrack?.id == track.id,
                        onPlay = { viewModel.playTrack(track, localResults) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        onDownload = { viewModel.downloadTrack(track) },
                        onAddToQueue = { viewModel.addToQueueNext(track) },
                        onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) }
                    )
                }
            } else {
                if (ytState.isSearching) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = colors.accent)
                        }
                    }
                }
                ytState.errorMessage?.let { err ->
                    item {
                        AppleCard(cornerRadius = 18.dp) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
                items(ytState.tracks, key = { "yt_${it.id}" }) { track ->
                    TrackRowItem(
                        track = track,
                        isCurrent = playback.currentTrack?.id == track.id,
                        onPlay = { viewModel.playTrack(track, ytState.tracks) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        onDownload = { viewModel.downloadTrack(track) },
                        onAddToQueue = { viewModel.addToQueueNext(track) },
                        onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) }
                    )
                }
                items(ytState.collections, key = { it.id }) { item ->
                    AppleCard(cornerRadius = 18.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(item.artworkUrl, item.title, Modifier.size(54.dp), 12.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}
