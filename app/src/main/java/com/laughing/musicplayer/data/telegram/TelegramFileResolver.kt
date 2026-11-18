package com.laughing.musicplayer.data.telegram

import android.net.Uri
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.storage.UnifiedStorageManager
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException

interface TelegramFileClient {
    suspend fun getFile(fileId: Int, timeoutMs: Long = 9_000L): Result<TdApi.File>
    suspend fun getRemoteFile(
        remoteFileId: String,
        fileType: TdApi.FileType,
        timeoutMs: Long = 9_000L
    ): Result<TdApi.File>
    suspend fun getMessage(
        chatId: Long,
        messageId: Long,
        timeoutMs: Long = 9_000L
    ): Result<TdApi.Message>
    suspend fun downloadFile(
        fileId: Int,
        priority: Int,
        offset: Long,
        limit: Long,
        synchronous: Boolean,
        timeoutMs: Long = 9_000L
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
    val trackId: String?,
    val refreshedArtworkUri: String? = null
)

class TelegramFileResolver(
    private val client: TelegramFileClient,
    private val trackDao: TrackDao,
    private val storageManager: UnifiedStorageManager?,
    private val logger: ActivityLogger
) {
    constructor(
        core: TelegramCore,
        trackDao: TrackDao,
        storageManager: UnifiedStorageManager?,
        logger: ActivityLogger
    ) : this(TdCoreFileClient(core), trackDao, storageManager, logger)

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
                persistUpdatedFileRef(
                    trackId = effectiveTrackId,
                    fileId = initialFile.id,
                    fileSize = maxOf(effectiveSize, initialFile.size, initialFile.expectedSize),
                    chatId = effectiveChatId,
                    messageId = effectiveMessageId,
                    remoteFileId = remoteFromTd,
                    existingFileId = dbTrack?.telegramFileId,
                    existingRemoteId = dbTrack?.telegramRemoteFileId
                )
                return Result.success(
                    ResolvedTelegramDownload(
                        fileId = initialFile.id,
                        tdFile = initialFile,
                        remoteFileId = remoteFromTd,
                        trackId = effectiveTrackId
                    )
                )
            }
            if (initialFile != null && matchesExpectedRemote) {
                val started = client.downloadFile(effectiveFileId, priority, offset, limit, synchronous = false)
                if (started.isSuccess) {
                    val activeFile = started.getOrNull() ?: initialFile
                    val remoteFromTd = activeFile.remote?.id?.takeIf { it.isNotBlank() } ?: effectiveRemoteId
                    persistUpdatedFileRef(
                        trackId = effectiveTrackId,
                        fileId = effectiveFileId,
                        fileSize = maxOf(effectiveSize, activeFile.size, activeFile.expectedSize),
                        chatId = effectiveChatId,
                        messageId = effectiveMessageId,
                        remoteFileId = remoteFromTd,
                        existingFileId = dbTrack?.telegramFileId,
                        existingRemoteId = dbTrack?.telegramRemoteFileId
                    )
                    return Result.success(
                        ResolvedTelegramDownload(
                            fileId = effectiveFileId,
                            tdFile = activeFile,
                            remoteFileId = remoteFromTd,
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
                    persistUpdatedFileRef(
                        trackId = effectiveTrackId,
                        fileId = readyOrStarted.id,
                        fileSize = maxOf(effectiveSize, readyOrStarted.size, readyOrStarted.expectedSize),
                        chatId = effectiveChatId,
                        messageId = effectiveMessageId,
                        remoteFileId = resolvedRemote,
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
                        persistUpdatedFileRef(
                            trackId = effectiveTrackId,
                            fileId = readyOrStarted.id,
                            fileSize = maxOf(effectiveSize, readyOrStarted.size, readyOrStarted.expectedSize),
                            chatId = effectiveChatId,
                            messageId = effectiveMessageId,
                            remoteFileId = resolvedRemote,
                            existingFileId = -1, // always persist fresh reference from GetMessage
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

    private suspend fun persistUpdatedFileRef(
        trackId: String?,
        fileId: Int,
        fileSize: Long,
        chatId: Long?,
        messageId: Long?,
        remoteFileId: String?,
        existingFileId: Int?,
        existingRemoteId: String?
    ) {
        if (trackId.isNullOrBlank() || fileId <= 0) return
        val fileChanged = existingFileId != fileId
        val remoteAdded = !remoteFileId.isNullOrBlank() && existingRemoteId.isNullOrBlank()
        if (!fileChanged && !remoteAdded) return

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
                streamUri = rebuiltUri
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

    private fun isUsableCompletedLocalFile(file: TdApi.File): Boolean {
        val local = file.local ?: return false
        val path = local.path
        return local.isDownloadingCompleted && !path.isNullOrBlank() && File(path).exists()
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
    }
}
