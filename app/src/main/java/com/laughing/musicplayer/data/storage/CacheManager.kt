package com.laughing.musicplayer.data.storage

import android.content.Context
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.data.local.CacheEntryEntity
import com.laughing.musicplayer.data.local.DownloadCacheDao
import com.laughing.musicplayer.data.local.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

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

    fun getYouTubeCacheFile(videoId: String, extension: String = "m4a"): File {
        val safeId = videoId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(streamCacheDir, "yt_${safeId}.$extension")
    }

    suspend fun findValidCachedYouTubeFile(videoId: String): File? = withContext(Dispatchers.IO) {
        val key = "yt_stream_$videoId"
        val entry = dao.getCacheEntry(key)
        if (entry != null) {
            val file = File(entry.filePath)
            if (file.exists() && file.length() > 16_384L) {
                dao.touchCacheEntry(key, System.currentTimeMillis())
                return@withContext file
            } else {
                dao.deleteCacheEntry(key)
            }
        }
        val candidate = listOf("m4a", "opus", "webm", "mp3")
            .map { getYouTubeCacheFile(videoId, it) }
            .firstOrNull { it.exists() && it.length() > 16_384L }
        if (candidate != null) {
            recordCacheAccess(
                key = key,
                trackId = "yt_$videoId",
                file = candidate,
                isPermanentOffline = false
            )
        }
        candidate
    }

    suspend fun touchCacheAccess(key: String) = withContext(Dispatchers.IO) {
        dao.touchCacheEntry(key, System.currentTimeMillis())
    }

    suspend fun cacheYouTubeStream(
        track: Track,
        streamUrl: String,
        httpClient: OkHttpClient
    ): File? = withContext(Dispatchers.IO) {
        val videoId = track.youtubeVideoId ?: return@withContext null
        val key = "yt_stream_$videoId"
        val existing = findValidCachedYouTubeFile(videoId)
        if (existing != null) return@withContext existing

        val limitBytes = settingsDataStore.preferencesFlow.first().cacheLimitBytes
        if (limitBytes < 16L * 1024L * 1024L) return@withContext null

        val targetFile = getYouTubeCacheFile(videoId, track.format.extension)
        val tmpFile = File(streamCacheDir, "${targetFile.name}.part")
        val maxSingleEntryBytes = minOf(limitBytes / 4L, 120L * 1024L * 1024L)

        runCatching {
            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null
                val contentLen = body.contentLength()
                if (contentLen > maxSingleEntryBytes) {
                    return@use null
                }
                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            written += read
                            if (written > maxSingleEntryBytes) {
                                tmpFile.delete()
                                return@use null
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (tmpFile.exists() && tmpFile.length() > 16_384L) {
                    if (targetFile.exists()) targetFile.delete()
                    tmpFile.renameTo(targetFile)
                    recordCacheAccess(
                        key = key,
                        trackId = track.id,
                        file = targetFile,
                        isPermanentOffline = false
                    )
                    logger.log("CACHE", "INFO", "Đã lưu đệm thông minh YouTube: ${track.title}")
                    targetFile
                } else {
                    tmpFile.delete()
                    null
                }
            }
        }.onFailure {
            tmpFile.delete()
        }.getOrNull()
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
        // Reconcile stale DB entries whose files no longer exist on disk
        val allEvictable = dao.getEvictableCacheEntriesOldestFirst()
        val liveEntries = mutableListOf<CacheEntryEntity>()
        for (entry in allEvictable) {
            if (entry.isPermanentOffline) continue
            val f = File(entry.filePath)
            if (!f.exists()) {
                dao.deleteCacheEntry(entry.key)
            } else {
                liveEntries += entry
            }
        }

        // LRU eviction: oldest lastAccessedEpochMs are at the beginning of liveEntries
        var currentTotal = liveEntries.sumOf { it.sizeBytes }
        if (currentTotal <= limitBytes) return@withContext

        var evictedCount = 0
        var freedBytes = 0L
        for (entry in liveEntries) {
            if (currentTotal <= limitBytes) break
            val file = File(entry.filePath)
            val fileLen = if (file.exists()) file.length() else entry.sizeBytes
            if (file.exists()) {
                file.delete()
            }
            dao.deleteCacheEntry(entry.key)
            currentTotal -= entry.sizeBytes
            freedBytes += fileLen
            evictedCount++
        }
        if (evictedCount > 0) {
            logger.log(
                "CACHE",
                "INFO",
                "LRU Smart Cache: Đã dọn $evictedCount tệp cũ nhất (${freedBytes / (1024 * 1024)} MB)"
            )
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
        logger.log("CACHE", "INFO", "Đã dọn bộ đệm tạm thời (${freed / (1024 * 1024)} MB), giữ nguyên bài hát đã lưu")
        freed
    }
}
