package com.example.data.youtube

import com.example.core.model.AudioFormat
import com.example.core.model.DownloadStatus
import com.example.core.model.MusicSource
import com.example.core.model.Track
import com.example.core.util.ActivityLogger
import com.example.core.util.AutoGenreClassifier
import com.example.core.util.VietnameseTextNormalizer
import com.example.data.local.TrackDao
import com.example.data.local.toEntity
import com.example.integrations.ScrobbleAndProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

enum class YouTubeSearchTab(val labelVi: String, val filterKey: String) {
    SONGS("Bài hát", "music_songs"),
    ALBUMS("Album", "music_albums"),
    ARTISTS("Nghệ sĩ", "music_artists"),
    PLAYLISTS("Playlist", "music_playlists")
}

data class YouTubeCollectionResult(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val url: String,
    val kind: YouTubeSearchTab
)

data class YouTubeSearchResult(
    val tracks: List<Track> = emptyList(),
    val collections: List<YouTubeCollectionResult> = emptyList()
)

class YouTubeMusicRepository(
    private val proxyManager: ScrobbleAndProxyManager,
    private val trackDao: TrackDao,
    private val logger: ActivityLogger
) {
    @Volatile
    private var initialized = false

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                val downloader = object : Downloader() {
                    override fun execute(request: NpRequest): NpResponse {
                        val builder = Request.Builder().url(request.url())
                        request.headers().forEach { (k, values) ->
                            values.forEach { v -> builder.addHeader(k, v) }
                        }
                        val bodyBytes = request.dataToSend()
                        when (request.httpMethod()) {
                            "POST" -> builder.post((bodyBytes ?: ByteArray(0)).toRequestBody(null))
                            "PUT" -> builder.put((bodyBytes ?: ByteArray(0)).toRequestBody(null))
                            else -> builder.get()
                        }
                        val client = proxyManager.getHttpClient()
                        client.newCall(builder.build()).execute().use { resp ->
                            val bodyStr = resp.body?.string().orEmpty()
                            return NpResponse(
                                resp.code,
                                resp.message,
                                resp.headers.toMultimap(),
                                bodyStr,
                                resp.request.url.toString()
                            )
                        }
                    }
                }
                NewPipe.init(downloader, Localization("vi", "VN"), ContentCountry("VN"))
                initialized = true
            }
        }
    }

    suspend fun search(
        query: String,
        tab: YouTubeSearchTab
    ): Result<YouTubeSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(YouTubeSearchResult())
        runCatching {
            proxyManager.refreshHttpClient()
            ensureInitialized()

            val extractor = ServiceList.YouTube.getSearchExtractor(
                query.trim(),
                listOf(tab.filterKey),
                ""
            )
            extractor.fetchPage()
            val items = extractor.initialPage.items

            val tracks = mutableListOf<Track>()
            val collections = mutableListOf<YouTubeCollectionResult>()

            items.forEach { item ->
                when (item) {
                    is StreamInfoItem -> {
                        val videoId = extractVideoId(item.url)
                        val thumb = item.thumbnails.maxByOrNull { it.width }?.url
                        val artist = item.uploaderName.orEmpty().ifBlank { "YouTube Music" }
                        val title = item.name.orEmpty()
                        val track = Track(
                            id = "yt_$videoId",
                            title = title,
                            artist = artist,
                            album = "YouTube Music",
                            source = MusicSource.YOUTUBE_MUSIC,
                            durationMs = (item.duration.coerceAtLeast(0L)) * 1000L,
                            // Compliant transparent labeling: YouTube streams are lossy AAC/M4A or OPUS
                            format = AudioFormat.M4A,
                            bitrateKbps = 128,
                            sampleRateHz = 44100,
                            bitDepth = null,
                            fileName = "$title.m4a",
                            artworkUri = thumb,
                            streamUri = item.url,
                            youtubeVideoId = videoId,
                            genre = AutoGenreClassifier.classify(title, artist, "YouTube Music", title),
                            downloadStatus = DownloadStatus.NONE
                        )
                        tracks += track
                    }
                    is PlaylistInfoItem -> {
                        collections += YouTubeCollectionResult(
                            id = item.url,
                            title = item.name.orEmpty(),
                            subtitle = "${item.uploaderName.orEmpty()} • ${item.streamCount} bài",
                            artworkUrl = item.thumbnails.maxByOrNull { it.width }?.url,
                            url = item.url,
                            kind = if (tab == YouTubeSearchTab.ALBUMS) YouTubeSearchTab.ALBUMS else YouTubeSearchTab.PLAYLISTS
                        )
                    }
                    is ChannelInfoItem -> {
                        collections += YouTubeCollectionResult(
                            id = item.url,
                            title = item.name.orEmpty(),
                            subtitle = "Nghệ sĩ YouTube Music",
                            artworkUrl = item.thumbnails.maxByOrNull { it.width }?.url,
                            url = item.url,
                            kind = YouTubeSearchTab.ARTISTS
                        )
                    }
                }
            }

            if (tracks.isNotEmpty()) {
                val existingMap = trackDao.getAllTracksOnce().associateBy { it.id }
                val mergedEntities = tracks.map { t ->
                    val existing = existingMap[t.id]
                    val merged = if (existing != null) {
                        t.copy(
                            isFavorite = existing.isFavorite,
                            playCount = existing.playCount,
                            lastPlayedEpochMs = existing.lastPlayedEpochMs,
                            localPath = existing.localPath,
                            downloadStatus = runCatching { DownloadStatus.valueOf(existing.downloadStatus) }
                                .getOrDefault(DownloadStatus.NONE),
                            downloadProgress = existing.downloadProgress
                        )
                    } else t
                    val blob = VietnameseTextNormalizer.normalize("${merged.title} ${merged.artist} ${merged.album}")
                    merged.toEntity(blob)
                }
                trackDao.upsertTracks(mergedEntities)
            }

            logger.log("YOUTUBE", "INFO", "Tìm kiếm YouTube Music '$query' (${tab.labelVi}): ${tracks.size} bài, ${collections.size} bộ sưu tập")
            YouTubeSearchResult(tracks = tracks, collections = collections)
        }.onFailure { err ->
            logger.log("YOUTUBE", "ERROR", "Lỗi tìm kiếm YouTube Music: ${err.message}")
        }
    }

    suspend fun resolveAudioStreamUrl(videoUrlOrId: String): Result<ResolvedYouTubeStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                proxyManager.refreshHttpClient()
                ensureInitialized()
                val fullUrl = if (videoUrlOrId.startsWith("http")) {
                    videoUrlOrId
                } else {
                    "https://www.youtube.com/watch?v=$videoUrlOrId"
                }
                val info = StreamInfo.getInfo(ServiceList.YouTube, fullUrl)
                val bestAudio = info.audioStreams
                    .filter { !it.content.isNullOrBlank() }
                    .sortedWith(
                        compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> {
                            it.averageBitrate
                        }
                    )
                    .firstOrNull()
                    ?: throw IllegalStateException("Không tìm thấy luồng âm thanh hỗ trợ cho video này")

                val mime = bestAudio.format?.mimeType.orEmpty()
                val format = if (mime.contains("webm", ignoreCase = true) || mime.contains("opus", ignoreCase = true)) {
                    AudioFormat.OPUS
                } else {
                    AudioFormat.M4A
                }
                ResolvedYouTubeStream(
                    streamUrl = bestAudio.content,
                    format = format,
                    bitrateKbps = bestAudio.averageBitrate.coerceAtLeast(64),
                    mimeType = mime.ifBlank { "audio/mp4" },
                    durationMs = (info.duration.coerceAtLeast(0L)) * 1000L,
                    artworkUrl = info.thumbnails.maxByOrNull { it.width }?.url
                )
            }.onFailure { err ->
                logger.log("YOUTUBE", "ERROR", "Lỗi trích xuất luồng YouTube: ${err.message}")
            }
        }

    private fun extractVideoId(url: String): String {
        val match = Regex("[?&]v=([^&]+)").find(url)
        return match?.groupValues?.getOrNull(1) ?: url.substringAfterLast('/').substringBefore('?')
    }
}

data class ResolvedYouTubeStream(
    val streamUrl: String,
    val format: AudioFormat,
    val bitrateKbps: Int,
    val mimeType: String,
    val durationMs: Long,
    val artworkUrl: String?
)
