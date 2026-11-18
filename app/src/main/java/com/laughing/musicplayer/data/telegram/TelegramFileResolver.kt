package com.laughing.musicplayer.data.telegram

import android.net.Uri
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.AudioFormatDetector
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.storage.UnifiedStorageManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException

interface TelegramFileClient {
    suspend fun getFile(fileId: Int, timeoutMs: Long = 5_500L): Result<TdApi.File>
    suspend fun getRemoteFile(
        remoteFileId: String,
        fileType: TdApi.FileType,
        timeoutMs: Long = 6_000L
    ): Result<TdApi.File>

    suspend fun getMessage(
        chatId: Long,
        messageId: Long,
        timeoutMs: Long = 6_500L
    ): Result<TdApi.Message>

    suspend fun downloadFile(
        fileId: Int,
        priority: Int,
        offset: Long,
        limit: Long,
        synchronous: Boolean,
        timeoutMs: Long = 6_500L
    ): Result<TdApi.File>
}

class TdCoreFileClient(
    private val core: TelegramCore
) : TelegramFileClient {
    override suspend fun getFile(fileId: Int, timeoutMs: Long): Result<TdApi.File> =
        core.sendQuery(TdApi.GetFile(fileId), timeoutMs)

    override suspend fun getRemoteFile(
        remoteFileId: String,
        fileType: TdApi.FileType,
        timeoutMs: Long
    ): Result<TdApi.File> =
        core.sendQuery(TdApi.GetRemoteFile(remoteFileId, fileType), timeoutMs)

    override suspend fun getMessage(
        chatId: Long,
        messageId: Long,
        timeoutMs: Long
    ): Result<TdApi.Message> =
        core.sendQuery(TdApi.GetMessage(chatId, messageId), timeoutMs)

    override suspend fun downloadFile(
        fileId: Int,
        priority: Int,
        offset: Long,
        limit: Long,
        synchronous: Boolean,
        timeoutMs: Long
    ): Result<TdApi.File> =
        core.sendQuery(
            TdApi.DownloadFile(fileId, priority, offset, limit, synchronous),
            timeoutMs
        )
}

data class TelegramFileTarget(
    val trackId: String? = null,
    val fileId: Int = 0,
    val remoteFileId: String? = null,
    val chatId: Long? = null,
    val messageId: Long? = null,
    val expectedSize: Long = 0L,
    val isVoiceMessage: Boolean = false,
    val format: AudioFormat = AudioFormat.MP3
)

data class ResolvedTelegramDownload(
    val fileId: Int,
    val tdFile: TdApi.File,
    val remoteFileId: String?,
    val remoteUniqueId: String? = null,
    val trackId: String?,
    val refreshedArtworkUri: String? = null
)

data class ProgressiveTelegramResolution(
    val track: Track,
    val playUri: String,
    val isLocalFile: Boolean,
    val mimeType: String? = null
)

