package com.laughing.musicplayer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY dateAddedEpochMs DESC")
    fun observeAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE source = :source ORDER BY dateAddedEpochMs DESC")
    fun observeTracksBySource(source: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY dateAddedEpochMs DESC")
    fun observeFavorites(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE downloadStatus = 'COMPLETED' OR source = 'OFFLINE' ORDER BY dateAddedEpochMs DESC")
    fun observeDownloadedOrOffline(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE lastPlayedEpochMs > 0 ORDER BY lastPlayedEpochMs DESC LIMIT 100")
    fun observeRecentTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE telegramChatId = :chatId AND telegramThreadId = :threadId ORDER BY telegramMessageId DESC")
    fun observeTracksForTopic(chatId: Long, threadId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getTracksByIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksOnce(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE telegramChatId = :chatId AND source = 'TELEGRAM'")
    suspend fun getTelegramTracksForChat(chatId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE telegramChatId = :chatId AND telegramMessageId = :messageId LIMIT 1")
    suspend fun findByTelegramMessage(chatId: Long, messageId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE telegramFileId = :fileId AND source = 'TELEGRAM' LIMIT 1")
    suspend fun findByTelegramFileId(fileId: Int): TrackEntity?

    @Query("""
        UPDATE tracks SET
            telegramFileId = :fileId,
            telegramRemoteFileId = COALESCE(NULLIF(:remoteFileId, ''), telegramRemoteFileId),
            telegramRemoteUniqueId = COALESCE(NULLIF(:remoteUniqueId, ''), telegramRemoteUniqueId),
            mimeType = COALESCE(NULLIF(:mimeType, ''), mimeType),
            streamUri = :streamUri,
            lastVerifiedAtEpochMs = CASE WHEN :verifiedAtMs > 0 THEN :verifiedAtMs ELSE lastVerifiedAtEpochMs END
        WHERE id = :trackId
    """)
    suspend fun updateTelegramFileRef(
        trackId: String,
        fileId: Int,
        remoteFileId: String?,
        remoteUniqueId: String? = null,
        mimeType: String? = null,
        streamUri: String,
        verifiedAtMs: Long = 0L
    )

    @Query("""
        UPDATE tracks SET
            localPath = :localPath,
            downloadStatus = :status,
            downloadProgress = :progress,
            lastVerifiedAtEpochMs = :verifiedAtMs,
            telegramFileId = CASE WHEN :fileId > 0 THEN :fileId ELSE telegramFileId END,
            telegramRemoteFileId = COALESCE(NULLIF(:remoteFileId, ''), telegramRemoteFileId),
            telegramRemoteUniqueId = COALESCE(NULLIF(:remoteUniqueId, ''), telegramRemoteUniqueId),
            streamUri = COALESCE(NULLIF(:streamUri, ''), streamUri)
        WHERE id = :trackId
    """)
    suspend fun updateVerifiedLocalAudio(
        trackId: String,
        localPath: String,
        status: String,
        progress: Float,
        verifiedAtMs: Long,
        fileId: Int = 0,
        remoteFileId: String? = null,
        remoteUniqueId: String? = null,
        streamUri: String? = null
    )

    @Query("""
        SELECT COUNT(DISTINCT telegramMessageId) FROM tracks
        WHERE telegramChatId = :chatId
          AND source = 'TELEGRAM'
          AND telegramMessageId IS NOT NULL
    """)
    suspend fun countDistinctTelegramTracksForChat(chatId: Long): Int

    @Query("""
        SELECT COUNT(DISTINCT telegramMessageId) FROM tracks
        WHERE telegramChatId = :chatId
          AND telegramThreadId = :threadId
          AND source = 'TELEGRAM'
          AND telegramMessageId IS NOT NULL
    """)
    suspend fun countDistinctTelegramTracksForTopic(chatId: Long, threadId: Long): Int

    @Query("DELETE FROM tracks WHERE source = 'TELEGRAM'")
    suspend fun deletePersonalTelegramTracks()

    @Query("""
        SELECT * FROM tracks
        WHERE source = 'TELEGRAM'
          AND telegramChatId = :chatId
          AND (telegramRemoteFileId IS NULL OR telegramRemoteFileId = '' OR telegramRemoteUniqueId IS NULL OR telegramRemoteUniqueId = '')
    """)
    suspend fun getIncompletePersonalTracksForChat(chatId: Long): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    @Query("UPDATE tracks SET isFavorite = :favorite WHERE id = :trackId")
    suspend fun setFavorite(trackId: String, favorite: Boolean)

    @Query("UPDATE tracks SET genre = :genre, genreManuallyEdited = 1 WHERE id = :trackId")
    suspend fun updateGenre(trackId: String, genre: String)

    @Query("""
        UPDATE tracks SET
            title = :title,
            artist = :artist,
            album = :album,
            albumArtist = :albumArtist,
            genre = :genre,
            genreManuallyEdited = 1,
            releaseYear = :releaseYear,
            normalizedSearchBlob = :searchBlob
        WHERE id = :trackId
    """)
    suspend fun updateEditableMetadata(
        trackId: String,
        title: String,
        artist: String,
        album: String,
        albumArtist: String?,
        genre: String,
        releaseYear: Int?,
        searchBlob: String
    )

    @Query("UPDATE tracks SET lastPlayedEpochMs = :timestamp, playCount = playCount + 1 WHERE id = :trackId")
    suspend fun markPlayed(trackId: String, timestamp: Long)

    @Query("UPDATE tracks SET downloadStatus = :status, downloadProgress = :progress, localPath = :localPath WHERE id = :trackId")
    suspend fun updateDownloadState(trackId: String, status: String, progress: Float, localPath: String?)

    @Query("UPDATE tracks SET loudnessGainDb = :loudnessGainDb, bpm = CASE WHEN :bpm > 0 THEN :bpm ELSE bpm END WHERE id = :trackId")
    suspend fun updateTrackAnalysis(trackId: String, loudnessGainDb: Float, bpm: Int)

    @Query("""
        UPDATE tracks SET
            artworkUri = COALESCE(:artworkUri, artworkUri),
            bitrateKbps = CASE WHEN :bitrateKbps > 0 THEN :bitrateKbps ELSE bitrateKbps END,
            sampleRateHz = CASE WHEN :sampleRateHz > 0 THEN :sampleRateHz ELSE sampleRateHz END,
            bitDepth = COALESCE(:bitDepth, bitDepth),
            durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END
        WHERE id = :trackId
    """)
    suspend fun updateTrackMetadata(
        trackId: String,
        artworkUri: String?,
        bitrateKbps: Int,
        sampleRateHz: Int,
        bitDepth: Int?,
        durationMs: Long
    )

    @Query("""
        UPDATE tracks SET
            format = :format,
            fileName = CASE WHEN :fileName != '' THEN :fileName ELSE fileName END,
            artworkUri = COALESCE(:artworkUri, artworkUri),
            bitrateKbps = CASE WHEN :bitrateKbps > 0 THEN :bitrateKbps ELSE bitrateKbps END,
            sampleRateHz = CASE WHEN :sampleRateHz > 0 THEN :sampleRateHz ELSE sampleRateHz END,
            bitDepth = COALESCE(:bitDepth, bitDepth),
            durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END
        WHERE id = :trackId
    """)
    suspend fun updateTrackAudioSpecs(
        trackId: String,
        format: String,
        fileName: String,
        artworkUri: String?,
        bitrateKbps: Int,
        sampleRateHz: Int,
        bitDepth: Int?,
        durationMs: Long
    )

    @Query("SELECT * FROM tracks WHERE lastPlayedEpochMs = 0 OR lastPlayedEpochMs < :cutoffEpochMs ORDER BY dateAddedEpochMs DESC")
    fun observeNotPlayedIn30Days(cutoffEpochMs: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE lastPlayedEpochMs >= :monthStartEpochMs AND playCount > 0 ORDER BY playCount DESC, lastPlayedEpochMs DESC LIMIT 50")
    fun observeMostPlayedThisMonth(monthStartEpochMs: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE bpm > 130 ORDER BY bpm DESC, playCount DESC")
    fun observeHighEnergyOver130Bpm(): Flow<List<TrackEntity>>

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrackById(trackId: String)
}

@Dao
interface TelegramDao {
    @Query("SELECT * FROM telegram_sources ORDER BY isSelected DESC, audioCount DESC, title ASC")
    fun observeSources(): Flow<List<TelegramSourceEntity>>

    @Query("SELECT * FROM telegram_sources WHERE isSelected = 1")
    suspend fun getSelectedSources(): List<TelegramSourceEntity>

    @Query("SELECT * FROM telegram_sources WHERE chatId = :chatId LIMIT 1")
    suspend fun getSource(chatId: Long): TelegramSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSources(sources: List<TelegramSourceEntity>)

    @Query("UPDATE telegram_sources SET isSelected = :selected WHERE chatId = :chatId")
    suspend fun setSelected(chatId: Long, selected: Boolean)

    @Query("UPDATE telegram_sources SET isSelected = :selected")
    suspend fun setAllSelected(selected: Boolean)

    @Query("UPDATE telegram_sources SET audioCount = :audioCount, topicCount = :topicCount, lastSyncedMessageId = :lastMsgId, lastSyncedEpochMs = :ts WHERE chatId = :chatId")
    suspend fun updateSyncStats(chatId: Long, audioCount: Int, topicCount: Int, lastMsgId: Long, ts: Long)

    @Query("SELECT * FROM forum_topics ORDER BY trackCount DESC, name ASC")
    fun observeAllTopics(): Flow<List<ForumTopicEntity>>

    @Query("SELECT * FROM forum_topics WHERE chatId = :chatId ORDER BY trackCount DESC, name ASC")
    fun observeTopicsForChat(chatId: Long): Flow<List<ForumTopicEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTopics(topics: List<ForumTopicEntity>)

    @Query("SELECT * FROM sync_checkpoints WHERE chatId = :chatId LIMIT 1")
    suspend fun getCheckpoint(chatId: Long): SyncCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: SyncCheckpointEntity)

    @Query("DELETE FROM telegram_sources")
    suspend fun clearAllSources()

    @Query("DELETE FROM forum_topics")
    suspend fun clearAllTopics()

    @Query("DELETE FROM sync_checkpoints")
    suspend fun clearAllCheckpoints()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAtEpochMs DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun countTracksInPlaylist(playlistId: String): Int

    @Query("""
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON t.id = pt.trackId
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position ASC
    """)
    fun observeTracksForPlaylist(playlistId: String): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrackRefs(refs: List<PlaylistTrackCrossRef>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)
}

@Dao
interface DownloadCacheDao {
    @Query("SELECT * FROM download_tasks ORDER BY updatedAtEpochMs DESC")
    fun observeDownloads(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getDownloadById(id: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteDownload(id: String)

    @Query("SELECT * FROM cache_entries ORDER BY lastAccessedEpochMs ASC")
    fun observeCacheEntries(): Flow<List<CacheEntryEntity>>

    @Query("SELECT * FROM cache_entries WHERE isPermanentOffline = 0 ORDER BY lastAccessedEpochMs ASC")
    suspend fun getEvictableCacheEntriesOldestFirst(): List<CacheEntryEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cache_entries WHERE isPermanentOffline = 0")
    fun observeTemporaryCacheSizeBytes(): Flow<Long>

    @Query("SELECT * FROM cache_entries WHERE `key` = :key LIMIT 1")
    suspend fun getCacheEntry(key: String): CacheEntryEntity?

    @Query("UPDATE cache_entries SET lastAccessedEpochMs = :timestamp WHERE `key` = :key")
    suspend fun touchCacheEntry(key: String, timestamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCacheEntry(entry: CacheEntryEntity)

    @Query("DELETE FROM cache_entries WHERE `key` = :key")
    suspend fun deleteCacheEntry(key: String)

    @Query("DELETE FROM cache_entries WHERE isPermanentOffline = 0")
    suspend fun clearTemporaryCacheEntries()
}

@Dao
interface ActivityLogDao {
    @Query("SELECT * FROM activity_logs ORDER BY timestampMs DESC, id DESC LIMIT 1000")
    fun observeRecentLogs(): Flow<List<ActivityLogEntity>>

    @Query("SELECT * FROM activity_logs ORDER BY timestampMs DESC, id DESC LIMIT 1000")
    suspend fun getRecentLogsOnce(): List<ActivityLogEntity>

    @Insert
    suspend fun insertLog(entry: ActivityLogEntity)

    @Query("DELETE FROM activity_logs WHERE id NOT IN (SELECT id FROM activity_logs ORDER BY timestampMs DESC, id DESC LIMIT 1000)")
    suspend fun trimToRingBufferLimit()

    @Query("DELETE FROM activity_logs")
    suspend fun clearLogs()
}
