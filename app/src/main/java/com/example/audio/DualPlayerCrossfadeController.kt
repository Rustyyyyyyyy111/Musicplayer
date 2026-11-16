package com.example.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.core.model.CrossfadeOption
import com.example.core.model.EqState
import com.example.core.model.MusicSource
import com.example.core.model.RepeatMode
import com.example.core.model.Track
import com.example.core.util.ActivityLogger
import com.example.data.local.SettingsDataStore
import com.example.data.local.TrackDao
import com.example.data.telegram.TelegramAudioDataSource
import com.example.data.telegram.TelegramCore
import com.example.data.storage.CacheManager
import com.example.data.youtube.YouTubeMusicRepository
import com.example.integrations.PlaylistAndSmartMixManager
import com.example.integrations.ScrobbleAndProxyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

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
    val crossfadeActive: Boolean = false,
    val errorMessage: String? = null
)

@UnstableApi
class DualPlayerCrossfadeController(
    private val context: Context,
    private val telegramCore: TelegramCore,
    private val cacheManager: CacheManager,
    private val youTubeRepository: YouTubeMusicRepository,
    private val trackDao: TrackDao,
    private val settingsDataStore: SettingsDataStore,
    private val playlistManager: PlaylistAndSmartMixManager,
    private val scrobbleManager: ScrobbleAndProxyManager,
    private val logger: ActivityLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    val eqProcessor = BiquadEqAudioProcessor()

    private val routingDataSourceFactory = object : DataSource.Factory {
        private val defaultFactory = DefaultDataSource.Factory(context)
        private val tgFactory = TelegramAudioDataSource.Factory(telegramCore, cacheManager, logger)

        override fun createDataSource(): DataSource {
            return object : DataSource {
                private var delegate: DataSource? = null
                private val listeners = mutableListOf<TransferListener>()

                override fun addTransferListener(transferListener: TransferListener) {
                    listeners += transferListener
                }

                override fun open(dataSpec: DataSpec): Long {
                    val chosen = if (dataSpec.uri.scheme == "tg") {
                        tgFactory.createDataSource()
                    } else {
                        defaultFactory.createDataSource()
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
    private val playerB: ExoPlayer = buildExoPlayer()

    private var activeSlotA = true
    val activePlayer: ExoPlayer
        get() = if (activeSlotA) playerA else playerB
    private val standbyPlayer: ExoPlayer
        get() = if (activeSlotA) playerB else playerA

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var crossfadeOption = CrossfadeOption.TWO_SECONDS
    private var autoPlayNext = true
    private var isCrossfading = false
    private var tickerJob: Job? = null
    private var sleepTimerJob: Job? = null

    init {
        attachListeners(playerA, isSlotA = true)
        attachListeners(playerB, isSlotA = false)
        observePreferences()
        startPositionTicker()
        restoreLastPlaybackCheckpoint()
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

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(routingDataSourceFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private fun observePreferences() {
        scope.launch {
            settingsDataStore.preferencesFlow.collect { prefs ->
                crossfadeOption = prefs.crossfadeOption
                autoPlayNext = prefs.autoPlayNext
                val currentTrack = _playbackState.value.currentTrack
                val resolvedEq = if (prefs.eqState.autoEqByGenre && currentTrack != null && prefs.eqState.enabled) {
                    val preset = currentTrack.genre.defaultEqPreset
                    prefs.eqState.copy(preset = preset, bandGainsDb = preset.gainsDb)
                } else {
                    prefs.eqState
                }
                eqProcessor.updateSettings(resolvedEq, currentTrack?.loudnessGainDb ?: 0f)
                _playbackState.value = _playbackState.value.copy(
                    shuffleEnabled = prefs.shuffleEnabled,
                    repeatMode = prefs.repeatMode
                )
            }
        }
    }

    private fun restoreLastPlaybackCheckpoint() {
        scope.launch(Dispatchers.IO) {
            val prefs = settingsDataStore.preferencesFlow.first()
            val lastId = prefs.lastTrackId ?: return@launch
            val track = trackDao.getTrackById(lastId)?.toDomain() ?: return@launch
            val allTracks = trackDao.getAllTracksOnce().map { it.toDomain() }
            val queue = if (allTracks.isNotEmpty()) allTracks else listOf(track)
            val idx = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            withContext(Dispatchers.Main) {
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

    fun togglePlayPause() {
        val p = activePlayer
        if (p.mediaItemCount == 0 && _playbackState.value.currentTrack != null) {
            playTrackInternal(_playbackState.value.currentTrack!!, resetStandby = true, seekToMs = _playbackState.value.positionMs)
            return
        }
        if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
    }

    fun seekTo(positionMs: Long) {
        activePlayer.seekTo(positionMs.coerceAtLeast(0L))
        _playbackState.value = _playbackState.value.copy(positionMs = positionMs.coerceAtLeast(0L))
    }

    fun skipToNext() {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return
        val nextIdx = computeNextIndex(state, manualSkip = true)
        if (nextIdx != null) {
            _playbackState.value = state.copy(currentIndex = nextIdx)
            playTrackInternal(state.queue[nextIdx], resetStandby = true)
        } else if (autoPlayNext && state.currentTrack != null) {
            scope.launch(Dispatchers.IO) {
                val suggestions = playlistManager.suggestAutoQueue(
                    state.currentTrack,
                    state.queue.map { it.id }.toSet()
                )
                if (suggestions.isNotEmpty()) {
                    val updatedQueue = state.queue + suggestions
                    val newIdx = state.queue.size
                    withContext(Dispatchers.Main) {
                        _playbackState.value = _playbackState.value.copy(queue = updatedQueue, currentIndex = newIdx)
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

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = 0L)
            return
        }
        var remaining = minutes * 60_000L
        _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = remaining)
        sleepTimerJob = scope.launch {
            while (remaining > 0L) {
                delay(1000L)
                remaining -= 1000L
                _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = remaining.coerceAtLeast(0L))
            }
            activePlayer.pause()
            standbyPlayer.pause()
            logger.log("PLAYER", "INFO", "Hẹn giờ tắt nhạc đã kích hoạt")
        }
    }

    fun applyEqImmediate(eqState: EqState) {
        val loudness = _playbackState.value.currentTrack?.loudnessGainDb ?: 0f
        eqProcessor.updateSettings(eqState, loudness)
    }

    private fun playTrackInternal(track: Track, resetStandby: Boolean, seekToMs: Long = 0L) {
        isCrossfading = false
        if (resetStandby) {
            standbyPlayer.stop()
            standbyPlayer.clearMediaItems()
        }
        _playbackState.value = _playbackState.value.copy(
            currentTrack = track,
            isBuffering = true,
            errorMessage = null,
            crossfadeActive = false
        )

        scope.launch {
            val prefs = settingsDataStore.preferencesFlow.first()
            if (prefs.eqState.autoEqByGenre && prefs.eqState.enabled) {
                val autoPreset = track.genre.defaultEqPreset
                eqProcessor.updateSettings(
                    prefs.eqState.copy(preset = autoPreset, bandGainsDb = autoPreset.gainsDb),
                    track.loudnessGainDb
                )
            } else {
                eqProcessor.updateSettings(prefs.eqState, track.loudnessGainDb)
            }

            val mediaUri = resolvePlayableUri(track)
            if (mediaUri == null) {
                _playbackState.value = _playbackState.value.copy(
                    isBuffering = false,
                    errorMessage = "Không thể mở nguồn phát cho '${track.title}'"
                )
                return@launch
            }

            val item = buildMediaItem(track, mediaUri)
            activePlayer.volume = 1f
            activePlayer.setMediaItem(item)
            if (seekToMs > 0L) activePlayer.seekTo(seekToMs)
            activePlayer.prepare()
            activePlayer.play()

            launch(Dispatchers.IO) {
                trackDao.markPlayed(track.id, System.currentTimeMillis())
                scrobbleManager.scrobbleTrack(track)
            }
            logger.log("PLAYER", "INFO", "Đang phát: ${track.title} — ${track.artist} [${track.format.extension}]")
        }
    }

    private suspend fun resolvePlayableUri(track: Track): String? {
        if (!track.localPath.isNullOrBlank()) {
            val path = track.localPath
            if (path.startsWith("content://") || path.startsWith("file://")) return path
            val file = java.io.File(path)
            if (file.exists()) return Uri.fromFile(file).toString()
        }
        if (track.source == MusicSource.YOUTUBE_MUSIC) {
            val target = track.youtubeVideoId ?: track.streamUri ?: return null
            return youTubeRepository.resolveAudioStreamUrl(target).getOrNull()?.streamUrl
        }
        return track.streamUri
    }

    private fun buildMediaItem(track: Track, uri: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.artworkUri?.let { Uri.parse(it) })
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
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
                    Player.STATE_BUFFERING -> _playbackState.value = _playbackState.value.copy(isBuffering = true)
                    Player.STATE_READY -> {
                        val dur = player.duration.takeIf { it > 0 } ?: _playbackState.value.currentTrack?.durationMs ?: 0L
                        _playbackState.value = _playbackState.value.copy(isBuffering = false, durationMs = dur)
                    }
                    Player.STATE_ENDED -> {
                        if (!isCrossfading) {
                            handleTrackEnded()
                        }
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isSlotA != activeSlotA) return
                val track = _playbackState.value.currentTrack
                logger.log("DECODER", "ERROR", "Lỗi phát '${track?.title}': ${error.errorCodeName}")
                // Instant resume: refresh expired YouTube link and seek back
                if (track != null && track.source == MusicSource.YOUTUBE_MUSIC) {
                    val resumePos = player.currentPosition
                    playTrackInternal(track, resetStandby = true, seekToMs = resumePos)
                } else {
                    _playbackState.value = _playbackState.value.copy(
                        isBuffering = false,
                        errorMessage = "Lỗi giải mã: ${error.localizedMessage ?: error.errorCodeName}"
                    )
                }
            }
        })
    }

    private fun handleTrackEnded() {
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
                val dur = p.duration.takeIf { it > 0 } ?: _playbackState.value.durationMs
                _playbackState.value = _playbackState.value.copy(
                    positionMs = pos,
                    durationMs = dur,
                    isPlaying = p.isPlaying
                )

                // Save playback checkpoint every ~2.5s
                tickCount++
                if (tickCount % 10 == 0) {
                    val id = _playbackState.value.currentTrack?.id
                    launch(Dispatchers.IO) {
                        settingsDataStore.savePlaybackCheckpoint(id, pos)
                    }
                }

                // Trigger dual-player crossfade if enabled and approaching end
                val fadeMs = crossfadeOption.seconds * 1000L
                if (fadeMs > 0L && p.isPlaying && !isCrossfading && dur > fadeMs + 2000L) {
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

        scope.launch {
            val nextUri = resolvePlayableUri(nextTrack)
            if (nextUri == null) {
                isCrossfading = false
                return@launch
            }
            val outgoing = activePlayer
            val incoming = standbyPlayer

            incoming.volume = 0f
            incoming.setMediaItem(buildMediaItem(nextTrack, nextUri))
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

            _playbackState.value = _playbackState.value.copy(
                currentTrack = nextTrack,
                currentIndex = nextIdx,
                crossfadeActive = false
            )
            launch(Dispatchers.IO) { trackDao.markPlayed(nextTrack.id, System.currentTimeMillis()) }
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
        tickerJob?.cancel()
        sleepTimerJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        playerA.release()
        playerB.release()
    }
}