class TelegramFileResolver(
    private val client: TelegramFileClient,
    private val trackDao: TrackDao,
    private val storageManager: UnifiedStorageManager?,
    private val logger: ActivityLogger,
    private val fileUpdates: Flow<TdApi.File> = emptyFlow(),
    private val waitForReady: (suspend () -> Boolean)? = null
) {
    constructor(
        core: TelegramCore,
        trackDao: TrackDao,
        storageManager: UnifiedStorageManager?,
        logger: ActivityLogger
    ) : this(
        client = TdCoreFileClient(core),
        trackDao = trackDao,
        storageManager = storageManager,
        logger = logger,
        fileUpdates = core.fileUpdates,
        waitForReady = { core.waitForReady() }
    )

    suspend fun findExistingLocalAudioTrack(track: Track): Track? {
        val dbEntity = trackDao.getTrackById(track.id)
            ?: if (track.telegramChatId != null && track.telegramMessageId != null) {
                trackDao.findByTelegramMessage(track.telegramChatId, track.telegramMessageId)
            } else {
                null
            }
        val dbTrack = dbEntity?.toDomain()
        val effectiveTrack = if (dbTrack != null) {
            track.copy(
                localPath = dbTrack.localPath ?: track.localPath,
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
        val expectedSize = effectiveTrack.fileSizeBytes

        val existingPath = effectiveTrack.localPath
        if (!existingPath.isNullOrBlank()) {
            if (existingPath.startsWith("content://")) {
                val now = System.currentTimeMillis()
                runCatching {
                    trackDao.updateVerifiedLocalAudio(
                        trackId = effectiveTrack.id,
                        localPath = existingPath,
                        status = DownloadStatus.COMPLETED.name,
                        progress = 1f,
                        verifiedAtMs = now
                    )
                }
                return effectiveTrack.copy(
                    downloadStatus = DownloadStatus.COMPLETED,
                    downloadProgress = 1f,
                    lastVerifiedAtEpochMs = now
                )
            }
            val candidateFile = toLocalFile(existingPath)
            if (isValidLocalFile(candidateFile, expectedSize)) {
                val now = System.currentTimeMillis()
                val canonical = candidateFile!!.absolutePath
                runCatching {
                    trackDao.updateVerifiedLocalAudio(
                        trackId = effectiveTrack.id,
                        localPath = canonical,
                        status = DownloadStatus.COMPLETED.name,
                        progress = 1f,
                        verifiedAtMs = now
                    )
                }
                return effectiveTrack.copy(
                    localPath = canonical,
                    downloadStatus = DownloadStatus.COMPLETED,
                    downloadProgress = 1f,
                    lastVerifiedAtEpochMs = now
                )
            }
        }

        val persistentCacheFile = storageManager?.let { mgr ->
            File(
                mgr.getPersistentTelegramAudioCacheDir(),
                buildPersistentCacheFileName(
                    chatId = effectiveTrack.telegramChatId,
                    messageId = effectiveTrack.telegramMessageId,
                    fileName = effectiveTrack.fileName,
                    format = effectiveTrack.format,
                    trackId = effectiveTrack.id
                )
            )
        }
        if (persistentCacheFile != null && isValidLocalFile(persistentCacheFile, expectedSize)) {
            val now = System.currentTimeMillis()
            val canonical = persistentCacheFile.absolutePath
            runCatching {
                trackDao.updateVerifiedLocalAudio(
                    trackId = effectiveTrack.id,
                    localPath = canonical,
                    status = DownloadStatus.COMPLETED.name,
                    progress = 1f,
                    verifiedAtMs = now
                )
            }
            return effectiveTrack.copy(
                localPath = canonical,
                downloadStatus = DownloadStatus.COMPLETED,
                downloadProgress = 1f,
                lastVerifiedAtEpochMs = now
            )
        }
        return null
    }

    /**
     * Progressive playback resolution:
     * 1. Plays immediately if a verified local/persistent cached file exists.
     * 2. Otherwise recovers the freshest TDLib file handle (GetMessage -> GetRemoteFile -> GetFile)
     *    within strict 5-7.5s bounds and returns a `tg://` progressive stream URI WITHOUT waiting
     *    for the whole file to download.
     */
    suspend fun resolveForProgressiveStream(track: Track): Result<ProgressiveTelegramResolution> {
        findExistingLocalAudioTrack(track)?.let { localTrack ->
            val path = localTrack.localPath
            if (!path.isNullOrBlank()) {
                val playUri = if (path.startsWith("content://") || path.startsWith("file://")) {
                    path
                } else {
                    Uri.fromFile(File(path)).toString()
                }
                return Result.success(
                    ProgressiveTelegramResolution(
                        track = localTrack,
                        playUri = playUri,
                        isLocalFile = true,
                        mimeType = localTrack.mimeType
                    )
                )
            }
        }

        val dbEntity = trackDao.getTrackById(track.id)
            ?: if (track.telegramChatId != null && track.telegramMessageId != null) {
                trackDao.findByTelegramMessage(track.telegramChatId, track.telegramMessageId)
            } else {
                null
            }
        val dbTrack = dbEntity?.toDomain()
        val effectiveTrack = if (dbTrack != null) {
            track.copy(
                localPath = dbTrack.localPath ?: track.localPath,
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

        val ready = waitForReady?.invoke() ?: true
        if (!ready) {
            return Result.failure(IOException("Telegram chưa kết nối. Vui lòng kiểm tra mạng hoặc đăng nhập Telegram."))
        }

        val expectedSize = effectiveTrack.fileSizeBytes
        val chatId = effectiveTrack.telegramChatId?.takeIf { it != 0L }
        val messageId = effectiveTrack.telegramMessageId?.takeIf { it != 0L }
        val remoteId = effectiveTrack.telegramRemoteFileId?.takeIf { it.isNotBlank() }
        val fileId = effectiveTrack.telegramFileId?.takeIf { it > 0 } ?: 0

        var freshFile: TdApi.File? = null
        var extractedThumb: TdApi.File? = null
        var extractedMime: String? = effectiveTrack.mimeType
        var messageDeletedOrInaccessible = false
        var lastErrorMsg: String? = null

        // Try existing fileId via fast local/TDLib check first when available
        if (fileId > 0) {
            val res = client.getFile(fileId, timeoutMs = 4_000L)
            val f = res.getOrNull()
            if (f != null && f.id > 0) {
                val matchesRemote = remoteId.isNullOrBlank() || f.remote?.id.isNullOrBlank() || f.remote?.id == remoteId
                if (matchesRemote) freshFile = f
            }
        }

        if (freshFile == null && chatId != null && messageId != null) {
            val msgRes = executeWithFloodRetry { client.getMessage(chatId, messageId, timeoutMs = 6_500L) }
            msgRes.onSuccess { msg ->
                val extracted = extractFilesFromMessage(msg)
                freshFile = extracted?.first
                extractedThumb = extracted?.second
                extractedMime = extractMimeTypeFromMessage(msg) ?: extractedMime
                if (freshFile == null) {
                    messageDeletedOrInaccessible = true
                    lastErrorMsg = "Tin nhắn Telegram không còn chứa tệp âm thanh."
                }
            }.onFailure { err ->
                lastErrorMsg = err.message
                if (isDeletedOrAccessDeniedError(err)) {
                    messageDeletedOrInaccessible = true
                }
            }
        }

        if (freshFile == null && !remoteId.isNullOrBlank()) {
            val candidateTypes = buildList<TdApi.FileType> {
                if (effectiveTrack.isVoiceMessage) add(TdApi.FileTypeVoiceNote())
                add(TdApi.FileTypeAudio())
                add(TdApi.FileTypeDocument())
                add(TdApi.FileTypeUnknown())
            }
            for (ft in candidateTypes) {
                val res = executeWithFloodRetry { client.getRemoteFile(remoteId, ft, timeoutMs = 5_500L) }
                val f = res.getOrNull()
                if (f != null && f.id > 0) {
                    freshFile = f
                    break
                } else {
                    res.exceptionOrNull()?.let { lastErrorMsg = it.message }
                }
            }
        }

        val targetFile = freshFile ?: return Result.failure(
            IOException(
                if (messageDeletedOrInaccessible) {
                    "Tin nhắn gốc đã bị xóa hoặc bạn không còn quyền truy cập '${effectiveTrack.title}'."
                } else {
                    "Không thể kết nối luồng Telegram cho '${effectiveTrack.title}': ${lastErrorMsg ?: "vui lòng thử lại"}"
                }
            )
        )

        val resolvedRemoteId = targetFile.remote?.id?.takeIf { it.isNotBlank() } ?: remoteId
        val resolvedUniqueId = targetFile.remote?.uniqueId?.takeIf { it.isNotBlank() } ?: effectiveTrack.telegramRemoteUniqueId
        val updatedSize = maxOf(expectedSize, targetFile.size, targetFile.expectedSize)

        persistUpdatedFileRef(
            trackId = effectiveTrack.id,
            fileId = targetFile.id,
            fileSize = updatedSize,
            chatId = chatId,
            messageId = messageId,
            remoteFileId = resolvedRemoteId,
            remoteUniqueId = resolvedUniqueId,
            mimeType = extractedMime,
            existingFileId = -1,
            existingRemoteId = effectiveTrack.telegramRemoteFileId
        )

        val refreshedArt = refreshMissingArtworkIfNeeded(
            trackId = effectiveTrack.id,
            currentArtworkUri = effectiveTrack.artworkUri,
            thumbFile = extractedThumb
        )
        val finalArtworkUri = refreshedArt ?: effectiveTrack.artworkUri

        // If TDLib already holds a complete local file, copy to persistent cache and play locally right away
        if (isUsableCompletedLocalFile(targetFile, updatedSize)) {
            val promoted = promoteCompletedTdFileToPersistentCache(effectiveTrack, targetFile, extractedMime, finalArtworkUri)
            if (promoted != null && !promoted.localPath.isNullOrBlank()) {
                return Result.success(
                    ProgressiveTelegramResolution(
                        track = promoted,
                        playUri = Uri.fromFile(File(promoted.localPath)).toString(),
                        isLocalFile = true,
                        mimeType = extractedMime
                    )
                )
            }
        }

        val streamUri = buildStreamUri(
            fileId = targetFile.id,
            fileSize = updatedSize,
            chatId = chatId,
            messageId = messageId,
            remoteFileId = resolvedRemoteId,
            trackId = effectiveTrack.id
        )
        val updatedTrack = effectiveTrack.copy(
            streamUri = streamUri,
            telegramFileId = targetFile.id,
            telegramRemoteFileId = resolvedRemoteId,
            telegramRemoteUniqueId = resolvedUniqueId,
            mimeType = extractedMime,
            fileSizeBytes = updatedSize,
            artworkUri = finalArtworkUri
        )
        logger.log("TG_STREAM", "INFO", "Sẵn sàng phát progressive Telegram fileId=${targetFile.id} cho '${effectiveTrack.title}'")
        return Result.success(
            ProgressiveTelegramResolution(
                track = updatedTrack,
                playUri = streamUri,
                isLocalFile = false,
                mimeType = extractedMime
            )
        )
    }

    suspend fun promoteCompletedTdFileToPersistentCache(
        track: Track,
        completedTdFile: TdApi.File,
        extractedMime: String? = track.mimeType,
        artworkUri: String? = track.artworkUri
    ): Track? {
        val tdLocalPath = completedTdFile.local?.path?.takeIf { it.isNotBlank() } ?: return null
        val tdLocalFile = File(tdLocalPath)
        val expectedSize = maxOf(track.fileSizeBytes, completedTdFile.size, completedTdFile.expectedSize)
        if (!isValidLocalFile(tdLocalFile, expectedSize)) return null

        val finalLocalFile = if (storageManager != null) {
            val targetName = buildPersistentCacheFileName(
                chatId = track.telegramChatId,
                messageId = track.telegramMessageId,
                fileName = track.fileName,
                format = track.format,
                trackId = track.id
            )
            storageManager.copyFileToPersistentAudioCache(
                sourceFile = tdLocalFile,
                targetDir = storageManager.getPersistentTelegramAudioCacheDir(),
                targetFileName = targetName,
                expectedSizeBytes = expectedSize
            ) ?: tdLocalFile
        } else {
            tdLocalFile
        }
        if (!isValidLocalFile(finalLocalFile, expectedSize)) return null

        val finalPath = finalLocalFile.absolutePath
        val now = System.currentTimeMillis()
        val finalRemote = completedTdFile.remote?.id?.takeIf { it.isNotBlank() } ?: track.telegramRemoteFileId
        val finalUnique = completedTdFile.remote?.uniqueId?.takeIf { it.isNotBlank() } ?: track.telegramRemoteUniqueId
        val rebuiltUri = buildStreamUri(
            fileId = completedTdFile.id,
            fileSize = expectedSize,
            chatId = track.telegramChatId,
            messageId = track.telegramMessageId,
            remoteFileId = finalRemote,
            trackId = track.id
        )
        val probed = runCatching { AudioFormatDetector.probeAudioSpecs(finalLocalFile, track.format) }.getOrNull()
        val embeddedArtPath = if (artworkUri.isNullOrBlank()) {
            probed?.embeddedArtwork?.let { bytes -> storageManager?.saveArtworkFile(track.id, bytes) }
        } else {
            null
        }
        val finalArtworkUri = artworkUri ?: embeddedArtPath ?: track.artworkUri

        runCatching {
            trackDao.updateVerifiedLocalAudio(
                trackId = track.id,
                localPath = finalPath,
                status = DownloadStatus.COMPLETED.name,
                progress = 1f,
                verifiedAtMs = now,
                fileId = completedTdFile.id,
                remoteFileId = finalRemote,
                remoteUniqueId = finalUnique,
                streamUri = rebuiltUri
            )
            if (probed != null || finalArtworkUri != track.artworkUri) {
                trackDao.updateTrackMetadata(
                    trackId = track.id,
                    artworkUri = finalArtworkUri,
                    bitrateKbps = probed?.bitrateKbps ?: 0,
                    sampleRateHz = probed?.sampleRateHz ?: 0,
                    bitDepth = probed?.bitDepth,
                    durationMs = probed?.durationMs ?: 0L
                )
            }
        }
        return track.copy(
            localPath = finalPath,
            streamUri = rebuiltUri,
            telegramFileId = completedTdFile.id,
            telegramRemoteFileId = finalRemote,
            telegramRemoteUniqueId = finalUnique,
            mimeType = extractedMime,
            artworkUri = finalArtworkUri,
            bitrateKbps = probed?.bitrateKbps?.takeIf { it > 0 } ?: track.bitrateKbps,
            sampleRateHz = probed?.sampleRateHz?.takeIf { it > 0 } ?: track.sampleRateHz,
            bitDepth = probed?.bitDepth ?: track.bitDepth,
            durationMs = probed?.durationMs?.takeIf { it > 0L } ?: track.durationMs,
            downloadStatus = DownloadStatus.COMPLETED,
            downloadProgress = 1f,
            lastVerifiedAtEpochMs = now
        )
    }

    suspend fun promoteCompletedTdFileById(trackId: String, completedTdFile: TdApi.File) {
        val entity = trackDao.getTrackById(trackId) ?: return
        promoteCompletedTdFileToPersistentCache(entity.toDomain(), completedTdFile)
    }

    /**
     * Full verified local recovery (used for offline downloads or full verification).
     */
    suspend fun ensureVerifiedLocalAudioFile(
        track: Track,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Track> {
        findExistingLocalAudioTrack(track)?.let { return Result.success(it) }

        val dbEntity = trackDao.getTrackById(track.id)
            ?: if (track.telegramChatId != null && track.telegramMessageId != null) {
                trackDao.findByTelegramMessage(track.telegramChatId, track.telegramMessageId)
            } else {
                null
            }
        val dbTrack = dbEntity?.toDomain()
        val effectiveTrack = if (dbTrack != null) {
            track.copy(
                localPath = dbTrack.localPath ?: track.localPath,
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

        val expectedSize = effectiveTrack.fileSizeBytes

        // 2. Ensure TDLib is ready if user just opened the app
        waitForReady?.invoke()

        val chatId = effectiveTrack.telegramChatId?.takeIf { it != 0L }
        val messageId = effectiveTrack.telegramMessageId?.takeIf { it != 0L }
        val remoteId = effectiveTrack.telegramRemoteFileId?.takeIf { it.isNotBlank() }
        val fileId = effectiveTrack.telegramFileId?.takeIf { it > 0 } ?: 0

        var freshFile: TdApi.File? = null
        var extractedThumb: TdApi.File? = null
        var extractedMime: String? = effectiveTrack.mimeType
        var messageDeletedOrInaccessible = false
        var lastErrorMsg: String? = null

        // 3A. Recover via GetMessage(chatId, messageId) first for freshest message & file reference
        if (chatId != null && messageId != null) {
            val msgRes = executeWithFloodRetry { client.getMessage(chatId, messageId, timeoutMs = 6_500L) }
            msgRes.onSuccess { msg ->
                val extracted = extractFilesFromMessage(msg)
                freshFile = extracted?.first
                extractedThumb = extracted?.second
                extractedMime = extractMimeTypeFromMessage(msg) ?: extractedMime
                if (freshFile == null) {
                    messageDeletedOrInaccessible = true
                    lastErrorMsg = "Tin nhắn Telegram không còn chứa tệp âm thanh."
                }
            }.onFailure { err ->
                lastErrorMsg = err.message
                if (isDeletedOrAccessDeniedError(err)) {
                    messageDeletedOrInaccessible = true
                }
            }
        }

        // 3B. Fallback to GetRemoteFile(remoteFileId)
        if (freshFile == null && !remoteId.isNullOrBlank()) {
            val candidateTypes = buildList<TdApi.FileType> {
                if (effectiveTrack.isVoiceMessage) add(TdApi.FileTypeVoiceNote())
                add(TdApi.FileTypeAudio())
                add(TdApi.FileTypeDocument())
                add(TdApi.FileTypeUnknown())
            }
            for (ft in candidateTypes) {
                val res = executeWithFloodRetry { client.getRemoteFile(remoteId, ft, timeoutMs = 6_000L) }
                val f = res.getOrNull()
                if (f != null && f.id > 0) {
                    freshFile = f
                    break
                } else {
                    res.exceptionOrNull()?.let { err ->
                        lastErrorMsg = err.message
                    }
                }
            }
        }

        // 3C. Fallback to existing fileId if still viable
        if (freshFile == null && fileId > 0) {
            val res = executeWithFloodRetry { client.getFile(fileId, timeoutMs = 5_500L) }
            val f = res.getOrNull()
            if (f != null && f.id > 0) {
                val matchesRemote = remoteId.isNullOrBlank() || f.remote?.id.isNullOrBlank() || f.remote?.id == remoteId
                if (matchesRemote) freshFile = f
            } else {
                res.exceptionOrNull()?.let { lastErrorMsg = it.message }
            }
        }

        val targetFile = freshFile ?: return Result.failure(
            IOException(
                if (messageDeletedOrInaccessible) {
                    "Tin nhắn gốc đã bị xóa hoặc bạn không còn quyền truy cập '${effectiveTrack.title}' trên Telegram."
                } else {
                    "Không thể khôi phục tệp Telegram cho '${effectiveTrack.title}': ${lastErrorMsg ?: "kiểm tra đăng nhập Telegram"}"
                }
            )
        )

        val resolvedRemoteId = targetFile.remote?.id?.takeIf { it.isNotBlank() } ?: remoteId
        val resolvedUniqueId = targetFile.remote?.uniqueId?.takeIf { it.isNotBlank() } ?: effectiveTrack.telegramRemoteUniqueId
        val updatedSize = maxOf(expectedSize, targetFile.size, targetFile.expectedSize)

        persistUpdatedFileRef(
            trackId = effectiveTrack.id,
            fileId = targetFile.id,
            fileSize = updatedSize,
            chatId = chatId,
            messageId = messageId,
            remoteFileId = resolvedRemoteId,
            remoteUniqueId = resolvedUniqueId,
            mimeType = extractedMime,
            existingFileId = -1,
            existingRemoteId = effectiveTrack.telegramRemoteFileId
        )

        val refreshedArt = refreshMissingArtworkIfNeeded(
            trackId = effectiveTrack.id,
            currentArtworkUri = effectiveTrack.artworkUri,
            thumbFile = extractedThumb
        )

        // 4. If TDLib does not already hold a complete valid file, download & monitor UpdateFile
        val completedTdFile = if (isUsableCompletedLocalFile(targetFile, updatedSize)) {
            targetFile
        } else {
            val started = executeWithFloodRetry {
                client.downloadFile(
                    fileId = targetFile.id,
                    priority = 32,
                    offset = 0L,
                    limit = 0L,
                    synchronous = false,
                    timeoutMs = 6_500L
                )
            }.getOrElse { err ->
                return Result.failure(
                    IOException("Không thể bắt đầu tải tệp Telegram '${effectiveTrack.title}': ${err.message}", err)
                )
            }
            if (isUsableCompletedLocalFile(started, updatedSize)) {
                started
            } else {
                awaitDownloadCompletionViaUpdates(
                    fileId = started.id,
                    expectedSize = updatedSize,
                    trackTitle = effectiveTrack.title,
                    onProgress = onProgress
                ).getOrElse { err ->
                    return Result.failure(err)
                }
            }
        }

        val tdLocalPath = completedTdFile.local?.path?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IOException("TDLib không trả về đường dẫn tệp sau khi tải xong"))
        val tdLocalFile = File(tdLocalPath)

        // 5. Copy atomically into app-owned persistent Telegram audio cache so subsequent restarts bypass TDLib temp cleanup
        val finalLocalFile = if (storageManager != null) {
            val targetName = buildPersistentCacheFileName(
                chatId = chatId,
                messageId = messageId,
                fileName = effectiveTrack.fileName,
                format = effectiveTrack.format,
                trackId = effectiveTrack.id
            )
            storageManager.copyFileToPersistentAudioCache(
                sourceFile = tdLocalFile,
                targetDir = storageManager.getPersistentTelegramAudioCacheDir(),
                targetFileName = targetName,
                expectedSizeBytes = updatedSize
            ) ?: tdLocalFile
        } else {
            tdLocalFile
        }

        if (!isValidLocalFile(finalLocalFile, updatedSize)) {
            return Result.failure(IOException("Tệp Telegram tải về bị lỗi hoặc không đầy đủ"))
        }

        val finalPath = finalLocalFile.absolutePath
        val now = System.currentTimeMillis()
        val finalRemote = completedTdFile.remote?.id?.takeIf { it.isNotBlank() } ?: resolvedRemoteId
        val finalUnique = completedTdFile.remote?.uniqueId?.takeIf { it.isNotBlank() } ?: resolvedUniqueId
        val rebuiltUri = buildStreamUri(
            fileId = completedTdFile.id,
            fileSize = updatedSize,
            chatId = chatId,
            messageId = messageId,
            remoteFileId = finalRemote,
            trackId = effectiveTrack.id
        )

        val probed = runCatching { AudioFormatDetector.probeAudioSpecs(finalLocalFile, effectiveTrack.format) }.getOrNull()
        val embeddedArtPath = if (refreshedArt == null && effectiveTrack.artworkUri.isNullOrBlank()) {
            probed?.embeddedArtwork?.let { bytes -> storageManager?.saveArtworkFile(effectiveTrack.id, bytes) }
        } else {
            null
        }
        val finalArtworkUri = refreshedArt ?: embeddedArtPath ?: effectiveTrack.artworkUri

        runCatching {
            trackDao.updateVerifiedLocalAudio(
                trackId = effectiveTrack.id,
                localPath = finalPath,
                status = DownloadStatus.COMPLETED.name,
                progress = 1f,
                verifiedAtMs = now,
                fileId = completedTdFile.id,
                remoteFileId = finalRemote,
                remoteUniqueId = finalUnique,
                streamUri = rebuiltUri
            )
            if (probed != null || finalArtworkUri != effectiveTrack.artworkUri) {
                trackDao.updateTrackMetadata(
                    trackId = effectiveTrack.id,
                    artworkUri = finalArtworkUri,
                    bitrateKbps = probed?.bitrateKbps ?: 0,
                    sampleRateHz = probed?.sampleRateHz ?: 0,
                    bitDepth = probed?.bitDepth,
                    durationMs = probed?.durationMs ?: 0L
                )
            }
        }

        logger.log("TG_RECOVER", "INFO", "Đã khôi phục & lưu cache bền vững: '${effectiveTrack.title}' -> $finalPath")

        return Result.success(
            effectiveTrack.copy(
                localPath = finalPath,
                streamUri = rebuiltUri,
                telegramFileId = completedTdFile.id,
                telegramRemoteFileId = finalRemote,
                telegramRemoteUniqueId = finalUnique,
                mimeType = extractedMime,
                artworkUri = finalArtworkUri,
                bitrateKbps = probed?.bitrateKbps?.takeIf { it > 0 } ?: effectiveTrack.bitrateKbps,
                sampleRateHz = probed?.sampleRateHz?.takeIf { it > 0 } ?: effectiveTrack.sampleRateHz,
                bitDepth = probed?.bitDepth ?: effectiveTrack.bitDepth,
                durationMs = probed?.durationMs?.takeIf { it > 0L } ?: effectiveTrack.durationMs,
                downloadStatus = DownloadStatus.COMPLETED,
                downloadProgress = 1f,
                lastVerifiedAtEpochMs = now
            )
        )
    }

    private suspend fun awaitDownloadCompletionViaUpdates(
        fileId: Int,
        expectedSize: Long,
        trackTitle: String,
        onProgress: ((Float) -> Unit)?
    ): Result<TdApi.File> = coroutineScope {
        val updateChannel = Channel<TdApi.File>(Channel.CONFLATED)
        val collectorJob = launch {
            fileUpdates
                .filter { it.id == fileId }
                .collect { updateChannel.trySend(it) }
        }

        try {
            var lastDownloaded = -1L
            var lastProgressAtMs = System.currentTimeMillis()
            var retriedStall = false
            val maxStallMs = 7_500L

            while (System.currentTimeMillis() - lastProgressAtMs < maxStallMs) {
                val nextUpdate = withTimeoutOrNull(450L) { updateChannel.receive() }
                    ?: client.getFile(fileId, timeoutMs = 4_000L).getOrNull()

                if (nextUpdate != null) {
                    val total = nextUpdate.size.takeIf { it > 0 }
                        ?: nextUpdate.expectedSize.takeIf { it > 0 }
                        ?: expectedSize
                    val downloaded = nextUpdate.local?.downloadedSize ?: 0L
                    if (total > 0L) {
                        onProgress?.invoke((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                    if (isUsableCompletedLocalFile(nextUpdate, expectedSize)) {
                        onProgress?.invoke(1f)
                        return@coroutineScope Result.success(nextUpdate)
                    }
                    if (downloaded > lastDownloaded) {
                        lastDownloaded = downloaded
                        lastProgressAtMs = System.currentTimeMillis()
                        retriedStall = false
                    } else if (!retriedStall && System.currentTimeMillis() - lastProgressAtMs >= 3_500L) {
                        retriedStall = true
                        logger.log("TG_RECOVER", "WARN", "Tiếp tục tải tệp Telegram #$fileId cho '$trackTitle' (thử lại 1 lần)")
                        client.downloadFile(fileId, 32, 0L, 0L, false, timeoutMs = 5_000L)
                    }
                } else {
                    delay(150L)
                }
            }
            Result.failure(IOException("Hết thời gian tải tệp Telegram '$trackTitle' (mạng không phản hồi)"))
        } finally {
            collectorJob.cancel()
            updateChannel.close()
        }
    }

    suspend fun resolveAndStartDownload(
        target: TelegramFileTarget,
        priority: Int,
        offset: Long,
        limit: Long
    ): Result<ResolvedTelegramDownload> {
        val dbTrack = when {
            !target.trackId.isNullOrBlank() -> trackDao.getTrackById(target.trackId)
            target.chatId != null && target.chatId != 0L && target.messageId != null && target.messageId != 0L ->
                trackDao.findByTelegramMessage(target.chatId, target.messageId)
            target.fileId > 0 -> trackDao.findByTelegramFileId(target.fileId)
            else -> null
        }

        val effectiveChatId = target.chatId?.takeIf { it != 0L } ?: dbTrack?.telegramChatId
        val effectiveMessageId = target.messageId?.takeIf { it != 0L } ?: dbTrack?.telegramMessageId
        val effectiveTrackId = target.trackId?.takeIf { it.isNotBlank() }
            ?: dbTrack?.id
            ?: if (effectiveChatId != null && effectiveMessageId != null) {
                "tg_${effectiveChatId}_${effectiveMessageId}"
            } else {
                null
            }
        val effectiveRemoteId = target.remoteFileId?.takeIf { it.isNotBlank() }
            ?: dbTrack?.telegramRemoteFileId?.takeIf { it.isNotBlank() }
        val effectiveFileId = target.fileId.takeIf { it > 0 } ?: dbTrack?.telegramFileId ?: 0
        val effectiveSize = maxOf(target.expectedSize, dbTrack?.fileSizeBytes ?: 0L)
        val isVoice = target.isVoiceMessage || (dbTrack?.isVoiceMessage == true)
        val effectiveFormat = dbTrack?.format?.let { runCatching { AudioFormat.valueOf(it) }.getOrNull() }
            ?: target.format

        // 1. Try existing ephemeral TDLib file.id first if available and matches expected remoteFileId
        if (effectiveFileId > 0) {
            val initialFile = client.getFile(effectiveFileId).getOrNull()
            val matchesExpectedRemote = initialFile != null && (
                effectiveRemoteId.isNullOrBlank() ||
                    initialFile.remote?.id.isNullOrBlank() ||
                    initialFile.remote?.id == effectiveRemoteId
                )
            if (initialFile != null && matchesExpectedRemote && isUsableCompletedLocalFile(initialFile)) {
                val remoteFromTd = initialFile.remote?.id?.takeIf { it.isNotBlank() } ?: effectiveRemoteId
                val uniqueFromTd = initialFile.remote?.uniqueId?.takeIf { it.isNotBlank() }
                persistUpdatedFileRef(
                    trackId = effectiveTrackId,
                    fileId = initialFile.id,
                    fileSize = maxOf(effectiveSize, initialFile.size, initialFile.expectedSize),
                    chatId = effectiveChatId,
                    messageId = effectiveMessageId,
                    remoteFileId = remoteFromTd,
                    remoteUniqueId = uniqueFromTd,
                    mimeType = dbTrack?.mimeType,
                    existingFileId = dbTrack?.telegramFileId,
                    existingRemoteId = dbTrack?.telegramRemoteFileId
                )
                return Result.success(
                    ResolvedTelegramDownload(
                        fileId = initialFile.id,
                        tdFile = initialFile,
                        remoteFileId = remoteFromTd,
                        remoteUniqueId = uniqueFromTd,
                        trackId = effectiveTrackId
                    )
                )
            }
            if (initialFile != null && matchesExpectedRemote) {
                val started = client.downloadFile(effectiveFileId, priority, offset, limit, synchronous = false)
                if (started.isSuccess) {
                    val activeFile = started.getOrNull() ?: initialFile
                    val remoteFromTd = activeFile.remote?.id?.takeIf { it.isNotBlank() } ?: effectiveRemoteId
                    val uniqueFromTd = activeFile.remote?.uniqueId?.takeIf { it.isNotBlank() }
                    persistUpdatedFileRef(
                        trackId = effectiveTrackId,
                        fileId = effectiveFileId,
                        fileSize = maxOf(effectiveSize, activeFile.size, activeFile.expectedSize),
                        chatId = effectiveChatId,
                        messageId = effectiveMessageId,
                        remoteFileId = remoteFromTd,
                        remoteUniqueId = uniqueFromTd,
                        mimeType = dbTrack?.mimeType,
                        existingFileId = dbTrack?.telegramFileId,
                        existingRemoteId = dbTrack?.telegramRemoteFileId
                    )
                    return Result.success(
                        ResolvedTelegramDownload(
                            fileId = effectiveFileId,
                            tdFile = activeFile,
                            remoteFileId = remoteFromTd,
                            remoteUniqueId = uniqueFromTd,
                            trackId = effectiveTrackId
                        )
                    )
                }
            }
        }

        // 2. Recover using permanent TDLib remoteFileId when file.id became stale after restart
        if (!effectiveRemoteId.isNullOrBlank()) {
            val candidateTypes = buildList<TdApi.FileType> {
                if (isVoice) add(TdApi.FileTypeVoiceNote())
                add(TdApi.FileTypeAudio())
                if (effectiveFormat.isLossless || effectiveFormat == AudioFormat.MP3 || effectiveFormat == AudioFormat.M4A) {
                    add(TdApi.FileTypeDocument())
                }
                add(TdApi.FileTypeUnknown())
            }
            for (fileType in candidateTypes) {
                val remoteFile = client.getRemoteFile(effectiveRemoteId, fileType).getOrNull() ?: continue
                if (remoteFile.id <= 0) continue

                val readyOrStarted = if (isUsableCompletedLocalFile(remoteFile)) {
                    remoteFile
                } else {
                    client.downloadFile(remoteFile.id, priority, offset, limit, synchronous = false).getOrNull()
                }
                if (readyOrStarted != null) {
                    val resolvedRemote = readyOrStarted.remote?.id?.takeIf { it.isNotBlank() } ?: effectiveRemoteId
                    val resolvedUnique = readyOrStarted.remote?.uniqueId?.takeIf { it.isNotBlank() }
                    persistUpdatedFileRef(
                        trackId = effectiveTrackId,
                        fileId = readyOrStarted.id,
                        fileSize = maxOf(effectiveSize, readyOrStarted.size, readyOrStarted.expectedSize),
                        chatId = effectiveChatId,
                        messageId = effectiveMessageId,
                        remoteFileId = resolvedRemote,
                        remoteUniqueId = resolvedUnique,
                        mimeType = dbTrack?.mimeType,
                        existingFileId = dbTrack?.telegramFileId,
                        existingRemoteId = dbTrack?.telegramRemoteFileId
                    )
                    logger.log(
                        "TG_RESOLVE",
                        "INFO",
                        "Khôi phục fileId Telegram qua GetRemoteFile: #$effectiveFileId -> #${readyOrStarted.id}"
                    )
                    return Result.success(
                        ResolvedTelegramDownload(
                            fileId = readyOrStarted.id,
                            tdFile = readyOrStarted,
                            remoteFileId = resolvedRemote,
                            remoteUniqueId = resolvedUnique,
                            trackId = effectiveTrackId
                        )
                    )
                }
            }
        }

        // 3. Fallback: fetch the original message via GetMessage(chatId, messageId) to extract a fresh TdApi.File
        if (effectiveChatId != null && effectiveMessageId != null) {
            val msgResult = client.getMessage(effectiveChatId, effectiveMessageId)
            val msg = msgResult.getOrNull()
            if (msg != null) {
                val extracted = extractFilesFromMessage(msg)
                val audioFile = extracted?.first
                if (audioFile != null && audioFile.id > 0) {
                    val readyOrStarted = if (isUsableCompletedLocalFile(audioFile)) {
                        audioFile
                    } else {
                        client.downloadFile(audioFile.id, priority, offset, limit, synchronous = false).getOrNull()
                    }
                    if (readyOrStarted != null) {
                        val resolvedRemote = readyOrStarted.remote?.id?.takeIf { it.isNotBlank() }
                            ?: audioFile.remote?.id?.takeIf { it.isNotBlank() }
                            ?: effectiveRemoteId
                        val resolvedUnique = readyOrStarted.remote?.uniqueId?.takeIf { it.isNotBlank() }
                            ?: audioFile.remote?.uniqueId?.takeIf { it.isNotBlank() }
                        val resolvedMime = extractMimeTypeFromMessage(msg) ?: dbTrack?.mimeType
                        persistUpdatedFileRef(
                            trackId = effectiveTrackId,
                            fileId = readyOrStarted.id,
                            fileSize = maxOf(effectiveSize, readyOrStarted.size, readyOrStarted.expectedSize),
                            chatId = effectiveChatId,
                            messageId = effectiveMessageId,
                            remoteFileId = resolvedRemote,
                            remoteUniqueId = resolvedUnique,
                            mimeType = resolvedMime,
                            existingFileId = -1,
                            existingRemoteId = dbTrack?.telegramRemoteFileId
                        )
                        val refreshedArtwork = refreshMissingArtworkIfNeeded(
                            trackId = effectiveTrackId,
                            currentArtworkUri = dbTrack?.artworkUri,
                            thumbFile = extracted.second
                        )
                        logger.log(
                            "TG_RESOLVE",
                            "INFO",
                            "Khôi phục fileId Telegram qua GetMessage($effectiveChatId, $effectiveMessageId): #$effectiveFileId -> #${readyOrStarted.id}"
                        )
                        return Result.success(
                            ResolvedTelegramDownload(
                                fileId = readyOrStarted.id,
                                tdFile = readyOrStarted,
                                remoteFileId = resolvedRemote,
                                remoteUniqueId = resolvedUnique,
                                trackId = effectiveTrackId,
                                refreshedArtworkUri = refreshedArtwork
                            )
                        )
                    }
                }
            }
        }

        val idLabel = if (effectiveFileId > 0) "#$effectiveFileId" else "(chưa rõ ID)"
        return Result.failure(IOException("Không tìm thấy tệp Telegram $idLabel"))
    }

    private suspend fun <T> executeWithFloodRetry(block: suspend () -> Result<T>): Result<T> {
        var attempt = 0
        while (true) {
            val res = block()
            val err = res.exceptionOrNull() ?: return res
            val waitSeconds = parseFloodWaitSeconds(err)
            if (waitSeconds != null && attempt < 2) {
                attempt++
                delay((waitSeconds * 1000L).coerceIn(500L, 15_000L))
                continue
            }
            return res
        }
    }

    private fun parseFloodWaitSeconds(err: Throwable): Long? {
        val msg = err.message.orEmpty()
        val tdCode = (err as? TelegramCore.TdLibException)?.code
        if (tdCode != 429 && !msg.contains("FLOOD_WAIT", ignoreCase = true) && !msg.contains("retry after", ignoreCase = true)) {
            return null
        }
        val match = Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE).find(msg)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 3L
    }

    private fun isDeletedOrAccessDeniedError(err: Throwable): Boolean {
        val msg = err.message.orEmpty().uppercase()
        val code = (err as? TelegramCore.TdLibException)?.code
        return code == 400 || code == 403 || code == 404 ||
            "MESSAGE_ID_INVALID" in msg ||
            "MESSAGE_NOT_FOUND" in msg ||
            "CHAT_NOT_FOUND" in msg ||
            "CHANNEL_PRIVATE" in msg ||
            "NO ACCESS" in msg ||
            "NOT FOUND" in msg
    }

    private suspend fun persistUpdatedFileRef(
        trackId: String?,
        fileId: Int,
        fileSize: Long,
        chatId: Long?,
        messageId: Long?,
        remoteFileId: String?,
        remoteUniqueId: String?,
        mimeType: String?,
        existingFileId: Int?,
        existingRemoteId: String?
    ) {
        if (trackId.isNullOrBlank() || fileId <= 0) return
        val fileChanged = existingFileId != fileId
        val remoteAdded = !remoteFileId.isNullOrBlank() && existingRemoteId.isNullOrBlank()
        if (!fileChanged && !remoteAdded && remoteUniqueId.isNullOrBlank()) return

        val rebuiltUri = buildStreamUri(
            fileId = fileId,
            fileSize = fileSize,
            chatId = chatId,
            messageId = messageId,
            remoteFileId = remoteFileId,
            trackId = trackId
        )
        runCatching {
            trackDao.updateTelegramFileRef(
                trackId = trackId,
                fileId = fileId,
                remoteFileId = remoteFileId,
                remoteUniqueId = remoteUniqueId,
                mimeType = mimeType,
                streamUri = rebuiltUri,
                verifiedAtMs = System.currentTimeMillis()
            )
        }
    }

    private suspend fun refreshMissingArtworkIfNeeded(
        trackId: String?,
        currentArtworkUri: String?,
        thumbFile: TdApi.File?
    ): String? {
        if (trackId.isNullOrBlank() || thumbFile == null) return null
        val alreadyExists = !currentArtworkUri.isNullOrBlank() && (
            currentArtworkUri.startsWith("http") || File(currentArtworkUri).exists()
        )
        if (alreadyExists) return null

        val completedFile = if (isUsableCompletedLocalFile(thumbFile)) {
            thumbFile
        } else if (thumbFile.id > 0) {
            client.downloadFile(thumbFile.id, 4, 0L, 0L, synchronous = true, timeoutMs = 4_000L).getOrNull()
        } else {
            null
        } ?: return null

        val thumbPath = completedFile.local?.path?.takeIf { it.isNotBlank() } ?: return null
        val src = File(thumbPath)
        if (!src.exists()) return null

        val persistentPath = storageManager?.let { mgr ->
            runCatching { mgr.saveArtworkFile(trackId, src.readBytes()) }.getOrNull()
        } ?: thumbPath

        runCatching {
            trackDao.updateTrackMetadata(
                trackId = trackId,
                artworkUri = persistentPath,
                bitrateKbps = 0,
                sampleRateHz = 0,
                bitDepth = null,
                durationMs = 0L
            )
        }
        return persistentPath
    }

    private fun toLocalFile(pathOrUri: String): File? {
        return runCatching {
            if (pathOrUri.startsWith("file://")) {
                Uri.parse(pathOrUri).path?.let(::File)
            } else {
                File(pathOrUri)
            }
        }.getOrNull()
    }

    private fun isValidLocalFile(file: File?, expectedSize: Long): Boolean {
        if (file == null || !file.exists() || !file.isFile || !file.canRead()) return false
        return storageManager?.isValidAudioFile(file, expectedSize)
            ?: (file.length() > 1024L && (expectedSize <= 4096L || file.length() >= (expectedSize * 85L) / 100L))
    }

    private fun isUsableCompletedLocalFile(file: TdApi.File, expectedSize: Long = 0L): Boolean {
        val local = file.local ?: return false
        val path = local.path
        if (!local.isDownloadingCompleted || path.isNullOrBlank()) return false
        val f = File(path)
        return isValidLocalFile(f, expectedSize)
    }

    private fun buildPersistentCacheFileName(
        chatId: Long?,
        messageId: Long?,
        fileName: String,
        format: AudioFormat,
        trackId: String
    ): String {
        val ext = fileName.substringAfterLast('.', "").ifBlank { format.extension }
        val base = fileName.substringBeforeLast('.', "").ifBlank { trackId }
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .take(48)
        val prefix = if (chatId != null && messageId != null) "tg_${chatId}_${messageId}" else trackId
        return "${prefix}_${base}.${ext}"
    }

    companion object {
        fun buildStreamUri(
            fileId: Int,
            fileSize: Long,
            chatId: Long? = null,
            messageId: Long? = null,
            remoteFileId: String? = null,
            trackId: String? = null
        ): String {
            return Uri.Builder()
                .scheme("tg")
                .authority("file")
                .appendPath(fileId.toString())
                .appendQueryParameter("size", fileSize.coerceAtLeast(0L).toString())
                .apply {
                    if (chatId != null && chatId != 0L) appendQueryParameter("chatId", chatId.toString())
                    if (messageId != null && messageId != 0L) appendQueryParameter("msgId", messageId.toString())
                    if (!remoteFileId.isNullOrBlank()) appendQueryParameter("remoteId", remoteFileId)
                    if (!trackId.isNullOrBlank()) appendQueryParameter("trackId", trackId)
                }
                .build()
                .toString()
        }

        fun parseTargetFromUri(uri: Uri): TelegramFileTarget? {
            if (uri.scheme != "tg") return null
            val fileId = uri.lastPathSegment?.toIntOrNull() ?: return null
            return TelegramFileTarget(
                trackId = uri.getQueryParameter("trackId"),
                fileId = fileId,
                remoteFileId = uri.getQueryParameter("remoteId"),
                chatId = uri.getQueryParameter("chatId")?.toLongOrNull(),
                messageId = uri.getQueryParameter("msgId")?.toLongOrNull(),
                expectedSize = uri.getQueryParameter("size")?.toLongOrNull() ?: 0L
            )
        }

        fun extractFilesFromMessage(msg: TdApi.Message): Pair<TdApi.File, TdApi.File?>? {
            return when (val content = msg.content) {
                is TdApi.MessageAudio -> content.audio.audio to content.audio.albumCoverThumbnail?.file
                is TdApi.MessageDocument -> content.document.document to content.document.thumbnail?.file
                is TdApi.MessageVoiceNote -> content.voiceNote.voice to null
                else -> null
            }
        }

        fun extractMimeTypeFromMessage(msg: TdApi.Message): String? {
            return when (val content = msg.content) {
                is TdApi.MessageAudio -> content.audio.mimeType?.takeIf { it.isNotBlank() }
                is TdApi.MessageDocument -> content.document.mimeType?.takeIf { it.isNotBlank() }
                is TdApi.MessageVoiceNote -> content.voiceNote.mimeType?.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }
}
