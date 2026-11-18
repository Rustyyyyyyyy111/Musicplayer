package com.laughing.musicplayer.data.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.data.storage.CacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

@UnstableApi
class TelegramAudioDataSource(
    private val core: TelegramCore,
    private val fileResolver: TelegramFileResolver,
    private val cacheManager: CacheManager,
    private val logger: ActivityLogger
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(
        private val core: TelegramCore,
        private val fileResolver: TelegramFileResolver,
        private val cacheManager: CacheManager,
        private val logger: ActivityLogger
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            TelegramAudioDataSource(core, fileResolver, cacheManager, logger)
    }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentUri: Uri? = null
    private var fileId: Int = 0
    private var resolvedTrackId: String? = null
    private var totalLength: Long = C.LENGTH_UNSET.toLong()
    private var readPosition: Long = 0L
    private var randomAccessFile: RandomAccessFile? = null
    private var localFile: File? = null
    private var opened = false
    private var completedTdFile: TdApi.File? = null

    @Volatile
    private var closed = false

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        val target = TelegramFileResolver.parseTargetFromUri(dataSpec.uri)
            ?: throw IOException("URI Telegram không hợp lệ: ${dataSpec.uri}")
        readPosition = dataSpec.position
        val expectedSize = target.expectedSize

        val resolved = runBlocking {
            fileResolver.resolveAndStartDownload(
                target = target,
                priority = 32,
                offset = readPosition,
                limit = 0L
            )
        }.getOrElse { err ->
            throw IOException(err.message ?: "Không tìm thấy tệp Telegram #${target.fileId}", err)
        }

        fileId = resolved.fileId
        resolvedTrackId = resolved.trackId
        val initialFile = resolved.tdFile
        if (initialFile.local?.isDownloadingCompleted == true) {
            completedTdFile = initialFile
        }

        val resolvedSize = when {
            initialFile.size > 0L -> initialFile.size
            initialFile.expectedSize > 0L -> initialFile.expectedSize
            expectedSize > 0L -> expectedSize
            else -> C.LENGTH_UNSET.toLong()
        }
        totalLength = resolvedSize

        val initialHeaderBytes = minOf(16L * 1024L, if (resolvedSize > readPosition) resolvedSize - readPosition else 16L * 1024L)
        val path = waitForLocalFileReady(fileId, readPosition + initialHeaderBytes, initialFile)
        localFile = File(path)
        randomAccessFile = RandomAccessFile(localFile, "r").apply {
            seek(readPosition)
        }

        val trackKey = resolvedTrackId ?: "tg_$fileId"
        backgroundScope.launch {
            if (initialFile.local?.isDownloadingCompleted == true) {
                localFile?.let { file ->
                    cacheManager.recordCacheAccess(
                        key = "tg_stream_$trackKey",
                        trackId = trackKey,
                        file = file,
                        isPermanentOffline = false
                    )
                }
                resolvedTrackId?.let { tid ->
                    fileResolver.promoteCompletedTdFileById(tid, initialFile)
                }
            } else {
                cacheManager.touchCacheAccess("tg_stream_$trackKey")
            }
        }

        opened = true
        transferStarted(dataSpec)
        logger.log("TG_PLAY", "INFO", "Phát progressive Telegram fileId=$fileId tại offset=$readPosition")

        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (totalLength > 0L) {
            (totalLength - readPosition).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) return C.RESULT_END_OF_INPUT
        if (length == 0) return 0
        if (totalLength > 0L && readPosition >= totalLength) return C.RESULT_END_OF_INPUT

        val chunkTarget = minOf(length.toLong(), 64L * 1024L).coerceAtLeast(1L)
        ensureBytesAvailable(readPosition + 1L)
        val raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT
        raf.seek(readPosition)
        var bytesRead = raf.read(buffer, offset, length)
        if (bytesRead <= 0) {
            if (totalLength > 0L && readPosition >= totalLength) return C.RESULT_END_OF_INPUT
            ensureBytesAvailable(readPosition + chunkTarget)
            raf.seek(readPosition)
            bytesRead = raf.read(buffer, offset, length)
            if (bytesRead <= 0) return C.RESULT_END_OF_INPUT
        }
        readPosition += bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        closed = true
        currentUri = null
        runCatching { randomAccessFile?.close() }
        randomAccessFile = null
        if (opened) {
            opened = false
            transferEnded()
            val activeFileId = fileId
            val activeTrackKey = resolvedTrackId ?: "tg_$activeFileId"
            val trackIdCopy = resolvedTrackId
            val f = localFile
            val completedSnapshot = completedTdFile
            // Cancel non-destructively (keep partial/completed cached prefix) and never block loader thread
            if (activeFileId != 0 && completedSnapshot == null) {
                core.cancelDownloadAsync(activeFileId, onlyIfPending = false)
            }
            backgroundScope.launch {
                val latestFile = if (completedSnapshot == null && activeFileId != 0) {
                    core.sendQuery(TdApi.GetFile(activeFileId), timeoutMs = 4_000L).getOrNull()
                } else {
                    completedSnapshot
                }
                if (f != null && f.exists()) {
                    cacheManager.recordCacheAccess(
                        key = "tg_stream_$activeTrackKey",
                        trackId = activeTrackKey,
                        file = f,
                        isPermanentOffline = false
                    )
                }
                if (trackIdCopy != null && latestFile?.local?.isDownloadingCompleted == true) {
                    fileResolver.promoteCompletedTdFileById(trackIdCopy, latestFile)
                }
            }
        }
    }

    private fun waitForLocalFileReady(
        targetFileId: Int,
        targetOffset: Long,
        initial: TdApi.File
    ): String {
        if (initial.local.isDownloadingCompleted && initial.local.path.isNotBlank()) {
            completedTdFile = initial
            return initial.local.path
        }
        var current = initial
        var lastProgressAtMs = System.currentTimeMillis()
        var lastPrefixSize = current.local.downloadedPrefixSize
        var retriedStall = false

        while (!closed && !Thread.currentThread().isInterrupted &&
            System.currentTimeMillis() - lastProgressAtMs < STALL_TIMEOUT_MS
        ) {
            val filePath = current.local.path
            val downloadedEnd = current.local.downloadOffset + current.local.downloadedPrefixSize
            if (filePath.isNotBlank() && File(filePath).exists()) {
                if (current.local.isDownloadingCompleted) {
                    completedTdFile = current
                    return filePath
                }
                if (downloadedEnd >= targetOffset || current.local.downloadedPrefixSize >= 8192) {
                    return filePath
                }
            }

            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Đã hủy tải luồng Telegram")
            }
            if (closed) throw IOException("Đã hủy tải luồng Telegram")

            current = runBlocking {
                core.sendQuery(TdApi.GetFile(targetFileId), timeoutMs = 4_000L).getOrNull()
            } ?: current

            val currentPrefix = current.local.downloadedPrefixSize
            if (currentPrefix > lastPrefixSize) {
                lastPrefixSize = currentPrefix
                lastProgressAtMs = System.currentTimeMillis()
                retriedStall = false
            } else if (!retriedStall && System.currentTimeMillis() - lastProgressAtMs >= RETRY_STALL_THRESHOLD_MS) {
                retriedStall = true
                logger.log("TG_PLAY", "WARN", "Luồng Telegram chậm, tự khôi phục fileId=$targetFileId (thử lại 1 lần)")
                runBlocking {
                    core.sendQuery(TdApi.CancelDownloadFile(targetFileId, false), timeoutMs = 3_000L)
                    core.sendQuery(TdApi.DownloadFile(targetFileId, 32, readPosition, 0L, false), timeoutMs = 4_000L)
                }
            }
        }
        throw IOException("Hết thời gian tải luồng Telegram (quá 7.5 giây)")
    }

    private fun ensureBytesAvailable(neededEndOffset: Long) {
        val currentDiskSize = localFile?.length() ?: 0L
        val clampedNeeded = if (totalLength > 0L) minOf(neededEndOffset, totalLength) else neededEndOffset
        if (completedTdFile != null || currentDiskSize >= clampedNeeded && currentDiskSize > readPosition) {
            return
        }

        var lastProgressAtMs = System.currentTimeMillis()
        var lastEnd = currentDiskSize
        var retriedStall = false

        while (!closed && !Thread.currentThread().isInterrupted &&
            System.currentTimeMillis() - lastProgressAtMs < STALL_TIMEOUT_MS
        ) {
            val f = runBlocking { core.sendQuery(TdApi.GetFile(fileId), timeoutMs = 4_000L).getOrNull() }
                ?: throw IOException("Mất kết nối luồng Telegram")

            if (f.local.isDownloadingCompleted) {
                completedTdFile = f
                return
            }

            val availableEnd = f.local.downloadOffset + f.local.downloadedPrefixSize
            val diskLen = localFile?.length() ?: 0L
            if ((availableEnd >= clampedNeeded || diskLen >= clampedNeeded) && diskLen > readPosition) {
                return
            }

            val progressMark = maxOf(availableEnd, diskLen)
            if (progressMark > lastEnd) {
                lastEnd = progressMark
                lastProgressAtMs = System.currentTimeMillis()
                retriedStall = false
            } else if (!retriedStall && System.currentTimeMillis() - lastProgressAtMs >= RETRY_STALL_THRESHOLD_MS) {
                retriedStall = true
                logger.log("TG_PLAY", "WARN", "Đệm Telegram bị đứng tại offset=$readPosition, tự thử lại 1 lần...")
                runBlocking {
                    core.sendQuery(TdApi.CancelDownloadFile(fileId, false), timeoutMs = 3_000L)
                    core.sendQuery(TdApi.DownloadFile(fileId, 32, readPosition, 0L, false), timeoutMs = 4_000L)
                }
            }

            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Đã hủy đệm dữ liệu Telegram")
            }
        }
        if ((localFile?.length() ?: 0L) <= readPosition) {
            throw IOException("Hết thời gian đệm dữ liệu Telegram (quá 7.5 giây)")
        }
    }

    companion object {
        private const val RETRY_STALL_THRESHOLD_MS = 3_500L
        private const val STALL_TIMEOUT_MS = 7_500L
    }
}
