package com.laughing.musicplayer.data.youtube

import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.AutoGenreClassifier
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.local.toEntity
import com.laughing.musicplayer.integrations.NetworkProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

enum class YouTubeSearchTab(val labelVi: String, val filterKey: String) {
    SONGS("Bài hát", "music_songs"),
    ALBUMS("Album", "music_albums"),
    ARTISTS("Nghệ sĩ", "music_artists"),
    PLAYLISTS("Playlist", "music_playlists")
}

enum class YouTubeExploreMood(
    val labelVi: String,
    val songQuery: String,
    val playlistQuery: String
) {
    VPOP("V-Pop", "Nhạc V-Pop Hot Hit", "Playlist V-Pop Hot"),
    CHILL("Chill", "Nhạc Chill Thư Giãn", "Playlist Nhạc Chill"),
    BALLAD("Ballad", "Nhạc Ballad Việt Hay Nhất", "Playlist Ballad Việt"),
    ACOUSTIC("Acoustic", "Acoustic Mộc Nhẹ Nhàng", "Playlist Acoustic Việt"),
    LOFI("Lo-fi", "Lofi Chill Beat Nhẹ", "Lofi Study Chill Playlist"),
    WORKOUT("Tập luyện", "Nhạc Tập Gym Sôi Động", "Workout Gym Music Playlist"),
    EDM_REMIX("EDM / Remix", "Nhạc Remix Vinahouse EDM", "EDM Remix Playlist"),
    BOLERO("Bolero", "Nhạc Bolero Trữ Tình", "Playlist Bolero Hay Nhất")
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

data class YouTubeExploreData(
    val trendingTracks: List<Track> = emptyList(),
    val chartTracks: List<Track> = emptyList(),
    val featuredPlaylists: List<YouTubeCollectionResult> = emptyList(),
    val newReleases: List<YouTubeCollectionResult> = emptyList()
)

data class YouTubeMoodExploreData(
    val mood: YouTubeExploreMood,
    val tracks: List<Track> = emptyList(),
    val playlists: List<YouTubeCollectionResult> = emptyList()
)

class YouTubeMusicRepository(
    private val proxyManager: NetworkProxyManager,
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
                        var hasUserAgent = false
                        var hasCookie = false
                        request.headers().forEach { (k, values) ->
                            if (k.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                            if (k.equals("Cookie", ignoreCase = true)) hasCookie = true
                            values.forEach { v -> builder.addHeader(k, v) }
                        }
                        if (!hasUserAgent) {
                            builder.header("User-Agent", YouTubeSessionCookieManager.DEFAULT_USER_AGENT)
                        }
                        if (!hasCookie) {
                            proxyManager.getYouTubeCookieHeader(request.url())?.let { cookie ->
                                builder.header("Cookie", cookie)
                            }
                        }
                        val bodyBytes = request.dataToSend()
                        when (request.httpMethod()) {
                            "POST" -> builder.post((bodyBytes ?: ByteArray(0)).toRequestBody(null))
                            "PUT" -> builder.put((bodyBytes ?: ByteArray(0)).toRequestBody(null))
                            else -> builder.get()
                        }
                        val client = proxyManager.getHttpClient()
                        val call = client.newCall(builder.build())
                        if (Thread.currentThread().isInterrupted) {
                            call.cancel()
                            throw java.io.InterruptedIOException("Interrupted")
                        }
                        call.execute().use { resp ->
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
        executeWithSingleRetry(onRetryLog = {
            logger.log("YOUTUBE", "WARN", "Tìm kiếm YouTube Music chậm, đang tự thử lại 1 lần...")
        }) {
            try {
                withTimeout(STREAM_FETCH_TIMEOUT_MS) {
                    proxyManager.refreshHttpClient()
                    ensureInitialized()
                    val raw = runInterruptible { executeSearchInternal(query.trim(), tab) }
                    val mergedTracks = mergeAndPersistTracks(raw.tracks)
                    logger.log("YOUTUBE", "INFO", "Tìm kiếm YouTube Music '$query' (${tab.labelVi}): ${mergedTracks.size} bài, ${raw.collections.size} bộ sưu tập")
                    Result.success(YouTubeSearchResult(tracks = mergedTracks, collections = raw.collections))
                }
            } catch (_: TimeoutCancellationException) {
                logger.log("YOUTUBE", "WARN", "Hết thời gian tìm kiếm YouTube Music (quá 7.5 giây)")
                Result.failure(IOException("Hết thời gian kết nối YouTube Music"))
            } catch (_: SocketTimeoutException) {
                logger.log("YOUTUBE", "WARN", "Timeout socket khi tìm YouTube Music")
                Result.failure(IOException("Mạng quá chậm, không thể tải kết quả YouTube Music"))
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                logger.log("YOUTUBE", "ERROR", "Lỗi tìm kiếm YouTube Music: ${err.message}")
                Result.failure(err)
            }
        }
    }

    suspend fun fetchExploreFeed(): Result<YouTubeExploreData> = withContext(Dispatchers.IO) {
        try {
            withTimeout(FEED_TIMEOUT_MS) {
                proxyManager.refreshHttpClient()
                ensureInitialized()
                coroutineScope {
                    val chartsDeferred = async {
                        runCatching {
                            runInterruptible {
                                executeSearchInternal("Top 100 Nhạc Việt Bảng Xếp Hạng", YouTubeSearchTab.SONGS).tracks
                            }
                        }.getOrDefault(emptyList())
                    }
                    val trendingDeferred = async {
                        runCatching {
                            runInterruptible {
                                executeSearchInternal("Nhạc Việt Thịnh Hành Hot Nhất", YouTubeSearchTab.SONGS).tracks
                            }
                        }.getOrDefault(emptyList())
                    }
                    val playlistsDeferred = async {
                        runCatching {
                            runInterruptible {
                                executeSearchInternal("Playlist Nhạc Việt Nổi Bật", YouTubeSearchTab.PLAYLISTS).collections
                            }
                        }.getOrDefault(emptyList())
                    }
                    val albumsDeferred = async {
                        runCatching {
                            runInterruptible {
                                executeSearchInternal("Album Nhạc Mới Phát Hành", YouTubeSearchTab.ALBUMS).collections
                            }
                        }.getOrDefault(emptyList())
                    }

                    val chartRaw = chartsDeferred.await().take(12)
                    val chartIds = chartRaw.map { it.id }.toSet()
                    val trendingRaw = trendingDeferred.await()
                        .filter { it.id !in chartIds }
                        .ifEmpty { trendingDeferred.await() }
                        .take(12)
                    val playlists = playlistsDeferred.await().take(15)
                    val newReleases = albumsDeferred.await().take(15)

                    if (chartRaw.isEmpty() && trendingRaw.isEmpty() && playlists.isEmpty() && newReleases.isEmpty()) {
                        throw IOException("Không thể tải dữ liệu Khám phá YouTube Music")
                    }

                    val persistedMap = mergeAndPersistTracks(chartRaw + trendingRaw).associateBy { it.id }
                    val charts = chartRaw.map { persistedMap[it.id] ?: it }
                    val trending = trendingRaw.map { persistedMap[it.id] ?: it }

                    logger.log(
                        "YOUTUBE",
                        "INFO",
                        "Tải Khám phá: ${charts.size} BXH, ${trending.size} thịnh hành, ${playlists.size} playlist, ${newReleases.size} phát hành mới"
                    )
                    Result.success(
                        YouTubeExploreData(
                            trendingTracks = trending,
                            chartTracks = charts,
                            featuredPlaylists = playlists,
                            newReleases = newReleases
                        )
                    )
                }
            }
        } catch (_: TimeoutCancellationException) {
            Result.failure(IOException("Hết thời gian tải Khám phá YouTube Music"))
        } catch (err: Exception) {
            if (err is kotlinx.coroutines.CancellationException) throw err
            logger.log("YOUTUBE", "ERROR", "Lỗi tải Khám phá YouTube Music: ${err.message}")
            Result.failure(err)
        }
    }

    suspend fun fetchMoodExplore(mood: YouTubeExploreMood): Result<YouTubeMoodExploreData> =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(FEED_TIMEOUT_MS) {
                    proxyManager.refreshHttpClient()
                    ensureInitialized()
                    coroutineScope {
                        val songsDeferred = async {
                            runCatching {
                                runInterruptible {
                                    executeSearchInternal(mood.songQuery, YouTubeSearchTab.SONGS).tracks
                                }
                            }.getOrDefault(emptyList())
                        }
                        val playlistsDeferred = async {
                            runCatching {
                                runInterruptible {
                                    executeSearchInternal(mood.playlistQuery, YouTubeSearchTab.PLAYLISTS).collections
                                }
                            }.getOrDefault(emptyList())
                        }
                        val songs = mergeAndPersistTracks(songsDeferred.await().take(20))
                        val playlists = playlistsDeferred.await().take(12)
                        if (songs.isEmpty() && playlists.isEmpty()) {
                            throw IOException("Không tìm thấy nội dung cho ${mood.labelVi}")
                        }
                        Result.success(
                            YouTubeMoodExploreData(
                                mood = mood,
                                tracks = songs,
                                playlists = playlists
                            )
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                Result.failure(IOException("Hết thời gian tải mục ${mood.labelVi}"))
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                logger.log("YOUTUBE", "ERROR", "Lỗi tải chủ đề ${mood.labelVi}: ${err.message}")
                Result.failure(err)
            }
        }

    suspend fun fetchPlaylistTracks(collection: YouTubeCollectionResult): Result<List<Track>> =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(FEED_TIMEOUT_MS) {
                    proxyManager.refreshHttpClient()
                    ensureInitialized()

                    val extractedTracks = if (collection.kind == YouTubeSearchTab.ARTISTS) {
                        runInterruptible {
                            executeSearchInternal(collection.title, YouTubeSearchTab.SONGS).tracks
                        }
                    } else {
                        val playlistUrl = normalizePlaylistUrl(collection.url)
                        val playlistInfo = runCatching {
                            runInterruptible {
                                PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
                            }
                        }.getOrNull()
                        val streamItems = playlistInfo?.relatedItems
                            ?.filterIsInstance<StreamInfoItem>()
                            .orEmpty()
                        if (streamItems.isNotEmpty()) {
                            streamItems.map { item ->
                                mapStreamItemToTrack(
                                    item = item,
                                    albumName = collection.title.ifBlank { "YouTube Music" }
                                )
                            }
                        } else {
                            // Graceful fallback to YouTube Music songs query if album/artist browse URL isn't a classic list= URL
                            runInterruptible {
                                executeSearchInternal(collection.title, YouTubeSearchTab.SONGS).tracks
                            }
                        }
                    }

                    if (extractedTracks.isEmpty()) {
                        throw IOException("Playlist này hiện chưa có bài hát hỗ trợ")
                    }

                    val merged = mergeAndPersistTracks(extractedTracks)
                    logger.log("YOUTUBE", "INFO", "Tải playlist '${collection.title}': ${merged.size} bài hát")
                    Result.success(merged)
                }
            } catch (_: TimeoutCancellationException) {
                Result.failure(IOException("Hết thời gian tải danh sách phát"))
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                logger.log("YOUTUBE", "ERROR", "Lỗi tải playlist '${collection.title}': ${err.message}")
                Result.failure(err)
            }
        }

    private fun normalizePlaylistUrl(url: String): String {
        val listMatch = Regex("[?&]list=([^&]+)").find(url)?.groupValues?.getOrNull(1)
        return if (!listMatch.isNullOrBlank()) {
            "https://www.youtube.com/playlist?list=$listMatch"
        } else {
            url
        }
    }

    private fun executeSearchInternal(
        query: String,
        tab: YouTubeSearchTab
    ): YouTubeSearchResult {
        val extractor = ServiceList.YouTube.getSearchExtractor(
            query,
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
                    tracks += mapStreamItemToTrack(item, albumName = "YouTube Music")
                }
                is PlaylistInfoItem -> {
                    collections += YouTubeCollectionResult(
                        id = item.url,
                        title = item.name.orEmpty(),
                        subtitle = buildString {
                            val uploader = item.uploaderName.orEmpty().trim()
                            if (uploader.isNotBlank()) append(uploader)
                            if (item.streamCount > 0) {
                                if (isNotEmpty()) append(" • ")
                                append("${item.streamCount} bài")
                            }
                            if (isEmpty()) append("Playlist YouTube Music")
                        },
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
        return YouTubeSearchResult(tracks = tracks, collections = collections)
    }

    private fun mapStreamItemToTrack(
        item: StreamInfoItem,
        albumName: String
    ): Track {
        val videoId = extractVideoId(item.url)
        val thumb = item.thumbnails.maxByOrNull { it.width }?.url
        val artist = item.uploaderName.orEmpty().ifBlank { "YouTube Music" }
        val title = item.name.orEmpty()
        return Track(
            id = "yt_$videoId",
            title = title,
            artist = artist,
            album = albumName,
            source = MusicSource.YOUTUBE_MUSIC,
            durationMs = (item.duration.coerceAtLeast(0L)) * 1000L,
            format = AudioFormat.OPUS,
            bitrateKbps = 160,
            sampleRateHz = 48000,
            bitDepth = null,
            fileName = "$title.opus",
            artworkUri = thumb,
            streamUri = item.url,
            youtubeVideoId = videoId,
            genre = AutoGenreClassifier.classify(title, artist, albumName, title),
            downloadStatus = DownloadStatus.NONE
        )
    }

    private suspend fun mergeAndPersistTracks(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return emptyList()
        val distinctTracks = tracks.distinctBy { it.id }
        val existingMap = trackDao.getAllTracksOnce().associateBy { it.id }
        val mergedDomain = distinctTracks.map { t ->
            val existing = existingMap[t.id]
            if (existing != null) {
                val existingStatus = runCatching { DownloadStatus.valueOf(existing.downloadStatus) }
                    .getOrDefault(DownloadStatus.NONE)
                val existingFmt = runCatching { AudioFormat.valueOf(existing.format) }
                    .getOrDefault(AudioFormat.OPUS)
                t.copy(
                    format = if (existingStatus == DownloadStatus.COMPLETED) existingFmt else AudioFormat.OPUS,
                    bitrateKbps = if (existingStatus == DownloadStatus.COMPLETED && existing.bitrateKbps > 0) existing.bitrateKbps else t.bitrateKbps,
                    sampleRateHz = if (existingStatus == DownloadStatus.COMPLETED && existing.sampleRateHz > 0) existing.sampleRateHz else t.sampleRateHz,
                    fileName = if (existingStatus == DownloadStatus.COMPLETED && existing.fileName.isNotBlank()) existing.fileName else t.fileName,
                    isFavorite = existing.isFavorite,
                    playCount = existing.playCount,
                    lastPlayedEpochMs = existing.lastPlayedEpochMs,
                    localPath = existing.localPath,
                    downloadStatus = existingStatus,
                    downloadProgress = existing.downloadProgress
                )
            } else {
                t
            }
        }
        val entities = mergedDomain.map { merged ->
            val blob = VietnameseTextNormalizer.normalize("${merged.title} ${merged.artist} ${merged.album}")
            merged.toEntity(blob)
        }
        trackDao.upsertTracks(entities)
        return mergedDomain
    }

    suspend fun resolveAudioStreamUrl(
        videoUrlOrId: String,
        allowRetry: Boolean = true
    ): Result<ResolvedYouTubeStream> = withContext(Dispatchers.IO) {
        val attemptBlock: suspend () -> Result<ResolvedYouTubeStream> = {
            try {
                withTimeout(STREAM_FETCH_TIMEOUT_MS) {
                    proxyManager.refreshHttpClient()
                    ensureInitialized()
                    val fullUrl = if (videoUrlOrId.startsWith("http")) {
                        videoUrlOrId
                    } else {
                        "https://www.youtube.com/watch?v=$videoUrlOrId"
                    }
                    val info = runInterruptible { StreamInfo.getInfo(ServiceList.YouTube, fullUrl) }
                    val bestAudio = selectBestOpusFirstStream(info.audioStreams)
                        ?: throw IllegalStateException("Không tìm thấy luồng âm thanh hỗ trợ cho video này")

                    val mime = bestAudio.format?.mimeType.orEmpty()
                    val isOpus = isOpusStreamCandidate(
                        codec = bestAudio.codec,
                        mimeType = mime,
                        formatName = bestAudio.format?.name
                    )
                    val format = if (isOpus) AudioFormat.OPUS else AudioFormat.M4A
                    val sampleRateHz = if (format == AudioFormat.OPUS) 48000 else 44100
                    val bitrateKbps = bestAudio.averageBitrate.coerceAtLeast(if (isOpus) 96 else 64)

                    logger.log(
                        "YOUTUBE",
                        "INFO",
                        "Đã chọn luồng ${format.extension.uppercase()} ($bitrateKbps kbps, ${sampleRateHz}Hz, mime=${mime.ifBlank { "n/a" }})"
                    )
                    Result.success(
                        ResolvedYouTubeStream(
                            streamUrl = bestAudio.content,
                            format = format,
                            bitrateKbps = bitrateKbps,
                            sampleRateHz = sampleRateHz,
                            mimeType = mime.ifBlank { if (isOpus) "audio/webm" else "audio/mp4" },
                            durationMs = (info.duration.coerceAtLeast(0L)) * 1000L,
                            artworkUrl = info.thumbnails.maxByOrNull { it.width }?.url
                        )
                    )
                }
            } catch (_: TimeoutCancellationException) {
                logger.log("YOUTUBE", "WARN", "Hết thời gian lấy luồng âm thanh YouTube (quá 7.5 giây)")
                Result.failure(IOException("Hết thời gian tải luồng YouTube Music"))
            } catch (_: SocketTimeoutException) {
                logger.log("YOUTUBE", "WARN", "Timeout socket khi tải luồng YouTube")
                Result.failure(IOException("Hết thời gian kết nối YouTube Music"))
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                val rawMsg = err.message.orEmpty()
                val friendly = if (isLoginRequiredError(rawMsg)) {
                    IOException("YouTube yêu cầu đăng nhập. Vui lòng vào Cài đặt > Nguồn nhạc > Đăng nhập YouTube.")
                } else err
                logger.log("YOUTUBE", "ERROR", "Lỗi trích xuất luồng YouTube: ${err.message}")
                Result.failure(friendly)
            }
        }

        if (!allowRetry) {
            return@withContext attemptBlock()
        }
        executeWithSingleRetry(
            shouldRetry = { err -> !isLoginRequiredError(err.message.orEmpty()) },
            onRetryLog = {
                logger.log("YOUTUBE", "WARN", "Trích xuất luồng YouTube chậm/lỗi, đang tự thử lại 1 lần...")
            },
            block = attemptBlock
        )
    }

    private fun isLoginRequiredError(rawMsg: String): Boolean {
        return rawMsg.contains("Sign in", ignoreCase = true) ||
            rawMsg.contains("confirm you're not a bot", ignoreCase = true) ||
            rawMsg.contains("login", ignoreCase = true) ||
            rawMsg.contains("Vui lòng vào Cài đặt", ignoreCase = true)
    }

    private suspend fun <T> executeWithSingleRetry(
        shouldRetry: (Throwable) -> Boolean = { true },
        onRetryLog: () -> Unit,
        block: suspend () -> Result<T>
    ): Result<T> {
        val first = block()
        val firstErr = first.exceptionOrNull() ?: return first
        if (!shouldRetry(firstErr)) return first
        onRetryLog()
        delay(250L)
        return block()
    }

    private fun extractVideoId(url: String): String {
        val match = Regex("[?&]v=([^&]+)").find(url)
        return match?.groupValues?.getOrNull(1) ?: url.substringAfterLast('/').substringBefore('?')
    }

    companion object {
        const val STREAM_FETCH_TIMEOUT_MS = 7_500L
        const val FEED_TIMEOUT_MS = 8_000L
        internal fun isOpusStreamCandidate(
            codec: String?,
            mimeType: String?,
            formatName: String?
        ): Boolean {
            return codec.orEmpty().contains("opus", ignoreCase = true) ||
                mimeType.orEmpty().contains("opus", ignoreCase = true) ||
                mimeType.orEmpty().contains("webm", ignoreCase = true) ||
                formatName.orEmpty().contains("opus", ignoreCase = true) ||
                formatName.orEmpty().contains("webm", ignoreCase = true)
        }

        internal fun selectBestOpusFirstStream(
            streams: List<org.schabi.newpipe.extractor.stream.AudioStream>
        ): org.schabi.newpipe.extractor.stream.AudioStream? {
            return streams
                .filter { !it.content.isNullOrBlank() }
                .sortedWith(
                    compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> { stream ->
                        isOpusStreamCandidate(
                            codec = stream.codec,
                            mimeType = stream.format?.mimeType,
                            formatName = stream.format?.name
                        )
                    }.thenByDescending { it.averageBitrate }
                )
                .firstOrNull()
        }
    }
}

data class ResolvedYouTubeStream(
    val streamUrl: String,
    val format: AudioFormat,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val mimeType: String,
    val durationMs: Long,
    val artworkUrl: String?
)
