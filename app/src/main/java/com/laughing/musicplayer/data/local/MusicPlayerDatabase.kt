package com.laughing.musicplayer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        TelegramSourceEntity::class,
        ForumTopicEntity::class,
        SyncCheckpointEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        DownloadTaskEntity::class,
        CacheEntryEntity::class,
        ActivityLogEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class MusicPlayerDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun telegramDao(): TelegramDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadCacheDao(): DownloadCacheDao
    abstract fun activityLogDao(): ActivityLogDao

    companion object {
        @Volatile
        private var INSTANCE: MusicPlayerDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN bpm INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN telegramRemoteFileId TEXT")
                db.execSQL(
                    """
                    DELETE FROM tracks
                    WHERE telegramChatId IS NOT NULL
                      AND telegramMessageId IS NOT NULL
                      AND EXISTS (
                          SELECT 1 FROM tracks t2
                          WHERE t2.telegramChatId = tracks.telegramChatId
                            AND t2.telegramMessageId = tracks.telegramMessageId
                            AND (
                                (CASE WHEN t2.downloadStatus = 'COMPLETED' THEN 1 ELSE 0 END) >
                                (CASE WHEN tracks.downloadStatus = 'COMPLETED' THEN 1 ELSE 0 END)
                                OR (
                                    (CASE WHEN t2.downloadStatus = 'COMPLETED' THEN 1 ELSE 0 END) =
                                    (CASE WHEN tracks.downloadStatus = 'COMPLETED' THEN 1 ELSE 0 END)
                                    AND (
                                        (CASE WHEN t2.isFavorite = 1 THEN 1 ELSE 0 END) >
                                        (CASE WHEN tracks.isFavorite = 1 THEN 1 ELSE 0 END)
                                        OR (
                                            (CASE WHEN t2.isFavorite = 1 THEN 1 ELSE 0 END) =
                                            (CASE WHEN tracks.isFavorite = 1 THEN 1 ELSE 0 END)
                                            AND (
                                                t2.playCount > tracks.playCount
                                                OR (t2.playCount = tracks.playCount AND t2.rowid > tracks.rowid)
                                            )
                                        )
                                    )
                                )
                            )
                      )
                    """.trimIndent()
                )
                db.execSQL("DROP INDEX IF EXISTS index_tracks_telegramChatId_telegramMessageId")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tracks_telegramChatId_telegramMessageId ON tracks(telegramChatId, telegramMessageId)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN telegramRemoteUniqueId TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN mimeType TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN lastVerifiedAtEpochMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN telegramOrigin TEXT NOT NULL DEFAULT 'PERSONAL_TELEGRAM'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN publicChannelUsername TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_telegramOrigin ON tracks(telegramOrigin)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_publicChannelUsername ON tracks(publicChannelUsername)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `public_channels` (
                        `channelKey` TEXT NOT NULL,
                        `channelId` INTEGER,
                        `username` TEXT NOT NULL,
                        `displayTitle` TEXT NOT NULL,
                        `avatarUrl` TEXT,
                        `discoveredCount` INTEGER NOT NULL DEFAULT 0,
                        `lastSyncedEpochMs` INTEGER NOT NULL DEFAULT 0,
                        `syncStatus` TEXT NOT NULL DEFAULT 'IDLE',
                        `isPaused` INTEGER NOT NULL DEFAULT 0,
                        `lastCursor` TEXT,
                        `lastError` TEXT,
                        PRIMARY KEY(`channelKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM playlist_tracks WHERE trackId LIKE 'tgpub_%'")
                db.execSQL("DELETE FROM download_tasks WHERE id LIKE 'dl_pub_%' OR trackId LIKE 'tgpub_%'")
                db.execSQL("DELETE FROM tracks WHERE telegramOrigin = 'PUBLIC_TELEGRAM' OR id LIKE 'tgpub_%'")
                db.execSQL("DROP TABLE IF EXISTS public_channels")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tracks_v5` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `format` TEXT NOT NULL,
                        `bitrateKbps` INTEGER NOT NULL,
                        `sampleRateHz` INTEGER NOT NULL,
                        `bitDepth` INTEGER,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `fileHash` TEXT,
                        `fileName` TEXT NOT NULL,
                        `artworkUri` TEXT,
                        `streamUri` TEXT,
                        `localPath` TEXT,
                        `telegramChatId` INTEGER,
                        `telegramChatTitle` TEXT,
                        `telegramMessageId` INTEGER,
                        `telegramFileId` INTEGER,
                        `telegramRemoteFileId` TEXT,
                        `telegramRemoteUniqueId` TEXT,
                        `mimeType` TEXT,
                        `lastVerifiedAtEpochMs` INTEGER NOT NULL,
                        `telegramThreadId` INTEGER,
                        `telegramTopicName` TEXT,
                        `youtubeVideoId` TEXT,
                        `genre` TEXT NOT NULL,
                        `genreManuallyEdited` INTEGER NOT NULL,
                        `isVoiceMessage` INTEGER NOT NULL,
                        `downloadStatus` TEXT NOT NULL,
                        `downloadProgress` REAL NOT NULL,
                        `isFavorite` INTEGER NOT NULL,
                        `dateAddedEpochMs` INTEGER NOT NULL,
                        `lastPlayedEpochMs` INTEGER NOT NULL,
                        `playCount` INTEGER NOT NULL,
                        `loudnessGainDb` REAL NOT NULL,
                        `bpm` INTEGER NOT NULL,
                        `normalizedSearchBlob` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `tracks_v5` (
                        id, title, artist, album, source, durationMs, format, bitrateKbps, sampleRateHz,
                        bitDepth, fileSizeBytes, fileHash, fileName, artworkUri, streamUri, localPath,
                        telegramChatId, telegramChatTitle, telegramMessageId, telegramFileId,
                        telegramRemoteFileId, telegramRemoteUniqueId, mimeType, lastVerifiedAtEpochMs,
                        telegramThreadId, telegramTopicName, youtubeVideoId, genre, genreManuallyEdited,
                        isVoiceMessage, downloadStatus, downloadProgress, isFavorite, dateAddedEpochMs,
                        lastPlayedEpochMs, playCount, loudnessGainDb, bpm, normalizedSearchBlob
                    )
                    SELECT
                        id, title, artist, album, source, durationMs, format, bitrateKbps, sampleRateHz,
                        bitDepth, fileSizeBytes, fileHash, fileName, artworkUri, streamUri, localPath,
                        telegramChatId, telegramChatTitle, telegramMessageId, telegramFileId,
                        telegramRemoteFileId, telegramRemoteUniqueId, mimeType, lastVerifiedAtEpochMs,
                        telegramThreadId, telegramTopicName, youtubeVideoId, genre, genreManuallyEdited,
                        isVoiceMessage, downloadStatus, downloadProgress, isFavorite, dateAddedEpochMs,
                        lastPlayedEpochMs, playCount, loudnessGainDb, bpm, normalizedSearchBlob
                    FROM `tracks`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `tracks`")
                db.execSQL("ALTER TABLE `tracks_v5` RENAME TO `tracks`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_source` ON `tracks` (`source`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_telegramChatId_telegramThreadId` ON `tracks` (`telegramChatId`, `telegramThreadId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_telegramChatId_telegramMessageId` ON `tracks` (`telegramChatId`, `telegramMessageId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_fileHash` ON `tracks` (`fileHash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_genre` ON `tracks` (`genre`)")
            }
        }

        fun getInstance(context: Context): MusicPlayerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicPlayerDatabase::class.java,
                    "music_player.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
