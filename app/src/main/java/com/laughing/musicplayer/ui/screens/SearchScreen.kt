package com.laughing.musicplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.model.UnifiedSearchTab
import com.laughing.musicplayer.data.youtube.YouTubeCollectionResult
import com.laughing.musicplayer.data.youtube.YouTubeExploreMood
import com.laughing.musicplayer.data.youtube.YouTubeSearchTab
import com.laughing.musicplayer.ui.components.AppleCard
import com.laughing.musicplayer.ui.components.ArtworkImage
import com.laughing.musicplayer.ui.components.TrackRowItem
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    viewModel: MusicPlayerViewModel,
    onOpenPlaylist: (YouTubeCollectionResult) -> Unit = {}
) {
    val colors = LocalMusicPlayerColors.current
    val searchState by viewModel.unifiedSearchState.collectAsStateWithLifecycle()
    val exploreState by viewModel.exploreState.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()

    // Local TextFieldValue keeps IME composition & cursor selection completely isolated from ViewModel flows
    var textFieldValue by remember { mutableStateOf(TextFieldValue(searchState.query)) }

    LaunchedEffect(textFieldValue.text) {
        if (textFieldValue.text != searchState.query) {
            delay(300L)
            viewModel.updateSearchQuery(textFieldValue.text)
        }
    }

    val isQueryBlank = textFieldValue.text.isBlank()
    val showExploreFeed = isQueryBlank &&
        (searchState.activeTab == UnifiedSearchTab.ALL || searchState.activeTab == UnifiedSearchTab.YOUTUBE_MUSIC)

    LaunchedEffect(showExploreFeed) {
        if (showExploreFeed) {
            viewModel.loadYouTubeExploreIfNeeded()
        }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showExploreFeed) "Khám phá" else "Tìm kiếm",
                    style = MaterialTheme.typography.displayLarge,
                    color = colors.textPrimary
                )
                if (showExploreFeed) {
                    IconButton(
                        onClick = { viewModel.loadYouTubeExploreIfNeeded(forceRefresh = true) },
                        enabled = !exploreState.isLoading,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("explore_refresh_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Làm mới Khám phá",
                            tint = colors.textSecondary
                        )
                    }
                }
            }

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
                                UnifiedSearchTab.YOUTUBE_MUSIC -> if (searchState.youtubeTracks.isNotEmpty() && !isQueryBlank) " (${searchState.youtubeTracks.size})" else ""
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
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_input"),
                placeholder = {
                    Text(
                        when (searchState.activeTab) {
                            UnifiedSearchTab.ALL -> "Tìm bài hát, playlist, Telegram & YouTube Music..."
                            UnifiedSearchTab.OFFLINE -> "Tìm bài hát, nghệ sĩ, tên tệp Offline (không dấu)..."
                            UnifiedSearchTab.TELEGRAM -> "Tìm nhạc, chủ đề Forum, kênh Telegram..."
                            UnifiedSearchTab.YOUTUBE_MUSIC -> "Tìm bài hát, album, nghệ sĩ YouTube Music..."
                        }
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (textFieldValue.text.isNotEmpty()) {
                        IconButton(onClick = {
                            textFieldValue = TextFieldValue("")
                            viewModel.updateSearchQuery("")
                        }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Xóa")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    viewModel.updateSearchQuery(textFieldValue.text)
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
                        onClick = {
                            viewModel.updateSearchQuery(textFieldValue.text)
                            viewModel.submitUnifiedSearch()
                        },
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
            modifier = Modifier
                .fillMaxSize()
                .testTag(if (showExploreFeed) "explore_feed_list" else "search_results_list"),
            contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (showExploreFeed) {
                item(key = "explore_moods_header") {
                    ExploreSectionHeader(
                        title = "Tâm trạng & Thể loại",
                        actionLabel = if (exploreState.selectedMood != null) "Tất cả" else null,
                        onAction = if (exploreState.selectedMood != null) {
                            { viewModel.selectExploreMood(null) }
                        } else null
                    )
                }

                item(key = "explore_moods_chips") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("explore_moods_row")
                    ) {
                        items(YouTubeExploreMood.entries) { mood ->
                            val selected = exploreState.selectedMood == mood
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.selectExploreMood(mood) },
                                label = { Text(mood.labelVi) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.accent,
                                    selectedLabelColor = Color.White,
                                    labelColor = colors.textPrimary
                                ),
                                modifier = Modifier.testTag("explore_mood_${mood.name.lowercase()}")
                            )
                        }
                    }
                }

                val selectedMood = exploreState.selectedMood
                if (selectedMood != null) {
                    if (exploreState.isLoadingMood) {
                        item(key = "mood_loading") {
                            ExploreLoadingRow("Đang tải chủ đề ${selectedMood.labelVi}...")
                        }
                    } else if (exploreState.moodError != null && exploreState.moodTracks.isEmpty() && exploreState.moodPlaylists.isEmpty()) {
                        item(key = "mood_error") {
                            ExploreErrorCard(
                                message = exploreState.moodError ?: "Không thể tải chủ đề",
                                onRetry = {
                                    viewModel.selectExploreMood(null)
                                    viewModel.selectExploreMood(selectedMood)
                                }
                            )
                        }
                    } else {
                        if (exploreState.moodPlaylists.isNotEmpty()) {
                            item(key = "mood_playlists_header") {
                                ExploreSectionHeader(title = "Playlist ${selectedMood.labelVi}")
                            }
                            item(key = "mood_playlists_row") {
                                ExploreCollectionRow(
                                    items = exploreState.moodPlaylists,
                                    onOpenPlaylist = onOpenPlaylist
                                )
                            }
                        }
                        if (exploreState.moodTracks.isNotEmpty()) {
                            item(key = "mood_tracks_header") {
                                ExploreSectionHeader(
                                    title = "Bài hát ${selectedMood.labelVi}",
                                    actionLabel = "Phát tất cả",
                                    onAction = {
                                        viewModel.playTrack(exploreState.moodTracks.first(), exploreState.moodTracks)
                                    }
                                )
                            }
                            items(exploreState.moodTracks, key = { "mood_tr_${it.id}" }) { track ->
                                UnifiedTrackRow(
                                    track = track,
                                    currentTrackId = playback.currentTrack?.id,
                                    queueContext = exploreState.moodTracks,
                                    viewModel = viewModel,
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                } else {
                    if (exploreState.isLoading &&
                        exploreState.featuredPlaylists.isEmpty() &&
                        exploreState.chartTracks.isEmpty() &&
                        exploreState.trendingTracks.isEmpty()
                    ) {
                        item(key = "explore_loading") {
                            ExploreLoadingRow("Đang tải Khám phá YouTube Music...")
                        }
                    } else if (exploreState.errorMessage != null &&
                        exploreState.featuredPlaylists.isEmpty() &&
                        exploreState.chartTracks.isEmpty() &&
                        exploreState.trendingTracks.isEmpty()
                    ) {
                        item(key = "explore_error") {
                            ExploreErrorCard(
                                message = exploreState.errorMessage ?: "Không thể tải Khám phá YouTube Music",
                                onRetry = { viewModel.loadYouTubeExploreIfNeeded(forceRefresh = true) }
                            )
                        }
                    }

                    if (exploreState.featuredPlaylists.isNotEmpty()) {
                        item(key = "explore_playlists_header") {
                            ExploreSectionHeader(title = "Playlist nổi bật")
                        }
                        item(key = "explore_playlists_row") {
                            ExploreCollectionRow(
                                items = exploreState.featuredPlaylists,
                                onOpenPlaylist = onOpenPlaylist
                            )
                        }
                    }

                    if (exploreState.newReleases.isNotEmpty()) {
                        item(key = "explore_releases_header") {
                            ExploreSectionHeader(title = "Phát hành mới")
                        }
                        item(key = "explore_releases_row") {
                            ExploreCollectionRow(
                                items = exploreState.newReleases,
                                onOpenPlaylist = onOpenPlaylist
                            )
                        }
                    }

                    if (exploreState.chartTracks.isNotEmpty()) {
                        item(key = "explore_charts_header") {
                            ExploreSectionHeader(
                                title = "Bảng xếp hạng",
                                actionLabel = "Phát tất cả",
                                onAction = {
                                    viewModel.playTrack(exploreState.chartTracks.first(), exploreState.chartTracks)
                                }
                            )
                        }
                        items(exploreState.chartTracks, key = { "exp_chart_${it.id}" }) { track ->
                            UnifiedTrackRow(
                                track = track,
                                currentTrackId = playback.currentTrack?.id,
                                queueContext = exploreState.chartTracks,
                                viewModel = viewModel,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }

                    if (exploreState.trendingTracks.isNotEmpty()) {
                        item(key = "explore_trending_header") {
                            ExploreSectionHeader(
                                title = "Thịnh hành",
                                actionLabel = "Phát tất cả",
                                onAction = {
                                    viewModel.playTrack(exploreState.trendingTracks.first(), exploreState.trendingTracks)
                                }
                            )
                        }
                        items(exploreState.trendingTracks, key = { "exp_trend_${it.id}" }) { track ->
                            UnifiedTrackRow(
                                track = track,
                                currentTrackId = playback.currentTrack?.id,
                                queueContext = exploreState.trendingTracks,
                                viewModel = viewModel,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            } else {
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
                            viewModel = viewModel,
                            modifier = Modifier.animateItem()
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
                            viewModel = viewModel,
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                if (showYouTube) {
                    if (searchState.isSearchingYouTube) {
                        item(key = "yt_loading") {
                            ExploreLoadingRow("Đang tìm trên YouTube Music...")
                        }
                    }
                    searchState.youtubeError?.let { err ->
                        if (searchState.activeTab == UnifiedSearchTab.YOUTUBE_MUSIC || searchState.query.trim().length >= 2) {
                            item(key = "yt_error") {
                                AppleCard(
                                    cornerRadius = 18.dp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                ) {
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
                                viewModel = viewModel,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                    if (searchState.youtubeCollections.isNotEmpty()) {
                        items(searchState.youtubeCollections, key = { "ytc_${it.id}" }) { item ->
                            AppleCard(
                                cornerRadius = 18.dp,
                                onClick = { onOpenPlaylist(item) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ArtworkImage(item.artworkUrl, item.title, Modifier.size(54.dp), 12.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        AppleCard(
                            cornerRadius = 20.dp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (searchState.query.isBlank()) {
                                        "Chưa có bài hát trong nguồn này"
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
}

@Composable
private fun ExploreSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = LocalMusicPlayerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.accent)
                Spacer(modifier = Modifier.width(2.dp))
                Text(actionLabel, color = colors.accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ExploreCollectionRow(
    items: List<YouTubeCollectionResult>,
    onOpenPlaylist: (YouTubeCollectionResult) -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { "exp_col_${it.id}" }) { item ->
            Column(
                modifier = Modifier
                    .width(140.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpenPlaylist(item) }
                    .padding(bottom = 4.dp)
                    .testTag("explore_collection_${item.id.hashCode()}")
            ) {
                ArtworkImage(
                    artworkUri = item.artworkUrl,
                    contentDescription = item.title,
                    modifier = Modifier.size(140.dp),
                    cornerRadius = 14.dp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ExploreLoadingRow(message: String) {
    val colors = LocalMusicPlayerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
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
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
    }
}

@Composable
private fun ExploreErrorCard(
    message: String,
    onRetry: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    AppleCard(
        cornerRadius = 18.dp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Thử lại")
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
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
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
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier
) {
    TrackRowItem(
        track = track,
        isCurrent = currentTrackId == track.id,
        onPlay = { viewModel.playTrack(track, queueContext) },
        onToggleFavorite = { viewModel.toggleFavorite(track) },
        onDownload = { viewModel.downloadTrack(track) },
        onAddToQueue = { viewModel.addToQueueNext(track) },
        onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) },
        modifier = modifier
    )
}
