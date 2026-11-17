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
    version = 2,
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

        fun getInstance(context: Context): MusicPlayerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicPlayerDatabase::class.java,
                    "music_player.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
