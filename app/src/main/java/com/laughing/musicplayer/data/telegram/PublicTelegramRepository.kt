package com.laughing.musicplayer.data.telegram

import android.content.Context
import android.net.Uri
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.PublicChannelAccessMode
import com.laughing.musicplayer.core.model.PublicChannelItem
import com.laughing.musicplayer.core.model.PublicSyncStatus
import com.laughing.musicplayer.core.model.TelegramOrigin
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.AudioFormatDetector
import com.laughing.musicplayer.core.util.AutoGenreClassifier
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.data.local.DownloadCacheDao
import com.laughing.musicplayer.data.local.DownloadTaskEntity
import com.laughing.musicplayer.data.local.PublicChannelDao
import com.laughing.musicplayer.data.local.PublicChannelEntity
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.local.toEntity
import com.laughing.musicplayer.data.storage.UnifiedStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

data class PlayableStreamRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null
)

class PublicTelegramRepository(
    private val context: Context,
    private val publicChannelDao: PublicChannelDao,
    private val trackDao: TrackDao,
    private val downloadCacheDao: DownloadCacheDao,
    private val settingsDataStore: SettingsDataStore,
    private val storageManager: UnifiedStorageManager,
    private val httpClientProvider: () -> OkHttpClient,
    private val logger: ActivityLogger,
    private val botApiBaseUrl: String = "https://api.telegram.org"
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSyncJobs = ConcurrentHashMap<String, Job>()
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()

    fun observeChannels(): Flow<List<PublicChannelItem>> =
        publicChannelDao.observePublicChannels().map { list -> list.map { it.toDomain() } }

    fun observeTracksForChannel(username: String): Flow<List<Track>> {
        val clean = normalizeChannelUsername(username).getOrNull() ?: username.lowercase().removePrefix("@")
        return trackDao.observeTracksForPublicChannel(clean).map { list -> list.map { it.toDomain() } }
    }

    suspend fun addChannel(rawInput: String): Result<PublicChannelItem> = withContext(Dispatchers.IO) {
        val username = normalizeChannelUsername(rawInput).getOrElse { err ->
            return@withContext Result.failure(err)
        }
        val prefs = settingsDataStore.preferencesFlow.first()
        val mode = prefs.publicChannelAccessMode

        val existing = publicChannelDao.getChannel(username)
        val metadataRes = resolveChannelMetadata(username, mode, prefs.publicBackendBaseUrl)
        val meta = metadataRes.getOrElse { err ->
            return@withContext Result.failure(err)
        }

        val entity = PublicChannelEntity(
            channelKey = username,
            username = username,
            displayTitle = meta.title.ifBlank { existing?.displayTitle ?: "@$username" },
            avatarUrl = meta.avatarUrl ?: existing?.avatarUrl,
            syncStatus = PublicSyncStatus.IDLE.name,
            isPaused = false,
            discoveredCount = trackDao.countPublicTracksForChannel(username),
            lastSyncedEpochMs = existing?.lastSyncedEpochMs ?: 0L,
            lastCursor = existing?.lastCursor,
            lastError = null
        )
        publicChannelDao.upsertChannel(entity)
        logger.log("PUBLIC_TG", "INFO", "Đã thêm kênh công khai @$username (${entity.displayTitle}) [${mode.name}]")

        if (prefs.publicChannelSyncEnabled) {
            launchChannelSync(username, resetCursor = existing == null)
        }
        Result.success(entity.toDomain())
    }

    fun launchChannelSync(channelKey: String, resetCursor: Boolean = false) {
        activeSyncJobs[channelKey]?.cancel()
        val job = scope.launch {
            syncChannel(channelKey, resetCursor)
        }
        activeSyncJobs[channelKey] = job
    }

    suspend fun syncAllEnabledChannels() = withContext(Dispatchers.IO) {
        val prefs = settingsDataStore.preferencesFlow.first()
        if (!prefs.publicChannelSyncEnabled) return@withContext
        val channels = publicChannelDao.getPublicChannelsOnce()
        for (channel in channels) {
            if (!channel.isPaused) {
                syncChannel(channel.channelKey, resetCursor = false)
            }
        }
    }

    suspend fun syncChannel(channelKey: String, resetCursor: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        val channel = publicChannelDao.getChannel(channelKey)
            ?: return@withContext Result.failure(IllegalArgumentException("Không tìm thấy kênh @$channelKey"))
        val prefs = settingsDataStore.preferencesFlow.first()
        if (!prefs.publicChannelSyncEnabled) {
            return@withContext Result.failure(IllegalStateException("Đồng bộ kênh công khai đang tắt."))
        }
        if (channel.isPaused) {
            return@withContext Result.success(trackDao.countPublicTracksForChannel(channelKey))
        }

        val mode = prefs.publicChannelAccessMode
        val currentCount = trackDao.countPublicTracksForChannel(channelKey)
        publicChannelDao.upsertChannel(
            channel.copy(
                syncStatus = PublicSyncStatus.SYNCING.name,
                lastError = null,
                discoveredCount = currentCount
            )
        )

        val result = try {
            when (mode) {
                PublicChannelAccessMode.BACKEND_API -> syncViaBackendApi(
                    channel = channel,
                    baseUrl = prefs.publicBackendBaseUrl,
                    resetCursor = resetCursor
                )
                PublicChannelAccessMode.BOT_API -> syncViaBotApi(channel = channel)
            }
        } catch (ce: CancellationException) {
            val latestCount = trackDao.countPublicTracksForChannel(channelKey)
            val latestChannel = publicChannelDao.getChannel(channelKey)
            if (latestChannel != null) {
                publicChannelDao.updateSyncState(
                    channelKey = channelKey,
                    status = if (latestChannel.isPaused) PublicSyncStatus.PAUSED.name else PublicSyncStatus.IDLE.name,
                    discoveredCount = latestCount,
                    lastSyncedEpochMs = latestChannel.lastSyncedEpochMs,
                    cursor = null,
                    error = latestChannel.lastError
                )
            }
            throw ce
        }

        result.onSuccess { outcome ->
            val updatedCount = trackDao.countPublicTracksForChannel(channelKey)
            val latest = publicChannelDao.getChannel(channelKey)
            val status = if (latest?.isPaused == true) PublicSyncStatus.PAUSED else PublicSyncStatus.IDLE
            publicChannelDao.updateSyncState(
                channelKey = channelKey,
                status = status.name,
                discoveredCount = updatedCount,
                lastSyncedEpochMs = System.currentTimeMillis(),
                cursor = outcome.nextCursor,
                error = outcome.infoNote
            )
            logger.log(
                "PUBLIC_TG",
                "INFO",
                "Đồng bộ @$channelKey hoàn tất: +${outcome.addedCount} mới (tổng $updatedCount bài)"
            )
        }.onFailure { err ->
            val updatedCount = trackDao.countPublicTracksForChannel(channelKey)
            publicChannelDao.updateSyncState(
                channelKey = channelKey,
                status = PublicSyncStatus.ERROR.name,
                discoveredCount = updatedCount,
                lastSyncedEpochMs = 0L,
                cursor = null,
                error = err.message ?: "Đồng bộ kênh công khai thất bại"
            )
            logger.log("PUBLIC_TG", "ERROR", "Lỗi đồng bộ @$channelKey: ${err.message}")
        }

        result.map { it.addedCount }
    }

    suspend fun pauseChannelSync(channelKey: String) = withContext(Dispatchers.IO) {
        activeSyncJobs.remove(channelKey)?.cancel()
        publicChannelDao.setPaused(channelKey, paused = true, status = PublicSyncStatus.PAUSED.name)
        logger.log("PUBLIC_TG", "INFO", "Đã tạm dừng đồng bộ kênh @$channelKey")
    }

    suspend fun resumeChannelSync(channelKey: String) = withContext(Dispatchers.IO) {
        publicChannelDao.setPaused(channelKey, paused = false, status = PublicSyncStatus.IDLE.name)
        launchChannelSync(channelKey, resetCursor = false)
    }

    suspend fun removeChannel(channelKey: String) = withContext(Dispatchers.IO) {
        activeSyncJobs.remove(channelKey)?.cancel()
        trackDao.deleteTracksForPublicChannel(channelKey)
        publicChannelDao.deleteChannel(channelKey)
        logger.log("PUBLIC_TG", "INFO", "Đã xóa kênh công khai @$channelKey và các bài nhạc của kênh")
    }

    suspend fun resolvePlayableRequest(track: Track): Result<PlayableStreamRequest> = withContext(Dispatchers.IO) {
        val rawUri = track.streamUri
            ?: return@withContext Result.failure(IllegalStateException("Thiếu nguồn phát cho '${track.title}'"))

        if (rawUri.startsWith("tgbot://")) {
            val uri = Uri.parse(rawUri)
            val fileId = uri.lastPathSegment?.takeIf { it.isNotBlank() }
                ?: track.telegramRemoteFileId
                ?: return@withContext Result.failure(IllegalStateException("Thiếu file_id Bot API"))
            val botToken = settingsDataStore.getPublicBotToken().trim()
            if (botToken.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Vui lòng nhập Bot Token trong Cài đặt Kênh công khai."))
            }
            val getFileUrl = "${botApiBaseUrl.trimEnd('/')}/bot$botToken/getFile?file_id=${Uri.encode(fileId)}"
            val json = executeJsonGetWithRetry(getFileUrl).getOrElse { err ->
                val rawMsg = err.message.orEmpty()
                val friendly = if ("file is too big" in rawMsg.lowercase()) {
                    "Tệp lớn hơn 20 MB vượt giới hạn Bot API chuẩn của Telegram. Hãy dùng Companion Backend API cho kênh lossless."
                } else {
                    "Không thể lấy đường dẫn phát Bot API: $rawMsg"
                }
                return@withContext Result.failure(IOException(friendly, err))
            }
            val filePath = json.optJSONObject("result")?.optString("file_path").orEmpty()
            if (filePath.isBlank()) {
                return@withContext Result.failure(IOException("Bot API không trả về đường dẫn tệp âm thanh."))
            }
            val downloadUrl = "${botApiBaseUrl.trimEnd('/')}/file/bot$botToken/$filePath"
            return@withContext Result.success(
                PlayableStreamRequest(
                    url = downloadUrl,
                    headers = emptyMap(),
                    mimeType = track.mimeType
                )
            )
        }

        val backendToken = settingsDataStore.getPublicBackendToken().trim()
        val headers = buildMap {
            if (backendToken.isNotBlank()) {
                put("Authorization", "Bearer $backendToken")
            }
        }
        Result.success(PlayableStreamRequest(url = rawUri, headers = headers, mimeType = track.mimeType))
    }

    fun startOfflineDownload(track: Track) {
        val taskId = "dl_pub_${track.id}"
        activeDownloadJobs[taskId]?.cancel()

        val job = scope.launch {
            trackDao.updateDownloadState(track.id, DownloadStatus.DOWNLOADING.name, 0f, null)
            val targetName = track.fileName.ifBlank { "${track.title}.${track.format.extension}" }
            downloadCacheDao.upsertDownload(
                DownloadTaskEntity(
                    id = taskId,
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    source = MusicSource.TELEGRAM.name,
                    status = DownloadStatus.DOWNLOADING.name,
                    bytesDownloaded = 0L,
                    totalBytes = track.fileSizeBytes,
                    destinationPath = "${storageManager.getDisplayRootPath()}$targetName",
                    errorMessage = null,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )

            val streamReq = resolvePlayableRequest(track).getOrElse { err ->
                markDownloadFailed(taskId, track, err.message ?: "Lỗi giải mã nguồn phát công khai")
                return@launch
            }

            val reqBuilder = Request.Builder().url(streamReq.url).get()
            streamReq.headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            val client = httpClientProvider()
            val tempFile = File(storageManager.getPersistentPublicAudioCacheDir(), "${track.id}.part")
            val downloadRes = runCatching {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code}: không thể tải tệp âm thanh")
                    }
                    val body = resp.body ?: throw IOException("Phản hồi rỗng từ máy chủ")
                    val total = body.contentLength().takeIf { it > 0 } ?: track.fileSizeBytes
                    var copied = 0L
                    body.byteStream().use { input ->
                        tempFile.outputStream().use { out ->
                            val buf = ByteArray(16 * 1024)
                            var lastUiMs = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                                copied += read
                                val now = System.currentTimeMillis()
                                if (now - lastUiMs > 250L && total > 0L) {
                                    lastUiMs = now
                                    val prog = (copied.toFloat() / total).coerceIn(0f, 0.99f)
                                    trackDao.updateDownloadState(track.id, DownloadStatus.DOWNLOADING.name, prog, null)
                                }
                            }
                            out.fd.sync()
                        }
                    }
                    if (!storageManager.isValidAudioFile(tempFile, total)) {
                        throw IOException("Tệp tải về không hợp lệ hoặc bị gián đoạn")
                    }
                }
            }

            if (downloadRes.isFailure) {
                tempFile.delete()
                markDownloadFailed(taskId, track, downloadRes.exceptionOrNull()?.message ?: "Lỗi tải tệp")
                return@launch
            }

            val mime = streamReq.mimeType ?: "audio/${track.format.extension}"
            val probed = runCatching { AudioFormatDetector.probeAudioSpecs(tempFile, track.format) }.getOrNull()
            val embeddedArt = probed?.embeddedArtwork?.let { storageManager.saveArtworkFile(track.id, it) }
            val savedPath = storageManager.saveAudioToUnifiedStorage(
                source = MusicSource.TELEGRAM,
                fileName = targetName,
                mimeType = mime
            ) { tempFile.inputStream() }
                ?: storageManager.copyFileToPersistentAudioCache(
                    sourceFile = tempFile,
                    targetDir = storageManager.getPersistentPublicAudioCacheDir(),
                    targetFileName = "${track.id}_${targetName}",
                    expectedSizeBytes = track.fileSizeBytes
                )?.absolutePath

            tempFile.delete()

            if (savedPath != null) {
                trackDao.updateVerifiedLocalAudio(
                    trackId = track.id,
                    localPath = savedPath,
                    status = DownloadStatus.COMPLETED.name,
                    progress = 1f,
                    verifiedAtMs = System.currentTimeMillis()
                )
                trackDao.updateTrackMetadata(
                    trackId = track.id,
                    artworkUri = embeddedArt,
                    bitrateKbps = probed?.bitrateKbps ?: 0,
                    sampleRateHz = probed?.sampleRateHz ?: 0,
                    bitDepth = probed?.bitDepth,
                    durationMs = probed?.durationMs ?: 0L
                )
                downloadCacheDao.upsertDownload(
                    DownloadTaskEntity(
                        id = taskId,
                        trackId = track.id,
                        title = track.title,
                        artist = track.artist,
                        source = MusicSource.TELEGRAM.name,
                        status = DownloadStatus.COMPLETED.name,
                        bytesDownloaded = track.fileSizeBytes,
                        totalBytes = track.fileSizeBytes,
                        destinationPath = savedPath,
                        errorMessage = null,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
                logger.log("PUBLIC_TG", "INFO", "Tải về từ kênh công khai thành công: ${track.title}")
            } else {
                markDownloadFailed(taskId, track, "Không thể lưu tệp vào bộ nhớ thiết bị")
            }
        }
        activeDownloadJobs[taskId] = job
    }

    private suspend fun markDownloadFailed(taskId: String, track: Track, message: String) {
        trackDao.updateDownloadState(track.id, DownloadStatus.FAILED.name, 0f, null)
        downloadCacheDao.upsertDownload(
            DownloadTaskEntity(
                id = taskId,
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                source = MusicSource.TELEGRAM.name,
                status = DownloadStatus.FAILED.name,
                bytesDownloaded = 0L,
                totalBytes = track.fileSizeBytes,
                destinationPath = "",
                errorMessage = message,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        logger.log("PUBLIC_TG", "ERROR", "Tải '${track.title}' thất bại: $message")
    }

    private data class ResolvedChannelMeta(
        val title: String,
        val avatarUrl: String?
    )

    private data class SyncOutcome(
        val addedCount: Int,
        val nextCursor: String?,
        val infoNote: String? = null
    )

    private suspend fun resolveChannelMetadata(
        username: String,
        mode: PublicChannelAccessMode,
        backendBaseUrl: String
    ): Result<ResolvedChannelMeta> {
        return when (mode) {
            PublicChannelAccessMode.BACKEND_API -> {
                val base = normalizeBackendBaseUrl(backendBaseUrl)
                    ?: return Result.failure(
                        IllegalStateException("Vui lòng nhập URL Companion Backend API (ví dụ: https://music-api.example.com) trước khi thêm kênh.")
                    )
                val token = settingsDataStore.getPublicBackendToken().trim()
                val headers = buildMap {
                    if (token.isNotBlank()) put("Authorization", "Bearer $token")
                }
                val url = "$base/v1/telegram/public/resolve?username=${Uri.encode(username)}"
                executeJsonGetWithRetry(url, headers).map { json ->
                    ResolvedChannelMeta(
                        title = json.optString("title").ifBlank { "@$username" },
                        avatarUrl = json.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" }
                    )
                }
            }

            PublicChannelAccessMode.BOT_API -> {
                val token = settingsDataStore.getPublicBotToken().trim()
                if (token.isBlank()) {
                    return Result.failure(
                        IllegalStateException("Vui lòng nhập Bot Token hợp lệ từ @BotFather trước khi thêm kênh ở chế độ Bot API.")
                    )
                }
                val url = "${botApiBaseUrl.trimEnd('/')}/bot$token/getChat?chat_id=@${Uri.encode(username)}"
                executeJsonGetWithRetry(url).map { root ->
                    val chat = root.optJSONObject("result")
                        ?: throw IOException("Không tìm thấy kênh công khai @$username")
                    val chatType = chat.optString("type")
                    if (chatType.isNotBlank() && chatType != "channel" && chatType != "supergroup") {
                        throw IOException("@$username không phải kênh Telegram công khai.")
                    }
                    val photoFileId = chat.optJSONObject("photo")?.optString("big_file_id")?.takeIf { it.isNotBlank() }
                    val avatarUrl = if (photoFileId != null) {
                        resolveBotFileUrl(token, photoFileId)
                    } else null
                    ResolvedChannelMeta(
                        title = chat.optString("title").ifBlank { "@$username" },
                        avatarUrl = avatarUrl
                    )
                }
            }
        }
    }

    private suspend fun resolveBotFileUrl(token: String, fileId: String): String? {
        val url = "${botApiBaseUrl.trimEnd('/')}/bot$token/getFile?file_id=${Uri.encode(fileId)}"
        val json = executeJsonGetWithRetry(url).getOrNull() ?: return null
        val path = json.optJSONObject("result")?.optString("file_path")?.takeIf { it.isNotBlank() } ?: return null
        return "${botApiBaseUrl.trimEnd('/')}/file/bot$token/$path"
    }

    private suspend fun syncViaBackendApi(
        channel: PublicChannelEntity,
        baseUrl: String,
        resetCursor: Boolean
    ): Result<SyncOutcome> {
        val base = normalizeBackendBaseUrl(baseUrl)
            ?: return Result.failure(IllegalStateException("Chưa cấu hình URL Companion Backend API."))
        val token = settingsDataStore.getPublicBackendToken().trim()
        val headers = buildMap {
            if (token.isNotBlank()) put("Authorization", "Bearer $token")
        }

        var cursor: String? = if (resetCursor) null else channel.lastCursor
        var totalAdded = 0
        var pages = 0

        while (pages < 20 && coroutineContext.isActive) {
            val latestChannel = publicChannelDao.getChannel(channel.channelKey)
            if (latestChannel?.isPaused == true) break

            val urlBuilder = Uri.parse("$base/v1/telegram/public/channels/${Uri.encode(channel.username)}/audio")
                .buildUpon()
                .appendQueryParameter("limit", "50")
            if (!cursor.isNullOrBlank()) {
                urlBuilder.appendQueryParameter("cursor", cursor)
            }

            val json = executeJsonGetWithRetry(urlBuilder.build().toString(), headers).getOrElse { err ->
                return Result.failure(err)
            }

            val items = json.optJSONArray("items") ?: JSONArray()
            val parsedTracks = mutableListOf<Track>()
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                parseBackendAudioItem(obj, channel)?.let { parsedTracks += it }
            }
            if (parsedTracks.isNotEmpty()) {
                persistPublicTracks(parsedTracks)
                totalAdded += parsedTracks.size
            }

            val nextCursor = json.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" }
            val hasMore = json.optBoolean("hasMore", false)
            if (nextCursor != null) cursor = nextCursor
            if (!hasMore || nextCursor == null || items.length() == 0) break

            pages++
            delay(120L)
        }

        return Result.success(SyncOutcome(addedCount = totalAdded, nextCursor = cursor))
    }

    private suspend fun syncViaBotApi(channel: PublicChannelEntity): Result<SyncOutcome> {
        val token = settingsDataStore.getPublicBotToken().trim()
        if (token.isBlank()) {
            return Result.failure(IllegalStateException("Chưa nhập Bot Token cho chế độ Bot API."))
        }
        val base = botApiBaseUrl.trimEnd('/')
        val getChatUrl = "$base/bot$token/getChat?chat_id=@${Uri.encode(channel.username)}"
        val chatJson = executeJsonGetWithRetry(getChatUrl).getOrElse { err ->
            return Result.failure(err)
        }
        val chatObj = chatJson.optJSONObject("result")
        val targetChatId = chatObj?.optLong("id")
        val resolvedTitle = chatObj?.optString("title")?.takeIf { it.isNotBlank() } ?: channel.displayTitle

        // Note: Bot API's getUpdates delivers recent channel_post updates for channels where the Bot is admin.
        val updatesUrl = "$base/bot$token/getUpdates?limit=100&allowed_updates=%5B%22channel_post%22%5D"
        val updatesJson = executeJsonGetWithRetry(updatesUrl).getOrElse { err ->
            return Result.failure(err)
        }

        val results = updatesJson.optJSONArray("result") ?: JSONArray()
        val tracks = mutableListOf<Track>()
        var highestUpdateId = 0L

        for (i in 0 until results.length()) {
            val upd = results.optJSONObject(i) ?: continue
            val updateId = upd.optLong("update_id", 0L)
            if (updateId > highestUpdateId) highestUpdateId = updateId
            val post = upd.optJSONObject("channel_post") ?: continue
            val postChat = post.optJSONObject("chat") ?: continue
            val postUsername = postChat.optString("username").lowercase()
            val postChatId = postChat.optLong("id", 0L)
            val matchesChannel = postUsername == channel.username.lowercase() ||
                (targetChatId != null && targetChatId != 0L && postChatId == targetChatId)
            if (!matchesChannel) continue

            parseBotChannelPostTrack(post, channel.copy(displayTitle = resolvedTitle))?.let { tracks += it }
        }

        if (tracks.isNotEmpty()) {
            persistPublicTracks(tracks)
        }

        val existingCount = trackDao.countPublicTracksForChannel(channel.channelKey)
        val infoNote = if (tracks.isEmpty() && existingCount == 0) {
            "Bot API chỉ nhận bài đăng mới kể từ khi Bot làm quản trị viên kênh @$channel.username. Để quét toàn bộ lịch sử kênh, hãy chuyển sang Companion Backend API."
        } else {
            null
        }

        return Result.success(
            SyncOutcome(
                addedCount = tracks.size,
                nextCursor = if (highestUpdateId > 0L) highestUpdateId.toString() else channel.lastCursor,
                infoNote = infoNote
            )
        )
    }

    private fun parseBackendAudioItem(obj: JSONObject, channel: PublicChannelEntity): Track? {
        val messageId = obj.optLong("messageId", 0L).takeIf { it > 0L }
            ?: obj.optLong("id", 0L).takeIf { it > 0L }
            ?: return null
        val streamUrl = obj.optString("streamUrl").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val fileName = obj.optString("fileName").ifBlank { "track_${messageId}.mp3" }
        val mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() && it != "null" }
        val rawFormat = obj.optString("format").takeIf { it.isNotBlank() && it != "null" }
        val format = rawFormat?.let { r ->
            AudioFormat.entries.firstOrNull { it.name.equals(r, ignoreCase = true) || it.extension.equals(r, ignoreCase = true) }
        } ?: AudioFormatDetector.detectFromMetadata(
            fileName = fileName,
            mimeType = mimeType,
            isVoiceNote = false,
            isVideoNote = false,
            includeVoiceMessages = false
        ) ?: AudioFormat.MP3

        val title = obj.optString("title").ifBlank {
            fileName.substringBeforeLast('.').ifBlank { "Track #$messageId" }
        }
        val artist = obj.optString("artist").ifBlank { channel.displayTitle }
        val album = obj.optString("album").ifBlank { "@${channel.username}" }
        val durationMs = obj.optLong("durationMs", 0L).takeIf { it > 0L }
            ?: (obj.optLong("durationSec", 0L) * 1000L)
        val fileSize = obj.optLong("fileSizeBytes", 0L)
        val bitrate = obj.optInt("bitrateKbps", 0).takeIf { it > 0 }
            ?: if (durationMs > 0L && fileSize > 0L) ((fileSize * 8L) / durationMs).toInt() else 0
        val sampleRate = obj.optInt("sampleRateHz", 44100).coerceAtLeast(8000)
        val bitDepth = if (obj.has("bitDepth") && !obj.isNull("bitDepth")) obj.optInt("bitDepth") else null
        val artworkUrl = obj.optString("artworkUrl").takeIf { it.isNotBlank() && it != "null" } ?: channel.avatarUrl
        val dateEpochMs = obj.optLong("dateEpochMs", 0L).takeIf { it > 0L }
            ?: (obj.optLong("dateEpochSec", 0L) * 1000L).takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val uniqueId = obj.optString("uniqueId").takeIf { it.isNotBlank() && it != "null" }

        return Track(
            id = "tgpub_${channel.username}_${messageId}",
            title = title,
            artist = artist,
            album = album,
            source = MusicSource.TELEGRAM,
            durationMs = durationMs,
            format = format,
            bitrateKbps = bitrate,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            fileSizeBytes = fileSize,
            fileName = fileName,
            artworkUri = artworkUrl,
            streamUri = streamUrl,
            telegramChatId = null, // keep NULL to never collide with Personal Telegram (chatId, messageId) unique index
            telegramChatTitle = channel.displayTitle,
            telegramMessageId = messageId,
            telegramRemoteUniqueId = uniqueId,
            mimeType = mimeType,
            telegramOrigin = TelegramOrigin.PUBLIC_TELEGRAM,
            publicChannelUsername = channel.username,
            genre = AutoGenreClassifier.classify(
                title = title,
                artist = artist,
                album = album,
                fileName = fileName,
                sourceContext = "@${channel.username} ${channel.displayTitle}"
            ),
            dateAddedEpochMs = dateEpochMs
        )
    }

    private fun parseBotChannelPostTrack(post: JSONObject, channel: PublicChannelEntity): Track? {
        val messageId = post.optLong("message_id", 0L).takeIf { it > 0L } ?: return null
        val audio = post.optJSONObject("audio")
        val doc = post.optJSONObject("document")

        val mediaObj = audio ?: doc ?: return null
        val fileId = mediaObj.optString("file_id").takeIf { it.isNotBlank() } ?: return null
        val fileUniqueId = mediaObj.optString("file_unique_id").takeIf { it.isNotBlank() }
        val fileName = mediaObj.optString("file_name").ifBlank { "audio_${messageId}.mp3" }
        val mimeType = mediaObj.optString("mime_type").takeIf { it.isNotBlank() }
        val format = AudioFormatDetector.detectFromMetadata(
            fileName = fileName,
            mimeType = mimeType,
            isVoiceNote = false,
            isVideoNote = false,
            includeVoiceMessages = false
        ) ?: if (audio != null) AudioFormat.MP3 else return null

        val rawTitle = mediaObj.optString("title").ifBlank { fileName.substringBeforeLast('.') }
        val performer = mediaObj.optString("performer").ifBlank { channel.displayTitle }
        val durationMs = mediaObj.optLong("duration", 0L) * 1000L
        val fileSize = mediaObj.optLong("file_size", 0L)
        val bitrate = if (durationMs > 0L && fileSize > 0L) ((fileSize * 8L) / durationMs).toInt() else 0
        val botStreamUri = Uri.Builder()
            .scheme("tgbot")
            .authority("file")
            .appendPath(fileId)
            .appendQueryParameter("username", channel.username)
            .appendQueryParameter("msgId", messageId.toString())
            .appendQueryParameter("size", fileSize.toString())
            .build()
            .toString()

        return Track(
            id = "tgpub_${channel.username}_${messageId}",
            title = rawTitle.ifBlank { "Audio #$messageId" },
            artist = performer,
            album = "@${channel.username}",
            source = MusicSource.TELEGRAM,
            durationMs = durationMs,
            format = format,
            bitrateKbps = bitrate,
            sampleRateHz = if (format.isLossless) 48000 else 44100,
            bitDepth = if (format.isLossless) 16 else null,
            fileSizeBytes = fileSize,
            fileName = fileName,
            artworkUri = channel.avatarUrl,
            streamUri = botStreamUri,
            telegramChatId = null,
            telegramChatTitle = channel.displayTitle,
            telegramMessageId = messageId,
            telegramRemoteFileId = fileId,
            telegramRemoteUniqueId = fileUniqueId,
            mimeType = mimeType,
            telegramOrigin = TelegramOrigin.PUBLIC_TELEGRAM,
            publicChannelUsername = channel.username,
            genre = AutoGenreClassifier.classify(
                title = rawTitle,
                artist = performer,
                album = channel.displayTitle,
                fileName = fileName,
                sourceContext = "@${channel.username}"
            ),
            dateAddedEpochMs = post.optLong("date", 0L) * 1000L
        )
    }

    private suspend fun persistPublicTracks(tracks: List<Track>) {
        val entities = tracks.distinctBy { it.id }.map { track ->
            val existing = trackDao.getTrackById(track.id)
            val merged = if (existing != null) {
                val existingLocalValid = existing.localPath?.takeIf {
                    it.startsWith("content://") || File(it).exists()
                }
                track.copy(
                    isFavorite = existing.isFavorite,
                    playCount = existing.playCount,
                    lastPlayedEpochMs = existing.lastPlayedEpochMs,
                    localPath = existingLocalValid ?: track.localPath,
                    downloadStatus = if (existing.downloadStatus == DownloadStatus.COMPLETED.name && existingLocalValid != null) {
                        DownloadStatus.COMPLETED
                    } else track.downloadStatus,
                    artworkUri = existing.artworkUri ?: track.artworkUri
                )
            } else track
            val blob = VietnameseTextNormalizer.normalize(
                "${merged.title} ${merged.artist} ${merged.album} ${merged.publicChannelUsername.orEmpty()} ${merged.fileName}"
            )
            merged.toEntity(blob)
        }
        trackDao.upsertTracks(entities)
    }

    private suspend fun executeJsonGetWithRetry(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): Result<JSONObject> {
        val client = httpClientProvider()
        var attempt = 0
        while (true) {
            val reqBuilder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            val outcome = runCatching {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.code == 429) {
                        val retryAfterHeader = resp.header("Retry-After")?.toLongOrNull()
                        val bodyRetry = runCatching {
                            JSONObject(bodyStr).optJSONObject("parameters")?.optLong("retry_after")
                        }.getOrNull()
                        throw RateLimitedException((retryAfterHeader ?: bodyRetry ?: 3L).coerceIn(1L, 10L))
                    }
                    if (!resp.isSuccessful) {
                        val desc = runCatching { JSONObject(bodyStr).optString("description") }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: runCatching { JSONObject(bodyStr).optString("error") }.getOrNull()
                                ?.takeIf { it.isNotBlank() }
                            ?: "HTTP ${resp.code}"
                        throw IOException(desc)
                    }
                    val json = JSONObject(bodyStr.ifBlank { "{}" })
                    if (json.has("ok") && !json.optBoolean("ok", true)) {
                        val retryAfter = json.optJSONObject("parameters")?.optLong("retry_after", 0L) ?: 0L
                        if (retryAfter > 0L) {
                            throw RateLimitedException(retryAfter.coerceIn(1L, 10L))
                        }
                        val errCode = json.optInt("error_code", 0)
                        val desc = json.optString("description").ifBlank { "Lỗi API Telegram ($errCode)" }
                        throw IOException(desc)
                    }
                    json
                }
            }
            val err = outcome.exceptionOrNull()
            if (err == null) return outcome
            if (err is RateLimitedException && attempt < 2) {
                attempt++
                delay(err.retryAfterSeconds * 1000L)
                continue
            }
            return Result.failure(err)
        }
    }

    private class RateLimitedException(val retryAfterSeconds: Long) : IOException("Quá nhiều yêu cầu (429)")

    companion object {
        fun normalizeChannelUsername(rawInput: String): Result<String> {
            val trimmed = rawInput.trim()
            if (trimmed.isBlank()) {
                return Result.failure(IllegalArgumentException("Vui lòng nhập @username hoặc liên kết t.me/ của kênh công khai."))
            }
            val lower = trimmed.lowercase()
            if (lower.contains("t.me/+") || lower.contains("joinchat/")) {
                return Result.failure(
                    IllegalArgumentException("Liên kết mời riêng tư (t.me/+...) không phải kênh công khai. Hãy dùng Đồng bộ Telegram cá nhân.")
                )
            }
            var candidate = trimmed
            if (candidate.startsWith("http://", ignoreCase = true) ||
                candidate.startsWith("https://", ignoreCase = true) ||
                candidate.startsWith("t.me/", ignoreCase = true) ||
                candidate.startsWith("telegram.me/", ignoreCase = true)
            ) {
                val withScheme = if (candidate.startsWith("http", ignoreCase = true)) candidate else "https://$candidate"
                val segments = runCatching { Uri.parse(withScheme).pathSegments }.getOrNull().orEmpty()
                candidate = when {
                    segments.size >= 2 && segments[0].equals("s", ignoreCase = true) -> segments[1]
                    segments.isNotEmpty() -> segments[0]
                    else -> ""
                }
            }
            candidate = candidate.removePrefix("@").trim().lowercase()
            if (!Regex("^[a-z][a-z0-9_]{3,63}$").matches(candidate)) {
                return Result.failure(
                    IllegalArgumentException("Tên kênh '@$candidate' không hợp lệ (cần 4-64 ký tự chữ, số hoặc '_').")
                )
            }
            return Result.success(candidate)
        }

        fun normalizeBackendBaseUrl(rawUrl: String): String? {
            val clean = rawUrl.trim().trimEnd('/')
            if (clean.isBlank()) return null
            return if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
                clean
            } else {
                "https://$clean"
            }
        }
    }
}
