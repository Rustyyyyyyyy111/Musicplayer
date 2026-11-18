package com.laughing.musicplayer.data.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.data.storage.CacheManager
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

    private var currentUri: Uri? = null
    private var fileId: Int = 0
    private var resolvedTrackId: String? = null
    private var totalLength: Long = C.LENGTH_UNSET.toLong()
    private var readPosition: Long = 0L
    private var randomAccessFile: RandomAccessFile? = null
    private var localFile: File? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
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

        val resolvedSize = when {
            initialFile.size > 0L -> initialFile.size
            initialFile.expectedSize > 0L -> initialFile.expectedSize
            expectedSize > 0L -> expectedSize
            else -> C.LENGTH_UNSET.toLong()
        }
        totalLength = resolvedSize

        val path = waitForLocalFileReady(fileId, readPosition + 16 * 1024L, initialFile)
        localFile = File(path)
        randomAccessFile = RandomAccessFile(localFile, "r").apply {
            seek(readPosition)
        }

        val trackKey = resolvedTrackId ?: "tg_$fileId"
        if (initialFile.local.isDownloadingCompleted) {
            runBlocking {
                cacheManager.recordCacheAccess(
                    key = "tg_stream_$trackKey",
                    trackId = trackKey,
                    file = localFile!!,
                    isPermanentOffline = false
                )
            }
        } else {
            runBlocking {
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
            val activeTrackKey = resolvedTrackId ?: "tg_$activeFileId"
            val f = localFile
            runBlocking {
                if (activeFileId != 0) {
                    core.sendQuery(TdApi.CancelDownloadFile(activeFileId, true))
                }
                if (f != null && f.exists()) {
                    cacheManager.recordCacheAccess(
                        key = "tg_stream_$activeTrackKey",
                        trackId = activeTrackKey,
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

        while (System.currentTimeMillis() - startMs < 8_500L) {
            val filePath = current.local.path
            val downloadedEnd = current.local.downloadOffset + current.local.downloadedPrefixSize
            if (filePath.isNotBlank() && File(filePath).exists() &&
                (current.local.isDownloadingCompleted || downloadedEnd >= targetOffset || current.local.downloadedPrefixSize >= 8192)
            ) {
                return filePath
            }

            Thread.sleep(150L)
            current = runBlocking {
                core.sendQuery(TdApi.GetFile(targetFileId), timeoutMs = 4_000L).getOrNull()
            } ?: current

            if (current.local.downloadedPrefixSize == lastPrefixSize) {
                stallCycles++
                // Recover stalled Telegram downloads within ~1.5s
                if (stallCycles == 10) {
                    logger.log("TG_PLAY", "WARN", "Khôi phục luồng tải Telegram bị treo fileId=$targetFileId")
                    runBlocking {
                        core.sendQuery(TdApi.CancelDownloadFile(targetFileId, true), timeoutMs = 3_000L)
                        core.sendQuery(TdApi.DownloadFile(targetFileId, 32, readPosition, 0L, false), timeoutMs = 4_000L)
                    }
                    stallCycles = 0
                }
            } else {
                lastPrefixSize = current.local.downloadedPrefixSize
                stallCycles = 0
            }
        }
        throw IOException("Hết thời gian tải luồng Telegram (quá 9 giây)")
    }

    private fun ensureBytesAvailable(neededEndOffset: Long) {
        val startMs = System.currentTimeMillis()
        var stallCount = 0
        var lastEnd = 0L
        while (System.currentTimeMillis() - startMs < 8_500L) {
            val f = runBlocking { core.sendQuery(TdApi.GetFile(fileId), timeoutMs = 4_000L).getOrNull() }
                ?: throw IOException("Mất kết nối luồng Telegram")
            if (f.local.isDownloadingCompleted) return
            val availableEnd = f.local.downloadOffset + f.local.downloadedPrefixSize
            val clampedNeeded = if (totalLength > 0L) minOf(neededEndOffset, totalLength) else neededEndOffset
            if (availableEnd >= clampedNeeded && (localFile?.length() ?: 0L) > readPosition) {
                return
            }
            if (availableEnd == lastEnd) {
                stallCount++
                if (stallCount == 12) {
                    runBlocking {
                        core.sendQuery(TdApi.DownloadFile(fileId, 32, readPosition, 0L, false), timeoutMs = 4_000L)
                    }
                    stallCount = 0
                }
            } else {
                lastEnd = availableEnd
                stallCount = 0
            }
            Thread.sleep(120L)
        }
        if ((localFile?.length() ?: 0L) <= readPosition) {
            throw IOException("Hết thời gian đệm dữ liệu Telegram (quá 9 giây)")
        }
    }
}
