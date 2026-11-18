package com.laughing.musicplayer.audio

import android.content.Context
import android.net.Uri
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.laughing.musicplayer.core.model.EqState
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.RepeatMode
import com.laughing.musicplayer.core.model.SleepTimerMode
import com.laughing.musicplayer.core.model.TelegramOrigin
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.ReplayGainExtractor
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.storage.CacheManager
import com.laughing.musicplayer.data.telegram.PublicTelegramRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
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
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    val sleepTimerRemainingTracks: Int = 0,
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
    private val publicTelegramRepository: PublicTelegramRepository? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val streamRequestHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    val eqProcessor = BiquadEqAudioProcessor()

    private val routingDataSourceFactory = object : DataSource.Factory {
        private val defaultFactory = DefaultDataSource.Factory(context)
        private val tgFactory = TelegramAudioDataSource.Factory(
            telegramCore,
            telegramFileResolver,
            cacheManager,
            logger
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
                            val customHeaders = streamRequestHeaders[uriStr]
                            if (!ytCookie.isNullOrBlank() || !customHeaders.isNullOrEmpty()) {
                                val mergedProps = buildMap {
                                    if (!customHeaders.isNullOrEmpty()) putAll(customHeaders)
                                    if (!ytCookie.isNullOrBlank()) put("Cookie", ytCookie)
                                }
                                DefaultHttpDataSource.Factory()
                                    .setUserAgent(YouTubeSessionCookieManager.DEFAULT_USER_AGENT)
                                    .setAllowCrossProtocolRedirects(true)
                                    .setConnectTimeoutMs(10_000)
                                    .setReadTimeoutMs(12_000)
                                    .setDefaultRequestProperties(mergedProps)
                                    .createDataSource()
                            } else {
                                defaultFactory.createDataSource()
                            }
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
    private val playerB: ExoPlayer = buildExoPlayer()

    private var activeSlotA = true
    val activePlayer: ExoPlayer
        get() = if (activeSlotA) playerA else playerB
    private val standbyPlayer: ExoPlayer
        get() = if (activeSlotA) playerB else playerA

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    var onActivePlayerChanged: ((ExoPlayer) -> Unit)? = null
    var onEnsureMediaSessionConnected: (() -> Unit)? = null

    private var crossfadeSeconds = 2
    private var autoPlayNext = true
    private var isCrossfading = false
    private var tickerJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var activePlayJob: Job? = null
    private var bufferingWatchdogJob: Job? = null
    private var retriedTrackId: String? = null

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
                crossfadeSeconds = prefs.crossfadeSeconds
                autoPlayNext = prefs.autoPlayNext
                val currentTrack = _playbackState.value.currentTrack
                applyTrackDspSettings(currentTrack, prefs.eqState)
                _playbackState.value = _playbackState.value.copy(
                    shuffleEnabled = prefs.shuffleEnabled,
                    repeatMode = prefs.repeatMode
                )
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
        _playbackState.value = _playbackState.value.copy(effectiveReplayGainDb = effectiveGain)
        return effectiveGain
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
            playTrackInternal(
                _playbackState.value.currentTrack!!,
                resetStandby = true,
                seekToMs = _playbackState.value.positionMs
            )
            return
        }
        if (p.isPlaying) {
            p.pause()
        } else {
            onEnsureMediaSessionConnected?.invoke()
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

    fun setSleepTimer(minutes: Int) = setSleepTimerMinutes(minutes)

    fun setSleepTimerMinutes(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        var remaining = minutes * 60_000L
        _playbackState.value = _playbackState.value.copy(
            sleepTimerMode = SleepTimerMode.AFTER_MINUTES,
            sleepTimerRemainingMs = remaining,
            sleepTimerRemainingTracks = 0
        )
        logger.log("SLEEP_TIMER", "INFO", "Hẹn giờ tắt nhạc sau $minutes phút")
        sleepTimerJob = scope.launch {
            while (remaining > 0L) {
                delay(1000L)
                remaining -= 1000L
                _playbackState.value = _playbackState.value.copy(
                    sleepTimerRemainingMs = remaining.coerceAtLeast(0L)
                )
            }
            triggerSleepTimerPause("Hẹn giờ tắt nhạc ($minutes phút) đã kích hoạt")
        }
    }

    fun setSleepTimerEndOfTrack() {
        sleepTimerJob?.cancel()
        _playbackState.value = _playbackState.value.copy(
            sleepTimerMode = SleepTimerMode.END_OF_CURRENT_TRACK,
            sleepTimerRemainingMs = 0L,
            sleepTimerRemainingTracks = 1
        )
        logger.log("SLEEP_TIMER", "INFO", "Hẹn giờ tắt nhạc sau bài hát hiện tại")
    }

    fun setSleepTimerAfterTracks(trackCount: Int) {
        sleepTimerJob?.cancel()
        if (trackCount <= 0) {
            cancelSleepTimer()
            return
        }
        _playbackState.value = _playbackState.value.copy(
            sleepTimerMode = SleepTimerMode.AFTER_N_TRACKS,
            sleepTimerRemainingMs = 0L,
            sleepTimerRemainingTracks = trackCount
        )
        logger.log("SLEEP_TIMER", "INFO", "Hẹn giờ tắt nhạc sau $trackCount bài tiếp theo")
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _playbackState.value = _playbackState.value.copy(
            sleepTimerMode = SleepTimerMode.OFF,
            sleepTimerRemainingMs = 0L,
            sleepTimerRemainingTracks = 0
        )
    }

    private fun triggerSleepTimerPause(logMessage: String) {
        activePlayer.pause()
        standbyPlayer.pause()
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            sleepTimerMode = SleepTimerMode.OFF,
            sleepTimerRemainingMs = 0L,
            sleepTimerRemainingTracks = 0
        )
        logger.log("PLAYER", "INFO", logMessage)
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

    private fun playTrackInternal(track: Track, resetStandby: Boolean, seekToMs: Long = 0L) {
        activePlayJob?.cancel()
        bufferingWatchdogJob?.cancel()
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

        activePlayJob = scope.launch {
            val prefs = settingsDataStore.preferencesFlow.first()
            applyTrackDspSettings(track, prefs.eqState)
            launchReplayGainProbeIfNeeded(track)

            val resolveTimeoutMs = if (
                track.source == MusicSource.TELEGRAM &&
                track.telegramOrigin == TelegramOrigin.PERSONAL_TELEGRAM
            ) 60_000L else 12_000L

            val resolvedResult = try {
                withTimeout(resolveTimeoutMs) {
                    resolvePlayableMedia(track)
                }
            } catch (_: TimeoutCancellationException) {
                Result.failure(
                    IllegalStateException("Hết thời gian tải '${track.title}' (quá ${resolveTimeoutMs / 1000} giây)")
                )
            }

            val resolvedMedia = resolvedResult.getOrElse { err ->
                _playbackState.value = _playbackState.value.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = err.message ?: "Không thể tải '${track.title}'"
                )
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

            launch(Dispatchers.IO) {
                trackDao.markPlayed(resolvedTrack.id, System.currentTimeMillis())
            }
            logger.log(
                "PLAYER",
                "INFO",
                "Đang phát: ${track.title} — ${track.artist} [${track.format.extension}]"
            )
        }
    }

    private fun launchReplayGainProbeIfNeeded(track: Track) {
        if (track.loudnessGainDb != 0f && track.bpm > 0) return
        val localPath = track.localPath ?: return
        scope.launch(Dispatchers.IO) {
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
        val mimeType: String? = null,
        val httpHeaders: Map<String, String> = emptyMap()
    )

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
                    com.laughing.musicplayer.core.model.AudioFormat.OPUS
                } else {
                    track.format
                }
                val cachedSampleRate = if (cachedFormat == com.laughing.musicplayer.core.model.AudioFormat.OPUS) 48000 else track.sampleRateHz
                val updated = track.copy(format = cachedFormat, sampleRateHz = cachedSampleRate)
                return Result.success(ResolvedPlayableMedia(Uri.fromFile(cached).toString(), updated))
            }
            val resolved = youTubeRepository.resolveAudioStreamUrl(videoId).getOrElse { err ->
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
            scope.launch(Dispatchers.IO) {
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
            val containerMime = when {
                resolved.mimeType.contains("webm", ignoreCase = true) -> MimeTypes.AUDIO_WEBM
                resolved.mimeType.contains("ogg", ignoreCase = true) -> MimeTypes.AUDIO_OGG
                resolved.mimeType.contains("mp4", ignoreCase = true) -> MimeTypes.AUDIO_MP4
                else -> null
            }
            return Result.success(ResolvedPlayableMedia(resolved.streamUrl, updatedTrack, containerMime))
        }
        if (track.source == MusicSource.TELEGRAM) {
            val latestTrack = withContext(Dispatchers.IO) {
                trackDao.getTrackById(track.id)?.toDomain() ?: track
            }
            if (latestTrack.telegramOrigin == TelegramOrigin.PUBLIC_TELEGRAM) {
                val repo = publicTelegramRepository
                    ?: return Result.failure(IllegalStateException("Dịch vụ kênh Telegram công khai chưa sẵn sàng"))
                val streamReq = repo.resolvePlayableRequest(latestTrack).getOrElse { err ->
                    return Result.failure(err)
                }
                if (streamReq.headers.isNotEmpty()) {
                    streamRequestHeaders[streamReq.url] = streamReq.headers
                }
                return Result.success(
                    ResolvedPlayableMedia(
                        uri = streamReq.url,
                        updatedTrack = latestTrack,
                        mimeType = streamReq.mimeType,
                        httpHeaders = streamReq.headers
                    )
                )
            }
            val verifiedTrack = withContext(Dispatchers.IO) {
                telegramFileResolver.ensureVerifiedLocalAudioFile(latestTrack)
            }.getOrElse { err ->
                return Result.failure(err)
            }
            val verifiedPath = verifiedTrack.localPath
                ?: return Result.failure(IllegalStateException("Không tìm thấy tệp Telegram đã xác minh cho '${track.title}'"))
            val playableUri = if (verifiedPath.startsWith("content://") || verifiedPath.startsWith("file://")) {
                verifiedPath
            } else {
                Uri.fromFile(File(verifiedPath)).toString()
            }
            return Result.success(ResolvedPlayableMedia(playableUri, verifiedTrack))
        }
        val uri = track.streamUri
            ?: return Result.failure(IllegalStateException("Không tìm thấy luồng phát cho '${track.title}'"))
        return Result.success(ResolvedPlayableMedia(uri, track))
    }

    private fun toArtworkUri(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("/")) Uri.fromFile(File(raw)) else Uri.parse(raw)
    }

    private fun buildMediaItem(track: Track, uri: String, mimeType: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(toArtworkUri(track.artworkUri))
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

    private fun startBufferingWatchdogIfNeeded() {
        bufferingWatchdogJob?.cancel()
        val track = _playbackState.value.currentTrack ?: return
        val isOnlineStream = track.localPath.isNullOrBlank() &&
            (track.source == MusicSource.TELEGRAM || track.source == MusicSource.YOUTUBE_MUSIC)
        if (!isOnlineStream) return
        bufferingWatchdogJob = scope.launch {
            delay(9_500L)
            val current = _playbackState.value
            if (current.isBuffering && current.currentTrack?.id == track.id) {
                activePlayer.stop()
                _playbackState.value = current.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = "Hết thời gian đệm '${track.title}' (quá 9 giây). Kiểm tra mạng rồi thử lại."
                )
                logger.log("PLAYER", "WARN", "Hết thời gian chờ đệm '${track.title}' (>9.5s)")
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
                        _playbackState.value = _playbackState.value.copy(isBuffering = true)
                        startBufferingWatchdogIfNeeded()
                    }
                    Player.STATE_READY -> {
                        bufferingWatchdogJob?.cancel()
                        retriedTrackId = null
                        val dur = player.duration.takeIf { it > 0 }
                            ?: _playbackState.value.currentTrack?.durationMs ?: 0L
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
                        bufferingWatchdogJob?.cancel()
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isSlotA != activeSlotA) return
                bufferingWatchdogJob?.cancel()
                val track = _playbackState.value.currentTrack
                logger.log("DECODER", "ERROR", "Lỗi phát '${track?.title}': ${error.errorCodeName}")
                if (track != null && track.source == MusicSource.YOUTUBE_MUSIC && retriedTrackId != track.id) {
                    retriedTrackId = track.id
                    val resumePos = player.currentPosition
                    playTrackInternal(track, resetStandby = true, seekToMs = resumePos)
                } else {
                    _playbackState.value = _playbackState.value.copy(
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = error.cause?.message
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
                val dur = p.duration.takeIf { it > 0 } ?: _playbackState.value.durationMs
                _playbackState.value = _playbackState.value.copy(
                    positionMs = pos,
                    durationMs = dur,
                    isPlaying = p.isPlaying
                )

                tickCount++
                if (tickCount % 10 == 0) {
                    val id = _playbackState.value.currentTrack?.id
                    launch(Dispatchers.IO) {
                        settingsDataStore.savePlaybackCheckpoint(id, pos)
                    }
                }

                // Do not start crossfade if track-based sleep timer expects playback to stop after current track
                val st = _playbackState.value
                val shouldStopAfterThisTrack = st.sleepTimerMode == SleepTimerMode.END_OF_CURRENT_TRACK ||
                    (st.sleepTimerMode == SleepTimerMode.AFTER_N_TRACKS && st.sleepTimerRemainingTracks <= 1)

                val fadeMs = crossfadeSeconds * 1000L
                if (!shouldStopAfterThisTrack && fadeMs > 0L && p.isPlaying && !isCrossfading && dur > fadeMs + 2000L) {
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
            val resolvedNext = try {
                withTimeout(15_000L) { resolvePlayableMedia(nextTrack).getOrNull() }
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

            _playbackState.value = _playbackState.value.copy(
                currentTrack = resolvedNextTrack,
                currentIndex = nextIdx,
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
        tickerJob?.cancel()
        sleepTimerJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        playerA.release()
        playerB.release()
    }
}
