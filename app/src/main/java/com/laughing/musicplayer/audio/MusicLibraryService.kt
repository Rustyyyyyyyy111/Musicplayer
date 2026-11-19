package com.laughing.musicplayer.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.laughing.musicplayer.MainActivity
import com.laughing.musicplayer.MusicPlayerApplication
import com.laughing.musicplayer.core.model.LyricLine
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.google.common.collect.ImmutableList
import java.io.File
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
class MusicLibraryService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var currentLyricLine: String? = null

    @Volatile
    private var showLyricsOnLockScreen: Boolean = true

    private var cachedLyricsTrackId: String? = null
    private var cachedLyricsLines: List<LyricLine> = emptyList()
    private var lyricsLoadJob: Job? = null

    companion object {
        private const val ROOT_ID = "root"
        private const val NODE_LIBRARY = "node_library"
        private const val NODE_ARTISTS = "node_artists"
        private const val NODE_ALBUMS = "node_albums"
        private const val NODE_PLAYLISTS = "node_playlists"
        private const val NODE_FAVORITES = "node_favorites"
        private const val NODE_QUEUE = "node_queue"

        internal val ALWAYS_ENABLED_TRANSPORT_COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            )
            .build()

        internal fun augmentAvailableCommands(base: Player.Commands): Player.Commands {
            return base.buildUpon()
                .addAll(ALWAYS_ENABLED_TRANSPORT_COMMANDS)
                .build()
        }

        internal fun buildCompactTransportButtons(showPauseButton: Boolean): ImmutableList<CommandButton> {
            val prevExtras = Bundle().apply {
                putInt(DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX, 0)
            }
            val playPauseExtras = Bundle().apply {
                putInt(DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX, 1)
            }
            val nextExtras = Bundle().apply {
                putInt(DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX, 2)
            }

            val prevButton = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setDisplayName("Bài trước")
                .setEnabled(true)
                .setExtras(prevExtras)
                .build()

            val playPauseButton = CommandButton.Builder(
                if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY
            )
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                .setDisplayName(if (showPauseButton) "Tạm dừng" else "Phát")
                .setEnabled(true)
                .setExtras(playPauseExtras)
                .build()

            val nextButton = CommandButton.Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setDisplayName("Bài tiếp theo")
                .setEnabled(true)
                .setExtras(nextExtras)
                .build()

            return ImmutableList.of(prevButton, playPauseButton, nextButton)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as MusicPlayerApplication
        val controller = app.container.playerController

        setMediaNotificationProvider(LyricsMediaNotificationProvider(this))

        val launchIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val forwardingPlayer = LyricsForwardingPlayer(controller.activePlayer, controller)
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            forwardingPlayer,
            LibraryCallback()
        )
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        controller.onActivePlayerChanged = { newPlayer ->
            mediaLibrarySession?.player = LyricsForwardingPlayer(newPlayer, controller)
        }

        observeLockScreenLyricPreferences(app)
        observePlaybackForSyncedLyrics(app)
    }

    private fun observeLockScreenLyricPreferences(app: MusicPlayerApplication) {
        serviceScope.launch {
            app.container.settingsDataStore.preferencesFlow
                .map { it.showLyricsOnLockScreen }
                .distinctUntilChanged()
                .collect { enabled ->
                    showLyricsOnLockScreen = enabled
                    if (!enabled) {
                        currentLyricLine = null
                    }
                    triggerNotificationUpdate()
                }
        }
    }

    private fun observePlaybackForSyncedLyrics(app: MusicPlayerApplication) {
        serviceScope.launch {
            val controller = app.container.playerController
            combine(
                controller.playbackState
                    .map { it.currentTrack }
                    .distinctUntilChanged { a, b -> a?.id == b?.id },
                controller.playbackProgress
            ) { track, progress -> track to progress.positionMs }
                .collect { (track, positionMs) ->
                    if (track == null) {
                        if (currentLyricLine != null) {
                            currentLyricLine = null
                            triggerNotificationUpdate()
                        }
                        return@collect
                    }

                    if (cachedLyricsTrackId != track.id) {
                        cachedLyricsTrackId = track.id
                        cachedLyricsLines = emptyList()
                        if (currentLyricLine != null) {
                            currentLyricLine = null
                            triggerNotificationUpdate()
                        }
                        lyricsLoadJob?.cancel()
                        lyricsLoadJob = launch(Dispatchers.IO) {
                            val res = app.container.lyricsEngine.loadLyricsForTrack(track)
                            cachedLyricsLines = res?.lines.orEmpty()
                        }
                    }

                    if (!showLyricsOnLockScreen || cachedLyricsLines.isEmpty()) {
                        if (currentLyricLine != null) {
                            currentLyricLine = null
                            triggerNotificationUpdate()
                        }
                        return@collect
                    }

                    val activeText = cachedLyricsLines
                        .lastOrNull { it.timestampMs <= positionMs }
                        ?.text
                        ?.takeIf { it.isNotBlank() }

                    if (activeText != currentLyricLine) {
                        currentLyricLine = activeText
                        triggerNotificationUpdate()
                    }
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        val app = application as? MusicPlayerApplication
        app?.container?.playerController?.onActivePlayerChanged = null
        lyricsLoadJob?.cancel()
        serviceScope.cancel()
        mediaLibrarySession?.run {
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }

    /**
     * Custom MediaNotification.Provider that projects the live synchronized `.lrc` line
     * and enforces 3-button compact transport controls: [Previous, Play/Pause, Next].
     */
    private inner class LyricsMediaNotificationProvider(context: Context) :
        DefaultMediaNotificationProvider(context) {

        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            showPauseButton: Boolean
        ): ImmutableList<CommandButton> {
            return buildCompactTransportButtons(showPauseButton)
        }

        override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence? {
            return super.getNotificationContentTitle(metadata)
        }

        override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
            val lyric = currentLyricLine
            return if (showLyricsOnLockScreen && !lyric.isNullOrBlank()) {
                "♪ $lyric"
            } else {
                super.getNotificationContentText(metadata)
            }
        }
    }

    /**
     * Non-destructive ForwardingPlayer that enriches MediaSession metadata with the active
     * synced lyric subtitle, exposes Next/Previous commands even on single-track queues,
     * and routes lock-screen Next/Previous taps into DualPlayerCrossfadeController.
     */
    internal inner class LyricsForwardingPlayer(
        player: Player,
        private val controller: DualPlayerCrossfadeController
    ) : ForwardingPlayer(player) {

        private val listenerWrappers = ConcurrentHashMap<Player.Listener, Player.Listener>()

        override fun getAvailableCommands(): Player.Commands {
            return augmentAvailableCommands(super.getAvailableCommands())
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
                else -> super.isCommandAvailable(command)
            }
        }

        override fun hasNextMediaItem(): Boolean = true

        override fun hasPreviousMediaItem(): Boolean = true

        override fun seekToNext() {
            controller.skipToNext()
        }

        override fun seekToNextMediaItem() {
            controller.skipToNext()
        }

        override fun seekToPrevious() {
            controller.skipToPrevious()
        }

        override fun seekToPreviousMediaItem() {
            controller.skipToPrevious()
        }

        override fun addListener(listener: Player.Listener) {
            val wrapper = object : Player.Listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    listener.onAvailableCommandsChanged(augmentAvailableCommands(availableCommands))
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    listener.onEvents(this@LyricsForwardingPlayer, events)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    listener.onIsPlayingChanged(isPlaying)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    listener.onPlaybackStateChanged(playbackState)
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    listener.onPlayWhenReadyChanged(playWhenReady, reason)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    listener.onMediaItemTransition(mediaItem, reason)
                }

                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    listener.onMediaMetadataChanged(this@LyricsForwardingPlayer.mediaMetadata)
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
                }
            }
            listenerWrappers[listener] = wrapper
            super.addListener(wrapper)
        }

        override fun removeListener(listener: Player.Listener) {
            val wrapper = listenerWrappers.remove(listener)
            super.removeListener(wrapper ?: listener)
        }

        override fun getMediaMetadata(): MediaMetadata {
            val base = super.getMediaMetadata()
            val lyric = currentLyricLine
            return if (showLyricsOnLockScreen && !lyric.isNullOrBlank()) {
                base.buildUpon()
                    .setSubtitle("♪ $lyric")
                    .build()
            } else {
                base
            }
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val playerCommands = augmentAvailableCommands(
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = browsableItem(ROOT_ID, "Music Player", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId == ROOT_ID) {
                val rootChildren = listOf(
                    browsableItem(NODE_LIBRARY, "Thư viện nhạc", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                    browsableItem(NODE_ARTISTS, "Nghệ sĩ", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                    browsableItem(NODE_ALBUMS, "Album", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                    browsableItem(NODE_PLAYLISTS, "Playlist", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                    browsableItem(NODE_FAVORITES, "Yêu thích", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                    browsableItem(NODE_QUEUE, "Hàng đợi", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                )
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(rootChildren), params))
            }
            val app = application as MusicPlayerApplication
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val items: List<MediaItem> = if (parentId == NODE_QUEUE) {
                        app.container.playerController.playbackState.value.queue.map { it.toBrowseMediaItem() }
                    } else {
                        val allTracks = app.container.database.trackDao().getAllTracksOnce().map { it.toDomain() }
                        when (parentId) {
                            NODE_LIBRARY -> allTracks.map { it.toBrowseMediaItem() }
                            NODE_FAVORITES -> allTracks.filter { it.isFavorite }.map { it.toBrowseMediaItem() }
                            NODE_ARTISTS -> allTracks.groupBy { it.telegramTopicName ?: it.artist }.keys.map { artist ->
                                browsableItem("artist:$artist", artist, MediaMetadata.MEDIA_TYPE_ARTIST)
                            }
                            NODE_ALBUMS -> allTracks.groupBy { it.album }.keys.map { album ->
                                browsableItem("album:$album", album, MediaMetadata.MEDIA_TYPE_ALBUM)
                            }
                            NODE_PLAYLISTS -> emptyList()
                            else -> when {
                                parentId.startsWith("artist:") -> {
                                    val target = parentId.removePrefix("artist:")
                                    allTracks.filter { (it.telegramTopicName ?: it.artist) == target }
                                        .map { it.toBrowseMediaItem() }
                                }
                                parentId.startsWith("album:") -> {
                                    val target = parentId.removePrefix("album:")
                                    allTracks.filter { it.album == target }.map { it.toBrowseMediaItem() }
                                }
                                else -> emptyList()
                            }
                        }
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } catch (t: Throwable) {
                    future.setException(t)
                }
            }
            return future
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val app = application as MusicPlayerApplication
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val allTracks = app.container.database.trackDao().getAllTracksOnce().map { it.toDomain() }
                    val matched = allTracks.filter {
                        VietnameseTextNormalizer.matches("${it.title} ${it.artist} ${it.album}", query)
                    }.map { it.toBrowseMediaItem() }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(matched), params))
                } catch (t: Throwable) {
                    future.setException(t)
                }
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val app = application as MusicPlayerApplication
            val trackDao = app.container.database.trackDao()
            val itemsSnapshot = mediaItems.toList()
            val future = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val ids = itemsSnapshot.map { it.mediaId }.distinct()
                    val byId = if (ids.isEmpty()) {
                        emptyMap()
                    } else {
                        trackDao.getTracksByIds(ids).associate { it.id to it.toDomain() }
                    }
                    val resolved = itemsSnapshot.map { item ->
                        val track = byId[item.mediaId] ?: return@map item
                        val uri = track.localPath ?: track.streamUri ?: return@map item
                        item.buildUpon().setUri(uri).build()
                    }.toMutableList()
                    future.set(resolved)
                } catch (t: Throwable) {
                    future.setException(t)
                }
            }
            return future
        }
    }

    private fun browsableItem(id: String, title: String, type: Int): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .build()
            )
            .build()
    }

    private fun Track.toBrowseMediaItem(): MediaItem {
        val artUri = artworkUri?.takeIf { it.isNotBlank() }?.let { raw ->
            if (raw.startsWith("/")) Uri.fromFile(File(raw)) else Uri.parse(raw)
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }
}
