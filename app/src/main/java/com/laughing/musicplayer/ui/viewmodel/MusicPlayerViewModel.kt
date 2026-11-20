package com.laughing.musicplayer.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import com.laughing.musicplayer.BuildConfig
import com.laughing.musicplayer.MusicPlayerApplication
import com.laughing.musicplayer.audio.CastUiState
import com.laughing.musicplayer.audio.PlaybackProgress
import com.laughing.musicplayer.audio.PlaybackState
import com.laughing.musicplayer.core.model.ActivityLogItem
import com.laughing.musicplayer.core.model.AlbumGroup
import com.laughing.musicplayer.core.model.ArtistGroup
import com.laughing.musicplayer.core.model.CrossfadeOption
import com.laughing.musicplayer.core.model.DownloadItem
import com.laughing.musicplayer.core.model.DuplicateGroup
import com.laughing.musicplayer.core.model.EqPreset
import com.laughing.musicplayer.core.model.EqState
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.PlaylistItem
import com.laughing.musicplayer.core.model.ProxyConfig
import com.laughing.musicplayer.core.model.SmartPlaylistType
import com.laughing.musicplayer.core.model.TelegramChatItem
import com.laughing.musicplayer.core.model.TelegramTopicItem
import com.laughing.musicplayer.core.model.ThemeMode
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.model.UnifiedSearchTab
import com.laughing.musicplayer.core.util.DuplicateDetector
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.data.local.UserPreferences
import com.laughing.musicplayer.data.storage.AudioTagEditRequest
import com.laughing.musicplayer.data.storage.AudioTagEditResult
import com.laughing.musicplayer.data.storage.OfflineScanMode
import com.laughing.musicplayer.data.storage.TrackMetadataInspector
import com.laughing.musicplayer.data.storage.TrackTechnicalInfo
import com.laughing.musicplayer.data.telegram.TelegramAuthUiState
import com.laughing.musicplayer.data.telegram.TelegramFileResolver
import com.laughing.musicplayer.data.youtube.YouTubeCollectionResult
import com.laughing.musicplayer.data.youtube.YouTubeExploreMood
import com.laughing.musicplayer.data.youtube.YouTubeSearchTab
import com.laughing.musicplayer.lyrics.LyricsResult
import com.laughing.musicplayer.ui.theme.ArtworkEffectsCache
import com.laughing.musicplayer.ui.theme.LiquidGlassPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class YouTubeUiState(
    val query: String = "",
    val activeTab: YouTubeSearchTab = YouTubeSearchTab.VIDEOS,
    val isSearching: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val collections: List<YouTubeCollectionResult> = emptyList(),
    val errorMessage: String? = null
)

data class UnifiedSearchUiState(
    val query: String = "",
    val activeTab: UnifiedSearchTab = UnifiedSearchTab.ALL,
    val youtubeSubTab: YouTubeSearchTab = YouTubeSearchTab.VIDEOS,
    val offlineResults: List<Track> = emptyList(),
    val telegramResults: List<Track> = emptyList(),
    val youtubeTracks: List<Track> = emptyList(),
    val youtubeCollections: List<YouTubeCollectionResult> = emptyList(),
    val isSearchingYouTube: Boolean = false,
    val youtubeError: String? = null
)

data class YouTubeExploreUiState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val trendingTracks: List<Track> = emptyList(),
    val chartTracks: List<Track> = emptyList(),
    val featuredPlaylists: List<YouTubeCollectionResult> = emptyList(),
    val newReleases: List<YouTubeCollectionResult> = emptyList(),
    val errorMessage: String? = null,
    val selectedMood: YouTubeExploreMood? = null,
    val isLoadingMood: Boolean = false,
    val moodTracks: List<Track> = emptyList(),
    val moodPlaylists: List<YouTubeCollectionResult> = emptyList(),
    val moodError: String? = null
)

data class YouTubePlaylistDetailUiState(
    val collection: YouTubeCollectionResult? = null,
    val isLoading: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val errorMessage: String? = null
)

data class LyricsUiState(
    val isLoading: Boolean = false,
    val result: LyricsResult? = null,
    val offsetMs: Long = 0L,
    val showRomanization: Boolean = true,
    val showTranslation: Boolean = false,
    val searchResults: List<LyricsResult> = emptyList()
)

data class OfflineScanUiState(
    val isScanning: Boolean = false,
    val mode: OfflineScanMode? = null,
    val scannedCount: Int = 0,
    val currentTitle: String = "",
    val lastScannedCount: Int? = null,
    val lastSummaryMessage: String? = null
)

