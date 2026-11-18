package com.laughing.musicplayer.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.laughing.musicplayer.core.model.ActivityLogItem
import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.DownloadItem
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.PlaylistItem
import com.laughing.musicplayer.core.model.TelegramChatItem
import com.laughing.musicplayer.core.model.TelegramChatType
import com.laughing.musicplayer.core.model.TelegramTopicItem
import com.laughing.musicplayer.core.model.Track

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["source"]),
        Index(value = ["telegramChatId", "telegramThreadId"]),
        Index(value = ["telegramChatId", "telegramMessageId"], unique = true),
        Index(value = ["fileHash"]),
        Index(value = ["genre"])
    ]
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: String,
    val durationMs: Long,
    val format: String,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val fileSizeBytes: Long,
    val fileHash: String?,
    val fileName: String,
    val artworkUri: String?,
    val streamUri: String?,
    val localPath: String?,
    val telegramChatId: Long?,
    val telegramChatTitle: String?,
    val telegramMessageId: Long?,
    val telegramFileId: Int?,
    val telegramRemoteFileId: String? = null,
    val telegramRemoteUniqueId: String? = null,
    val mimeType: String? = null,
    val lastVerifiedAtEpochMs: Long = 0L,
    val telegramThreadId: Long?,
    val telegramTopicName: String?,
    val youtubeVideoId: String?,
    val genre: String,
    val genreManuallyEdited: Boolean,
    val isVoiceMessage: Boolean,
    val downloadStatus: String,
    val downloadProgress: Float,
    val isFavorite: Boolean,
    val dateAddedEpochMs: Long,
    val lastPlayedEpochMs: Long,
    val playCount: Int,
    val loudnessGainDb: Float,
    val bpm: Int = 0,
    val normalizedSearchBlob: String
) {
    fun toDomain(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        source = runCatching { MusicSource.valueOf(source) }.getOrDefault(MusicSource.OFFLINE),
        durationMs = durationMs,
        format = runCatching { AudioFormat.valueOf(format) }.getOrDefault(AudioFormat.OTHER),
        bitrateKbps = bitrateKbps,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        fileSizeBytes = fileSizeBytes,
        fileHash = fileHash,
        fileName = fileName,
        artworkUri = artworkUri,
        streamUri = streamUri,
        localPath = localPath,
        telegramChatId = telegramChatId,
        telegramChatTitle = telegramChatTitle,
        telegramMessageId = telegramMessageId,
        telegramFileId = telegramFileId,
        telegramRemoteFileId = telegramRemoteFileId,
        telegramRemoteUniqueId = telegramRemoteUniqueId,
        mimeType = mimeType,
        lastVerifiedAtEpochMs = lastVerifiedAtEpochMs,
        telegramThreadId = telegramThreadId,
        telegramTopicName = telegramTopicName,
        youtubeVideoId = youtubeVideoId,
        genre = runCatching { GenreType.valueOf(genre) }.getOrDefault(GenreType.OTHER),
        genreManuallyEdited = genreManuallyEdited,
        isVoiceMessage = isVoiceMessage,
        downloadStatus = runCatching { DownloadStatus.valueOf(downloadStatus) }.getOrDefault(DownloadStatus.NONE),
        downloadProgress = downloadProgress,
        isFavorite = isFavorite,
        dateAddedEpochMs = dateAddedEpochMs,
        lastPlayedEpochMs = lastPlayedEpochMs,
        playCount = playCount,
        loudnessGainDb = loudnessGainDb,
        bpm = bpm
    )
}

