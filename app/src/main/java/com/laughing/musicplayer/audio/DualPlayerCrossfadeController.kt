package com.laughing.musicplayer.audio

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.EqState
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.RepeatMode
import com.laughing.musicplayer.core.model.SleepTimerMode
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.ReplayGainExtractor
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.storage.CacheManager
import com.laughing.musicplayer.data.telegram.TelegramAudioDataSource
import com.laughing.musicplayer.data.telegram.TelegramCore
import com.laughing.musicplayer.data.telegram.TelegramFileResolver
import com.laughing.musicplayer.data.youtube.YouTubeMusicRepository
import com.laughing.musicplayer.data.youtube.YouTubeSessionCookieManager
import com.laughing.musicplayer.integrations.NetworkProxyManager
import com.laughing.musicplayer.integrations.PlaylistAndSmartMixManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val sleepTimerRemainingMs: Long = 0L
)

data class PlaybackState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val sleepTimerRemainingMs: Long = 0L,
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    val sleepTimerRemainingTracks: Int = 0,
    val sleepFadeOutEnabled: Boolean = true,
    val sleepFadeOutActive: Boolean = false,
    val effectiveReplayGainDb: Float = 0f,
    val crossfadeActive: Boolean = false,
    val errorMessage: String? = null
)

@UnstableApi
class DualPlayerCrossfadeController(
    private val context: Context,
    private val telegramCore: TelegramCore,
    private val telegramFileResolver: TelegramFileResolver,
    private val cacheManager: CacheManager,
    private val youTubeRepository: YouTubeMusicRepository,
    private val trackDao: TrackDao,
    private val settingsDataStore: SettingsDataStore,
    private val playlistManager: PlaylistAndSmartMixManager,
    private val proxyManager: NetworkProxyManager,
    private val logger: ActivityLogger,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    val eqProcessor = BiquadEqAudioProcessor()

    private val routingDataSourceFactory = object : DataSource.Factory {
        private val defaultFactory = DefaultDataSource.Factory(context)
        private val tgFactory = TelegramAudioDataSource.Factory(
            telegramCore,
            telegramFileResolver,
            cacheManager,
            logger,
            externalScope = ioScope
        )

        override fun createDataSource(): DataSource {
            return object : DataSource {
                private var delegate: DataSource? = null
                private val listeners = mutableListOf<TransferListener>()

                override fun addTransferListener(transferListener: TransferListener) {
                    listeners += transferListener
                }

                override fun open(dataSpec: DataSpec): Long {
                    val uriStr = dataSpec.uri.toString()
                    val chosen = when {
                        dataSpec.uri.scheme == "tg" -> tgFactory.createDataSource()
                        dataSpec.uri.scheme == "http" || dataSpec.uri.scheme == "https" -> {
                            val ytCookie = proxyManager.getYouTubeCookieHeader(uriStr)
                            val httpFactory = DefaultHttpDataSource.Factory()
                                .setUserAgent(YouTubeSessionCookieManager.DEFAULT_USER_AGENT)
                                .setAllowCrossProtocolRedirects(true)
                                .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
                                .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
                            if (!ytCookie.isNullOrBlank()) {
                                httpFactory.setDefaultRequestProperties(mapOf("Cookie" to ytCookie))
                            }
                            httpFactory.createDataSource()
                        }
                        else -> defaultFactory.createDataSource()
                    }
                    listeners.forEach { chosen.addTransferListener(it) }
                    delegate = chosen
                    return chosen.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
                }

                override fun getUri(): Uri? = delegate?.uri

                override fun close() {
                    delegate?.close()
                    delegate = null
                }
            }
        }
    }

    private val playerA: ExoPlayer = buildExoPlayer()
    private val playerBDelegate = lazy(LazyThreadSafetyMode.NONE) {
        buildExoPlayer().also { attachListeners(it, isSlotA = false) }
    }
    private val playerB: ExoPlayer by playerBDelegate

    private var activeSlotA = true
    val activePlayer: ExoPlayer
        get() = if (activeSlotA) playerA else playerB
    private val standbyPlayer: ExoPlayer
        get() = if (activeSlotA) playerB else playerA
    private fun standbyIfAllocated(): ExoPlayer? =
        if (activeSlotA) {
            if (playerBDelegate.isInitialized()) playerB else null
        } else {
            playerA
        }

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _playbackProgress = MutableStateFlow(PlaybackProgress())
    val playbackProgress: StateFlow<PlaybackProgress> = _playbackProgress.asStateFlow()

    var onActivePlayerChanged: ((ExoPlayer) -> Unit)? = null
    var onEnsureMediaSessionConnected: (() -> Unit)? = null

    private var crossfadeSeconds = 2
    private var autoPlayNext = true
    private var sleepFadeOutEnabled = true
    private var isCrossfading = false
    private var tickerJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var activePlayJob: Job? = null
    private var bufferingWatchdogJob: Job? = null
    private var cacheYouTubeJob: Job? = null
    private var crossfadeJob: Job? = null
    private var autoQueueJob: Job? = null
    private var replayGainJob: Job? = null
    private var activeLoadAttempt = 0
    private val checkpointRestoreStarted = AtomicBoolean(false)
    private var lastSavedCheckpointTrackId: String? = null
    private var lastSavedCheckpointPosMs: Long = -1L

    init {
        attachListeners(playerA, isSlotA = true)
        observePreferences()
        startPositionTicker()
    }

    private fun buildExoPlayer(): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(eqProcessor))
                    .build()
            }
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 12_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 600,
                /* bufferForPlaybackAfterRebufferMs = */ 1_200
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(routingDataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private data class PlaybackPrefsSnapshot(
        val crossfadeSeconds: Int,
        val autoPlayNext: Boolean,
        val sleepFadeOutEnabled: Boolean,
        val eqState: EqState,
        val shuffleEnabled: Boolean,
        val repeatMode: RepeatMode
    )

    private fun observePreferences() {
        scope.launch {
            settingsDataStore.preferencesFlow
                .map { prefs ->
                    PlaybackPrefsSnapshot(
                        crossfadeSeconds = prefs.crossfadeSeconds,
                        autoPlayNext = prefs.autoPlayNext,
                        sleepFadeOutEnabled = prefs.sleepFadeOutEnabled,
                        eqState = prefs.eqState,
                        shuffleEnabled = prefs.shuffleEnabled,
                        repeatMode = prefs.repeatMode
                    )
                }
                .distinctUntilChanged()
                .collect { prefs ->
                    crossfadeSeconds = prefs.crossfadeSeconds
                    autoPlayNext = prefs.autoPlayNext
                    sleepFadeOutEnabled = prefs.sleepFadeOutEnabled
                    val currentTrack = _playbackState.value.currentTrack
                    applyTrackDspSettings(currentTrack, prefs.eqState)
                    _playbackState.value = _playbackState.value.copy(
                        shuffleEnabled = prefs.shuffleEnabled,
                        repeatMode = prefs.repeatMode,
                        sleepFadeOutEnabled = prefs.sleepFadeOutEnabled
                    )
                    if (!prefs.sleepFadeOutEnabled && _playbackState.value.sleepFadeOutActive) {
                        activePlayer.volume = 1f
                        standbyIfAllocated()?.volume = 1f
                        _playbackState.value = _playbackState.value.copy(sleepFadeOutActive = false)
                    }
                }
        }
    }

    private fun applyTrackDspSettings(track: Track?, eqState: EqState): Float {
        val resolvedEq = if (eqState.autoEqByGenre && track != null && eqState.enabled) {
            val preset = track.genre.defaultEqPreset
            eqState.copy(preset = preset, bandGainsDb = preset.gainsDb)
        } else {
            eqState
        }
        val rawGainDb = track?.let { ReplayGainExtractor.resolvePlaybackLoudnessGainDb(it) } ?: 0f
        eqProcessor.updateSettings(resolvedEq, rawGainDb)
        val effectiveGain = if (eqState.loudnessNormalization) rawGainDb else 0f
        if (_playbackState.value.effectiveReplayGainDb != effectiveGain) {
            _playbackState.value = _playbackState.value.copy(effectiveReplayGainDb = effectiveGain)
        }
        return effectiveGain
    }

    fun restoreLastPlaybackCheckpointIfNeeded() {
        if (!checkpointRestoreStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            val prefs = settingsDataStore.preferencesFlow.first()
            val lastId = prefs.lastTrackId ?: return@launch
            val track = trackDao.getTrackById(lastId)?.toDomain() ?: return@launch
            val allTracks = trackDao.getAllTracksOnce().map { it.toDomain() }
            val queue = if (allTracks.isNotEmpty()) allTracks else listOf(track)
            val idx = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            withContext(Dispatchers.Main) {
                if (_playbackState.value.currentTrack != null) return@withContext
                _playbackProgress.value = _playbackProgress.value.copy(
                    positionMs = prefs.lastPositionMs,
                    durationMs = track.durationMs
                )
                _playbackState.value = _playbackState.value.copy(
                    currentTrack = track,
                    queue = queue,
                    currentIndex = idx,
                    positionMs = prefs.lastPositionMs,
                    durationMs = track.durationMs
                )
            }
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val safeIdx = startIndex.coerceIn(0, tracks.lastIndex)
        _playbackState.value = _playbackState.value.copy(
            queue = tracks,
            currentIndex = safeIdx,
            errorMessage = null
        )
        playTrackInternal(tracks[safeIdx], resetStandby = true)
    }

    fun playSingleTrack(track: Track) {
        val currentQueue = _playbackState.value.queue
        val existingIdx = currentQueue.indexOfFirst { it.id == track.id }
        if (existingIdx >= 0) {
            playQueue(currentQueue, existingIdx)
        } else {
            playQueue(listOf(track) + currentQueue, 0)
        }
    }

    fun addToQueueNext(track: Track) {
        val state = _playbackState.value
        val mutable = state.queue.toMutableList()
        val insertPos = (state.currentIndex + 1).coerceIn(0, mutable.size)
        mutable.add(insertPos, track)
        _playbackState.value = state.copy(queue = mutable)
    }

    fun clearErrorMessage() {
        _playbackState.value = _playbackState.value.copy(errorMessage = null)
    }

    fun cancelActivePlaybackLoad() {
        activePlayJob?.cancel()
        activePlayJob = null
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
        cacheYouTubeJob?.cancel()
        cacheYouTubeJob = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        autoQueueJob?.cancel()
        autoQueueJob = null
        replayGainJob?.cancel()
        replayGainJob = null
        isCrossfading = false
        activeLoadAttempt = 0
        runCatching {
            activePlayer.stop()
            activePlayer.clearMediaItems()
        }
        _playbackState.value = _playbackState.value.copy(
            isBuffering = false,
            isPlaying = false,
            crossfadeActive = false,
            errorMessage = null
        )
        logger.log("PLAYER", "INFO", "Đã hủy tải luồng phát theo yêu cầu người dùng")
    }

    fun togglePlayPause() {
        val state = _playbackState.value
        if (state.isBuffering) {
            cancelActivePlaybackLoad()
            return
        }
        val p = activePlayer
        if (p.isPlaying) {
            p.pause()
            _playbackState.value = _playbackState.value.copy(isPlaying = false)
            return
        }
        val needsReload = p.mediaItemCount == 0 ||
            p.playbackState == Player.STATE_IDLE ||
            p.playerError != null
        if (needsReload) {
            val targetTrack = state.currentTrack
                ?: state.queue.getOrNull(state.currentIndex.coerceIn(0, (state.queue.size - 1).coerceAtLeast(0)))
                ?: return
            playTrackInternal(
                track = targetTrack,
                resetStandby = true,
                seekToMs = _playbackProgress.value.positionMs.takeIf { it > 0L } ?: state.positionMs,
                attempt = 0
            )
            return
        }
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0L)
        }
        if (!_playbackState.value.sleepFadeOutActive) {
            p.volume = 1f
        }
        onEnsureMediaSessionConnected?.invoke()
        p.play()
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        activePlayer.seekTo(clamped)
        _playbackProgress.value = _playbackProgress.value.copy(positionMs = clamped)
        _playbackState.value = _playbackState.value.copy(positionMs = clamped)
    }

    fun skipToNext() {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return
        val nextIdx = computeNextIndex(state, manualSkip = true)
        if (nextIdx != null) {
            _playbackState.value = state.copy(currentIndex = nextIdx)
            playTrackInternal(state.queue[nextIdx], resetStandby = true)
        } else if (autoPlayNext && state.currentTrack != null) {
            autoQueueJob?.cancel()
            autoQueueJob = scope.launch(Dispatchers.IO) {
                val suggestions = playlistManager.suggestAutoQueue(
                    state.currentTrack,
                    state.queue.map { it.id }.toSet()
                )
                if (suggestions.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val latestState = _playbackState.value
                        val updatedQueue = latestState.queue + suggestions
                        val newIdx = latestState.queue.size
                        _playbackState.value = latestState.copy(queue = updatedQueue, currentIndex = newIdx)
                        playTrackInternal(updatedQueue[newIdx], resetStandby = true)
                    }
                }
            }
        }
    }

    fun skipToPrevious() {
        val state = _playbackState.value
        if (activePlayer.currentPosition > 3500L) {
            seekTo(0L)
            return
        }
        if (state.queue.isEmpty()) return
        val prevIdx = if (state.currentIndex > 0) state.currentIndex - 1 else state.queue.lastIndex
        _playbackState.value = state.copy(currentIndex = prevIdx)
        playTrackInternal(state.queue[prevIdx], resetStandby = true)
    }

    fun toggleShuffle() {
        scope.launch {
            val next = !_playbackState.value.shuffleEnabled
            settingsDataStore.setRepeatAndShuffle(_playbackState.value.repeatMode, next)
        }
    }

    fun cycleRepeatMode() {
        scope.launch {
            val next = when (_playbackState.value.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            settingsDataStore.setRepeatAndShuffle(next, _playbackState.value.shuffleEnabled)
        }
    }

    fun setSleepTimer(minutes: Int) = setSleepTimerMinutes(minutes)

    fun setSleepTimerMinutes(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        var remaining = minutes * 60_000L
        if (!sleepFadeOutEnabled || remaining > SLEEP_FADEOUT_DURATION_MS) {
            activePlayer.volume = 1f
        }
        _playbackProgress.value = _playbackProgress.value.copy(sleepTimerRemainingMs = remaining)
        _playbackState.value = _playbackState.value.copy(
            sleepTimerMode = SleepTimerMode.AFTER_MINUTES,
            sleepTimerRemainingMs = remaining,
            sleepTimerRemainingTracks = 0,
            sleepFadeOutActive = false
        )
        logger.log("SLEEP_TIMER", "INFO", "Hẹn giờ tắt nhạc sau $minutes phút (giảm dần âm lượng: $sleepFadeOutEnabled)")
        sleepTimerJob = scope.launch {
            while (remaining > 0L) {
                val stepMs = if (sleepFadeOutEnabled && remaining <= SLEEP_FADEOUT_DURATION_MS) 250L else 1000L
                delay(stepMs)
                remaining = (remaining - stepMs).coerceAtLeast(0L)
                val fadingNow = sleepFadeOutEnabled && remaining in 1L..SLEEP_FADEOUT_DURATION_MS
                if (fadingNow && !isCrossfading) {
                    val norm = (remaining.toFloat() / SLEEP_FADEOUT_DURATION_MS.toFloat()).coerceIn(0f, 1f)
                    activePlayer.volume = (norm * norm).coerceIn(0.02f, 1f)
                }
                _playbackProgress.value = _playbackProgress.value.copy(sleepTimerRemainingMs = remaining)
                if (_playbackState.value.sleepFadeOutActive != fadingNow) {
                    _playbackState.value = _playbackState.value.copy(
                        sleepTimerRemainingMs = remaining,
                        sleepFadeOutActive = fadingNow
                    )
                }
            }
            triggerSleepTimerPause("Hẹn giờ tắt nhạc ($minutes phút) đã kích hoạt")
        }
    }

    fun setSleepTimerEndOfTrack() {
        sleepTimerJob?.cancel()
        activePlayer.volume = 1f
        _playbackProgress.value = _playbackProgress.value.copy(sleepTimerRemainingMs = 0L)
        _playbackState.value = _playbackState.value.copy(
            sleepTimerMode = SleepTimerMode.END_OF_CURRENT_TRACK,
            sleepTimerRemainingMs = 0L,
            sleepTimerRemainingTracks = 1,
            sleepFadeOutActive = false
        )
        logger.log("SLEEP_TIMER", "INFO", "Hẹn giờ tắt nhạc sau bài hát hiện tại")
    }

    fun setSleepTimerAfterTracks(trackCount: Int) {
        sleepTimerJob?.cancel()
        if (trackCount <= 0) {
            cancelSleepTimer()
            return
        }
        activePlayer.volume = 1f
        _playbackProgress.value = _playbackProgress.value.copy(sleepTimerRemainingMs = 0L)
        _playbackState.value = _playbackState.value.copy(
            sleepTimerMode = SleepTimerMode.AFTER_N_TRACKS,
            sleepTimerRemainingMs = 0L,
            sleepTimerRemainingTracks = trackCount,
            sleepFadeOutActive = false
        )
        logger.log("SLEEP_TIMER", "INFO", "Hẹn giờ tắt nhạc sau $trackCount bài tiếp theo")
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        activePlayer.volume = 1f
        standbyIfAllocated()?.volume = 1f
        _playbackProgress.value = _playbackProgress.value.copy(sleepTimerRemainingMs = 0L)
        _playbackState.value = _playbackState.value.copy(
            sleepTimerMode = SleepTimerMode.OFF,
            sleepTimerRemainingMs = 0L,
            sleepTimerRemainingTracks = 0,
            sleepFadeOutActive = false
        )
    }

    private fun triggerSleepTimerPause(logMessage: String) {
        activePlayer.pause()
        standbyIfAllocated()?.pause()
        activePlayer.volume = 1f
        standbyIfAllocated()?.volume = 1f
        _playbackProgress.value = _playbackProgress.value.copy(sleepTimerRemainingMs = 0L)
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            sleepTimerMode = SleepTimerMode.OFF,
            sleepTimerRemainingMs = 0L,
            sleepTimerRemainingTracks = 0,
            sleepFadeOutActive = false
        )
        logger.log("PLAYER", "INFO", logMessage)
    }

    fun refreshTrackMetadata(updatedTrack: Track) {
        val state = _playbackState.value
        val updatedQueue = state.queue.map { if (it.id == updatedTrack.id) updatedTrack else it }
        val updatedCurrent = if (state.currentTrack?.id == updatedTrack.id) updatedTrack else state.currentTrack
        _playbackState.value = state.copy(
            currentTrack = updatedCurrent,
            queue = updatedQueue
        )
    }

    /**
     * Evaluates whether a track-based sleep timer should stop playback instead of advancing.
     * Returns true if playback was paused by the sleep timer.
     */
    internal fun consumeTrackSleepTimerOnEnd(): Boolean {
        val state = _playbackState.value
        return when (state.sleepTimerMode) {
            SleepTimerMode.END_OF_CURRENT_TRACK -> {
                triggerSleepTimerPause("Đã dừng phát sau khi kết thúc bài hát hiện tại")
                true
            }
            SleepTimerMode.AFTER_N_TRACKS -> {
                val left = state.sleepTimerRemainingTracks - 1
                if (left <= 0) {
                    triggerSleepTimerPause("Đã dừng phát sau khi hoàn tất số bài đã hẹn")
                    true
                } else {
                    _playbackState.value = state.copy(sleepTimerRemainingTracks = left)
                    false
                }
            }
            else -> false
        }
    }

    fun applyEqImmediate(eqState: EqState) {
        applyTrackDspSettings(_playbackState.value.currentTrack, eqState)
    }

    private fun failPlayback(message: String) {
        activePlayJob?.cancel()
        activePlayJob = null
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = null
        cacheYouTubeJob?.cancel()
        cacheYouTubeJob = null
        activeLoadAttempt = 0
        runCatching {
            activePlayer.stop()
            activePlayer.clearMediaItems()
        }
        _playbackState.value = _playbackState.value.copy(
            isBuffering = false,
            isPlaying = false,
            errorMessage = message
        )
    }

    private fun playTrackInternal(
        track: Track,
        resetStandby: Boolean,
        seekToMs: Long = 0L,
        attempt: Int = 0
    ) {
        activePlayJob?.cancel()
        bufferingWatchdogJob?.cancel()
        cacheYouTubeJob?.cancel()
        crossfadeJob?.cancel()
        autoQueueJob?.cancel()
        replayGainJob?.cancel()
        isCrossfading = false
        activeLoadAttempt = attempt
        if (resetStandby) {
            standbyIfAllocated()?.let { standby ->
                standby.stop()
                standby.clearMediaItems()
            }
        }
        runCatching {
            activePlayer.stop()
            activePlayer.clearMediaItems()
        }
        val initialDur = track.durationMs.coerceAtLeast(0L)
        _playbackProgress.value = _playbackProgress.value.copy(
            positionMs = seekToMs.coerceAtLeast(0L),
            durationMs = initialDur
        )
        _playbackState.value = _playbackState.value.copy(
            currentTrack = track,
            isBuffering = true,
            isPlaying = false,
            positionMs = seekToMs.coerceAtLeast(0L),
            durationMs = initialDur,
            errorMessage = null,
            crossfadeActive = false,
            sleepFadeOutActive = false
        )

        activePlayJob = scope.launch {
            val prefs = settingsDataStore.preferencesFlow.first()
            applyTrackDspSettings(track, prefs.eqState)
            launchReplayGainProbeIfNeeded(track)

            val resolvedResult = try {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    resolvePlayableMedia(track)
                }
            } catch (_: TimeoutCancellationException) {
                Result.failure(
                    IllegalStateException("Hết thời gian tải '${track.title}' (quá 7.5 giây)")
                )
            }

            val resolvedMedia = resolvedResult.getOrElse { err ->
                if (attempt == 0 && isRetryableTrack(track)) {
                    logger.log("PLAYER", "WARN", "Kết nối '${track.title}' thất bại/chậm, đang tự thử lại 1 lần...")
                    delay(250L)
                    playTrackInternal(track, resetStandby = true, seekToMs = seekToMs, attempt = 1)
                } else {
                    failPlayback(err.message ?: "Không thể tải '${track.title}'")
                }
                return@launch
            }

            val resolvedTrack = resolvedMedia.updatedTrack
            if (resolvedTrack != track) {
                val updatedQueue = _playbackState.value.queue.map { q ->
                    if (q.id == resolvedTrack.id) resolvedTrack else q
                }
                _playbackState.value = _playbackState.value.copy(
                    currentTrack = resolvedTrack,
                    queue = updatedQueue,
                    durationMs = resolvedTrack.durationMs.takeIf { it > 0 } ?: _playbackState.value.durationMs
                )
            }

            val item = buildMediaItem(resolvedTrack, resolvedMedia.uri, resolvedMedia.mimeType)
            onEnsureMediaSessionConnected?.invoke()
            activePlayer.volume = 1f
            activePlayer.setMediaItem(item)
            if (seekToMs > 0L) activePlayer.seekTo(seekToMs)
            activePlayer.prepare()
            activePlayer.play()
            startBufferingWatchdogIfNeeded(resolvedTrack, seekToMs, attempt)

            launch(Dispatchers.IO) {
                trackDao.markPlayed(resolvedTrack.id, System.currentTimeMillis())
            }
            logger.log(
                "PLAYER",
                "INFO",
                "Đang phát: ${track.title} — ${track.artist} [${resolvedTrack.format.extension}]"
            )
        }
    }

    private fun isRetryableTrack(track: Track): Boolean {
        return track.source == MusicSource.TELEGRAM || track.source == MusicSource.YOUTUBE_MUSIC
    }

    private fun launchReplayGainProbeIfNeeded(track: Track) {
        if (track.loudnessGainDb != 0f && track.bpm > 0) return
        val localPath = track.localPath ?: return
        replayGainJob?.cancel()
        replayGainJob = scope.launch(Dispatchers.IO) {
            val analysis = when {
                localPath.startsWith("content://") -> {
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(Uri.parse(localPath))?.use { stream ->
                            val buf = ByteArray(256 * 1024)
                            val n = stream.read(buf)
                            if (n > 0) buf.copyOf(n) else null
                        }
                    }.getOrNull()
                    if (bytes != null) ReplayGainExtractor.analyzeBytes(bytes, track.title, track.fileName)
                    else return@launch
                }
                localPath.startsWith("file://") -> {
                    val path = Uri.parse(localPath).path ?: return@launch
                    ReplayGainExtractor.analyzeFile(File(path), track.title, track.fileName)
                }
                else -> ReplayGainExtractor.analyzeFile(File(localPath), track.title, track.fileName)
            }

            val updatedGain = analysis.replayGainDb ?: track.loudnessGainDb
            val updatedBpm = if (analysis.bpm > 0) analysis.bpm else track.bpm
            if (analysis.replayGainDb != null || analysis.bpm > 0) {
                trackDao.updateTrackAnalysis(track.id, updatedGain, updatedBpm)
                val updatedTrack = track.copy(loudnessGainDb = updatedGain, bpm = updatedBpm)
                withContext(Dispatchers.Main) {
                    val current = _playbackState.value.currentTrack
                    if (current?.id == track.id) {
                        val prefs = settingsDataStore.preferencesFlow.first()
                        _playbackState.value = _playbackState.value.copy(currentTrack = updatedTrack)
                        applyTrackDspSettings(updatedTrack, prefs.eqState)
                    }
                }
            }
        }
    }

    private data class ResolvedPlayableMedia(
        val uri: String,
        val updatedTrack: Track,
        val mimeType: String? = null
    )

    private fun resolveMimeTypeForTrack(track: Track, rawMime: String?): String? {
        val mime = rawMime.orEmpty().lowercase()
        return when {
            mime.contains("flac") || track.format == AudioFormat.FLAC -> MimeTypes.AUDIO_FLAC
            mime.contains("mpeg") || mime.contains("mp3") || track.format == AudioFormat.MP3 -> MimeTypes.AUDIO_MPEG
            mime.contains("webm") -> MimeTypes.AUDIO_WEBM
            mime.contains("ogg") || mime.contains("opus") ||
                track.format == AudioFormat.OGG || track.format == AudioFormat.OPUS -> MimeTypes.AUDIO_OGG
            mime.contains("mp4") || mime.contains("m4a") ||
                track.format == AudioFormat.M4A || track.format == AudioFormat.ALAC -> MimeTypes.AUDIO_MP4
            mime.contains("aac") || track.format == AudioFormat.AAC -> MimeTypes.AUDIO_AAC
            mime.contains("wav") || track.format == AudioFormat.WAV -> MimeTypes.AUDIO_WAV
            else -> null
        }
    }

    private suspend fun resolvePlayableMedia(track: Track): Result<ResolvedPlayableMedia> {
        val candidateLocal = track.localPath
        if (!candidateLocal.isNullOrBlank()) {
            if (candidateLocal.startsWith("content://")) {
                return Result.success(ResolvedPlayableMedia(candidateLocal, track))
            }
            val cleanFile = if (candidateLocal.startsWith("file://")) {
                Uri.parse(candidateLocal).path?.let(::File)
            } else {
                File(candidateLocal)
            }
            if (cleanFile != null && cleanFile.exists() && cleanFile.isFile && cleanFile.length() > 1024L) {
                return Result.success(ResolvedPlayableMedia(Uri.fromFile(cleanFile).toString(), track))
            }
        }
        if (track.source == MusicSource.YOUTUBE_MUSIC) {
            val videoId = track.youtubeVideoId ?: track.streamUri
                ?: return Result.failure(IllegalStateException("Thiếu ID bài hát YouTube Music"))
            val cached = cacheManager.findValidCachedYouTubeFile(videoId)
            if (cached != null) {
                val cachedFormat = if (cached.extension.equals("opus", ignoreCase = true) ||
                    cached.extension.equals("webm", ignoreCase = true)
                ) {
                    AudioFormat.OPUS
                } else {
                    track.format
                }
                val cachedSampleRate = if (cachedFormat == AudioFormat.OPUS) 48000 else track.sampleRateHz
                val updated = track.copy(format = cachedFormat, sampleRateHz = cachedSampleRate)
                return Result.success(ResolvedPlayableMedia(Uri.fromFile(cached).toString(), updated))
            }
            val resolved = youTubeRepository.resolveAudioStreamUrl(
                videoUrlOrId = videoId,
                allowRetry = false
            ).getOrElse { err ->
                return Result.failure(err)
            }
            val updatedTrack = track.copy(
                format = resolved.format,
                bitrateKbps = resolved.bitrateKbps,
                sampleRateHz = resolved.sampleRateHz,
                durationMs = resolved.durationMs.takeIf { it > 0 } ?: track.durationMs,
                artworkUri = track.artworkUri ?: resolved.artworkUrl,
                fileName = "${track.title}.${resolved.format.extension}"
            )
            cacheYouTubeJob = scope.launch(Dispatchers.IO) {
                trackDao.updateTrackAudioSpecs(
                    trackId = updatedTrack.id,
                    format = updatedTrack.format.name,
                    fileName = updatedTrack.fileName,
                    artworkUri = updatedTrack.artworkUri,
                    bitrateKbps = updatedTrack.bitrateKbps,
                    sampleRateHz = updatedTrack.sampleRateHz,
                    bitDepth = updatedTrack.bitDepth,
                    durationMs = updatedTrack.durationMs
                )
                cacheManager.cacheYouTubeStream(
                    track = updatedTrack,
                    streamUrl = resolved.streamUrl,
                    httpClient = proxyManager.getStreamHttpClient()
                )
            }
            val containerMime = resolveMimeTypeForTrack(updatedTrack, resolved.mimeType)
            return Result.success(ResolvedPlayableMedia(resolved.streamUrl, updatedTrack, containerMime))
        }
        if (track.source == MusicSource.TELEGRAM) {
            val latestTrack = withContext(Dispatchers.IO) {
                val dbTrack = trackDao.getTrackById(track.id)?.toDomain()
                    ?: if (track.telegramChatId != null && track.telegramMessageId != null) {
                        trackDao.findByTelegramMessage(track.telegramChatId, track.telegramMessageId)?.toDomain()
                    } else {
                        null
                    }
                if (dbTrack != null) {
                    track.copy(
                        localPath = dbTrack.localPath ?: track.localPath,
                        streamUri = dbTrack.streamUri ?: track.streamUri,
                        telegramChatId = dbTrack.telegramChatId ?: track.telegramChatId,
                        telegramMessageId = dbTrack.telegramMessageId ?: track.telegramMessageId,
                        telegramFileId = dbTrack.telegramFileId ?: track.telegramFileId,
                        telegramRemoteFileId = dbTrack.telegramRemoteFileId ?: track.telegramRemoteFileId,
                        telegramRemoteUniqueId = dbTrack.telegramRemoteUniqueId ?: track.telegramRemoteUniqueId,
                        mimeType = dbTrack.mimeType ?: track.mimeType,
                        fileSizeBytes = maxOf(dbTrack.fileSizeBytes, track.fileSizeBytes),
                        artworkUri = dbTrack.artworkUri ?: track.artworkUri
                    )
                } else {
                    track
                }
            }
            val progressive = withContext(Dispatchers.IO) {
                telegramFileResolver.resolveForProgressiveStream(latestTrack)
            }.getOrElse { err ->
                return Result.failure(err)
            }
            val containerMime = if (progressive.isLocalFile) {
                null
            } else {
                resolveMimeTypeForTrack(progressive.track, progressive.mimeType)
            }
            return Result.success(
                ResolvedPlayableMedia(
                    uri = progressive.playUri,
                    updatedTrack = progressive.track,
                    mimeType = containerMime
                )
            )
        }
        val uri = track.streamUri
            ?: return Result.failure(IllegalStateException("Không tìm thấy luồng phát cho '${track.title}'"))
        return Result.success(ResolvedPlayableMedia(uri, track))
    }

    private fun toArtworkUri(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("/")) Uri.fromFile(File(raw)) else Uri.parse(raw)
    }

    internal fun buildMediaItem(track: Track, uri: String, mimeType: String? = null): MediaItem {
        val extras = buildMediaItemExtras(track, uri)
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(toArtworkUri(track.artworkUri))
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
            }
            .setMediaMetadata(metadata)
            .build()
    }

    private fun startBufferingWatchdogIfNeeded(
        track: Track? = _playbackState.value.currentTrack,
        seekToMs: Long = _playbackState.value.positionMs,
        attempt: Int = activeLoadAttempt
    ) {
        bufferingWatchdogJob?.cancel()
        val target = track ?: return
        val isOnlineStream = target.localPath.isNullOrBlank() &&
            (target.source == MusicSource.TELEGRAM || target.source == MusicSource.YOUTUBE_MUSIC)
        if (!isOnlineStream) return
        bufferingWatchdogJob = scope.launch {
            delay(BUFFERING_STALL_TIMEOUT_MS)
            val current = _playbackState.value
            if (current.isBuffering && current.currentTrack?.id == target.id) {
                if (attempt == 0) {
                    logger.log("PLAYER", "WARN", "Đệm '${target.title}' bị treo (>7.5s), đang tự thử lại 1 lần...")
                    playTrackInternal(target, resetStandby = true, seekToMs = seekToMs, attempt = 1)
                } else {
                    logger.log("PLAYER", "WARN", "Hết thời gian chờ đệm '${target.title}' sau khi thử lại")
                    failPlayback("Hết thời gian đệm '${target.title}'. Vui lòng kiểm tra mạng rồi thử lại.")
                }
            }
        }
    }

    private fun attachListeners(player: ExoPlayer, isSlotA: Boolean) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isSlotA != activeSlotA) return
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (isSlotA != activeSlotA) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        val curTrack = _playbackState.value.currentTrack
                        _playbackState.value = _playbackState.value.copy(isBuffering = true)
                        startBufferingWatchdogIfNeeded(
                            track = curTrack,
                            seekToMs = player.currentPosition.coerceAtLeast(0L),
                            attempt = activeLoadAttempt
                        )
                    }
                    Player.STATE_READY -> {
                        bufferingWatchdogJob?.cancel()
                        activeLoadAttempt = 0
                        val dur = player.duration.takeIf { it > 0 }
                            ?: _playbackState.value.currentTrack?.durationMs ?: 0L
                        _playbackProgress.value = _playbackProgress.value.copy(durationMs = dur)
                        _playbackState.value = _playbackState.value.copy(
                            isBuffering = false,
                            durationMs = dur
                        )
                    }
                    Player.STATE_ENDED -> {
                        bufferingWatchdogJob?.cancel()
                        if (!isCrossfading) {
                            handleTrackEnded()
                        }
                    }
                    Player.STATE_IDLE -> {
                        if (activePlayJob?.isActive != true) {
                            bufferingWatchdogJob?.cancel()
                        }
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isSlotA != activeSlotA) return
                bufferingWatchdogJob?.cancel()
                val track = _playbackState.value.currentTrack
                logger.log("DECODER", "ERROR", "Lỗi phát '${track?.title}': ${error.errorCodeName}")
                if (track != null && isRetryableTrack(track) && activeLoadAttempt == 0) {
                    val resumePos = player.currentPosition.coerceAtLeast(0L)
                    playTrackInternal(track, resetStandby = true, seekToMs = resumePos, attempt = 1)
                } else {
                    failPlayback(
                        error.cause?.message
                            ?: "Không thể phát '${track?.title ?: "bài hát"}': ${error.errorCodeName}"
                    )
                }
            }
        })
    }

    private fun handleTrackEnded() {
        if (consumeTrackSleepTimerOnEnd()) return
        val state = _playbackState.value
        if (state.repeatMode == RepeatMode.ONE && state.currentTrack != null) {
            playTrackInternal(state.currentTrack, resetStandby = true)
            return
        }
        skipToNext()
    }

    private fun startPositionTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var tickCount = 0
            while (true) {
                delay(250L)
                val p = activePlayer
                val pos = p.currentPosition.coerceAtLeast(0L)
                val currentlyPlaying = p.isPlaying
                val st = _playbackState.value
                val dur = p.duration.takeIf { it > 0 } ?: st.durationMs

                val prevProgress = _playbackProgress.value
                if (prevProgress.positionMs != pos || prevProgress.durationMs != dur) {
                    _playbackProgress.value = prevProgress.copy(positionMs = pos, durationMs = dur)
                }

                if (st.isPlaying != currentlyPlaying || (dur > 0L && st.durationMs != dur)) {
                    _playbackState.value = st.copy(
                        durationMs = dur,
                        isPlaying = currentlyPlaying,
                        positionMs = pos
                    )
                }

                tickCount++
                if (tickCount % 10 == 0) {
                    val id = st.currentTrack?.id
                    val movedEnough = id != lastSavedCheckpointTrackId ||
                        abs(pos - lastSavedCheckpointPosMs) >= 1_500L
                    if (id != null && movedEnough && (currentlyPlaying || lastSavedCheckpointTrackId != id)) {
                        lastSavedCheckpointTrackId = id
                        lastSavedCheckpointPosMs = pos
                        launch(Dispatchers.IO) {
                            settingsDataStore.savePlaybackCheckpoint(id, pos)
                        }
                    }
                }

                // Do not start crossfade if track-based sleep timer expects playback to stop after current track
                val shouldStopAfterThisTrack = st.sleepTimerMode == SleepTimerMode.END_OF_CURRENT_TRACK ||
                    (st.sleepTimerMode == SleepTimerMode.AFTER_N_TRACKS && st.sleepTimerRemainingTracks <= 1)

                if (shouldStopAfterThisTrack && sleepFadeOutEnabled && !isCrossfading && currentlyPlaying && dur > SLEEP_FADEOUT_DURATION_MS + 3_000L) {
                    val remaining = dur - pos
                    if (remaining in 100L..SLEEP_FADEOUT_DURATION_MS) {
                        val norm = (remaining.toFloat() / SLEEP_FADEOUT_DURATION_MS.toFloat()).coerceIn(0f, 1f)
                        p.volume = (norm * norm).coerceIn(0.02f, 1f)
                        if (!st.sleepFadeOutActive) {
                            _playbackState.value = _playbackState.value.copy(sleepFadeOutActive = true)
                        }
                    } else if (remaining > SLEEP_FADEOUT_DURATION_MS && st.sleepFadeOutActive) {
                        p.volume = 1f
                        _playbackState.value = _playbackState.value.copy(sleepFadeOutActive = false)
                    }
                }

                val fadeMs = crossfadeSeconds * 1000L
                if (!shouldStopAfterThisTrack && fadeMs > 0L && currentlyPlaying && !isCrossfading && dur > fadeMs + 2000L) {
                    val remaining = dur - pos
                    if (remaining in 400L..fadeMs) {
                        triggerCrossfade(fadeMs)
                    }
                }
            }
        }
    }

    private fun triggerCrossfade(fadeMs: Long) {
        val state = _playbackState.value
        if (state.repeatMode == RepeatMode.ONE) return
        val nextIdx = computeNextIndex(state, manualSkip = false) ?: return
        val nextTrack = state.queue[nextIdx]
        isCrossfading = true

        crossfadeJob?.cancel()
        crossfadeJob = scope.launch {
            val resolvedNext = try {
                withTimeout(RESOLVE_TIMEOUT_MS) { resolvePlayableMedia(nextTrack).getOrNull() }
            } catch (_: TimeoutCancellationException) {
                null
            }
            if (resolvedNext == null) {
                isCrossfading = false
                return@launch
            }
            val resolvedNextTrack = resolvedNext.updatedTrack
            val outgoing = activePlayer
            val incoming = standbyPlayer

            incoming.volume = 0f
            incoming.setMediaItem(buildMediaItem(resolvedNextTrack, resolvedNext.uri, resolvedNext.mimeType))
            incoming.prepare()
            incoming.play()

            _playbackState.value = _playbackState.value.copy(crossfadeActive = true)
            val steps = 20
            val stepDelay = (fadeMs / steps).coerceAtLeast(40L)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val angle = t * (Math.PI / 2.0)
                outgoing.volume = cos(angle).toFloat()
                incoming.volume = sin(angle).toFloat()
                delay(stepDelay)
            }

            outgoing.stop()
            outgoing.clearMediaItems()
            outgoing.volume = 1f
            incoming.volume = 1f
            activeSlotA = !activeSlotA
            isCrossfading = false
            onActivePlayerChanged?.invoke(activePlayer)

            val updatedTimerLeft = if (_playbackState.value.sleepTimerMode == SleepTimerMode.AFTER_N_TRACKS) {
                (_playbackState.value.sleepTimerRemainingTracks - 1).coerceAtLeast(1)
            } else {
                _playbackState.value.sleepTimerRemainingTracks
            }

            _playbackProgress.value = _playbackProgress.value.copy(
                positionMs = 0L,
                durationMs = resolvedNextTrack.durationMs.coerceAtLeast(0L)
            )
            _playbackState.value = _playbackState.value.copy(
                currentTrack = resolvedNextTrack,
                currentIndex = nextIdx,
                positionMs = 0L,
                durationMs = resolvedNextTrack.durationMs.coerceAtLeast(0L),
                crossfadeActive = false,
                sleepTimerRemainingTracks = updatedTimerLeft
            )
            val prefs = settingsDataStore.preferencesFlow.first()
            applyTrackDspSettings(resolvedNextTrack, prefs.eqState)
            launchReplayGainProbeIfNeeded(resolvedNextTrack)
            launch(Dispatchers.IO) { trackDao.markPlayed(resolvedNextTrack.id, System.currentTimeMillis()) }
        }
    }

    private fun computeNextIndex(state: PlaybackState, manualSkip: Boolean): Int? {
        if (state.queue.isEmpty()) return null
        if (state.shuffleEnabled && state.queue.size > 1) {
            var candidate: Int
            do {
                candidate = (state.queue.indices).random()
            } while (candidate == state.currentIndex)
            return candidate
        }
        val next = state.currentIndex + 1
        return when {
            next < state.queue.size -> next
            state.repeatMode == RepeatMode.ALL || manualSkip -> 0
            else -> null
        }
    }

    fun release() {
        activePlayJob?.cancel()
        bufferingWatchdogJob?.cancel()
        cacheYouTubeJob?.cancel()
        crossfadeJob?.cancel()
        autoQueueJob?.cancel()
        replayGainJob?.cancel()
        tickerJob?.cancel()
        sleepTimerJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        playerA.release()
        if (playerBDelegate.isInitialized()) {
            playerB.release()
        }
    }

    companion object {
        const val HTTP_CONNECT_TIMEOUT_MS = 6_000
        const val HTTP_READ_TIMEOUT_MS = 8_000
        const val RESOLVE_TIMEOUT_MS = 7_500L
        const val BUFFERING_STALL_TIMEOUT_MS = 7_500L
        const val SLEEP_FADEOUT_DURATION_MS = 30_000L

        const val EXTRA_URI = "uri"
        const val EXTRA_FILE_ID = "fileId"
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_TOTAL_FILE_SIZE = "totalFileSize"

        fun buildMediaItemExtras(track: Track, uri: String): Bundle {
            val parsedTarget = runCatching { TelegramFileResolver.parseTargetFromUri(Uri.parse(uri)) }.getOrNull()
            val resolvedFileId = track.telegramFileId?.takeIf { it > 0 } ?: parsedTarget?.fileId ?: 0
            val resolvedChatId = track.telegramChatId?.takeIf { it != 0L } ?: parsedTarget?.chatId ?: 0L
            val resolvedMessageId = track.telegramMessageId?.takeIf { it != 0L } ?: parsedTarget?.messageId ?: 0L
            val resolvedTotalSize = maxOf(track.fileSizeBytes, parsedTarget?.expectedSize ?: 0L)
            return Bundle().apply {
                putString(EXTRA_URI, uri)
                putInt(EXTRA_FILE_ID, resolvedFileId)
                putLong(EXTRA_CHAT_ID, resolvedChatId)
                putLong(EXTRA_MESSAGE_ID, resolvedMessageId)
                putLong(EXTRA_TOTAL_FILE_SIZE, resolvedTotalSize)
            }
        }
    }
}