@androidx.annotation.OptIn(UnstableApi::class)
class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MusicPlayerApplication).container
    private val db = container.database
    private val trackDao = db.trackDao()
    private val downloadCacheDao = db.downloadCacheDao()

    val preferences: StateFlow<UserPreferences> = container.settingsDataStore.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val isYouTubeLoggedIn: StateFlow<Boolean> = preferences
        .map { it.isYouTubeLoggedIn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val playbackState: StateFlow<PlaybackState> = container.playerController.playbackState
    val playbackProgress: StateFlow<PlaybackProgress> = container.playerController.playbackProgress

    val allTracks: StateFlow<List<Track>> = trackDao.observeAllTracks()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites: StateFlow<List<Track>> = trackDao.observeFavorites()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTracks: StateFlow<List<Track>> = trackDao.observeRecentTracks()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedTracks: StateFlow<List<Track>> = trackDao.observeDownloadedOrOffline()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<PlaylistItem>> = container.playlistAndSmartMixManager.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notPlayed30DaysTracks: StateFlow<List<Track>> = container.playlistAndSmartMixManager
        .observeSmartPlaylist(SmartPlaylistType.NOT_PLAYED_30_DAYS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mostPlayedThisMonthTracks: StateFlow<List<Track>> = container.playlistAndSmartMixManager
        .observeSmartPlaylist(SmartPlaylistType.MOST_PLAYED_THIS_MONTH)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val highEnergyTracks: StateFlow<List<Track>> = container.playlistAndSmartMixManager
        .observeSmartPlaylist(SmartPlaylistType.HIGH_ENERGY_130_BPM)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val telegramAuthState: StateFlow<TelegramAuthUiState> = container.telegramAuthentication.uiState
    val telegramAccountSlot: StateFlow<Int> = container.telegramAuthentication.activeAccountSlot

    val telegramChats: StateFlow<List<TelegramChatItem>> = container.telegramChatBrowser.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val telegramTopics: StateFlow<List<TelegramTopicItem>> = container.telegramForumTopics.observeAllTopics()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloads: StateFlow<List<DownloadItem>> = downloadCacheDao.observeDownloads()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cacheSizeBytes: StateFlow<Long> = container.cacheManager.cacheSizeBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val activityLogs: StateFlow<List<ActivityLogItem>> = container.activityLogger.recentLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val duplicateGroups: StateFlow<List<DuplicateGroup>> = allTracks
        .map { tracks -> DuplicateDetector.findDuplicates(tracks) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedLibrarySource = MutableStateFlow(MusicSource.TELEGRAM)
    val selectedLibrarySource: StateFlow<MusicSource> = _selectedLibrarySource.asStateFlow()

    val libraryArtists: StateFlow<List<ArtistGroup>> = combine(
        allTracks,
        telegramTopics,
        _selectedLibrarySource
    ) { tracks, topics, src ->
        val filtered = tracks.filter { it.source == src }
        buildArtistGroups(filtered, if (src == MusicSource.TELEGRAM) topics else emptyList())
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val libraryAlbums: StateFlow<List<AlbumGroup>> = combine(
        allTracks,
        _selectedLibrarySource
    ) { tracks, src ->
        buildAlbumGroups(tracks.filter { it.source == src })
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val libraryGenreCounts: StateFlow<Map<GenreType, Int>> = allTracks
        .map { tracks -> tracks.groupingBy { it.genre }.eachCount() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _hasAllFilesAccess = MutableStateFlow(false)
    val hasAllFilesAccess: StateFlow<Boolean> = _hasAllFilesAccess.asStateFlow()

    private val _permissionsOnboardingCompleted = MutableStateFlow(
        container.settingsDataStore.isPermissionsOnboardingCompletedSync()
    )
    val permissionsOnboardingCompleted: StateFlow<Boolean> = _permissionsOnboardingCompleted.asStateFlow()

    private val _liquidGlassPalette = MutableStateFlow(LiquidGlassPalette())
    val liquidGlassPalette: StateFlow<LiquidGlassPalette> = _liquidGlassPalette.asStateFlow()

    private val _liquidGlassArtwork = MutableStateFlow<ImageBitmap?>(null)
    val liquidGlassArtwork: StateFlow<ImageBitmap?> = _liquidGlassArtwork.asStateFlow()

    private val _isScanningTelegram = MutableStateFlow(false)
    val isScanningTelegram: StateFlow<Boolean> = _isScanningTelegram.asStateFlow()

    private val _offlineScanState = MutableStateFlow(OfflineScanUiState())
    val offlineScanState: StateFlow<OfflineScanUiState> = _offlineScanState.asStateFlow()

    private val _isIndexingOffline = MutableStateFlow(false)
    val isIndexingOffline: StateFlow<Boolean> = _isIndexingOffline.asStateFlow()

    private val _youtubeState = MutableStateFlow(YouTubeUiState())
    val youtubeState: StateFlow<YouTubeUiState> = _youtubeState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _searchTab = MutableStateFlow(UnifiedSearchTab.ALL)
    private val _searchYouTubeSubTab = MutableStateFlow(YouTubeSearchTab.VIDEOS)
    private val _manualSearchPulse = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    private val manualPulseCounter = AtomicInteger(0)

    private var exploreJob: Job? = null
    private var moodJob: Job? = null
    private var playlistJob: Job? = null
    private var lyricsLoadJob: Job? = null
    private var lyricsSearchJob: Job? = null
    private var techInfoJob: Job? = null

    @OptIn(FlowPreview::class)
    private val youtubeSearchTrigger: Flow<Pair<String, Int>> = merge(
        _searchQuery.map { it.trim() }.debounce(350L).map { it to manualPulseCounter.get() },
        _manualSearchPulse.map { pulse -> _searchQuery.value.trim() to pulse }
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _youtubeAsyncResults: StateFlow<YouTubeUiState> = combine(
        youtubeSearchTrigger,
        _searchTab,
        _searchYouTubeSubTab
    ) { (query, pulseId), tab, subTab ->
        SearchRequestKey(query = query, tab = tab, subTab = subTab, pulseId = pulseId)
    }
        .distinctUntilChanged()
        .flatMapLatest { req ->
            val shouldQueryYouTube = req.query.length >= 2 &&
                (req.tab == UnifiedSearchTab.ALL || req.tab == UnifiedSearchTab.YOUTUBE_MUSIC)
            if (!shouldQueryYouTube) {
                flow { emit(YouTubeUiState(query = req.query, activeTab = req.subTab)) }
            } else {
                flow {
                    emit(YouTubeUiState(query = req.query, activeTab = req.subTab, isSearching = true))
                    val result = container.youTubeMusicRepository.search(req.query, req.subTab)
                    result.onSuccess { res ->
                        emit(
                            YouTubeUiState(
                                query = req.query,
                                activeTab = req.subTab,
                                isSearching = false,
                                tracks = res.tracks,
                                collections = res.collections
                            )
                        )
                    }.onFailure { err ->
                        emit(
                            YouTubeUiState(
                                query = req.query,
                                activeTab = req.subTab,
                                isSearching = false,
                                errorMessage = err.message ?: "Lỗi tìm kiếm YouTube Music"
                            )
                        )
                    }
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), YouTubeUiState())

    private data class SearchRequestKey(
        val query: String,
        val tab: UnifiedSearchTab,
        val subTab: YouTubeSearchTab,
        val pulseId: Int
    )

    val unifiedSearchState: StateFlow<UnifiedSearchUiState> = combine(
        _searchQuery,
        _searchTab,
        _searchYouTubeSubTab,
        allTracks,
        _youtubeAsyncResults
    ) { query, tab, ytSubTab, tracks, ytRes ->
        val trimmed = query.trim()
        val liveById = tracks.associateBy { it.id }
        val offline = tracks.filter { t ->
            t.source == MusicSource.OFFLINE &&
                (trimmed.isEmpty() || matchesSearchQuery(t, trimmed))
        }
        val telegram = tracks.filter { t ->
            t.source == MusicSource.TELEGRAM &&
                (trimmed.isEmpty() || matchesSearchQuery(t, trimmed))
        }
        UnifiedSearchUiState(
            query = query,
            activeTab = tab,
            youtubeSubTab = ytSubTab,
            offlineResults = offline,
            telegramResults = telegram,
            youtubeTracks = ytRes.tracks.map { liveById[it.id] ?: it },
            youtubeCollections = ytRes.collections,
            isSearchingYouTube = ytRes.isSearching,
            youtubeError = ytRes.errorMessage
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UnifiedSearchUiState())

    private val _exploreRawState = MutableStateFlow(YouTubeExploreUiState())
    val exploreState: StateFlow<YouTubeExploreUiState> = combine(
        _exploreRawState,
        allTracks
    ) { raw, tracks ->
        val liveById = tracks.associateBy { it.id }
        raw.copy(
            trendingTracks = raw.trendingTracks.map { liveById[it.id] ?: it },
            chartTracks = raw.chartTracks.map { liveById[it.id] ?: it },
            moodTracks = raw.moodTracks.map { liveById[it.id] ?: it }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), YouTubeExploreUiState())

    private val _playlistRawState = MutableStateFlow(YouTubePlaylistDetailUiState())
    val playlistDetailState: StateFlow<YouTubePlaylistDetailUiState> = combine(
        _playlistRawState,
        allTracks
    ) { raw, tracks ->
        val liveById = tracks.associateBy { it.id }
        raw.copy(tracks = raw.tracks.map { liveById[it.id] ?: it })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), YouTubePlaylistDetailUiState())

    private val _lyricsState = MutableStateFlow(LyricsUiState())
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    private val _toastChannel = Channel<String>(Channel.BUFFERED)
    val toastEvents: Flow<String> = _toastChannel.receiveAsFlow()

    private val _proxyTestResult = MutableStateFlow<String?>(null)
    val proxyTestResult: StateFlow<String?> = _proxyTestResult.asStateFlow()

    val castUiState: StateFlow<CastUiState> = container.castPlaybackCoordinator.uiState

    private val _sleepCalmLockEnabled = MutableStateFlow(false)
    val sleepCalmLockEnabled: StateFlow<Boolean> = _sleepCalmLockEnabled.asStateFlow()

    private val _trackTechnicalInfo = MutableStateFlow<TrackTechnicalInfo?>(null)
    val trackTechnicalInfo: StateFlow<TrackTechnicalInfo?> = _trackTechnicalInfo.asStateFlow()

    private val _isSavingTrackTags = MutableStateFlow(false)
    val isSavingTrackTags: StateFlow<Boolean> = _isSavingTrackTags.asStateFlow()

    init {
        viewModelScope.launch {
            playbackState
                .map { it.currentTrack }
                .distinctUntilChanged { a, b -> a?.id == b?.id && a?.artworkUri == b?.artworkUri }
                .collectLatest { track ->
                    if (track != null) {
                        val visuals = ArtworkEffectsCache.getOrCompute(
                            context = getApplication(),
                            uriString = track.artworkUri,
                            httpClient = container.networkProxyManager.getHttpClient()
                        )
                        _liquidGlassPalette.value = visuals.palette
                        _liquidGlassArtwork.value = visuals.blurredBitmap
                        loadLyricsForTrack(track)
                    } else {
                        _liquidGlassArtwork.value = null
                    }
                }
        }
        viewModelScope.launch {
            _youtubeAsyncResults.collect { ytRes ->
                _youtubeState.value = ytRes
            }
        }
        viewModelScope.launch {
            preferences.map { it.autoSyncTelegram }.distinctUntilChanged().collect { enabled ->
                container.telegramMusicRepository.scheduleBackgroundSync(enabled)
            }
        }
        viewModelScope.launch {
            preferences.map { it.proxyConfig }.distinctUntilChanged().collect { proxy ->
                container.telegramCore.applyProxy(proxy)
                container.networkProxyManager.refreshHttpClient()
            }
        }
        viewModelScope.launch {
            playbackState
                .map { it.errorMessage }
                .distinctUntilChanged()
                .collect { msg ->
                    if (!msg.isNullOrBlank()) {
                        postToast(msg)
                        container.playerController.clearErrorMessage()
                    }
                }
        }
    }

    private fun postToast(message: String) {
        _statusToast.value = message
        _toastChannel.trySend(message)
    }

    fun clearToast() {
        _statusToast.value = null
    }

    fun onFirstFrameRendered() {
        container.onFirstFrameRendered()
        refreshStoragePermissions()
    }

    fun refreshStoragePermissions() {
        viewModelScope.launch(Dispatchers.IO) {
            _hasAllFilesAccess.value = container.storageManager.hasAllFilesAccessPermission()
        }
    }

    fun ensureTelegramAuthStarted() {
        container.telegramAuthentication.ensureStarted()
    }

    fun observeTopicTracks(chatId: Long, threadId: Long): kotlinx.coroutines.flow.Flow<List<Track>> {
        return trackDao.observeTracksForTopic(chatId, threadId).map { list -> list.map { it.toDomain() } }
    }

    fun clearActivityLogs() {
        viewModelScope.launch {
            container.activityLogger.clear()
            postToast("Đã xóa nhật ký hoạt động")
        }
    }

    fun selectLibrarySource(source: MusicSource) {
        _selectedLibrarySource.value = source
    }

    fun buildArtistGroups(tracks: List<Track>, topics: List<TelegramTopicItem>): List<ArtistGroup> {
        val topicGroups = mutableListOf<ArtistGroup>()
        val nonTopicBuckets = linkedMapOf<String, MutableList<Track>>()

        tracks.forEach { track ->
            if (track.source == MusicSource.TELEGRAM && !track.telegramTopicName.isNullOrBlank() && track.telegramThreadId != null) {
                // Grouped strictly by (chatId, threadId) so Forum Topics appear as distinct Artists
                return@forEach
            }
            val key = track.artist.ifBlank { "Nghệ sĩ không rõ" }
            nonTopicBuckets.getOrPut(key) { mutableListOf() }.add(track)
        }

        val telegramTopicTracks = tracks.filter {
            it.source == MusicSource.TELEGRAM && !it.telegramTopicName.isNullOrBlank() && it.telegramThreadId != null
        }.groupBy { (it.telegramChatId ?: 0L) to (it.telegramThreadId ?: 0L) }

        telegramTopicTracks.forEach { (keyPair, topicTracks) ->
            val first = topicTracks.first()
            topicGroups += ArtistGroup(
                key = "topic_${keyPair.first}_${keyPair.second}",
                name = first.telegramTopicName ?: first.artist,
                trackCount = topicTracks.size,
                artworkUri = topicTracks.firstNotNullOfOrNull { it.artworkUri },
                isTelegramTopic = true,
                chatId = keyPair.first,
                threadId = keyPair.second,
                chatTitle = first.telegramChatTitle
            )
        }

        // Also include scanned Forum Topics that may have metadata cached in Room
        topics.forEach { topic ->
            val key = "topic_${topic.chatId}_${topic.messageThreadId}"
            if (topicGroups.none { it.key == key } && topic.trackCount > 0) {
                topicGroups += ArtistGroup(
                    key = key,
                    name = topic.name,
                    trackCount = topic.trackCount,
                    artworkUri = topic.iconUrl,
                    isTelegramTopic = true,
                    chatId = topic.chatId,
                    threadId = topic.messageThreadId
                )
            }
        }

        val regularGroups = nonTopicBuckets.map { (artist, list) ->
            ArtistGroup(
                key = "artist_$artist",
                name = artist,
                trackCount = list.size,
                artworkUri = list.firstNotNullOfOrNull { it.artworkUri },
                isTelegramTopic = false
            )
        }

        return (topicGroups + regularGroups).sortedByDescending { it.trackCount }
    }

    fun buildAlbumGroups(tracks: List<Track>): List<AlbumGroup> {
        return tracks.groupBy { it.album.ifBlank { "Đĩa đơn" } }
            .map { (album, list) ->
                AlbumGroup(
                    name = album,
                    artist = list.firstOrNull()?.artist ?: "Nhiều nghệ sĩ",
                    trackCount = list.size,
                    artworkUri = list.firstNotNullOfOrNull { it.artworkUri }
                )
            }
            .sortedByDescending { it.trackCount }
    }

    fun playTrack(track: Track, contextList: List<Track>? = null) {
        val normalizedTrack = normalizeTelegramTrackForPlayback(track)
        if (contextList != null && contextList.isNotEmpty()) {
            val normalizedList = contextList.map { normalizeTelegramTrackForPlayback(it) }
            val idx = normalizedList.indexOfFirst { it.id == normalizedTrack.id }.coerceAtLeast(0)
            container.playerController.playQueue(normalizedList, idx)
        } else {
            container.playerController.playSingleTrack(normalizedTrack)
        }
    }

    internal fun normalizeTelegramTrackForPlayback(track: Track): Track {
        if (track.source != MusicSource.TELEGRAM) return track
        val parsedUriTarget = track.streamUri?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { TelegramFileResolver.parseTargetFromUri(Uri.parse(raw)) }.getOrNull()
        }
        val fileId = track.telegramFileId?.takeIf { it > 0 } ?: parsedUriTarget?.fileId ?: 0
        val chatId = track.telegramChatId?.takeIf { it != 0L } ?: parsedUriTarget?.chatId
        val messageId = track.telegramMessageId?.takeIf { it != 0L } ?: parsedUriTarget?.messageId
        val totalFileSize = maxOf(track.fileSizeBytes, parsedUriTarget?.expectedSize ?: 0L)
        val normalizedUri = if (fileId > 0) {
            TelegramFileResolver.buildStreamUri(
                fileId = fileId,
                fileSize = totalFileSize,
                chatId = chatId,
                messageId = messageId,
                remoteFileId = track.telegramRemoteFileId,
                trackId = track.id
            )
        } else {
            track.streamUri
        }
        return track.copy(
            streamUri = normalizedUri,
            telegramFileId = fileId.takeIf { it > 0 } ?: track.telegramFileId,
            telegramChatId = chatId,
            telegramMessageId = messageId,
            fileSizeBytes = totalFileSize
        )
    }

    fun togglePlayPause() = container.playerController.togglePlayPause()
    fun seekTo(ms: Long) = container.playerController.seekTo(ms)
    fun skipNext() = container.playerController.skipToNext()
    fun skipPrevious() = container.playerController.skipToPrevious()
    fun toggleShuffle() = container.playerController.toggleShuffle()
    fun cycleRepeat() = container.playerController.cycleRepeatMode()
    fun addToQueueNext(track: Track) {
        container.playerController.addToQueueNext(track)
        postToast("Đã thêm '${track.title}' vào phát tiếp theo")
    }

    fun playTrackByIdFromWidget(trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val track = trackDao.getTrackById(trackId)?.toDomain() ?: return@launch
            val currentQueue = playbackState.value.queue
            val targetList = if (currentQueue.any { it.id == trackId }) currentQueue else listOf(track)
            withContext(Dispatchers.Main) {
                playTrack(track, targetList)
            }
        }
    }

    fun setSleepTimer(minutes: Int) = setSleepTimerMinutes(minutes)

    fun setSleepTimerMinutes(minutes: Int) {
        container.playerController.setSleepTimerMinutes(minutes)
        postToast(if (minutes > 0) "Đã hẹn giờ tắt sau $minutes phút" else "Đã tắt hẹn giờ")
    }

    fun setSleepTimerEndOfTrack() {
        container.playerController.setSleepTimerEndOfTrack()
        postToast("Sẽ dừng phát sau bài hát hiện tại")
    }

    fun setSleepTimerAfterTracks(count: Int) {
        container.playerController.setSleepTimerAfterTracks(count)
        postToast(if (count > 0) "Sẽ dừng phát sau $count bài tiếp theo" else "Đã tắt hẹn giờ")
    }

    fun cancelSleepTimer() {
        container.playerController.cancelSleepTimer()
        postToast("Đã hủy hẹn giờ tắt nhạc")
    }

    fun setSleepFadeOutEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsDataStore.setSleepFadeOutEnabled(enabled)
            postToast(
                if (enabled) {
                    "Đã bật giảm dần âm lượng 30 giây khi hẹn tắt"
                } else {
                    "Đã tắt giảm dần âm lượng khi hẹn giờ"
                }
            )
        }
    }

    fun setSleepCalmLockEnabled(enabled: Boolean) {
        _sleepCalmLockEnabled.value = enabled
        postToast(
            if (enabled) {
                "Đã bật khóa thao tác nhầm — giao diện dịu mắt"
            } else {
                "Đã mở khóa màn hình Đang phát"
            }
        )
    }

    fun startCastDiscovery() {
        container.castPlaybackCoordinator.startDiscovery()
    }

    fun stopCastDiscovery() {
        container.castPlaybackCoordinator.stopDiscovery()
    }

    fun refreshCastAndAudioOutputs() {
        container.castPlaybackCoordinator.refreshRoutes()
        container.castPlaybackCoordinator.refreshAudioOutputs()
        val castState = container.castPlaybackCoordinator.uiState.value
        if (castState.connectedDeviceName == null &&
            (!castState.isCastFrameworkAvailable || castState.castRoutes.isEmpty())
        ) {
            postToast(
                castState.statusNotice
                    ?: "Không tìm thấy thiết bị Chromecast — hãy chọn Bluetooth hoặc đầu ra âm thanh."
            )
        }
    }

    fun selectCastRoute(routeId: String) {
        postToast(container.castPlaybackCoordinator.selectCastRoute(routeId))
    }

    fun disconnectCast() {
        postToast(container.castPlaybackCoordinator.disconnectCast())
    }

    fun openSystemAudioOutput(callerContext: Context) {
        val opened = container.castPlaybackCoordinator.openSystemAudioOutputSwitcher(callerContext)
        if (!opened) {
            postToast("Không thể mở bộ chọn đầu ra âm thanh trên thiết bị này")
        }
    }

    fun openBluetoothSettings(callerContext: Context) {
        val opened = container.castPlaybackCoordinator.openBluetoothSettings(callerContext)
        if (!opened) {
            postToast("Không thể mở cài đặt Bluetooth trên thiết bị này")
        }
    }

    fun inspectTrackTechnicalInfo(track: Track) {
        techInfoJob?.cancel()
        techInfoJob = viewModelScope.launch {
            _trackTechnicalInfo.value = TrackMetadataInspector.inspect(getApplication(), track)
        }
    }

    fun saveOfflineTrackTags(
        track: Track,
        request: AudioTagEditRequest,
        onCompleted: (AudioTagEditResult) -> Unit
    ) {
        viewModelScope.launch {
            _isSavingTrackTags.value = true
            val result = container.audioTagEditor.updateOfflineTrackTags(track, request)
            _isSavingTrackTags.value = false
            when (result) {
                is AudioTagEditResult.Success -> {
                    container.playerController.refreshTrackMetadata(result.updatedTrack)
                    _trackTechnicalInfo.value = TrackMetadataInspector.inspect(getApplication(), result.updatedTrack)
                    postToast(result.messageVi)
                }
                is AudioTagEditResult.NotOfflineTrack -> {
                    postToast(result.messageVi)
                }
                is AudioTagEditResult.Error -> {
                    postToast(result.messageVi)
                }
            }
            onCompleted(result)
        }
    }

    fun postStatusMessage(message: String) {
        postToast(message)
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.setFavorite(track.id, !track.isFavorite)
        }
    }

    fun updateTrackGenre(track: Track, genre: GenreType) {
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.updateGenre(track.id, genre.name)
            postToast("Đã đổi thể loại thành ${genre.displayNameVi}")
        }
    }

    // Download & Local Management
    fun downloadTrack(track: Track) {
        when (track.source) {
            MusicSource.TELEGRAM -> container.telegramMusicRepository.startOfflineDownload(track)
            MusicSource.YOUTUBE_MUSIC -> container.youTubeDownloadManager.downloadTrack(track)
            MusicSource.OFFLINE -> Unit
        }
    }

    fun pauseDownload(track: Track) {
        if (track.source == MusicSource.TELEGRAM) {
            container.telegramMusicRepository.pauseDownload(track)
        }
    }

    fun cancelDownload(track: Track) {
        when (track.source) {
            MusicSource.TELEGRAM -> container.telegramMusicRepository.cancelDownload(track)
            MusicSource.YOUTUBE_MUSIC -> container.youTubeDownloadManager.cancelDownload(track)
            MusicSource.OFFLINE -> Unit
        }
    }

    fun retryDownload(item: DownloadItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val track = trackDao.getTrackById(item.trackId)?.toDomain() ?: return@launch
            downloadTrack(track)
        }
    }

    fun deleteLocalTrackOnly(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            when (track.source) {
                MusicSource.TELEGRAM -> container.telegramMusicRepository.deleteLocalCopyOnly(track)
                MusicSource.YOUTUBE_MUSIC -> container.youTubeDownloadManager.deleteLocalCopy(track)
                MusicSource.OFFLINE -> {
                    container.storageManager.deleteLocalFile(track.localPath)
                    trackDao.deleteTrackById(track.id)
                }
            }
            postToast("Đã xóa bản lưu cục bộ (không ảnh hưởng Telegram)")
        }
    }

    fun resolveDuplicateGroup(group: DuplicateGroup, keepTrackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            group.tracks.filter { it.id != keepTrackId }.forEach { duplicate ->
                if (duplicate.source == MusicSource.TELEGRAM) {
                    container.telegramMusicRepository.deleteLocalCopyOnly(duplicate)
                } else {
                    container.storageManager.deleteLocalFile(duplicate.localPath)
                    trackDao.deleteTrackById(duplicate.id)
                }
            }
            postToast("Đã giữ bản chất lượng tốt nhất và dọn bản trùng lặp cục bộ")
        }
    }

    fun hasAudioReadPermission(): Boolean = container.storageManager.hasAudioReadPermission()
    fun hasAllFilesAccessPermission(): Boolean = container.storageManager.hasAllFilesAccessPermission()
    fun buildAllFilesAccessIntent(): Intent = container.storageManager.buildManageAllFilesAccessIntent()
    fun getUnifiedDownloadDisplayPath(): String = container.storageManager.getDisplayRootPath()

    fun refreshPermissionStates() {
        _hasAllFilesAccess.value = container.storageManager.hasAllFilesAccessPermission()
    }

    fun markPermissionsOnboardingCompleted() {
        container.settingsDataStore.markPermissionsOnboardingCompletedSync(true)
        _permissionsOnboardingCompleted.value = true
        viewModelScope.launch {
            container.settingsDataStore.setPermissionsOnboardingCompleted(true)
        }
    }

    // Offline / Local Music Scanner
    fun indexOfflineLibrary() = scanEntireDevice()

    fun scanEntireDevice() {
        if (_offlineScanState.value.isScanning) return
        viewModelScope.launch {
            _isIndexingOffline.value = true
            _offlineScanState.value = _offlineScanState.value.copy(
                isScanning = true,
                mode = OfflineScanMode.ENTIRE_DEVICE,
                scannedCount = 0,
                currentTitle = ""
            )
            val count = runCatching {
                container.offlineMusicRepository.scanEntireDevice { progress ->
                    _offlineScanState.value = _offlineScanState.value.copy(
                        scannedCount = progress.scannedCount,
                        currentTitle = progress.currentTitle
                    )
                }
            }.getOrElse { 0 }
            val summary = "Đã quét xong: tìm thấy $count bài hát"
            _offlineScanState.value = OfflineScanUiState(
                isScanning = false,
                mode = OfflineScanMode.ENTIRE_DEVICE,
                scannedCount = count,
                currentTitle = "",
                lastScannedCount = count,
                lastSummaryMessage = summary
            )
            _isIndexingOffline.value = false
            postToast(summary)
        }
    }

    fun scanSelectedFolder(treeUri: Uri) {
        if (_offlineScanState.value.isScanning) return
        viewModelScope.launch {
            _isIndexingOffline.value = true
            _offlineScanState.value = _offlineScanState.value.copy(
                isScanning = true,
                mode = OfflineScanMode.SELECTED_FOLDER,
                scannedCount = 0,
                currentTitle = ""
            )
            val count = runCatching {
                container.offlineMusicRepository.scanSelectedFolder(treeUri) { progress ->
                    _offlineScanState.value = _offlineScanState.value.copy(
                        scannedCount = progress.scannedCount,
                        currentTitle = progress.currentTitle
                    )
                }
            }.getOrElse { 0 }
            val summary = "Đã quét xong: tìm thấy $count bài hát"
            _offlineScanState.value = OfflineScanUiState(
                isScanning = false,
                mode = OfflineScanMode.SELECTED_FOLDER,
                scannedCount = count,
                currentTitle = "",
                lastScannedCount = count,
                lastSummaryMessage = summary
            )
            _isIndexingOffline.value = false
            postToast(summary)
        }
    }

    // Telegram Auth & Sources
    fun submitTelegramApiCredentials(apiId: String, apiHash: String): String? {
        val err = container.telegramAuthentication.submitApiCredentials(apiId, apiHash)
        if (err == null) {
            postToast("Đã lưu thông số API Telegram")
        }
        return err
    }

    fun submitTelegramPhone(phone: String) {
        viewModelScope.launch { container.telegramAuthentication.submitPhoneNumber(phone) }
    }

    fun submitTelegramOtp(code: String) {
        viewModelScope.launch { container.telegramAuthentication.submitOtpCode(code) }
    }

    fun resendTelegramOtp() {
        viewModelScope.launch {
            container.telegramAuthentication.resendOtpCode()
            postToast("Đã gửi lại yêu cầu mã xác thực Telegram")
        }
    }

    fun changeTelegramPhoneNumber() {
        viewModelScope.launch { container.telegramAuthentication.changePhoneNumber() }
    }

    fun submitTelegram2FA(password: String) {
        viewModelScope.launch { container.telegramAuthentication.submitTwoFactorPassword(password) }
    }

    fun logoutTelegram() {
        viewModelScope.launch { container.telegramAuthentication.logout() }
    }

    fun switchTelegramAccount(slot: Int) {
        viewModelScope.launch { container.telegramAuthentication.switchAccount(slot) }
    }

    fun dismissTelegramError() = container.telegramAuthentication.dismissError()

    fun refreshTelegramChats() {
        viewModelScope.launch {
            _isScanningTelegram.value = true
            container.telegramChatBrowser.refreshChatList()
            _isScanningTelegram.value = false
        }
    }

    fun toggleTelegramChat(chatId: Long, selected: Boolean) {
        viewModelScope.launch { container.telegramChatBrowser.toggleChatSelection(chatId, selected) }
    }

    fun selectAllTelegramChats() {
        viewModelScope.launch { container.telegramChatBrowser.selectAll() }
    }

    fun deselectAllTelegramChats() {
        viewModelScope.launch { container.telegramChatBrowser.deselectAll() }
    }

    fun scanSelectedTelegramSources(fullRescan: Boolean = false) {
        viewModelScope.launch {
            _isScanningTelegram.value = true
            val count = container.telegramMusicRepository.syncSelectedSources(fullRescan)
            _isScanningTelegram.value = false
            postToast("Đã đồng bộ $count bản nhạc từ Telegram")
        }
    }

    fun scanSingleTelegramChat(chatId: Long) {
        viewModelScope.launch {
            _isScanningTelegram.value = true
            val count = container.telegramMusicRepository.scanSingleChat(chatId)
            _isScanningTelegram.value = false
            postToast("Đã quét $count bài nhạc từ hội thoại")
        }
    }

    // Unified Search & YouTube Music
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _youtubeState.value = _youtubeState.value.copy(query = query)
    }

    fun selectSearchTab(tab: UnifiedSearchTab) {
        _searchTab.value = tab
    }

    fun selectSearchYouTubeSubTab(tab: YouTubeSearchTab) {
        _searchYouTubeSubTab.value = tab
        _youtubeState.value = _youtubeState.value.copy(activeTab = tab)
        if (_searchQuery.value.trim().length >= 2) {
            _manualSearchPulse.tryEmit(manualPulseCounter.incrementAndGet())
        }
    }

    fun submitUnifiedSearch() {
        if (_searchQuery.value.trim().length >= 2) {
            _manualSearchPulse.tryEmit(manualPulseCounter.incrementAndGet())
        }
    }

    fun updateYouTubeQuery(query: String) = updateSearchQuery(query)

    fun selectYouTubeTab(tab: YouTubeSearchTab) {
        selectSearchYouTubeSubTab(tab)
    }

    // YouTube WebView Cookie Auth
    suspend fun onYouTubeWebViewCookiesDetected(cookieManager: CookieManager): Boolean {
        val captured = container.youTubeCookieManager.captureCookiesFromWebView(cookieManager)
        if (captured) {
            container.networkProxyManager.rebuildClients()
            postToast("Đã đăng nhập YouTube Music & lưu cookies.txt")
        }
        return captured
    }

    fun logoutYouTube() {
        viewModelScope.launch {
            container.youTubeCookieManager.clearSession()
            container.networkProxyManager.rebuildClients()
            postToast("Đã đăng xuất YouTube Music")
        }
    }

    fun getYouTubeCookiesPath(): String = container.youTubeCookieManager.getCookiesTxtFile().absolutePath

    fun searchYouTubeMusic() {
        submitUnifiedSearch()
    }

    fun loadYouTubeExploreIfNeeded(forceRefresh: Boolean = false) {
        val current = _exploreRawState.value
        if (current.isLoading) return
        if (!forceRefresh && current.hasLoaded &&
            (current.trendingTracks.isNotEmpty() || current.chartTracks.isNotEmpty() || current.featuredPlaylists.isNotEmpty())
        ) {
            return
        }
        exploreJob?.cancel()
        exploreJob = viewModelScope.launch {
            _exploreRawState.value = _exploreRawState.value.copy(
                isLoading = true,
                errorMessage = null
            )
            container.youTubeMusicRepository.fetchExploreFeed()
                .onSuccess { data ->
                    _exploreRawState.value = _exploreRawState.value.copy(
                        isLoading = false,
                        hasLoaded = true,
                        trendingTracks = data.trendingTracks,
                        chartTracks = data.chartTracks,
                        featuredPlaylists = data.featuredPlaylists,
                        newReleases = data.newReleases,
                        errorMessage = null
                    )
                }
                .onFailure { err ->
                    _exploreRawState.value = _exploreRawState.value.copy(
                        isLoading = false,
                        hasLoaded = true,
                        errorMessage = err.message ?: "Không thể tải Khám phá YouTube Music"
                    )
                }
        }
    }

    fun selectExploreMood(mood: YouTubeExploreMood?) {
        moodJob?.cancel()
        if (mood == null || _exploreRawState.value.selectedMood == mood) {
            _exploreRawState.value = _exploreRawState.value.copy(
                selectedMood = null,
                isLoadingMood = false,
                moodTracks = emptyList(),
                moodPlaylists = emptyList(),
                moodError = null
            )
            return
        }
        moodJob = viewModelScope.launch {
            _exploreRawState.value = _exploreRawState.value.copy(
                selectedMood = mood,
                isLoadingMood = true,
                moodTracks = emptyList(),
                moodPlaylists = emptyList(),
                moodError = null
            )
            container.youTubeMusicRepository.fetchMoodExplore(mood)
                .onSuccess { res ->
                    if (_exploreRawState.value.selectedMood == mood) {
                        _exploreRawState.value = _exploreRawState.value.copy(
                            isLoadingMood = false,
                            moodTracks = res.tracks,
                            moodPlaylists = res.playlists,
                            moodError = null
                        )
                    }
                }
                .onFailure { err ->
                    if (_exploreRawState.value.selectedMood == mood) {
                        _exploreRawState.value = _exploreRawState.value.copy(
                            isLoadingMood = false,
                            moodError = err.message ?: "Không thể tải danh sách ${mood.labelVi}"
                        )
                    }
                }
        }
    }

    fun openYouTubePlaylist(collection: YouTubeCollectionResult) {
        playlistJob?.cancel()
        playlistJob = viewModelScope.launch {
            _playlistRawState.value = YouTubePlaylistDetailUiState(
                collection = collection,
                isLoading = true,
                tracks = emptyList(),
                errorMessage = null
            )
            container.youTubeMusicRepository.fetchPlaylistTracks(collection)
                .onSuccess { tracks ->
                    _playlistRawState.value = _playlistRawState.value.copy(
                        isLoading = false,
                        tracks = tracks,
                        errorMessage = null
                    )
                }
                .onFailure { err ->
                    _playlistRawState.value = _playlistRawState.value.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Không thể tải danh sách phát"
                    )
                }
        }
    }

    fun downloadPlaylistTracks(tracks: List<Track>) {
        val count = container.youTubeDownloadManager.downloadPlaylist(tracks)
        postToast(
            if (count > 0) {
                "Đang tải $count bài hát vào ${container.storageManager.getYouTubeDisplayPath()}"
            } else {
                "Tất cả bài hát trong playlist đã được tải về"
            }
        )
    }

    private fun matchesSearchQuery(track: Track, query: String): Boolean {
        val blob = "${track.title} ${track.artist} ${track.album} ${track.telegramTopicName.orEmpty()} ${track.fileName} ${track.telegramChatTitle.orEmpty()}"
        return VietnameseTextNormalizer.matches(blob, query)
    }

    // Playlists & Smart Mix & M3U
    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            container.playlistAndSmartMixManager.createPlaylist(name)
            postToast("Đã tạo playlist '$name'")
        }
    }

    fun createAndPlaySmartMix(seed: Track? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val (name, tracks) = container.playlistAndSmartMixManager.generateSmartMix(seed)
            if (tracks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    container.playerController.playQueue(tracks, 0)
                    postToast("Đang phát $name (${tracks.size} bài)")
                }
            } else {
                postToast("Thư viện chưa có bài hát để tạo Smart Mix")
            }
        }
    }

    fun importM3u(name: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val matched = container.playlistAndSmartMixManager.importFromM3u(name, content)
            postToast("Đã nhập M3U: $matched bài khớp trong thư viện")
        }
    }

    fun exportAllToM3uIntent(): Intent? {
        val tracks = allTracks.value
        if (tracks.isEmpty()) return null
        val content = container.playlistAndSmartMixManager.exportToM3u("Music Player Library", tracks)
        val outDir = File(getApplication<Application>().filesDir, "exports").apply { mkdirs() }
        val file = File(outDir, "music_player_library.m3u")
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            getApplication(),
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "audio/x-mpegurl"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // Lyrics
    private fun loadLyricsForTrack(track: Track) {
        lyricsLoadJob?.cancel()
        lyricsLoadJob = viewModelScope.launch {
            _lyricsState.value = _lyricsState.value.copy(isLoading = true, result = null)
            val res = container.lyricsEngine.loadLyricsForTrack(track)
            _lyricsState.value = _lyricsState.value.copy(isLoading = false, result = res)
        }
    }

    fun adjustLyricsOffset(deltaMs: Long) {
        _lyricsState.value = _lyricsState.value.copy(
            offsetMs = (_lyricsState.value.offsetMs + deltaMs).coerceIn(-10_000L, 10_000L)
        )
    }

    fun toggleRomanization() {
        _lyricsState.value = _lyricsState.value.copy(showRomanization = !_lyricsState.value.showRomanization)
    }

    fun toggleTranslation() {
        _lyricsState.value = _lyricsState.value.copy(showTranslation = !_lyricsState.value.showTranslation)
    }

    fun searchLyricsManual(query: String) {
        lyricsSearchJob?.cancel()
        lyricsSearchJob = viewModelScope.launch {
            val hits = container.lyricsEngine.searchLyricsManual(query)
            _lyricsState.value = _lyricsState.value.copy(searchResults = hits)
        }
    }

    fun applyManualLyrics(result: LyricsResult) {
        val track = playbackState.value.currentTrack ?: return
        viewModelScope.launch {
            container.lyricsEngine.saveCustomLrc(track.id, result.rawLrc)
            _lyricsState.value = _lyricsState.value.copy(result = result, searchResults = emptyList())
        }
    }

    // Settings
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { container.settingsDataStore.setThemeMode(mode) }
    fun setCrossfade(option: CrossfadeOption) = viewModelScope.launch { container.settingsDataStore.setCrossfade(option) }
    fun setCrossfadeSeconds(seconds: Int) = viewModelScope.launch { container.settingsDataStore.setCrossfadeSeconds(seconds) }
    fun setAutoPlayNext(enabled: Boolean) = viewModelScope.launch { container.settingsDataStore.setAutoPlayNext(enabled) }
    fun setIncludeVoice(enabled: Boolean) = viewModelScope.launch { container.settingsDataStore.setIncludeVoiceMessages(enabled) }
    fun setAutoSync(enabled: Boolean) = viewModelScope.launch { container.settingsDataStore.setAutoSyncTelegram(enabled) }
    fun setPreloadNext(enabled: Boolean) = viewModelScope.launch { container.settingsDataStore.setPreloadNextTrack(enabled) }
    fun setShowLyricsOnLockScreen(enabled: Boolean) = viewModelScope.launch {
        container.settingsDataStore.setShowLyricsOnLockScreen(enabled)
    }
    fun setCacheLimitBytes(bytes: Long) = viewModelScope.launch {
        container.settingsDataStore.setCacheLimitBytes(bytes)
        container.cacheManager.enforceCacheLimit()
    }

    fun setSafTreeUri(uri: Uri?) {
        viewModelScope.launch {
            container.settingsDataStore.setSafTreeUri(uri?.toString())
            postToast("Đã cập nhật thư mục tải nhạc")
        }
    }

    fun clearTemporaryCache() {
        viewModelScope.launch {
            val freed = container.cacheManager.clearTemporaryCache()
            postToast("Đã dọn ${freed / (1024 * 1024)} MB bộ đệm (giữ nguyên bài hát đã lưu)")
        }
    }

    fun updateEqState(eqState: EqState) {
        container.playerController.applyEqImmediate(eqState)
        viewModelScope.launch { container.settingsDataStore.saveEqState(eqState) }
    }

    fun selectEqPreset(preset: EqPreset) {
        val current = preferences.value.eqState
        updateEqState(current.copy(preset = preset, bandGainsDb = preset.gainsDb))
    }

    fun updateProxyConfig(config: ProxyConfig) {
        viewModelScope.launch {
            container.settingsDataStore.saveProxyConfig(config)
            postToast("Đã lưu cấu hình Proxy")
        }
    }

    fun testProxyConnection(config: ProxyConfig) {
        viewModelScope.launch {
            _proxyTestResult.value = "Đang kiểm tra kết nối..."
            container.networkProxyManager.testProxyConnection(config)
                .onSuccess { ms -> _proxyTestResult.value = "Kết nối thành công (${ms} ms)" }
                .onFailure { err -> _proxyTestResult.value = "Thất bại: ${err.message}" }
        }
    }

    fun buildExportLogsIntent(): Intent {
        val content = kotlinx.coroutines.runBlocking { container.activityLogger.exportFormattedLogs() }
        val logDir = File(getApplication<Application>().cacheDir, "logs").apply { mkdirs() }
        val file = File(logDir, "music_player_logs.txt")
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            getApplication(),
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun searchLocalTracks(query: String): List<Track> {
        if (query.isBlank()) return allTracks.value
        return allTracks.value.filter { track ->
            val blob = "${track.title} ${track.artist} ${track.album} ${track.telegramTopicName.orEmpty()} ${track.fileName} ${track.telegramChatTitle.orEmpty()}"
            VietnameseTextNormalizer.matches(blob, query)
        }
    }

    override fun onCleared() {
        container.playerController.release()
        super.onCleared()
    }
}
