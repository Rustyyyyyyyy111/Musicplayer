package com.example.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.glance.appwidget.updateAll
import com.example.core.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class WidgetQueueEntry(
    val id: String,
    val title: String,
    val artist: String,
    val sourceLabel: String
)

@Serializable
data class WidgetSnapshot(
    val trackId: String? = null,
    val title: String = "Chưa phát bài hát",
    val artist: String = "Music Player",
    val sourceLabel: String = "Apple Music Style",
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val queue: List<WidgetQueueEntry> = emptyList(),
    val updatedAtEpochMs: Long = 0L
)

object WidgetStateManager {
    private val json = Json { ignoreUnknownKeys = true }
    private const val SNAPSHOT_FILE = "glance_widget_snapshot.json"

    fun readSnapshot(context: Context): WidgetSnapshot {
        val file = File(context.filesDir, SNAPSHOT_FILE)
        if (!file.exists()) return WidgetSnapshot()
        return runCatching {
            json.decodeFromString<WidgetSnapshot>(file.readText())
        }.getOrDefault(WidgetSnapshot())
    }

    suspend fun publishPlaybackSnapshot(
        context: Context,
        currentTrack: Track?,
        queue: List<Track>,
        isPlaying: Boolean
    ) {
        val snapshot = WidgetSnapshot(
            trackId = currentTrack?.id,
            title = currentTrack?.title ?: "Chưa phát bài hát",
            artist = currentTrack?.artist ?: "Mở Music Player để bắt đầu",
            sourceLabel = currentTrack?.let {
                "${it.source.labelVi} • ${it.format.extension.uppercase()}"
            } ?: "Telegram • YouTube • Offline",
            artworkUri = currentTrack?.artworkUri,
            isPlaying = isPlaying,
            queue = queue.take(8).map {
                WidgetQueueEntry(
                    id = it.id,
                    title = it.title,
                    artist = it.artist,
                    sourceLabel = it.source.labelVi
                )
            },
            updatedAtEpochMs = System.currentTimeMillis()
        )
        runCatching {
            File(context.filesDir, SNAPSHOT_FILE).writeText(json.encodeToString(snapshot))
        }
        runCatching {
            MiniPlayerGlanceWidget().updateAll(context)
        }
        runCatching {
            LiquidGlassPlaylistGlanceWidget().updateAll(context)
        }
    }

    /**
     * Decodes a safe downsampled RGB_565 bitmap (max 144x144px) for Glance RemoteViews memory compliance.
     */
    fun decodeWidgetArtwork(context: Context, artworkUri: String?, maxDimPx: Int = 144): Bitmap? {
        if (artworkUri.isNullOrBlank()) return null
        return runCatching {
            val uri = Uri.parse(artworkUri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openUriStream(context, uri, artworkUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while ((bounds.outWidth / sampleSize) > maxDimPx * 2 || (bounds.outHeight / sampleSize) > maxDimPx * 2) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            openUriStream(context, uri, artworkUri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    private fun openUriStream(context: Context, uri: Uri, raw: String) = when {
        raw.startsWith("content://") -> context.contentResolver.openInputStream(uri)
        raw.startsWith("file://") -> File(uri.path ?: "").takeIf { it.exists() }?.inputStream()
        raw.startsWith("/") -> File(raw).takeIf { it.exists() }?.inputStream()
        else -> null
    }
}
