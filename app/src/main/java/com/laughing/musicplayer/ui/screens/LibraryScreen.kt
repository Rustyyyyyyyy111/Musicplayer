package com.laughing.musicplayer.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laughing.musicplayer.core.model.ArtistGroup
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.LibraryCategory
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.SmartPlaylistType
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.ui.components.AppleCard
import com.laughing.musicplayer.ui.components.AppleMusicSegmentedSwitcher
import com.laughing.musicplayer.ui.components.ArtworkImage
import com.laughing.musicplayer.ui.components.TrackRowItem
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel

private enum class LibraryTabOption(val labelVi: String) {
    ALL_SONGS(LibraryCategory.ALL_SONGS.labelVi),
    ARTISTS(LibraryCategory.ARTISTS.labelVi),
    ALBUMS(LibraryCategory.ALBUMS.labelVi),
    GENRES(LibraryCategory.GENRES.labelVi),
    PLAYLISTS(LibraryCategory.PLAYLISTS.labelVi),
    FAVORITES(LibraryCategory.FAVORITES.labelVi),
    DOWNLOADED(LibraryCategory.DOWNLOADED.labelVi),
    RECENT(LibraryCategory.RECENT.labelVi),
    DUPLICATES("Trùng lặp")
}

@Composable
fun LibraryScreen(
    viewModel: MusicPlayerViewModel,
    onOpenSearch: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalMusicPlayerColors.current
    val selectedSource by viewModel.selectedLibrarySource.collectAsStateWithLifecycle()
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloadedTracks.collectAsStateWithLifecycle()
    val recent by viewModel.recentTracks.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val notPlayed30Days by viewModel.notPlayed30DaysTracks.collectAsStateWithLifecycle()
    val mostPlayedMonth by viewModel.mostPlayedThisMonthTracks.collectAsStateWithLifecycle()
    val highEnergy130Bpm by viewModel.highEnergyTracks.collectAsStateWithLifecycle()
    val artists by viewModel.libraryArtists.collectAsStateWithLifecycle()
    val albums by viewModel.libraryAlbums.collectAsStateWithLifecycle()
    val genreCounts by viewModel.libraryGenreCounts.collectAsStateWithLifecycle()
    val hasAllFilesAccess by viewModel.hasAllFilesAccess.collectAsStateWithLifecycle()
    val duplicates by viewModel.duplicateGroups.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val isIndexingOffline by viewModel.isIndexingOffline.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStoragePermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Preserve per-source category tab and scroll state
    var tgCategory by rememberSaveable { mutableStateOf(LibraryTabOption.ALL_SONGS) }
    var ytCategory by rememberSaveable { mutableStateOf(LibraryTabOption.ALL_SONGS) }
    var offCategory by rememberSaveable { mutableStateOf(LibraryTabOption.ALL_SONGS) }

    val tgListState = rememberLazyListState()
    val ytListState = rememberLazyListState()
    val offListState = rememberLazyListState()

    var selectedArtistGroup by remember { mutableStateOf<ArtistGroup?>(null) }
    var selectedGenre by remember { mutableStateOf<GenreType?>(null) }
    var selectedAlbumName by remember { mutableStateOf<String?>(null) }
    var selectedSmartPlaylist by remember { mutableStateOf<SmartPlaylistType?>(null) }
    var editingGenreTrack by remember { mutableStateOf<Track?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    // Collapsible Local Search with isolated TextFieldValue to preserve Vietnamese Telex/VNI IME cursor
    var isLocalSearchVisible by rememberSaveable { mutableStateOf(false) }
    var localSearchValue by remember { mutableStateOf(TextFieldValue("")) }

    BackHandler(enabled = isLocalSearchVisible) {
        localSearchValue = TextFieldValue("")
        isLocalSearchVisible = false
    }

    val storagePermissions = remember {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it } || viewModel.hasAudioReadPermission()) {
            viewModel.indexOfflineLibrary()
        }
    }
    fun triggerOfflineScanWithPermissions() {
        if (viewModel.hasAudioReadPermission()) {
            viewModel.indexOfflineLibrary()
        } else {
            permissionLauncher.launch(storagePermissions)
        }
    }

    val m3uImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val content = runCatching {
                context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()
            }.getOrNull()
            if (!content.isNullOrBlank()) {
                viewModel.importM3u("Playlist M3U", content)
            }
        }
    }

    val activeCategory = when (selectedSource) {
        MusicSource.TELEGRAM -> tgCategory
        MusicSource.YOUTUBE_MUSIC -> ytCategory
        MusicSource.OFFLINE -> offCategory
    }

    fun setActiveCategory(cat: LibraryTabOption) {
        selectedArtistGroup = null
        selectedGenre = null
        selectedAlbumName = null
        selectedSmartPlaylist = null
        when (selectedSource) {
            MusicSource.TELEGRAM -> tgCategory = cat
            MusicSource.YOUTUBE_MUSIC -> ytCategory = cat
            MusicSource.OFFLINE -> offCategory = cat
        }
    }

    val sourceTracks = remember(allTracks, selectedSource) {
        allTracks.filter { it.source == selectedSource }
    }

    val localSearchQuery = localSearchValue.text.trim()
    val isFilteringLocally = isLocalSearchVisible && localSearchQuery.isNotEmpty()
    val localFilteredTracks = remember(allTracks, localSearchQuery, isFilteringLocally) {
        if (!isFilteringLocally) {
            emptyList()
        } else {
            allTracks.filter { track ->
                val blob = "${track.title} ${track.artist} ${track.album} ${track.telegramTopicName.orEmpty()} ${track.fileName}"
                VietnameseTextNormalizer.matches(blob, localSearchQuery)
            }
        }
    }

    val artistDrillTracks = remember(sourceTracks, selectedArtistGroup) {
        val target = selectedArtistGroup ?: return@remember emptyList()
        if (target.isTelegramTopic && target.chatId != null && target.threadId != null) {
            sourceTracks.filter { it.telegramChatId == target.chatId && it.telegramThreadId == target.threadId }
        } else {
            sourceTracks.filter { it.artist == target.name }
        }
    }

    val albumDrillTracks = remember(sourceTracks, selectedAlbumName) {
        val album = selectedAlbumName ?: return@remember emptyList()
        sourceTracks.filter { it.album == album }
    }

    val genreDrillTracks = remember(allTracks, selectedGenre) {
        val genre = selectedGenre ?: return@remember emptyList()
        allTracks.filter { it.genre == genre }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("library_screen")
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
                    text = "Thư viện",
                    style = MaterialTheme.typography.displayLarge,
                    color = colors.textPrimary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (isLocalSearchVisible) {
                                localSearchValue = TextFieldValue("")
                                isLocalSearchVisible = false
                            } else {
                                isLocalSearchVisible = true
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("library_search_btn")
                    ) {
                        Icon(
                            imageVector = if (isLocalSearchVisible) Icons.Filled.Clear else Icons.Filled.Search,
                            contentDescription = if (isLocalSearchVisible) "Đóng tìm kiếm thư viện" else "Tìm trong thư viện",
                            tint = if (isLocalSearchVisible) colors.accent else colors.textPrimary
                        )
                    }
                    when (selectedSource) {
                        MusicSource.TELEGRAM -> Unit
                        MusicSource.YOUTUBE_MUSIC -> {
                            Button(
                                onClick = onOpenSearch,
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Tìm YouTube")
                            }
                        }
                        MusicSource.OFFLINE -> {
                            Button(
                                onClick = { triggerOfflineScanWithPermissions() },
                                enabled = !isIndexingOffline,
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isIndexingOffline) "Đang quét" else "Quét nhạc")
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = isLocalSearchVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = localSearchValue,
                        onValueChange = { localSearchValue = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("library_search_input"),
                        placeholder = { Text("Tìm trong thư viện...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (localSearchValue.text.isNotEmpty()) {
                                IconButton(
                                    onClick = { localSearchValue = TextFieldValue("") },
                                    modifier = Modifier.testTag("library_search_clear")
                                ) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Xóa từ khóa")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                    )
                    TextButton(
                        onClick = {
                            localSearchValue = TextFieldValue("")
                            isLocalSearchVisible = false
                        },
                        modifier = Modifier.testTag("library_search_cancel")
                    ) {
                        Text("Hủy", color = colors.accent)
                    }
                }
            }

            if (!isFilteringLocally) {
                // Segmented Source Switcher: [ Telegram | YouTube Music | Offline ]
                AppleMusicSegmentedSwitcher(
                    selectedSource = selectedSource,
                    onSelect = {
                        selectedArtistGroup = null
                        selectedGenre = null
                        selectedAlbumName = null
                        viewModel.selectLibrarySource(it)
                    }
                )

                // Category Pills
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("library_category_row")
                ) {
                    items(LibraryTabOption.entries) { cat ->
                        val selected = cat == activeCategory
                        FilterChip(
                            selected = selected,
                            onClick = { setActiveCategory(cat) },
                            label = { Text(cat.labelVi) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accent,
                                selectedLabelColor = Color.White,
                                labelColor = colors.textPrimary
                            ),
                            modifier = Modifier.testTag("lib_cat_${cat.name.lowercase()}")
                        )
                    }
                }
            }
        }

        // Drill-down header if viewing an Artist / Forum Topic / Album / Genre / Smart Playlist
        if (!isFilteringLocally && (selectedArtistGroup != null || selectedAlbumName != null || selectedGenre != null || selectedSmartPlaylist != null)) {
            val headerTitle = selectedArtistGroup?.name
                ?: selectedAlbumName
                ?: selectedGenre?.displayNameVi
                ?: selectedSmartPlaylist?.titleVi.orEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    selectedArtistGroup = null
                    selectedAlbumName = null
                    selectedGenre = null
                    selectedSmartPlaylist = null
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = colors.accent)
                }
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
            }
        }

        val activeListState = when (selectedSource) {
            MusicSource.TELEGRAM -> tgListState
            MusicSource.YOUTUBE_MUSIC -> ytListState
            MusicSource.OFFLINE -> offListState
        }
        LazyColumn(
            state = activeListState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("library_content_list"),
            contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (isFilteringLocally) {
                item(key = "local_search_summary") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Kết quả trong thư viện (${localFilteredTracks.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Telegram • YouTube • Offline",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                    }
                }
                if (localFilteredTracks.isEmpty()) {
                    item(key = "local_search_empty") {
                        AppleCard(
                            cornerRadius = 20.dp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Không tìm thấy bài hát phù hợp trong thư viện",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = "Không có bài hát nào khớp với \"$localSearchQuery\" ở các nguồn đã lưu.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary
                                )
                                Button(
                                    onClick = onOpenSearch,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Tìm trên YouTube Music")
                                }
                            }
                        }
                    }
                } else {
                    items(localFilteredTracks, key = { "loc_${it.id}" }) { track ->
                        TrackRowItem(
                            track = track,
                            isCurrent = playback.currentTrack?.id == track.id,
                            onPlay = { viewModel.playTrack(track, localFilteredTracks) },
                            onToggleFavorite = { viewModel.toggleFavorite(track) },
                            onDownload = { viewModel.downloadTrack(track) },
                            onAddToQueue = { viewModel.addToQueueNext(track) },
                            onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) },
                            onEditGenre = { editingGenreTrack = track },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            } else if (selectedArtistGroup != null) {
                items(artistDrillTracks, key = { it.id }) { track ->
                    TrackRowItem(
                        track = track,
                        isCurrent = playback.currentTrack?.id == track.id,
                        onPlay = { viewModel.playTrack(track, artistDrillTracks) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        onDownload = { viewModel.downloadTrack(track) },
                        onAddToQueue = { viewModel.addToQueueNext(track) },
                        onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) },
                        onEditGenre = { editingGenreTrack = track }
                    )
                }
            } else if (selectedAlbumName != null) {
                items(albumDrillTracks, key = { it.id }) { track ->
                    TrackRowItem(
                        track = track,
                        isCurrent = playback.currentTrack?.id == track.id,
                        onPlay = { viewModel.playTrack(track, albumDrillTracks) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        onDownload = { viewModel.downloadTrack(track) },
                        onAddToQueue = { viewModel.addToQueueNext(track) },
                        onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) },
                        onEditGenre = { editingGenreTrack = track }
                    )
                }
            } else if (selectedGenre != null) {
                items(genreDrillTracks, key = { it.id }) { track ->
                    TrackRowItem(
                        track = track,
                        isCurrent = playback.currentTrack?.id == track.id,
                        onPlay = { viewModel.playTrack(track, genreDrillTracks) },
                        onToggleFavorite = { viewModel.toggleFavorite(track) },
                        onDownload = { viewModel.downloadTrack(track) },
                        onAddToQueue = { viewModel.addToQueueNext(track) },
                        onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) },
                        onEditGenre = { editingGenreTrack = track }
                    )
                }
            } else if (selectedSmartPlaylist != null) {
                val smartTracks = when (selectedSmartPlaylist!!) {
                    SmartPlaylistType.NOT_PLAYED_30_DAYS -> notPlayed30Days
                    SmartPlaylistType.MOST_PLAYED_THIS_MONTH -> mostPlayedMonth
                    SmartPlaylistType.HIGH_ENERGY_130_BPM -> highEnergy130Bpm
                }
                if (smartTracks.isEmpty()) {
                    item {
                        AppleCard(
                            cornerRadius = 20.dp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "Chưa có bài hát nào khớp điều kiện của danh sách phát thông minh này.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(18.dp)
                            )
                        }
                    }
                } else {
                    items(smartTracks, key = { "sp_${it.id}" }) { track ->
                        TrackRowItem(
                            track = track,
                            isCurrent = playback.currentTrack?.id == track.id,
                            onPlay = { viewModel.playTrack(track, smartTracks) },
                            onToggleFavorite = { viewModel.toggleFavorite(track) },
                            onDownload = { viewModel.downloadTrack(track) },
                            onAddToQueue = { viewModel.addToQueueNext(track) },
                            onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) },
                            onEditGenre = { editingGenreTrack = track }
                        )
                    }
                }
            } else {
                when (activeCategory) {
                    LibraryTabOption.ALL_SONGS -> {
                        if (sourceTracks.isEmpty()) {
                            item {
                                EmptySourceCard(
                                    source = selectedSource,
                                    hasAllFilesAccess = hasAllFilesAccess,
                                    onOpenSearch = onOpenSearch,
                                    onGrantAllFilesAccess = {
                                        runCatching { context.startActivity(viewModel.buildAllFilesAccessIntent()) }
                                    },
                                    onScanOffline = { triggerOfflineScanWithPermissions() }
                                )
                            }
                        } else {
                            items(sourceTracks, key = { it.id }) { track ->
                                TrackRowItem(
                                    track = track,
                                    isCurrent = playback.currentTrack?.id == track.id,
                                    onPlay = { viewModel.playTrack(track, sourceTracks) },
                                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                                    onDownload = { viewModel.downloadTrack(track) },
                                    onAddToQueue = { viewModel.addToQueueNext(track) },
                                    onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) },
                                    onEditGenre = { editingGenreTrack = track },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }

                    LibraryTabOption.ARTISTS -> {
                        items(artists, key = { it.key }) { artist ->
                            AppleCard(
                                cornerRadius = 18.dp,
                                onClick = { selectedArtistGroup = artist },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ArtworkImage(
                                        artworkUri = artist.artworkUri,
                                        contentDescription = artist.name,
                                        modifier = Modifier.size(52.dp),
                                        cornerRadius = 26.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (artist.isTelegramTopic) Icons.Filled.Forum else Icons.Filled.Person,
                                                contentDescription = null,
                                                tint = colors.accent,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = artist.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        val desc = if (artist.isTelegramTopic) {
                                            "${artist.trackCount} bài • Topic ${artist.chatTitle.orEmpty()}"
                                        } else {
                                            "${artist.trackCount} bài hát"
                                        }
                                        Text(desc, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                                    }
                                }
                            }
                        }
                    }

                    LibraryTabOption.ALBUMS -> {
                        items(albums, key = { it.name }) { album ->
                            AppleCard(
                                cornerRadius = 18.dp,
                                onClick = { selectedAlbumName = album.name },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ArtworkImage(
                                        artworkUri = album.artworkUri,
                                        contentDescription = album.name,
                                        modifier = Modifier.size(54.dp),
                                        cornerRadius = 12.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(album.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                        Text("${album.artist} • ${album.trackCount} bài", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                                    }
                                    Icon(Icons.Filled.Album, contentDescription = null, tint = colors.textSecondary)
                                }
                            }
                        }
                    }

                    LibraryTabOption.GENRES -> {
                        items(GenreType.entries) { genre ->
                            val count = genreCounts[genre] ?: 0
                            AppleCard(
                                cornerRadius = 18.dp,
                                onClick = { selectedGenre = genre },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Category, contentDescription = null, tint = colors.accent)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(genre.displayNameVi, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                            Text("EQ tự động: ${genre.defaultEqPreset.displayNameVi}", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                                        }
                                    }
                                    Text("$count bài", style = MaterialTheme.typography.labelLarge, color = colors.accent)
                                }
                            }
                        }
                    }

                    LibraryTabOption.PLAYLISTS -> {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { showCreatePlaylistDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Tạo Playlist")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.createAndPlaySmartMix() },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Smart Mix")
                                }
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { m3uImportLauncher.launch(arrayOf("*/*")) },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Nhập M3U")
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.exportAllToM3uIntent()?.let { intent ->
                                            context.startActivity(Intent.createChooser(intent, "Xuất M3U"))
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Xuất M3U")
                                }
                            }
                        }
                        item {
                            Text(
                                text = "Danh sách phát thông minh (Room DB)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
                            )
                        }
                        items(SmartPlaylistType.entries, key = { it.id }) { smartType ->
                            val smartTracks = when (smartType) {
                                SmartPlaylistType.NOT_PLAYED_30_DAYS -> notPlayed30Days
                                SmartPlaylistType.MOST_PLAYED_THIS_MONTH -> mostPlayedMonth
                                SmartPlaylistType.HIGH_ENERGY_130_BPM -> highEnergy130Bpm
                            }
                            val smartIcon = when (smartType) {
                                SmartPlaylistType.NOT_PLAYED_30_DAYS -> Icons.Filled.History
                                SmartPlaylistType.MOST_PLAYED_THIS_MONTH -> Icons.Filled.TrendingUp
                                SmartPlaylistType.HIGH_ENERGY_130_BPM -> Icons.Filled.Bolt
                            }
                            AppleCard(
                                cornerRadius = 20.dp,
                                onClick = { selectedSmartPlaylist = smartType },
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 5.dp)
                                    .testTag("smart_playlist_${smartType.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = colors.accent.copy(alpha = 0.16f),
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = smartIcon,
                                                contentDescription = smartType.titleVi,
                                                tint = colors.accent
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = smartType.titleVi,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = colors.textPrimary
                                        )
                                        Text(
                                            text = "${smartTracks.size} bài • ${smartType.descriptionVi}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.textSecondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (smartTracks.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.playTrack(smartTracks.first(), smartTracks) }) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = "Phát tất cả", tint = colors.accent)
                                        }
                                    }
                                }
                            }
                        }
                        if (playlists.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Playlist của bạn",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
                                )
                            }
                        }
                        items(playlists, key = { it.id }) { pl ->
                            AppleCard(
                                cornerRadius = 18.dp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ArtworkImage(pl.artworkUri, pl.name, Modifier.size(50.dp), 12.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pl.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                        Text("${pl.trackCount} bài • ${pl.description}", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }

                    LibraryTabOption.FAVORITES -> {
                        items(favorites, key = { "fav_${it.id}" }) { track ->
                            TrackRowItem(
                                track = track,
                                isCurrent = playback.currentTrack?.id == track.id,
                                onPlay = { viewModel.playTrack(track, favorites) },
                                onToggleFavorite = { viewModel.toggleFavorite(track) },
                                onDownload = { viewModel.downloadTrack(track) },
                                onAddToQueue = { viewModel.addToQueueNext(track) },
                                onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) }
                            )
                        }
                    }

                    LibraryTabOption.DOWNLOADED -> {
                        items(downloaded, key = { "dl_${it.id}" }) { track ->
                            TrackRowItem(
                                track = track,
                                isCurrent = playback.currentTrack?.id == track.id,
                                onPlay = { viewModel.playTrack(track, downloaded) },
                                onToggleFavorite = { viewModel.toggleFavorite(track) },
                                onDownload = { viewModel.downloadTrack(track) },
                                onAddToQueue = { viewModel.addToQueueNext(track) },
                                onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) }
                            )
                        }
                    }

                    LibraryTabOption.RECENT -> {
                        items(recent, key = { "rec_${it.id}" }) { track ->
                            TrackRowItem(
                                track = track,
                                isCurrent = playback.currentTrack?.id == track.id,
                                onPlay = { viewModel.playTrack(track, recent) },
                                onToggleFavorite = { viewModel.toggleFavorite(track) },
                                onDownload = { viewModel.downloadTrack(track) },
                                onAddToQueue = { viewModel.addToQueueNext(track) },
                                onDeleteLocal = { viewModel.deleteLocalTrackOnly(track) }
                            )
                        }
                    }

                    LibraryTabOption.DUPLICATES -> {
                        if (duplicates.isEmpty()) {
                            item {
                                AppleCard(
                                    cornerRadius = 20.dp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = colors.accent)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            "Không phát hiện bài hát trùng lặp nào. Các bản Remix/Live khác nhau được giữ riêng biệt.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.textSecondary
                                        )
                                    }
                                }
                            }
                        } else {
                            items(duplicates, key = { it.groupId }) { group ->
                                AppleCard(
                                    cornerRadius = 20.dp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(group.reason, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                        group.tracks.forEach { t ->
                                            val isRecommended = t.id == group.recommendedKeepId
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        "${t.title} (${t.format.extension.uppercase()} • ${t.bitrateKbps} kbps)",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = colors.textPrimary,
                                                        fontWeight = if (isRecommended) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                    Text(
                                                        "${t.source.labelVi} • ${t.fileSizeBytes / 1024} KB",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = colors.textSecondary
                                                    )
                                                }
                                                TextButton(onClick = { viewModel.resolveDuplicateGroup(group, t.id) }) {
                                                    Text(if (isRecommended) "Giữ bản tốt nhất" else "Giữ bản này")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingGenreTrack != null) {
        val targetTrack = editingGenreTrack!!
        AlertDialog(
            onDismissRequest = { editingGenreTrack = null },
            title = { Text("Chọn thể loại cho '${targetTrack.title}'") },
            text = {
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(GenreType.entries) { g ->
                        Text(
                            text = "${g.displayNameVi} (EQ: ${g.defaultEqPreset.displayNameVi})",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateTrackGenre(targetTrack, g)
                                    editingGenreTrack = null
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            color = if (targetTrack.genre == g) colors.accent else colors.textPrimary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingGenreTrack = null }) { Text("Đóng") }
            }
        )
    }

    if (showCreatePlaylistDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("Tạo Playlist mới") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Tên Playlist") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createPlaylist(newName)
                    showCreatePlaylistDialog = false
                }) { Text("Tạo") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("Hủy") }
            }
        )
    }
}