fun Track.toEntity(searchBlob: String): TrackEntity = TrackEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    source = source.name,
    durationMs = durationMs,
    format = format.name,
    bitrateKbps = bitrateKbps,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    fileSizeBytes = fileSizeBytes,
    fileHash = fileHash,
    fileName = fileName,
    artworkUri = artworkUri,
    streamUri = streamUri,
    localPath = localPath,
    telegramChatId = telegramChatId,
    telegramChatTitle = telegramChatTitle,
    telegramMessageId = telegramMessageId,
    telegramFileId = telegramFileId,
    telegramRemoteFileId = telegramRemoteFileId,
    telegramRemoteUniqueId = telegramRemoteUniqueId,
    mimeType = mimeType,
    lastVerifiedAtEpochMs = lastVerifiedAtEpochMs,
    telegramThreadId = telegramThreadId,
    telegramTopicName = telegramTopicName,
    youtubeVideoId = youtubeVideoId,
    genre = genre.name,
    genreManuallyEdited = genreManuallyEdited,
    isVoiceMessage = isVoiceMessage,
    downloadStatus = downloadStatus.name,
    downloadProgress = downloadProgress,
    isFavorite = isFavorite,
    dateAddedEpochMs = dateAddedEpochMs,
    lastPlayedEpochMs = lastPlayedEpochMs,
    playCount = playCount,
    loudnessGainDb = loudnessGainDb,
    bpm = bpm,
    normalizedSearchBlob = searchBlob
)

@Entity(tableName = "telegram_sources")
data class TelegramSourceEntity(
    @PrimaryKey val chatId: Long,
    val title: String,
    val chatType: String,
    val isForum: Boolean,
    val isSelected: Boolean,
    val audioCount: Int,
    val topicCount: Int,
    val lastSyncedMessageId: Long,
    val lastSyncedEpochMs: Long
) {
    fun toDomain(): TelegramChatItem = TelegramChatItem(
        chatId = chatId,
        title = title,
        chatType = runCatching { TelegramChatType.valueOf(chatType) }.getOrDefault(TelegramChatType.PRIVATE_CHAT),
        isForum = isForum,
        isSelected = isSelected,
        audioCount = audioCount,
        topicCount = topicCount,
        lastSyncedMessageId = lastSyncedMessageId,
        lastSyncedEpochMs = lastSyncedEpochMs
    )
}

@Entity(
    tableName = "forum_topics",
    primaryKeys = ["chatId", "messageThreadId"]
)
data class ForumTopicEntity(
    val chatId: Long,
    val messageThreadId: Long,
    val topicId: Long,
    val name: String,
    val iconUrl: String?,
    val trackCount: Int
) {
    fun toDomain(): TelegramTopicItem = TelegramTopicItem(
        chatId = chatId,
        messageThreadId = messageThreadId,
        topicId = topicId,
        name = name,
        iconUrl = iconUrl,
        trackCount = trackCount
    )
}

@Entity(tableName = "sync_checkpoints")
data class SyncCheckpointEntity(
    @PrimaryKey val chatId: Long,
    val oldestMessageId: Long,
    val newestMessageId: Long,
    val completedInitialScan: Boolean,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val isSmartMix: Boolean,
    val artworkUri: String?,
    val createdAtEpochMs: Long
) {
    fun toDomain(trackCount: Int): PlaylistItem = PlaylistItem(
        id = id,
        name = name,
        description = description,
        trackCount = trackCount,
        isSmartMix = isSmartMix,
        artworkUri = artworkUri
    )
}

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"]
)
data class PlaylistTrackCrossRef(
    val playlistId: String,
    val trackId: String,
    val position: Int
)

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val source: String,
    val status: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val destinationPath: String,
    val errorMessage: String?,
    val updatedAtEpochMs: Long
) {
    fun toDomain(): DownloadItem = DownloadItem(
        id = id,
        trackId = trackId,
        title = title,
        artist = artist,
        source = runCatching { MusicSource.valueOf(source) }.getOrDefault(MusicSource.TELEGRAM),
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        destinationPath = destinationPath,
        errorMessage = errorMessage,
        updatedAtEpochMs = updatedAtEpochMs
    )
}

@Entity(tableName = "cache_entries")
data class CacheEntryEntity(
    @PrimaryKey val key: String,
    val trackId: String,
    val filePath: String,
    val sizeBytes: Long,
    val isPermanentOffline: Boolean,
    val lastAccessedEpochMs: Long
)

@Entity(tableName = "activity_logs")
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMs: Long,
    val category: String,
    val level: String,
    val message: String
) {
    fun toDomain(): ActivityLogItem = ActivityLogItem(
        id = id,
        timestampMs = timestampMs,
        category = category,
        level = level,
        message = message
    )
}
