package com.example.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.MainActivity
import com.example.MusicPlayerApplication
import com.example.core.model.Track
import com.example.core.util.VietnameseTextNormalizer
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking

@UnstableApi
class MusicLibraryService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null

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

        val launchIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            controller.activePlayer,
            LibraryCallback()
        )
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        mediaLibrarySession?.run {
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
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
