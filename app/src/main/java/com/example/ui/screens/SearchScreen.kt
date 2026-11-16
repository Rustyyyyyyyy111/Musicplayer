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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.model.Track
import com.example.core.model.UnifiedSearchTab
import com.example.data.youtube.YouTubeSearchTab
import com.example.ui.components.AppleCard
import com.example.ui.components.ArtworkImage
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.LocalMusicPlayerColors
import com.example.ui.viewmodel.MusicPlayerViewModel

@Composable
fun SearchScreen(viewModel: MusicPlayerViewModel) {
    val colors = LocalMusicPlayerColors.current
    val searchState by viewModel.unifiedSearchState.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()

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

            // Unified Search Tabs: [Tất cả] | [Thư viện Offline] | [Telegram] | [YouTube Music]
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("unified_search_tabs")
            ) {
                items(UnifiedSearchTab.entries) { tab ->
                    val selected = searchState.activeTab == tab
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.selectSearchTab(tab) },
                        label = {
                            val countSuffix = when (tab) {
                                UnifiedSearchTab.OFFLINE -> " (${searchState.offlineResults.size})"
                                UnifiedSearchTab.TELEGRAM -> " (${searchState.telegramResults.size})"
                                UnifiedSearchTab.YOUTUBE_MUSIC -> if (searchState.youtubeTracks.isNotEmpty()) " (${searchState.youtubeTracks.size})" else ""
                                UnifiedSearchTab.ALL -> ""
                            }
                            Text("${tab.labelVi}$countSuffix")
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent,
                            selectedLabelColor = Color.White,
                            labelColor = colors.textPrimary
                        ),
                        modifier = Modifier.testTag("search_tab_${tab.name.lowercase()}")
                    )
                }
            }

            OutlinedTextField(
                value = searchState.query,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_input"),
                placeholder = {
                    Text(
                        when (searchState.activeTab) {
                            UnifiedSearchTab.ALL -> "Tìm hợp nhất Offline, Telegram & YouTube Music..."
                            UnifiedSearchTab.OFFLINE -> "Tìm bài hát, nghệ sĩ, tên tệp Offline (không dấu)..."
                            UnifiedSearchTab.TELEGRAM -> "Tìm nhạc, chủ đề Forum, kênh Telegram..."
                            UnifiedSearchTab.YOUTUBE_MUSIC -> "Tìm bài hát, album, nghệ sĩ YouTube Music..."
                        }
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchState.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Xóa")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    viewModel.submitUnifiedSearch()
                })
            )

            if (searchState.activeTab == UnifiedSearchTab.YOUTUBE_MUSIC) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(YouTubeSearchTab.entries) { subTab ->
                            FilterChip(
                                selected = searchState.youtubeSubTab == subTab,
                                onClick = { viewModel.selectSearchYouTubeSubTab(subTab) },
                                label = { Text(subTab.labelVi) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.accent,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.submitUnifiedSearch() },
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
            val showOffline = searchState.activeTab == UnifiedSearchTab.ALL ||
                searchState.activeTab == UnifiedSearchTab.OFFLINE
            val showTelegram = searchState.activeTab == UnifiedSearchTab.ALL ||
                searchState.activeTab == UnifiedSearchTab.TELEGRAM
            val showYouTube = searchState.activeTab == UnifiedSearchTab.ALL ||
                searchState.activeTab == UnifiedSearchTab.YOUTUBE_MUSIC

            if (showOffline && searchState.offlineResults.isNotEmpty()) {
                item(key = "header_offline") {
                    SearchGroupHeader(
                        title = "Thư viện Offline",
                        count = searchState.offlineResults.size
                    )
                }
                items(searchState.offlineResults, key = { "off_${it.id}" }) { track ->
                    UnifiedTrackRow(
                        track = track,
                        currentTrackId = playback.currentTrack?.id,
                        queueContext = searchState.offlineResults,
                        viewModel = viewModel
                    )
                }
            }

            if (showTelegram && searchState.telegramResults.isNotEmpty()) {
                item(key = "header_telegram") {
                    SearchGroupHeader(
                        title = "Telegram",
                        count = searchState.telegramResults.size
                    )
                }
                items(searchState.telegramResults, key = { "tg_${it.id}" }) { track ->
                    UnifiedTrackRow(
                        track = track,
                        currentTrackId = playback.currentTrack?.id,
                        queueContext = searchState.telegramResults,
                        viewModel = viewModel
                    )
                }
            }

            if (showYouTube) {
                if (searchState.isSearchingYouTube) {
                    item(key = "yt_loading") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = colors.accent,
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Đang tìm trên YouTube Music...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
                searchState.youtubeError?.let { err ->
                    if (searchState.activeTab == UnifiedSearchTab.YOUTUBE_MUSIC || searchState.query.trim().length >= 2) {
                        item(key = "yt_error") {
                            AppleCard(cornerRadius = 18.dp) {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }
                }
                if (searchState.youtubeTracks.isNotEmpty()) {
                    item(key = "header_youtube") {
                        SearchGroupHeader(
                            title = "YouTube Music",
                            count = searchState.youtubeTracks.size
                        )
                    }
                    items(searchState.youtubeTracks, key = { "yt_${it.id}" }) { track ->
                        UnifiedTrackRow(
                            track = track,
                            currentTrackId = playback.currentTrack?.id,
                            queueContext = searchState.youtubeTracks,
                            viewModel = viewModel
                        )
                    }
                }
                if (searchState.youtubeCollections.isNotEmpty()) {
                    items(searchState.youtubeCollections, key = { "ytc_${it.id}" }) { item ->
                        AppleCard(cornerRadius = 18.dp) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
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

            val nothingVisible = (!showOffline || searchState.offlineResults.isEmpty()) &&
                (!showTelegram || searchState.telegramResults.isEmpty()) &&
                (!showYouTube || (searchState.youtubeTracks.isEmpty() && searchState.youtubeCollections.isEmpty() && !searchState.isSearchingYouTube))

            if (nothingVisible) {
                item(key = "empty_search") {
                    AppleCard(cornerRadius = 20.dp) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (searchState.query.isBlank()) {
                                    "Nhập từ khóa để tìm kiếm hợp nhất"
                                } else {
                                    "Không tìm thấy kết quả phù hợp"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Hỗ trợ tìm tiếng Việt có/không dấu qua thư viện Offline, nguồn Telegram và YouTube Music.",
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

@Composable
private fun SearchGroupHeader(title: String, count: Int) {
    val colors = LocalMusicPlayerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary
        )
        Text(
            text = "$count kết quả",
            style = MaterialTheme.typography.labelMedium,
            color = colors.accent
        )
    }
}

@Composable
private fun UnifiedTrackRow(
    track: Track,
    currentTrackId: String?,
    queueContext: List<Track>,
    viewModel: MusicPlayerViewModel
) {
    TrackRowItem(
        track = track,
        isCurrent = currentTrackId == track.id,
        onPlay = { viewModel.playTrack(track, queueContext) },
        onToggleFavorite = { viewModel.toggleFavorite(track) },
        onDownload = { viewModel.downloadTrack(track) },
        onAddToQueue = { viewModel.addToQueueNext(track) },
        onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) }
    )
}