@Composable
private fun EmptySourceCard(
    source: MusicSource,
    hasAllFilesAccess: Boolean,
    onOpenSearch: () -> Unit,
    onGrantAllFilesAccess: () -> Unit,
    onScanOffline: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    AppleCard(
        cornerRadius = 22.dp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = when (source) {
                    MusicSource.TELEGRAM -> "Chưa có bài hát từ Telegram"
                    MusicSource.YOUTUBE_MUSIC -> "Chưa có bài hát YouTube Music đã lưu"
                    MusicSource.OFFLINE -> "Chưa lập chỉ mục nhạc Offline"
                },
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
            Text(
                text = when (source) {
                    MusicSource.TELEGRAM -> "Mở Cài đặt > Nguồn nhạc để đăng nhập Telegram, chọn kênh/nhóm lấy nhạc và nhấn Đồng bộ ngay."
                    MusicSource.YOUTUBE_MUSIC -> "Chuyển sang Tìm kiếm để tìm bài hát, remix, cover, live, lofi trên YouTube và tải về /sdcard/Music Player/YouTube/."
                    MusicSource.OFFLINE -> "Cấp quyền đọc âm thanh để lập chỉ mục nhạc lưu trữ trên máy, /sdcard/Music Player/YouTube/ và /sdcard/Music/Musicplayer/."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
            if (source != MusicSource.TELEGRAM) {
                Button(
                    onClick = when (source) {
                        MusicSource.YOUTUBE_MUSIC -> onOpenSearch
                        MusicSource.OFFLINE -> onScanOffline
                        MusicSource.TELEGRAM -> ({})
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        when (source) {
                            MusicSource.YOUTUBE_MUSIC -> "Tìm trên YouTube Music"
                            MusicSource.OFFLINE -> "Quét nhạc Offline ngay"
                            MusicSource.TELEGRAM -> ""
                        }
                    )
                }
            }
            if (source == MusicSource.OFFLINE && !hasAllFilesAccess) {
                OutlinedButton(
                    onClick = onGrantAllFilesAccess,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Cấp quyền All Files (/sdcard/Music/Musicplayer/)")
                }
            }
        }
    }
}
