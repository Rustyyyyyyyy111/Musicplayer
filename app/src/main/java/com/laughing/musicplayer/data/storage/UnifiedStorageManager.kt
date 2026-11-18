package com.laughing.musicplayer.data.storage

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.data.local.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.coroutines.resume

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
        const val ROOT_FOLDER_NAME = "Musicplayer"
        const val PUBLIC_DISPLAY_PATH = "/sdcard/Music/Musicplayer/"
        const val YOUTUBE_PUBLIC_DISPLAY_PATH = "/sdcard/Music Player/YouTube/"
        const val YOUTUBE_SCOPED_RELATIVE_SUBPATH = "Music Player/YouTube"
    }

    fun getDisplayRootPath(): String = PUBLIC_DISPLAY_PATH

    fun getYouTubeDisplayPath(): String {
        return if (hasAllFilesAccessPermission()) {
            YOUTUBE_PUBLIC_DISPLAY_PATH
        } else {
            "/sdcard/${Environment.DIRECTORY_MUSIC}/$YOUTUBE_SCOPED_RELATIVE_SUBPATH/"
        }
    }

    fun getDirectPublicMusicDir(): File {
        val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return File(publicMusic, ROOT_FOLDER_NAME)
    }

    fun getDirectYouTubeMusicDir(): File {
        return File(Environment.getExternalStorageDirectory(), YOUTUBE_SCOPED_RELATIVE_SUBPATH)
    }

    fun getMediaStoreYouTubeDir(): File {
        val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return File(publicMusic, YOUTUBE_SCOPED_RELATIVE_SUBPATH)
    }

    fun hasAudioReadPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasAllFilesAccessPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun buildManageAllFilesAccessIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }.getOrElse {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
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
        val safeFileName = sanitizeFileName(fileName)

        // 1. Direct write to /sdcard/Music/Musicplayer/ when MANAGE_EXTERNAL_STORAGE (R+) or WRITE_EXTERNAL_STORAGE (<Q) is granted
        if (hasAllFilesAccessPermission()) {
            val directSaved = runCatching {
                val targetDir = getDirectPublicMusicDir().apply { if (!exists()) mkdirs() }
                if (!targetDir.exists() || !targetDir.canWrite()) return@runCatching null
                val outFile = File(targetDir, safeFileName)
                FileOutputStream(outFile).use { out ->
                    inputProvider().use { input -> input.copyTo(out, 64 * 1024) }
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outFile.absolutePath),
                    arrayOf(mimeType),
                    null
                )
                logger.log("STORAGE", "INFO", "Đã lưu trực tiếp vào $PUBLIC_DISPLAY_PATH$safeFileName")
                outFile.absolutePath
            }.onFailure { err ->
                logger.log("STORAGE", "WARN", "Ghi trực tiếp thất bại, chuyển sang MediaStore/SAF: ${err.message}")
            }.getOrNull()
            if (directSaved != null) return@withContext directSaved
        }

        // 2. Android 10+ MediaStore Music/Musicplayer/<file> (places directly in /sdcard/Music/Musicplayer/ without MANAGE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaUri = runCatching {
                val relativePath = "${Environment.DIRECTORY_MUSIC}/$ROOT_FOLDER_NAME"
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
                    inputProvider().use { input -> input.copyTo(out, 64 * 1024) }
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

        // 3. User-authorized SAF tree if configured
        val safUriStr = settingsDataStore.preferencesFlow.first().safTreeUri
        if (!safUriStr.isNullOrBlank()) {
            val safSaved = runCatching {
                val treeUri = Uri.parse(safUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                rootDoc?.findFile(safeFileName)?.delete()
                val target = rootDoc?.createFile(mimeType, safeFileName)
                if (target != null) {
                    context.contentResolver.openOutputStream(target.uri)?.use { out ->
                        inputProvider().use { input -> input.copyTo(out, 64 * 1024) }
                    }
                    logger.log("STORAGE", "INFO", "Đã lưu qua SAF: $safeFileName")
                    target.uri.toString()
                } else null
            }.getOrNull()
            if (safSaved != null) return@withContext safSaved
        }

        // 4. Fallback to persistent external app music directory (never cacheDir)
        val subdir = when (source) {
            MusicSource.TELEGRAM -> StorageSubdir.TELEGRAM
            MusicSource.YOUTUBE_MUSIC -> StorageSubdir.YOUTUBE
            MusicSource.OFFLINE -> StorageSubdir.TELEGRAM
        }
        runCatching {
            val outFile = File(getAppManagedDir(subdir), safeFileName)
            FileOutputStream(outFile).use { out ->
                inputProvider().use { input -> input.copyTo(out, 64 * 1024) }
            }
            logger.log("STORAGE", "INFO", "Đã lưu tệp: ${outFile.absolutePath}")
            outFile.absolutePath
        }.onFailure { err ->
            logger.log("STORAGE", "ERROR", "Lỗi lưu tệp $safeFileName: ${err.message}")
        }.getOrNull()
    }

    suspend fun saveYouTubeAudioToStorage(
        fileName: String,
        mimeType: String,
        title: String,
        artist: String,
        album: String = "YouTube",
        durationMs: Long = 0L,
        inputProvider: () -> InputStream
    ): String? = withContext(Dispatchers.IO) {
        val safeFileName = sanitizeFileName(fileName)

        // 1. User-configured SAF directory (writes inside "YouTube/" subfolder)
        val safUriStr = settingsDataStore.preferencesFlow.first().safTreeUri
        if (!safUriStr.isNullOrBlank()) {
            val safSaved = runCatching {
                val treeUri = Uri.parse(safUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching null
                val ytFolder = rootDoc.findFile(StorageSubdir.YOUTUBE.folderName)?.takeIf { it.isDirectory }
                    ?: rootDoc.createDirectory(StorageSubdir.YOUTUBE.folderName)
                    ?: rootDoc
                ytFolder.findFile(safeFileName)?.delete()
                val target = ytFolder.createFile(mimeType, safeFileName) ?: return@runCatching null
                context.contentResolver.openOutputStream(target.uri)?.use { out ->
                    inputProvider().use { input -> input.copyTo(out, 64 * 1024) }
                } ?: return@runCatching null
                logger.log("STORAGE", "INFO", "Đã lưu nhạc YouTube qua SAF: $safeFileName")
                target.uri.toString()
            }.onFailure { err ->
                logger.log("STORAGE", "WARN", "Lưu SAF thất bại, chuyển sang lưu trữ mặc định: ${err.message}")
            }.getOrNull()
            if (safSaved != null) return@withContext safSaved
        }

        // 2. Direct /sdcard/Music Player/YouTube/ when All Files Access (R+) or WRITE_EXTERNAL_STORAGE (<Q) is granted
        if (hasAllFilesAccessPermission()) {
            val directSaved = runCatching {
                val targetDir = getDirectYouTubeMusicDir().apply { if (!exists()) mkdirs() }
                if (!targetDir.exists() || !targetDir.canWrite()) return@runCatching null
                val outFile = File(targetDir, safeFileName)
                FileOutputStream(outFile).use { out ->
                    inputProvider().use { input -> input.copyTo(out, 64 * 1024) }
                    out.fd.sync()
                }
                scanMediaFileAndWait(outFile.absolutePath, mimeType)
                logger.log("STORAGE", "INFO", "Đã lưu trực tiếp vào $YOUTUBE_PUBLIC_DISPLAY_PATH$safeFileName")
                outFile.absolutePath
            }.onFailure { err ->
                logger.log("STORAGE", "WARN", "Ghi trực tiếp vào $YOUTUBE_PUBLIC_DISPLAY_PATH thất bại: ${err.message}")
            }.getOrNull()
            if (directSaved != null) return@withContext directSaved
        }

        // 3. Android 10+ Scoped Storage fallback: MediaStore Music/Music Player/YouTube/
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = "${Environment.DIRECTORY_MUSIC}/$YOUTUBE_SCOPED_RELATIVE_SUBPATH"
            val mediaUri = saveToMediaStoreAudioWithFallbackMime(
                safeFileName = safeFileName,
                primaryMimeType = mimeType,
                relativePath = relativePath,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                inputProvider = inputProvider
            )
            if (mediaUri != null) {
                logger.log("STORAGE", "INFO", "Đã lưu vào MediaStore $relativePath/$safeFileName")
                return@withContext mediaUri
            }
        }

        // 4. Final fallback to persistent app-managed YouTube folder + MediaStore scan
        runCatching {
            val outFile = File(getAppManagedDir(StorageSubdir.YOUTUBE), safeFileName)
            FileOutputStream(outFile).use { out ->
                inputProvider().use { input -> input.copyTo(out, 64 * 1024) }
                out.fd.sync()
            }
            scanMediaFileAndWait(outFile.absolutePath, mimeType)
            logger.log("STORAGE", "INFO", "Đã lưu tệp YouTube: ${outFile.absolutePath}")
            outFile.absolutePath
        }.onFailure { err ->
            logger.log("STORAGE", "ERROR", "Lỗi lưu tệp YouTube $safeFileName: ${err.message}")
        }.getOrNull()
    }

    private fun saveToMediaStoreAudioWithFallbackMime(
        safeFileName: String,
        primaryMimeType: String,
        relativePath: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        inputProvider: () -> InputStream
    ): String? {
        val resolver = context.contentResolver
        val ext = safeFileName.substringAfterLast('.', "").lowercase()
        val candidateMimes = buildList {
            if (ext == "opus") {
                add("audio/ogg")
                add("audio/opus")
            } else if (ext == "m4a") {
                add("audio/mp4")
            }
            add(primaryMimeType)
            add(null)
        }.distinct()

        for (candidateMime in candidateMimes) {
            var insertedUri: Uri? = null
            val attempt = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, safeFileName)
                    if (!candidateMime.isNullOrBlank()) {
                        put(MediaStore.Audio.Media.MIME_TYPE, candidateMime)
                    }
                    put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Audio.Media.TITLE, title.ifBlank { safeFileName.substringBeforeLast('.') })
                    put(MediaStore.Audio.Media.ARTIST, artist.ifBlank { "YouTube" })
                    put(MediaStore.Audio.Media.ALBUM, album.ifBlank { "YouTube" })
                    if (durationMs > 0L) {
                        put(MediaStore.Audio.Media.DURATION, durationMs)
                    }
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                insertedUri = uri
                resolver.openOutputStream(uri)?.use { out ->
                    inputProvider().use { input -> input.copyTo(out, 64 * 1024) }
                } ?: return@runCatching null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, doneValues, null, null)
                }
                uri.toString()
            }
            val resultUri = attempt.getOrNull()
            if (resultUri != null) return resultUri
            insertedUri?.let { runCatching { resolver.delete(it, null, null) } }
        }
        return null
    }

    suspend fun scanMediaFileAndWait(path: String, mimeType: String) {
        withTimeoutOrNull(2_500L) {
            suspendCancellableCoroutine<Unit> { cont ->
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(path),
                    arrayOf(mimeType)
                ) { _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
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

    fun getPersistentTelegramAudioCacheDir(): File {
        return File(File(context.filesDir, "music_cache"), "telegram").apply {
            if (!exists()) mkdirs()
        }
    }

    fun isValidAudioFile(file: File?, expectedSizeBytes: Long = 0L): Boolean {
        if (file == null || !file.exists() || !file.isFile || !file.canRead()) return false
        val len = file.length()
        if (len <= 1024L) return false
        if (expectedSizeBytes > 4096L && len < (expectedSizeBytes * 85L) / 100L) {
            return false
        }
        return true
    }

    suspend fun copyFileToPersistentAudioCache(
        sourceFile: File,
        targetDir: File,
        targetFileName: String,
        expectedSizeBytes: Long = 0L
    ): File? = withContext(Dispatchers.IO) {
        if (!isValidAudioFile(sourceFile, expectedSizeBytes)) return@withContext null
        targetDir.mkdirs()
        val safeName = sanitizeFileName(targetFileName)
        val destFile = File(targetDir, safeName)
        if (destFile.absolutePath == sourceFile.absolutePath && isValidAudioFile(destFile, expectedSizeBytes)) {
            return@withContext destFile
        }
        val partFile = File(targetDir, "$safeName.part")
        runCatching {
            sourceFile.inputStream().use { input ->
                FileOutputStream(partFile).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            if (!isValidAudioFile(partFile, expectedSizeBytes)) {
                partFile.delete()
                return@runCatching null
            }
            if (destFile.exists()) destFile.delete()
            if (!partFile.renameTo(destFile)) {
                partFile.copyTo(destFile, overwrite = true)
                partFile.delete()
            }
            if (isValidAudioFile(destFile, expectedSizeBytes)) destFile else null
        }.onFailure {
            partFile.delete()
        }.getOrNull()
    }

    fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "audio_${System.currentTimeMillis()}" }
    }
}
