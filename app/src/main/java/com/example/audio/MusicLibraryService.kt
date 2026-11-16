package com.example.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.MainActivity
import com.example.MusicPlayerApplication
import com.example.core.model.LyricLine
import com.example.core.model.Track
import com.example.core.util.VietnameseTextNormalizer
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

        val forwardingPlayer = LyricsForwardingPlayer(controller.activePlayer)
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            forwardingPlayer,
            LibraryCallback()
        )
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        controller.onActivePlayerChanged = { newPlayer ->
            mediaLibrarySession?.player = LyricsForwardingPlayer(newPlayer)
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
            app.container.playerController.playbackState.collect { state ->
                val track = state.currentTrack
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
                    .lastOrNull { it.timestampMs <= state.positionMs }
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
     * directly onto the Android MediaStyle Notification & Lock Screen media card.
     */
    private inner class LyricsMediaNotificationProvider(context: Context) :
        DefaultMediaNotificationProvider(context) {

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
     * synced lyric subtitle without modifying ExoPlayer's active MediaItem.
     */
    private inner class LyricsForwardingPlayer(player: Player) : ForwardingPlayer(player) {
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
            val app = application as MusicPlayerApplication
            val allTracks = runBlocking { app.container.database.trackDao().getAllTracksOnce().map { it.toDomain() } }

            val items: List<MediaItem> = when (parentId) {
                ROOT_ID -> listOf(
                    browsableItem(NODE_LIBRARY, "Thư viện nhạc", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                    browsableItem(NODE_ARTISTS, "Nghệ sĩ", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                    browsableItem(NODE_ALBUMS, "Album", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                    browsableItem(NODE_PLAYLISTS, "Playlist", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                    browsableItem(NODE_FAVORITES, "Yêu thích", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                    browsableItem(NODE_QUEUE, "Hàng đợi", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                )
                NODE_LIBRARY -> allTracks.map { it.toBrowseMediaItem() }
                NODE_FAVORITES -> allTracks.filter { it.isFavorite }.map { it.toBrowseMediaItem() }
                NODE_QUEUE -> app.container.playerController.playbackState.value.queue.map { it.toBrowseMediaItem() }
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
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
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
            val allTracks = runBlocking { app.container.database.trackDao().getAllTracksOnce().map { it.toDomain() } }
            val matched = allTracks.filter {
                VietnameseTextNormalizer.matches("${it.title} ${it.artist} ${it.album}", query)
            }.map { it.toBrowseMediaItem() }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(matched), params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val app = application as MusicPlayerApplication
            val trackDao = app.container.database.trackDao()
            val resolved = runBlocking {
                mediaItems.mapNotNull { item ->
                    val track = trackDao.getTrackById(item.mediaId)?.toDomain() ?: return@mapNotNull item
                    val uri = track.localPath ?: track.streamUri ?: return@mapNotNull item
                    item.buildUpon().setUri(uri).build()
                }.toMutableList()
            }
            return Futures.immediateFuture(resolved)
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
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()
    }
}
