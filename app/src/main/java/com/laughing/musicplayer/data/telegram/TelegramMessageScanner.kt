package com.laughing.musicplayer.data.telegram

import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.core.util.AudioFormatDetector
import com.laughing.musicplayer.core.util.AutoGenreClassifier
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import com.laughing.musicplayer.data.local.ForumTopicEntity
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.data.local.SyncCheckpointEntity
import com.laughing.musicplayer.data.local.TelegramDao
import com.laughing.musicplayer.data.local.TrackDao
import com.laughing.musicplayer.data.local.toEntity
import com.laughing.musicplayer.data.storage.UnifiedStorageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi
import java.io.File

class TelegramMessageScanner(
    private val core: TelegramCore,
    private val forumTopics: TelegramForumTopics,
    private val trackDao: TrackDao,
    private val telegramDao: TelegramDao,
    private val settingsDataStore: SettingsDataStore,
    private val storageManager: UnifiedStorageManager,
    private val logger: ActivityLogger
) {
    private val concurrencySemaphore = Semaphore(3)

    private data class ExtractedMessageResult(
        val track: Track,
        val thumbnailFile: TdApi.File? = null
    )

    suspend fun scanSelectedSources(fullRescan: Boolean = false): Int {
        val selected = telegramDao.getSelectedSources()
        if (selected.isEmpty()) return 0
        var totalTracks = 0
        for (source in selected) {
            totalTracks += concurrencySemaphore.withPermit {
                scanChat(source.chatId, fullRescan = fullRescan)
            }
        }
        return totalTracks
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

        val checkpoint = if (fullRescan) null else telegramDao.getCheckpoint(chat.id)
        val stopAtNewestId = if (checkpoint?.completedInitialScan == true) checkpoint.newestMessageId else 0L
        val topicNameMap = topics.associate { it.topicId to it.name }
        val seenMessageKeys = HashSet<String>()
        var newestSeenId = checkpoint?.newestMessageId ?: 0L
        val updatedTopics = mutableListOf<ForumTopicEntity>()

        for (topic in topics) {
            val topicResults = mutableListOf<ExtractedMessageResult>()
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

                var reachedAlreadySynced = false
                for (msg in messages) {
                    if (msg.id > newestSeenId) newestSeenId = msg.id
                    if (stopAtNewestId > 0L && msg.id <= stopAtNewestId) {
                        reachedAlreadySynced = true
                        break
                    }
                    val uniqueKey = "${msg.chatId}_${msg.id}"
                    if (!seenMessageKeys.add(uniqueKey)) continue
                    extractTrackFromMessage(
                        msg = msg,
                        chatTitle = chat.title,
                        topicNameOverride = topic.name,
                        topicThreadIdOverride = topic.messageThreadId,
                        includeVoice = includeVoice
                    )?.let { topicResults += it }
                }
                if (reachedAlreadySynced) break
                val oldestId = messages.minOfOrNull { it.id } ?: break
                if (oldestId == fromMsgId) break
                fromMsgId = oldestId
                pages++
                delay(120L)
            }

            if (topicResults.isNotEmpty()) {
                persistMergedTracks(topicResults)
            }
            val deduplicatedTopicCount = trackDao.countDistinctTelegramTracksForTopic(
                chatId = chat.id,
                threadId = topic.messageThreadId
            )
            updatedTopics += ForumTopicEntity(
                chatId = chat.id,
                messageThreadId = topic.messageThreadId,
                topicId = topic.topicId,
                name = topic.name,
                iconUrl = topic.iconUrl,
                trackCount = deduplicatedTopicCount
            )
            logger.log(
                "TG_SYNC",
                "INFO",
                "Forum topic '${topic.name}' (${topic.topicId}): $deduplicatedTopicCount bài nhạc"
            )
        }

        // Catch only messages that were not already visited by topic scans (e.g., general/un-threaded messages)
        val newlyAddedFromGeneral = scanPagedHistory(
            chat = chat,
            checkpoint = checkpoint,
            includeVoice = includeVoice,
            topicNameMap = topicNameMap,
            excludedMessageKeys = seenMessageKeys
        )

        if (newestSeenId > (checkpoint?.newestMessageId ?: 0L)) {
            val latestCheckpoint = telegramDao.getCheckpoint(chat.id)
            telegramDao.upsertCheckpoint(
                SyncCheckpointEntity(
                    chatId = chat.id,
                    oldestMessageId = latestCheckpoint?.oldestMessageId ?: 0L,
                    newestMessageId = maxOf(newestSeenId, latestCheckpoint?.newestMessageId ?: 0L),
                    completedInitialScan = latestCheckpoint?.completedInitialScan ?: true,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }

        val refreshedTopics = updatedTopics.map { topicEntity ->
            val refreshedCount = if (newlyAddedFromGeneral > 0) {
                trackDao.countDistinctTelegramTracksForTopic(chat.id, topicEntity.messageThreadId)
            } else {
                topicEntity.trackCount
            }
            topicEntity.copy(trackCount = refreshedCount)
        }
        if (refreshedTopics.isNotEmpty()) {
            telegramDao.upsertTopics(refreshedTopics)
        }

        val deduplicatedChatCount = trackDao.countDistinctTelegramTracksForChat(chat.id)
        telegramDao.updateSyncStats(
            chatId = chat.id,
            audioCount = deduplicatedChatCount,
            topicCount = refreshedTopics.size,
            lastMsgId = chat.lastMessage?.id ?: 0L,
            ts = System.currentTimeMillis()
        )
        return deduplicatedChatCount
    }

    private suspend fun scanStandardChat(
        chat: TdApi.Chat,
        includeVoice: Boolean,
        fullRescan: Boolean
    ): Int {
        val checkpoint = if (fullRescan) null else telegramDao.getCheckpoint(chat.id)
        val seenMessageKeys = HashSet<String>()
        scanPagedHistory(
            chat = chat,
            checkpoint = checkpoint,
            includeVoice = includeVoice,
            topicNameMap = emptyMap(),
            excludedMessageKeys = seenMessageKeys
        )
        val deduplicatedChatCount = trackDao.countDistinctTelegramTracksForChat(chat.id)
        telegramDao.updateSyncStats(
            chatId = chat.id,
            audioCount = deduplicatedChatCount,
            topicCount = 0,
            lastMsgId = chat.lastMessage?.id ?: 0L,
            ts = System.currentTimeMillis()
        )
        return deduplicatedChatCount
    }

    private suspend fun scanPagedHistory(
        chat: TdApi.Chat,
        checkpoint: SyncCheckpointEntity?,
        includeVoice: Boolean,
        topicNameMap: Map<Long, String>,
        excludedMessageKeys: MutableSet<String> = HashSet()
    ): Int {
        var fromMessageId = if (checkpoint != null && !checkpoint.completedInitialScan) {
            checkpoint.oldestMessageId
        } else {
            0L
        }
        val stopAtNewestId = if (checkpoint?.completedInitialScan == true) checkpoint.newestMessageId else 0L
        var newestSeenId = checkpoint?.newestMessageId ?: 0L
        var oldestSeenId = checkpoint?.oldestMessageId ?: Long.MAX_VALUE
        val collected = mutableListOf<ExtractedMessageResult>()
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
                val uniqueKey = "${msg.chatId}_${msg.id}"
                if (!excludedMessageKeys.add(uniqueKey)) continue
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
        logger.log("TG_SYNC", "INFO", "Đồng bộ '${chat.title}': đã quét $page trang ($totalAdded bài mới)")
        return totalAdded
    }

    private fun extractTopicKey(msg: TdApi.Message): Long? {
        return when (val topic = msg.topicId) {
            is TdApi.MessageTopicForum -> topic.forumTopicId.toLong()
            is TdApi.MessageTopicThread -> topic.messageThreadId
            else -> null
        }
    }

    private suspend fun extractTrackFromMessage(
        msg: TdApi.Message,
        chatTitle: String,
        topicNameOverride: String?,
        topicThreadIdOverride: Long?,
        includeVoice: Boolean
    ): ExtractedMessageResult? {
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

                val fileSize = audio.audio.size.takeIf { it > 0 } ?: audio.audio.expectedSize
                val localAudioPath = audio.audio.local.path.takeIf {
                    audio.audio.local.isDownloadingCompleted && it.isNotBlank() && File(it).exists()
                }
                val probed = localAudioPath?.let { path ->
                    AudioFormatDetector.probeAudioSpecs(File(path), format)
                }

                val rawTitle = probed?.title
                    ?: audio.title.ifBlank { audio.fileName.substringBeforeLast('.') }
                        .ifBlank { "Telegram Audio #${msg.id}" }
                val resolvedArtist = probed?.artist
                    ?: audio.performer.takeIf { it.isNotBlank() }
                    ?: topicNameOverride?.takeIf { it.isNotBlank() }
                    ?: chatTitle.ifBlank { "Telegram" }
                val resolvedAlbum = probed?.album
                    ?: topicNameOverride
                    ?: chatTitle.ifBlank { "Telegram" }
                val durationMs = probed?.durationMs?.takeIf { it > 0L } ?: (audio.duration * 1000L)
                val bitrate = probed?.bitrateKbps?.takeIf { it > 0 } ?: if (durationMs > 0 && fileSize > 0) {
                    ((fileSize * 8L) / durationMs).toInt()
                } else 0

                val artworkPath = resolveTelegramArtworkPath(
                    trackId = baseId,
                    thumbnailFile = audio.albumCoverThumbnail?.file,
                    miniThumbBytes = audio.albumCoverMinithumbnail?.data,
                    embeddedBytes = probed?.embeddedArtwork
                )

                val guessedSpecs = inferSpecsFromMeta(audio.fileName, format, bitrate)
                val remoteFileId = audio.audio.remote?.id?.takeIf { it.isNotBlank() }
                val track = Track(
                    id = baseId,
                    title = rawTitle,
                    artist = resolvedArtist,
                    album = resolvedAlbum,
                    source = MusicSource.TELEGRAM,
                    durationMs = durationMs,
                    format = format,
                    bitrateKbps = bitrate,
                    sampleRateHz = probed?.sampleRateHz?.takeIf { it > 0 } ?: guessedSpecs.first,
                    bitDepth = probed?.bitDepth ?: guessedSpecs.second,
                    fileSizeBytes = fileSize,
                    fileName = audio.fileName.ifBlank { "$rawTitle.${format.extension}" },
                    artworkUri = artworkPath,
                    streamUri = TelegramFileResolver.buildStreamUri(
                        fileId = audio.audio.id,
                        fileSize = fileSize,
                        chatId = msg.chatId,
                        messageId = msg.id,
                        remoteFileId = remoteFileId,
                        trackId = baseId
                    ),
                    telegramChatId = msg.chatId,
                    telegramChatTitle = chatTitle,
                    telegramMessageId = msg.id,
                    telegramFileId = audio.audio.id,
                    telegramRemoteFileId = remoteFileId,
                    telegramThreadId = effectiveThreadId,
                    telegramTopicName = topicNameOverride,
                    genre = AutoGenreClassifier.classify(
                        title = rawTitle,
                        artist = resolvedArtist,
                        album = resolvedAlbum,
                        fileName = audio.fileName,
                        sourceContext = "${topicNameOverride.orEmpty()} $chatTitle"
                    ),
                    isVoiceMessage = false,
                    downloadStatus = if (audio.audio.local.isDownloadingCompleted) DownloadStatus.COMPLETED else DownloadStatus.NONE,
                    localPath = localAudioPath,
                    dateAddedEpochMs = msg.date * 1000L
                )
                ExtractedMessageResult(track, audio.albumCoverThumbnail?.file)
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
                val fileSize = doc.document.size.takeIf { it > 0 } ?: doc.document.expectedSize
                val localDocPath = doc.document.local.path.takeIf {
                    doc.document.local.isDownloadingCompleted && it.isNotBlank() && File(it).exists()
                }
                val probed = localDocPath?.let { path ->
                    AudioFormatDetector.probeAudioSpecs(File(path), format)
                }

                val title = probed?.title ?: parsedTitle.ifBlank { doc.fileName }
                val resolvedArtist = probed?.artist
                    ?: parsedArtist
                    ?: topicNameOverride?.takeIf { it.isNotBlank() }
                    ?: chatTitle.ifBlank { "Telegram" }
                val resolvedAlbum = probed?.album ?: topicNameOverride ?: chatTitle.ifBlank { "Telegram" }

                val artworkPath = resolveTelegramArtworkPath(
                    trackId = baseId,
                    thumbnailFile = doc.thumbnail?.file,
                    miniThumbBytes = doc.minithumbnail?.data,
                    embeddedBytes = probed?.embeddedArtwork
                )
                val durationMs = probed?.durationMs ?: 0L
                val bitrate = probed?.bitrateKbps?.takeIf { it > 0 } ?: if (durationMs > 0 && fileSize > 0) {
                    ((fileSize * 8L) / durationMs).toInt()
                } else 0
                val guessedSpecs = inferSpecsFromMeta(doc.fileName, format, bitrate)

                val docRemoteFileId = doc.document.remote?.id?.takeIf { it.isNotBlank() }
                val track = Track(
                    id = baseId,
                    title = title,
                    artist = resolvedArtist,
                    album = resolvedAlbum,
                    source = MusicSource.TELEGRAM,
                    durationMs = durationMs,
                    format = format,
                    bitrateKbps = bitrate,
                    sampleRateHz = probed?.sampleRateHz?.takeIf { it > 0 } ?: guessedSpecs.first,
                    bitDepth = probed?.bitDepth ?: guessedSpecs.second,
                    fileSizeBytes = fileSize,
                    fileName = doc.fileName,
                    artworkUri = artworkPath,
                    streamUri = TelegramFileResolver.buildStreamUri(
                        fileId = doc.document.id,
                        fileSize = fileSize,
                        chatId = msg.chatId,
                        messageId = msg.id,
                        remoteFileId = docRemoteFileId,
                        trackId = baseId
                    ),
                    telegramChatId = msg.chatId,
                    telegramChatTitle = chatTitle,
                    telegramMessageId = msg.id,
                    telegramFileId = doc.document.id,
                    telegramRemoteFileId = docRemoteFileId,
                    telegramThreadId = effectiveThreadId,
                    telegramTopicName = topicNameOverride,
                    genre = AutoGenreClassifier.classify(
                        title = title,
                        artist = resolvedArtist,
                        album = resolvedAlbum,
                        fileName = doc.fileName,
                        sourceContext = "${topicNameOverride.orEmpty()} $chatTitle"
                    ),
                    isVoiceMessage = false,
                    downloadStatus = if (doc.document.local.isDownloadingCompleted) DownloadStatus.COMPLETED else DownloadStatus.NONE,
                    localPath = localDocPath,
                    dateAddedEpochMs = msg.date * 1000L
                )
                ExtractedMessageResult(track, doc.thumbnail?.file)
            }

            is TdApi.MessageVoiceNote -> {
                if (!includeVoice) return null
                val voice = content.voiceNote
                val fileSize = voice.voice.size.takeIf { it > 0 } ?: voice.voice.expectedSize
                val durationMs = voice.duration * 1000L
                val bitrate = if (durationMs > 0 && fileSize > 0) ((fileSize * 8L) / durationMs).toInt() else 64
                val voiceRemoteFileId = voice.voice.remote?.id?.takeIf { it.isNotBlank() }
                ExtractedMessageResult(
                    track = Track(
                        id = baseId,
                        title = "Tin nhắn thoại #${msg.id}",
                        artist = topicNameOverride ?: chatTitle.ifBlank { "Telegram" },
                        album = chatTitle,
                        source = MusicSource.TELEGRAM,
                        durationMs = durationMs,
                        format = AudioFormat.OGG,
                        bitrateKbps = bitrate,
                        sampleRateHz = 48000,
                        bitDepth = null,
                        fileSizeBytes = fileSize,
                        fileName = "voice_${msg.id}.ogg",
                        streamUri = TelegramFileResolver.buildStreamUri(
                            fileId = voice.voice.id,
                            fileSize = fileSize,
                            chatId = msg.chatId,
                            messageId = msg.id,
                            remoteFileId = voiceRemoteFileId,
                            trackId = baseId
                        ),
                        telegramChatId = msg.chatId,
                        telegramChatTitle = chatTitle,
                        telegramMessageId = msg.id,
                        telegramFileId = voice.voice.id,
                        telegramRemoteFileId = voiceRemoteFileId,
                        telegramThreadId = effectiveThreadId,
                        telegramTopicName = topicNameOverride,
                        isVoiceMessage = true,
                        dateAddedEpochMs = msg.date * 1000L
                    )
                )
            }

            else -> null
        }
    }

    private suspend fun resolveTelegramArtworkPath(
        trackId: String,
        thumbnailFile: TdApi.File?,
        miniThumbBytes: ByteArray?,
        embeddedBytes: ByteArray?
    ): String? {
        if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
            storageManager.saveArtworkFile(trackId, embeddedBytes)?.let { return it }
        }
        val localThumb = thumbnailFile?.local?.path
        if (!localThumb.isNullOrBlank() && thumbnailFile.local.isDownloadingCompleted) {
            val thumbFile = File(localThumb)
            if (thumbFile.exists()) {
                storageManager.saveArtworkFile(trackId, thumbFile.readBytes())?.let { return it }
                return localThumb
            }
        }
        if (miniThumbBytes != null && miniThumbBytes.isNotEmpty()) {
            storageManager.saveArtworkFile("${trackId}_thumb", miniThumbBytes)?.let { return it }
        }
        return null
    }

    private fun inferSpecsFromMeta(fileName: String, format: AudioFormat, bitrateKbps: Int): Pair<Int, Int?> {
        val lower = fileName.lowercase()
        val sr = when {
            "192k" in lower -> 192000
            "96k" in lower -> 96000
            "88.2k" in lower -> 88200
            "48k" in lower -> 48000
            "44.1k" in lower -> 44100
            format == AudioFormat.OPUS -> 48000
            format.isLossless && bitrateKbps > 1100 -> 48000
            else -> 44100
        }
        val depth = if (format.isLossless) {
            when {
                "24bit" in lower || "24-bit" in lower || "hi-res" in lower || "hires" in lower || bitrateKbps > 1150 -> 24
                else -> 16
            }
        } else null
        return sr to depth
    }

    private suspend fun persistMergedTracks(results: List<ExtractedMessageResult>) {
        val deduplicated = results.distinctBy { it.track.telegramChatId to it.track.telegramMessageId }
        val entities = deduplicated.map { item ->
            val track = item.track
            val existing = trackDao.findByTelegramMessage(
                track.telegramChatId ?: 0L,
                track.telegramMessageId ?: 0L
            )
            val merged = if (existing != null) {
                val existingArtValid = existing.artworkUri?.takeIf {
                    it.startsWith("http") || File(it).exists()
                }
                val existingLocalValid = existing.localPath?.takeIf {
                    it.startsWith("content://") || File(it).exists()
                }
                track.copy(
                    id = existing.id,
                    isFavorite = existing.isFavorite,
                    playCount = existing.playCount,
                    lastPlayedEpochMs = existing.lastPlayedEpochMs,
                    artworkUri = existingArtValid ?: track.artworkUri,
                    localPath = existingLocalValid ?: track.localPath,
                    telegramRemoteFileId = track.telegramRemoteFileId ?: existing.telegramRemoteFileId,
                    downloadStatus = if (existing.downloadStatus == DownloadStatus.COMPLETED.name && existingLocalValid != null) {
                        DownloadStatus.COMPLETED
                    } else track.downloadStatus,
                    genre = if (existing.genreManuallyEdited) {
                        runCatching { GenreType.valueOf(existing.genre) }
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

        // Download higher-res TDLib album thumbnails cleanly and copy into persistent artwork storage
        deduplicated.forEach { item ->
            val thumb = item.thumbnailFile ?: return@forEach
            val existingArtwork = trackDao.getTrackById(item.track.id)?.artworkUri
            val hasFullArtwork = !existingArtwork.isNullOrBlank() &&
                !existingArtwork.endsWith("_thumb.jpg") &&
                (existingArtwork.startsWith("http") || File(existingArtwork).exists())
            if (hasFullArtwork) return@forEach

            if (!thumb.local.isDownloadingCompleted && thumb.id > 0) {
                val downloaded = core.sendQuery(
                    TdApi.DownloadFile(thumb.id, 4, 0L, 0L, true),
                    timeoutMs = 5_000L
                ).getOrNull()
                val thumbPath = downloaded?.local?.path
                val thumbFile = thumbPath?.let(::File)?.takeIf { it.exists() }
                if (thumbFile != null) {
                    val persistentPath = storageManager.saveArtworkFile(item.track.id, thumbFile.readBytes())
                        ?: thumbPath
                    trackDao.updateTrackMetadata(
                        trackId = item.track.id,
                        artworkUri = persistentPath,
                        bitrateKbps = 0,
                        sampleRateHz = 0,
                        bitDepth = null,
                        durationMs = 0L
                    )
                }
            }
        }
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
