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
    private var dataSpecBytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var verifiedDownloadStart: Long = 0L
    private var verifiedAvailableEnd: Long = 0L
    private var randomAccessFile: RandomAccessFile? = null
    private var localFile: File? = null
    private var opened = false
    private var completedTdFile: TdApi.File? = null

    @Volatile
    private var closed = false

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        completedTdFile = null
        verifiedDownloadStart = 0L
        verifiedAvailableEnd = 0L
        if (dataSpec.position < 0L) {
            throw IOException("Vị trí đọc Telegram không hợp lệ: ${dataSpec.position}")
        }
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
        updateDownloadedBounds(initialFile)

        val resolvedSize = when {
            initialFile.size > 0L -> initialFile.size
            initialFile.expectedSize > 0L -> initialFile.expectedSize
            expectedSize > 0L -> expectedSize
            else -> C.LENGTH_UNSET.toLong()
        }
        totalLength = resolvedSize

        if (totalLength > 0L && readPosition >= totalLength) {
            dataSpecBytesRemaining = 0L
            opened = true
            transferStarted(dataSpec)
            return 0L
        }

        val remainingFromPos = if (totalLength > 0L) (totalLength - readPosition).coerceAtLeast(1L) else 4096L
        val initialHeaderBytes = minOf(4096L, remainingFromPos)
        val readyFile = waitForLocalFileReady(fileId, readPosition + initialHeaderBytes, initialFile)
        val path = readyFile.local?.path?.takeIf { it.isNotBlank() }
            ?: throw IOException("TDLib không trả về đường dẫn tệp hợp lệ cho fileId=$fileId")
        val fileObj = File(path)
        localFile = fileObj
        updateDownloadedBounds(readyFile)
        randomAccessFile = RandomAccessFile(fileObj, "r").apply {
            seek(readPosition)
        }

        val trackKey = resolvedTrackId ?: "tg_$fileId"
        backgroundScope.launch {
            if (completedTdFile != null || readyFile.local?.isDownloadingCompleted == true) {
                localFile?.let { file ->
                    cacheManager.recordCacheAccess(
                        key = "tg_stream_$trackKey",
                        trackId = trackKey,
                        file = file,
                        isPermanentOffline = false
                    )
                }
                resolvedTrackId?.let { tid ->
                    fileResolver.promoteCompletedTdFileById(tid, readyFile)
                }
            } else {
                cacheManager.touchCacheAccess("tg_stream_$trackKey")
            }
        }

        dataSpecBytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length.coerceAtLeast(0L)
        } else if (totalLength > 0L) {
            (totalLength - readPosition).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        logger.log("TG_PLAY", "INFO", "Phát progressive Telegram fileId=$fileId tại offset=$readPosition")

        return dataSpecBytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) return C.RESULT_END_OF_INPUT
        if (length == 0) return 0
        if (buffer.isEmpty() || offset < 0 || length < 0 || offset >= buffer.size) return 0
        if (dataSpecBytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (totalLength > 0L && readPosition >= totalLength) return C.RESULT_END_OF_INPUT

        val maxBufferWritable = (buffer.size - offset).coerceAtLeast(0)
        if (maxBufferWritable == 0) return 0

        var availableBytes = computeVerifiedAvailableBytes(readPosition)
        if (availableBytes <= 0L) {
            ensureBytesAvailable(readPosition + 1L)
            availableBytes = computeVerifiedAvailableBytes(readPosition)
            if (availableBytes <= 0L) return C.RESULT_END_OF_INPUT
        }

        var safeReadLength = clampReadLength(
            requestedLength = length,
            bufferSize = buffer.size,
            bufferOffset = offset,
            availableStreamBytes = availableBytes,
            dataSpecRemaining = dataSpecBytesRemaining,
            totalFileLength = totalLength,
            currentReadPosition = readPosition
        )
        if (safeReadLength <= 0) return C.RESULT_END_OF_INPUT

        var raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT
        raf.seek(readPosition)
        var bytesRead = raf.read(buffer, offset, safeReadLength)
        if (bytesRead <= 0) {
            if (totalLength > 0L && readPosition >= totalLength) return C.RESULT_END_OF_INPUT
            val chunkTarget = minOf(length.toLong(), maxBufferWritable.toLong(), 64L * 1024L).coerceAtLeast(1L)
            ensureBytesAvailable(readPosition + chunkTarget)
            availableBytes = computeVerifiedAvailableBytes(readPosition)
            safeReadLength = clampReadLength(
                requestedLength = length,
                bufferSize = buffer.size,
                bufferOffset = offset,
                availableStreamBytes = availableBytes,
                dataSpecRemaining = dataSpecBytesRemaining,
                totalFileLength = totalLength,
                currentReadPosition = readPosition
            )
            if (safeReadLength <= 0) return C.RESULT_END_OF_INPUT
            raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT
            raf.seek(readPosition)
            bytesRead = raf.read(buffer, offset, safeReadLength)
            if (bytesRead <= 0) return C.RESULT_END_OF_INPUT
        }

        readPosition += bytesRead
        if (dataSpecBytesRemaining > 0L) {
            dataSpecBytesRemaining = (dataSpecBytesRemaining - bytesRead).coerceAtLeast(0L)
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        closed = true
        currentUri = null
        dataSpecBytesRemaining = C.LENGTH_UNSET.toLong()
        verifiedDownloadStart = 0L
        verifiedAvailableEnd = 0L
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

    private fun updateDownloadedBounds(file: TdApi.File) {
        val sizeHint = maxOf(file.size, file.expectedSize)
        if (sizeHint > 0L && (totalLength <= 0L || sizeHint > totalLength)) {
            totalLength = sizeHint
        }
        val local = file.local ?: return
        val path = local.path
        val diskLen = if (!path.isNullOrBlank()) File(path).length() else 0L
        if (local.isDownloadingCompleted && !path.isNullOrBlank() && diskLen > 0L) {
            val completedEnd = if (sizeHint > 0L) minOf(sizeHint, diskLen) else diskLen
            verifiedDownloadStart = 0L
            verifiedAvailableEnd = completedEnd
            if (totalLength <= 0L) totalLength = completedEnd
            completedTdFile = file
            return
        }
        val start = local.downloadOffset.coerceAtLeast(0L)
        val prefix = local.downloadedPrefixSize.coerceAtLeast(0L)
        if (prefix > 0L) {
            val end = start + prefix
            if (readPosition in start until end) {
                verifiedDownloadStart = start
                verifiedAvailableEnd = end
            } else if (verifiedAvailableEnd <= readPosition || readPosition < verifiedDownloadStart) {
                verifiedDownloadStart = start
                verifiedAvailableEnd = end
            }
        }
    }

    private fun computeVerifiedAvailableBytes(pos: Long): Long {
        if (pos < 0L) return 0L
        val file = localFile ?: return 0L
        if (!file.exists()) return 0L
        val diskLen = file.length()
        return computeAvailableBytesAtPosition(
            readPosition = pos,
            downloadStart = verifiedDownloadStart,
            downloadedEnd = verifiedAvailableEnd,
            diskLength = diskLen,
            totalFileLength = totalLength,
            isCompleted = completedTdFile != null
        )
    }

    private fun hasReadyBytesAtReadPosition(file: TdApi.File, neededEndOffset: Long): Boolean {
        val local = file.local ?: return false
        val path = local.path
        if (path.isNullOrBlank()) return false
        val diskFile = File(path)
        if (!diskFile.exists()) return false
        val diskLen = diskFile.length()
        if (local.isDownloadingCompleted) {
            return diskLen > readPosition
        }
        val start = local.downloadOffset.coerceAtLeast(0L)
        val prefix = local.downloadedPrefixSize.coerceAtLeast(0L)
        if (prefix <= 0L) return false
        val tdEnd = start + prefix
        val usableEnd = minOf(tdEnd, diskLen)
        val clampedNeed = if (totalLength > 0L) minOf(neededEndOffset, totalLength) else neededEndOffset
        val minNeed = maxOf(readPosition + 1L, clampedNeed)
        return readPosition >= start && usableEnd >= minNeed && usableEnd > readPosition
    }

    private fun reopenRandomAccessFileIfPathChanged(file: TdApi.File) {
        val newPath = file.local?.path?.takeIf { it.isNotBlank() } ?: return
        if (localFile?.absolutePath != newPath) {
            val nextFile = File(newPath)
            if (nextFile.exists()) {
                runCatching { randomAccessFile?.close() }
                localFile = nextFile
                randomAccessFile = RandomAccessFile(nextFile, "r").apply {
                    seek(readPosition)
                }
            }
        }
    }

    private fun waitForLocalFileReady(
        targetFileId: Int,
        targetOffset: Long,
        initial: TdApi.File
    ): TdApi.File {
        if (hasReadyBytesAtReadPosition(initial, targetOffset)) {
            updateDownloadedBounds(initial)
            return initial
        }
        var current = initial
        var lastProgressAtMs = System.currentTimeMillis()
        var lastContiguousEnd = 0L
        var retriedStall = false
        var reissuedSeekOffset = false

        while (!closed && !Thread.currentThread().isInterrupted &&
            System.currentTimeMillis() - lastProgressAtMs < STALL_TIMEOUT_MS
        ) {
            if (hasReadyBytesAtReadPosition(current, targetOffset)) {
                updateDownloadedBounds(current)
                return current
            }

            val start = current.local?.downloadOffset?.coerceAtLeast(0L) ?: 0L
            val prefix = current.local?.downloadedPrefixSize?.coerceAtLeast(0L) ?: 0L
            val currentEnd = if (prefix > 0L && readPosition in start until (start + prefix)) {
                start + prefix
            } else {
                0L
            }

            if (currentEnd > lastContiguousEnd) {
                lastContiguousEnd = currentEnd
                lastProgressAtMs = System.currentTimeMillis()
                retriedStall = false
            } else {
                val coversReadPos = prefix > 0L && readPosition in start until (start + prefix)
                if (!coversReadPos && !reissuedSeekOffset &&
                    System.currentTimeMillis() - lastProgressAtMs >= SEEK_REISSUE_THRESHOLD_MS
                ) {
                    reissuedSeekOffset = true
                    runBlocking {
                        core.sendQuery(TdApi.DownloadFile(targetFileId, 32, readPosition, 0L, false), timeoutMs = 4_000L)
                    }
                } else if (!retriedStall && System.currentTimeMillis() - lastProgressAtMs >= RETRY_STALL_THRESHOLD_MS) {
                    retriedStall = true
                    logger.log("TG_PLAY", "WARN", "Luồng Telegram chậm tại offset=$readPosition, tự khôi phục fileId=$targetFileId")
                    runBlocking {
                        core.sendQuery(TdApi.CancelDownloadFile(targetFileId, false), timeoutMs = 3_000L)
                        core.sendQuery(TdApi.DownloadFile(targetFileId, 32, readPosition, 0L, false), timeoutMs = 4_000L)
                    }
                }
            }

            try {
                Thread.sleep(80L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Đã hủy tải luồng Telegram")
            }
            if (closed) throw IOException("Đã hủy tải luồng Telegram")

            current = runBlocking {
                core.sendQuery(TdApi.GetFile(targetFileId), timeoutMs = 4_000L).getOrNull()
            } ?: current
        }
        throw IOException("Hết thời gian tải luồng Telegram (quá 7.5 giây)")
    }

    private fun ensureBytesAvailable(neededEndOffset: Long) {
        val clampedNeeded = if (totalLength > 0L) minOf(neededEndOffset, totalLength) else neededEndOffset
        if (clampedNeeded <= readPosition) return
        val minNeededBytes = (clampedNeeded - readPosition).coerceAtLeast(1L)
        if (computeVerifiedAvailableBytes(readPosition) >= minNeededBytes) {
            return
        }

        var lastProgressAtMs = System.currentTimeMillis()
        var lastContiguousEnd = if (readPosition in verifiedDownloadStart until verifiedAvailableEnd) {
            minOf(verifiedAvailableEnd, localFile?.length() ?: 0L)
        } else {
            0L
        }
        var retriedStall = false
        var reissuedSeekDownload = false

        while (!closed && !Thread.currentThread().isInterrupted &&
            System.currentTimeMillis() - lastProgressAtMs < STALL_TIMEOUT_MS
        ) {
            val f = runBlocking { core.sendQuery(TdApi.GetFile(fileId), timeoutMs = 4_000L).getOrNull() }
                ?: throw IOException("Mất kết nối luồng Telegram")

            reopenRandomAccessFileIfPathChanged(f)
            updateDownloadedBounds(f)

            if (f.local.isDownloadingCompleted) {
                if (computeVerifiedAvailableBytes(readPosition) > 0L) return
                return
            }

            val tdStart = f.local.downloadOffset.coerceAtLeast(0L)
            val tdPrefix = f.local.downloadedPrefixSize.coerceAtLeast(0L)
            val tdEnd = tdStart + tdPrefix
            val diskLen = localFile?.length() ?: 0L
            val usableEnd = minOf(tdEnd, diskLen)

            if (tdPrefix > 0L && readPosition >= tdStart && usableEnd > readPosition) {
                verifiedDownloadStart = tdStart
                verifiedAvailableEnd = tdEnd
                if (usableEnd >= clampedNeeded || (usableEnd - readPosition) >= minOf(minNeededBytes, 1024L)) {
                    return
                }
                if (usableEnd > lastContiguousEnd) {
                    lastContiguousEnd = usableEnd
                    lastProgressAtMs = System.currentTimeMillis()
                    retriedStall = false
                }
            } else if (!reissuedSeekDownload) {
                reissuedSeekDownload = true
                runBlocking {
                    core.sendQuery(TdApi.DownloadFile(fileId, 32, readPosition, 0L, false), timeoutMs = 4_000L)
                }
            }

            if (!retriedStall && System.currentTimeMillis() - lastProgressAtMs >= RETRY_STALL_THRESHOLD_MS) {
                retriedStall = true
                logger.log("TG_PLAY", "WARN", "Đệm Telegram bị đứng tại offset=$readPosition, tự thử lại 1 lần...")
                runBlocking {
                    core.sendQuery(TdApi.CancelDownloadFile(fileId, false), timeoutMs = 3_000L)
                    core.sendQuery(TdApi.DownloadFile(fileId, 32, readPosition, 0L, false), timeoutMs = 4_000L)
                }
            }

            try {
                Thread.sleep(80L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Đã hủy đệm dữ liệu Telegram")
            }
        }
        if (computeVerifiedAvailableBytes(readPosition) <= 0L) {
            throw IOException("Hết thời gian đệm dữ liệu Telegram (quá 7.5 giây)")
        }
    }

    companion object {
        private const val SEEK_REISSUE_THRESHOLD_MS = 1_200L
        private const val RETRY_STALL_THRESHOLD_MS = 3_500L
        private const val STALL_TIMEOUT_MS = 7_500L

        internal fun computeAvailableBytesAtPosition(
            readPosition: Long,
            downloadStart: Long,
            downloadedEnd: Long,
            diskLength: Long,
            totalFileLength: Long,
            isCompleted: Boolean
        ): Long {
            if (readPosition < 0L || diskLength <= readPosition) return 0L
            val diskBound = if (totalFileLength > 0L) minOf(diskLength, totalFileLength) else diskLength
            if (diskBound <= readPosition) return 0L
            if (isCompleted) {
                return (diskBound - readPosition).coerceAtLeast(0L)
            }
            if (readPosition < downloadStart || readPosition >= downloadedEnd) {
                return 0L
            }
            val effectiveEnd = minOf(downloadedEnd, diskBound)
            return (effectiveEnd - readPosition).coerceAtLeast(0L)
        }

        internal fun clampReadLength(
            requestedLength: Int,
            bufferSize: Int,
            bufferOffset: Int,
            availableStreamBytes: Long,
            dataSpecRemaining: Long,
            totalFileLength: Long,
            currentReadPosition: Long
        ): Int {
            if (requestedLength <= 0 || bufferSize <= 0 || bufferOffset < 0 || bufferOffset >= bufferSize) {
                return 0
            }
            val bufferRemaining = (bufferSize - bufferOffset).toLong()
            var bound = minOf(requestedLength.toLong(), bufferRemaining, availableStreamBytes.coerceAtLeast(0L))
            if (dataSpecRemaining >= 0L) {
                bound = minOf(bound, dataSpecRemaining)
            }
            if (totalFileLength > 0L) {
                val fileRemaining = (totalFileLength - currentReadPosition).coerceAtLeast(0L)
                bound = minOf(bound, fileRemaining)
            }
            return bound.coerceIn(0L, bufferRemaining).toInt()
        }
    }
}
