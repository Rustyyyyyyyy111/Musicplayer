package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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

        fun getInstance(context: Context): MusicPlayerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicPlayerDatabase::class.java,
                    "music_player.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
