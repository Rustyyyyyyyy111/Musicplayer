package com.example.data.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.core.util.ActivityLogger
import com.example.data.storage.CacheManager
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

@UnstableApi
class TelegramAudioDataSource(
    private val core: TelegramCore,
    private val cacheManager: CacheManager,
    private val logger: ActivityLogger
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(
        private val core: TelegramCore,
        private val cacheManager: CacheManager,
        private val logger: ActivityLogger
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            TelegramAudioDataSource(core, cacheManager, logger)
    }

    private var currentUri: Uri? = null
    private var fileId: Int = 0
    private var totalLength: Long = C.LENGTH_UNSET.toLong()
    private var readPosition: Long = 0L
    private var randomAccessFile: RandomAccessFile? = null
    private var localFile: File? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw IOException("URI Telegram không hợp lệ: ${dataSpec.uri}")
        readPosition = dataSpec.position
        val expectedSize = dataSpec.uri.getQueryParameter("size")?.toLongOrNull() ?: 0L

        val initialFile = runBlocking {
            core.sendQuery(TdApi.GetFile(fileId)).getOrNull()
        } ?: throw IOException("Không tìm thấy tệp Telegram #$fileId")

        val resolvedSize = when {
            initialFile.size > 0L -> initialFile.size
            initialFile.expectedSize > 0L -> initialFile.expectedSize
            expectedSize > 0L -> expectedSize
            else -> C.LENGTH_UNSET.toLong()
        }
        totalLength = resolvedSize

        // Start progressive download from the requested seek offset
        runBlocking {
            core.sendQuery(
                TdApi.DownloadFile(
                    fileId,
                    /* priority = */ 32,
                    /* offset = */ readPosition,
                    /* limit = */ 0L,
                    /* synchronous = */ false
                )
            )
        }

        val path = waitForLocalFileReady(fileId, readPosition + 16 * 1024L, initialFile)
        localFile = File(path)
        randomAccessFile = RandomAccessFile(localFile, "r").apply {
            seek(readPosition)
        }

        if (initialFile.local.isDownloadingCompleted) {
            runBlocking {
                cacheManager.recordCacheAccess(
                    key = "tg_stream_$fileId",
                    trackId = "tg_$fileId",
                    file = localFile!!,
                    isPermanentOffline = false
                )
            }
        } else {
            runBlocking {
                cacheManager.touchCacheAccess("tg_stream_$fileId")
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
        if (length == 0) return 0
        if (totalLength > 0L && readPosition >= totalLength) return C.RESULT_END_OF_INPUT

        ensureBytesAvailable(readPosition + 1L)
        val raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT
        raf.seek(readPosition)
        val bytesRead = raf.read(buffer, offset, length)
        if (bytesRead <= 0) {
            if (totalLength > 0L && readPosition >= totalLength) return C.RESULT_END_OF_INPUT
            // Retry progressive fetch if stalled
            ensureBytesAvailable(readPosition + minOf(length.toLong(), 32 * 1024L))
            raf.seek(readPosition)
            val retryRead = raf.read(buffer, offset, length)
            if (retryRead <= 0) return C.RESULT_END_OF_INPUT
            readPosition += retryRead
            bytesTransferred(retryRead)
            return retryRead
        }
        readPosition += bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        runCatching { randomAccessFile?.close() }
        randomAccessFile = null
        if (opened) {
            opened = false
            transferEnded()
            val activeFileId = fileId
            val f = localFile
            runBlocking {
                if (activeFileId != 0) {
                    core.sendQuery(TdApi.CancelDownloadFile(activeFileId, true))
                }
                if (f != null && f.exists()) {
                    cacheManager.recordCacheAccess(
                        key = "tg_stream_$activeFileId",
                        trackId = "tg_$activeFileId",
                        file = f,
                        isPermanentOffline = false
                    )
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
            return initial.local.path
        }
        var current = initial
        var stallCycles = 0
        var lastPrefixSize = current.local.downloadedPrefixSize
        val startMs = System.currentTimeMillis()

        while (System.currentTimeMillis() - startMs < 25_000L) {
            val filePath = current.local.path
            val downloadedEnd = current.local.downloadOffset + current.local.downloadedPrefixSize
            if (filePath.isNotBlank() && File(filePath).exists() &&
                (current.local.isDownloadingCompleted || downloadedEnd >= targetOffset || current.local.downloadedPrefixSize >= 8192)
            ) {
                return filePath
            }

            Thread.sleep(150L)
            current = runBlocking {
                core.sendQuery(TdApi.GetFile(targetFileId)).getOrNull()
            } ?: current

            if (current.local.downloadedPrefixSize == lastPrefixSize) {
                stallCycles++
                // MelodyRide v1.2.18: recover stalled Telegram downloads
                if (stallCycles == 12) {
                    logger.log("TG_PLAY", "WARN", "Khôi phục luồng tải Telegram bị treo fileId=$targetFileId")
                    runBlocking {
                        core.sendQuery(TdApi.CancelDownloadFile(targetFileId, true))
                        core.sendQuery(TdApi.DownloadFile(targetFileId, 32, readPosition, 0L, false))
                    }
                    stallCycles = 0
                }
            } else {
                lastPrefixSize = current.local.downloadedPrefixSize
                stallCycles = 0
            }
        }
        throw IOException("Hết thời gian chờ đệm dữ liệu Telegram fileId=$targetFileId")
    }

    private fun ensureBytesAvailable(neededEndOffset: Long) {
        val startMs = System.currentTimeMillis()
        var stallCount = 0
        var lastEnd = 0L
        while (System.currentTimeMillis() - startMs < 20_000L) {
            val f = runBlocking { core.sendQuery(TdApi.GetFile(fileId)).getOrNull() } ?: break
            if (f.local.isDownloadingCompleted) return
            val availableEnd = f.local.downloadOffset + f.local.downloadedPrefixSize
            val clampedNeeded = if (totalLength > 0L) minOf(neededEndOffset, totalLength) else neededEndOffset
            if (availableEnd >= clampedNeeded && (localFile?.length() ?: 0L) > readPosition) {
                return
            }
            if (availableEnd == lastEnd) {
                stallCount++
                if (stallCount == 15) {
                    runBlocking {
                        core.sendQuery(TdApi.DownloadFile(fileId, 32, readPosition, 0L, false))
                    }
                    stallCount = 0
                }
            } else {
                lastEnd = availableEnd
                stallCount = 0
            }
            Thread.sleep(120L)
        }
    }
}
