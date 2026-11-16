package com.example.data.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.core.model.MusicSource
import com.example.core.util.ActivityLogger
import com.example.data.local.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

enum class StorageSubdir(val folderName: String) {
    TELEGRAM("Telegram"),
    YOUTUBE("YouTube"),
    ARTWORK("Artwork"),
    LYRICS("Lyrics")
}

class UnifiedStorageManager(
    private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val logger: ActivityLogger
) {
    companion object {
        const val ROOT_FOLDER_NAME = "Music Player"
    }

    fun getDisplayRootPath(): String {
        return "/sdcard/$ROOT_FOLDER_NAME/"
    }

    fun getAppManagedDir(subdir: StorageSubdir): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        return File(File(base, ROOT_FOLDER_NAME), subdir.folderName).apply {
            if (!exists()) mkdirs()
        }
    }

    suspend fun saveAudioToUnifiedStorage(
        source: MusicSource,
        fileName: String,
        mimeType: String,
        inputProvider: () -> InputStream
    ): String? = withContext(Dispatchers.IO) {
        val subdir = when (source) {
            MusicSource.TELEGRAM -> StorageSubdir.TELEGRAM
            MusicSource.YOUTUBE_MUSIC -> StorageSubdir.YOUTUBE
            MusicSource.OFFLINE -> StorageSubdir.TELEGRAM
        }
        val safeFileName = sanitizeFileName(fileName)

        // 1. Prefer user-authorized SAF tree (/sdcard/Music Player/) if configured
        val safUriStr = settingsDataStore.preferencesFlow.first().safTreeUri
        if (!safUriStr.isNullOrBlank()) {
            val safSaved = runCatching {
                val treeUri = Uri.parse(safUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                val subDoc = rootDoc?.findFile(subdir.folderName)
                    ?: rootDoc?.createDirectory(subdir.folderName)
                subDoc?.findFile(safeFileName)?.delete()
                val target = subDoc?.createFile(mimeType, safeFileName)
                if (target != null) {
                    context.contentResolver.openOutputStream(target.uri)?.use { out ->
                        inputProvider().use { input -> input.copyTo(out) }
                    }
                    logger.log("STORAGE", "INFO", "Đã lưu qua SAF vào ${subdir.folderName}/$safeFileName")
                    target.uri.toString()
                } else null
            }.getOrNull()
            if (safSaved != null) return@withContext safSaved
        }

        // 2. Android 10+ MediaStore Music/Music Player/<Subdir> (zero broad permissions required)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaUri = runCatching {
                val relativePath = "${Environment.DIRECTORY_MUSIC}/$ROOT_FOLDER_NAME/${subdir.folderName}"
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, safeFileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { out ->
                    inputProvider().use { input -> input.copyTo(out) }
                }
                val doneValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                logger.log("STORAGE", "INFO", "Đã lưu vào MediaStore $relativePath/$safeFileName")
                uri.toString()
            }.getOrNull()
            if (mediaUri != null) return@withContext mediaUri
        }

        // 3. Fallback to persistent external app music directory (never cacheDir)
        runCatching {
            val outFile = File(getAppManagedDir(subdir), safeFileName)
            FileOutputStream(outFile).use { out ->
                inputProvider().use { input -> input.copyTo(out) }
            }
            logger.log("STORAGE", "INFO", "Đã lưu tệp offline: ${outFile.absolutePath}")
            outFile.absolutePath
        }.onFailure { err ->
            logger.log("STORAGE", "ERROR", "Lỗi lưu tệp $safeFileName: ${err.message}")
        }.getOrNull()
    }

    suspend fun saveArtworkFile(trackId: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val dir = getAppManagedDir(StorageSubdir.ARTWORK)
        val file = File(dir, "${sanitizeFileName(trackId)}.jpg")
        runCatching {
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    suspend fun saveLyricsFile(trackId: String, lrcContent: String): String? = withContext(Dispatchers.IO) {
        val dir = getAppManagedDir(StorageSubdir.LYRICS)
        val file = File(dir, "${sanitizeFileName(trackId)}.lrc")
        runCatching {
            file.writeText(lrcContent, Charsets.UTF_8)
            file.absolutePath
        }.getOrNull()
    }

    suspend fun readLyricsFile(trackId: String): String? = withContext(Dispatchers.IO) {
        val file = File(getAppManagedDir(StorageSubdir.LYRICS), "${sanitizeFileName(trackId)}.lrc")
        if (file.exists() && file.canRead()) file.readText(Charsets.UTF_8) else null
    }

    suspend fun deleteLocalFile(pathOrUri: String?): Boolean = withContext(Dispatchers.IO) {
        if (pathOrUri.isNullOrBlank()) return@withContext false
        runCatching {
            if (pathOrUri.startsWith("content://")) {
                val uri = Uri.parse(pathOrUri)
                val doc = DocumentFile.fromSingleUri(context, uri)
                if (doc != null && doc.exists() && doc.delete()) return@runCatching true
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                val file = File(pathOrUri)
                !file.exists() || file.delete()
            }
        }.getOrDefault(false)
    }

    fun getMediaStoreAudioUri(id: Long): Uri {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    }

    fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "audio_${System.currentTimeMillis()}" }
    }
}
