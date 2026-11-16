package com.example.data.storage

import android.content.Context
import com.example.core.util.ActivityLogger
import com.example.data.local.CacheEntryEntity
import com.example.data.local.DownloadCacheDao
import com.example.data.local.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class CacheManager(
    private val context: Context,
    private val dao: DownloadCacheDao,
    private val settingsDataStore: SettingsDataStore,
    private val logger: ActivityLogger
) {
    val streamCacheDir: File
        get() = File(context.cacheDir, "audio_stream_cache").apply {
            if (!exists()) mkdirs()
        }

    val cacheSizeBytes: Flow<Long> = dao.observeTemporaryCacheSizeBytes()

    fun getCacheFileForTrack(trackId: String): File {
        val safeKey = trackId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(streamCacheDir, "$safeKey.cache")
    }

    suspend fun recordCacheAccess(
        key: String,
        trackId: String,
        file: File,
        isPermanentOffline: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        dao.upsertCacheEntry(
            CacheEntryEntity(
                key = key,
                trackId = trackId,
                filePath = file.absolutePath,
                sizeBytes = file.length(),
                isPermanentOffline = isPermanentOffline,
                lastAccessedEpochMs = System.currentTimeMillis()
            )
        )
        enforceCacheLimit()
    }

    suspend fun enforceCacheLimit() = withContext(Dispatchers.IO) {
        val limitBytes = settingsDataStore.preferencesFlow.first().cacheLimitBytes
        val evictable = dao.getEvictableCacheEntriesOldestFirst()
        var currentTotal = evictable.sumOf { it.sizeBytes }
        if (currentTotal <= limitBytes) return@withContext

        var evictedCount = 0
        for (entry in evictable) {
            if (currentTotal <= limitBytes) break
            if (entry.isPermanentOffline) continue
            val file = File(entry.filePath)
            if (file.exists()) {
                file.delete()
            }
            dao.deleteCacheEntry(entry.key)
            currentTotal -= entry.sizeBytes
            evictedCount++
        }
        if (evictedCount > 0) {
            logger.log("CACHE", "INFO", "Đã tự động giải phóng $evictedCount mục cache cũ theo giới hạn bộ đệm")
        }
    }

    suspend fun clearTemporaryCache(): Long = withContext(Dispatchers.IO) {
        val entries = dao.getEvictableCacheEntriesOldestFirst()
        var freed = 0L
        entries.forEach { entry ->
            if (!entry.isPermanentOffline) {
                val f = File(entry.filePath)
                if (f.exists()) {
                    freed += f.length()
                    f.delete()
                }
            }
        }
        streamCacheDir.listFiles()?.forEach { file ->
            freed += file.length()
            file.delete()
        }
        dao.clearTemporaryCacheEntries()
        logger.log("CACHE", "INFO", "Đã dọn bộ đệm tạm thời (${freed / (1024 * 1024)} MB), giữ nguyên bài tải offline")
        freed
    }
}
