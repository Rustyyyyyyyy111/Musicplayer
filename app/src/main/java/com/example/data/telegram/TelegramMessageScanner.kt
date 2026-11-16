package com.example.data.telegram

import com.example.core.model.AudioFormat
import com.example.core.model.DownloadStatus
import com.example.core.model.MusicSource
import com.example.core.model.Track
import com.example.core.util.ActivityLogger
import com.example.core.util.AudioFormatDetector
import com.example.core.util.AutoGenreClassifier
import com.example.core.util.VietnameseTextNormalizer
import com.example.data.local.ForumTopicEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.SyncCheckpointEntity
import com.example.data.local.TelegramDao
import com.example.data.local.TrackDao
import com.example.data.local.toEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi

class TelegramMessageScanner(
    private val core: TelegramCore,
    private val forumTopics: TelegramForumTopics,
    private val trackDao: TrackDao,
    private val telegramDao: TelegramDao,
    private val settingsDataStore: SettingsDataStore,
    private val logger: ActivityLogger
) {
    private val concurrencySemaphore = Semaphore(3)

    suspend fun scanSelectedSources(fullRescan: Boolean = false): Int {
        val selected = telegramDao.getSelectedSources()
        if (selected.isEmpty()) return 0
        var totalImported = 0
        for (source in selected) {
            totalImported += concurrencySemaphore.withPermit {
                scanChat(source.chatId, fullRescan = fullRescan)
            }
        }
        return totalImported
    }

    suspend fun scanChat(chatId: Long, fullRescan: Boolean = false): Int {
        val chat = core.sendQuery(TdApi.GetChat(chatId)).getOrElse { err ->
            logger.log("TG_SYNC", "ERROR", "Không thể đọc thông tin chat $chatId: ${err.message}")
            return 0
        }
        val includeVoice = settingsDataStore.preferencesFlow.first().includeVoiceMessages
        val isForum = forumTopics.isForumSupergroup(chat)

        return if (isForum) {
            scanForumGroup(chat, includeVoice, fullRescan)
        } else {
            scanStandardChat(chat, includeVoice, fullRescan)
        }
    }

    private suspend fun scanForumGroup(
        chat: TdApi.Chat,
        includeVoice: Boolean,
        fullRescan: Boolean
    ): Int {
        val topics = forumTopics.fetchForumTopics(chat.id)
        if (topics.isEmpty()) {
            return scanStandardChat(chat, includeVoice, fullRescan)
        }

        val topicNameMap = topics.associate { it.topicId to it.name }
        var totalTracks = 0
        val updatedTopics = mutableListOf<ForumTopicEntity>()

        for (topic in topics) {
            val topicTracks = mutableListOf<Track>()
            var fromMsgId = 0L
            var pages = 0
            while (pages < 40) {
                val messages = forumTopics.fetchMessagesForTopic(
                    chatId = chat.id,
                    forumTopicId = topic.topicId.toInt(),
                    fromMessageId = fromMsgId,
                    limit = 50
                )
                if (messages.isEmpty()) break

                messages.forEach { msg ->
                    extractTrackFromMessage(
                        msg = msg,
                        chatTitle = chat.title,
                        topicNameOverride = topic.name,
                        topicThreadIdOverride = topic.messageThreadId,
                        includeVoice = includeVoice
                    )?.let { topicTracks += it }
                }
                val oldestId = messages.minOfOrNull { it.id } ?: break
                if (oldestId == fromMsgId) break
                fromMsgId = oldestId
                pages++
                delay(120L)
            }

            if (topicTracks.isNotEmpty()) {
                persistMergedTracks(topicTracks)
                totalTracks += topicTracks.size
            }
            updatedTopics += ForumTopicEntity(
                chatId = chat.id,
                messageThreadId = topic.messageThreadId,
                topicId = topic.topicId,
                name = topic.name,
                iconUrl = topic.iconUrl,
                trackCount = topicTracks.size
            )
            logger.log(
                "TG_SYNC",
                "INFO",
                "Forum topic '${topic.name}' (${topic.topicId}): ${topicTracks.size} bài nhạc"
            )
        }

        val checkpoint = if (fullRescan) null else telegramDao.getCheckpoint(chat.id)
        val extra = scanPagedHistory(chat, checkpoint, includeVoice, topicNameMap)
        totalTracks += extra

        if (updatedTopics.isNotEmpty()) {
            telegramDao.upsertTopics(updatedTopics)
        }
        telegramDao.updateSyncStats(
            chatId = chat.id,
            audioCount = totalTracks,
            topicCount = updatedTopics.size,
            lastMsgId = chat.lastMessage?.id ?: 0L,
            ts = System.currentTimeMillis()
        )
        return totalTracks
    }

    private suspend fun scanStandardChat(
        chat: TdApi.Chat,
        includeVoice: Boolean,
        fullRescan: Boolean
    ): Int {
        val checkpoint = if (fullRescan) null else telegramDao.getCheckpoint(chat.id)
        val count = scanPagedHistory(chat, checkpoint, includeVoice, emptyMap())
        telegramDao.updateSyncStats(
            chatId = chat.id,
            audioCount = count,
            topicCount = 0,
            lastMsgId = chat.lastMessage?.id ?: 0L,
            ts = System.currentTimeMillis()
        )
        return count
    }

    private suspend fun scanPagedHistory(
        chat: TdApi.Chat,
        checkpoint: SyncCheckpointEntity?,
        includeVoice: Boolean,
        topicNameMap: Map<Long, String>
    ): Int {
        var fromMessageId = if (checkpoint != null && !checkpoint.completedInitialScan) {
            checkpoint.oldestMessageId
        } else {
            0L
        }
        val stopAtNewestId = if (checkpoint?.completedInitialScan == true) checkpoint.newestMessageId else 0L
        var newestSeenId = checkpoint?.newestMessageId ?: 0L
        var oldestSeenId = checkpoint?.oldestMessageId ?: Long.MAX_VALUE
        val collected = mutableListOf<Track>()
        var totalAdded = 0
        var page = 0

        while (page < 60) {
            val response = core.sendQuery(
                TdApi.GetChatHistory(chat.id, fromMessageId, 0, 50, false)
            ).getOrElse { err ->
                if (err is TelegramCore.TdLibException && err.code == 429) {
                    logger.log("TG_SYNC", "WARN", "Giới hạn tốc độ Telegram cho ${chat.title}, tạm nghỉ...")
                    delay(2500L)
                    return@getOrElse null
                }
                logger.log("TG_SYNC", "ERROR", "Lỗi đồng bộ ${chat.title}: ${err.message}")
                null
            } ?: break

            val messages = response.messages
            if (messages.isEmpty()) break

            var reachedAlreadySynced = false
            for (msg in messages) {
                if (msg.id > newestSeenId) newestSeenId = msg.id
                if (msg.id < oldestSeenId) oldestSeenId = msg.id
                if (stopAtNewestId > 0L && msg.id <= stopAtNewestId) {
                    reachedAlreadySynced = true
                    break
                }
                val topicKey = extractTopicKey(msg)
                val topicName = topicKey?.let { topicNameMap[it] }
                extractTrackFromMessage(
                    msg = msg,
                    chatTitle = chat.title,
                    topicNameOverride = topicName,
                    topicThreadIdOverride = topicKey,
                    includeVoice = includeVoice
                )?.let {
                    collected += it
                    totalAdded++
                }
            }

            if (collected.size >= 30) {
                persistMergedTracks(collected.toList())
                collected.clear()
            }

            telegramDao.upsertCheckpoint(
                SyncCheckpointEntity(
                    chatId = chat.id,
                    oldestMessageId = if (oldestSeenId == Long.MAX_VALUE) 0L else oldestSeenId,
                    newestMessageId = newestSeenId,
                    completedInitialScan = reachedAlreadySynced,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )

            if (reachedAlreadySynced) break
            val nextFromId = messages.minOfOrNull { it.id } ?: break
            if (nextFromId == fromMessageId) break
            fromMessageId = nextFromId
            page++
            delay(100L)
        }

        if (collected.isNotEmpty()) {
            persistMergedTracks(collected)
        }
        telegramDao.upsertCheckpoint(
            SyncCheckpointEntity(
                chatId = chat.id,
                oldestMessageId = if (oldestSeenId == Long.MAX_VALUE) 0L else oldestSeenId,
                newestMessageId = newestSeenId,
                completedInitialScan = true,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        logger.log("TG_SYNC", "INFO", "Đồng bộ '${chat.title}': đã quét $page trang ($totalAdded bài)")
        return totalAdded
    }

    private fun extractTopicKey(msg: TdApi.Message): Long? {
        return when (val topic = msg.topicId) {
            is TdApi.MessageTopicForum -> topic.forumTopicId.toLong()
            is TdApi.MessageTopicThread -> topic.messageThreadId
            else -> null
        }
    }

    private fun extractTrackFromMessage(
        msg: TdApi.Message,
        chatTitle: String,
        topicNameOverride: String?,
        topicThreadIdOverride: Long?,
        includeVoice: Boolean
    ): Track? {
        val effectiveThreadId = topicThreadIdOverride ?: extractTopicKey(msg)
        val baseId = "tg_${msg.chatId}_${msg.id}"

        return when (val content = msg.content) {
            is TdApi.MessageAudio -> {
                val audio = content.audio
                val format = AudioFormatDetector.detectFromMetadata(
                    fileName = audio.fileName,
                    mimeType = audio.mimeType,
                    isVoiceNote = false,
                    isVideoNote = false,
                    includeVoiceMessages = includeVoice
                ) ?: AudioFormat.MP3

                val rawTitle = audio.title.ifBlank { audio.fileName.substringBeforeLast('.') }
                    .ifBlank { "Telegram Audio #${msg.id}" }
                val resolvedArtist = topicNameOverride?.takeIf { it.isNotBlank() }
                    ?: audio.performer.ifBlank { chatTitle.ifBlank { "Telegram" } }
                val fileSize = audio.audio.size.takeIf { it > 0 } ?: audio.audio.expectedSize
                val durationMs = audio.duration * 1000L
                val bitrate = if (durationMs > 0 && fileSize > 0) {
                    ((fileSize * 8L) / durationMs).toInt()
                } else 0

                Track(
                    id = baseId,
                    title = rawTitle,
                    artist = resolvedArtist,
                    album = topicNameOverride ?: chatTitle.ifBlank { "Telegram" },
                    source = MusicSource.TELEGRAM,
                    durationMs = durationMs,
                    format = format,
                    bitrateKbps = bitrate,
                    sampleRateHz = if (format.isLossless) 48000 else 44100,
                    bitDepth = if (format.isLossless) 24 else 16,
                    fileSizeBytes = fileSize,
                    fileName = audio.fileName.ifBlank { "$rawTitle.${format.extension}" },
                    streamUri = "tg://file/${audio.audio.id}?size=$fileSize",
                    telegramChatId = msg.chatId,
                    telegramChatTitle = chatTitle,
                    telegramMessageId = msg.id,
                    telegramFileId = audio.audio.id,
                    telegramThreadId = effectiveThreadId,
                    telegramTopicName = topicNameOverride,
                    genre = AutoGenreClassifier.classify(
                        title = rawTitle,
                        artist = resolvedArtist,
                        album = chatTitle,
                        fileName = audio.fileName,
                        sourceContext = "${topicNameOverride.orEmpty()} $chatTitle"
                    ),
                    isVoiceMessage = false,
                    downloadStatus = if (audio.audio.local.isDownloadingCompleted) DownloadStatus.COMPLETED else DownloadStatus.NONE,
                    localPath = audio.audio.local.path.takeIf { audio.audio.local.isDownloadingCompleted },
                    dateAddedEpochMs = msg.date * 1000L
                )
            }

            is TdApi.MessageDocument -> {
                val doc = content.document
                val format = AudioFormatDetector.detectFromMetadata(
                    fileName = doc.fileName,
                    mimeType = doc.mimeType,
                    isVoiceNote = false,
                    isVideoNote = false,
                    includeVoiceMessages = includeVoice
                ) ?: return null

                val parsedName = doc.fileName.substringBeforeLast('.')
                val (parsedArtist, parsedTitle) = splitArtistAndTitle(parsedName)
                val resolvedArtist = topicNameOverride?.takeIf { it.isNotBlank() }
                    ?: parsedArtist
                    ?: chatTitle.ifBlank { "Telegram" }
                val fileSize = doc.document.size.takeIf { it > 0 } ?: doc.document.expectedSize

                Track(
                    id = baseId,
                    title = parsedTitle.ifBlank { doc.fileName },
                    artist = resolvedArtist,
                    album = topicNameOverride ?: chatTitle.ifBlank { "Telegram" },
                    source = MusicSource.TELEGRAM,
                    durationMs = 0L,
                    format = format,
                    bitrateKbps = 0,
                    sampleRateHz = if (format.isLossless) 48000 else 44100,
                    bitDepth = if (format.isLossless) 24 else 16,
                    fileSizeBytes = fileSize,
                    fileName = doc.fileName,
                    streamUri = "tg://file/${doc.document.id}?size=$fileSize",
                    telegramChatId = msg.chatId,
                    telegramChatTitle = chatTitle,
                    telegramMessageId = msg.id,
                    telegramFileId = doc.document.id,
                    telegramThreadId = effectiveThreadId,
                    telegramTopicName = topicNameOverride,
                    genre = AutoGenreClassifier.classify(
                        title = parsedTitle,
                        artist = resolvedArtist,
                        album = chatTitle,
                        fileName = doc.fileName,
                        sourceContext = "${topicNameOverride.orEmpty()} $chatTitle"
                    ),
                    isVoiceMessage = false,
                    downloadStatus = if (doc.document.local.isDownloadingCompleted) DownloadStatus.COMPLETED else DownloadStatus.NONE,
                    localPath = doc.document.local.path.takeIf { doc.document.local.isDownloadingCompleted },
                    dateAddedEpochMs = msg.date * 1000L
                )
            }

            is TdApi.MessageVoiceNote -> {
                if (!includeVoice) return null
                val voice = content.voiceNote
                val fileSize = voice.voice.size.takeIf { it > 0 } ?: voice.voice.expectedSize
                Track(
                    id = baseId,
                    title = "Tin nhắn thoại #${msg.id}",
                    artist = topicNameOverride ?: chatTitle.ifBlank { "Telegram" },
                    album = chatTitle,
                    source = MusicSource.TELEGRAM,
                    durationMs = voice.duration * 1000L,
                    format = AudioFormat.OGG,
                    fileSizeBytes = fileSize,
                    fileName = "voice_${msg.id}.ogg",
                    streamUri = "tg://file/${voice.voice.id}?size=$fileSize",
                    telegramChatId = msg.chatId,
                    telegramChatTitle = chatTitle,
                    telegramMessageId = msg.id,
                    telegramFileId = voice.voice.id,
                    telegramThreadId = effectiveThreadId,
                    telegramTopicName = topicNameOverride,
                    isVoiceMessage = true,
                    dateAddedEpochMs = msg.date * 1000L
                )
            }

            else -> null
        }
    }

    private suspend fun persistMergedTracks(tracks: List<Track>) {
        val entities = tracks.map { track ->
            val existing = trackDao.findByTelegramMessage(
                track.telegramChatId ?: 0L,
                track.telegramMessageId ?: 0L
            )
            val merged = if (existing != null) {
                track.copy(
                    isFavorite = existing.isFavorite,
                    playCount = existing.playCount,
                    lastPlayedEpochMs = existing.lastPlayedEpochMs,
                    localPath = existing.localPath ?: track.localPath,
                    downloadStatus = if (existing.downloadStatus == DownloadStatus.COMPLETED.name) {
                        DownloadStatus.COMPLETED
                    } else track.downloadStatus,
                    genre = if (existing.genreManuallyEdited) {
                        runCatching { com.example.core.model.GenreType.valueOf(existing.genre) }
                            .getOrDefault(track.genre)
                    } else track.genre,
                    genreManuallyEdited = existing.genreManuallyEdited
                )
            } else track
            val blob = VietnameseTextNormalizer.normalize(
                "${merged.title} ${merged.artist} ${merged.album} ${merged.telegramTopicName.orEmpty()} ${merged.fileName} ${merged.telegramChatTitle.orEmpty()}"
            )
            merged.toEntity(blob)
        }
        trackDao.upsertTracks(entities)
    }

    private fun splitArtistAndTitle(name: String): Pair<String?, String> {
        val parts = name.split(" - ", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0].trim() to parts[1].trim()
        } else {
            null to name.trim()
        }
    }
}
