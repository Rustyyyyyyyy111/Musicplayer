package com.laughing.musicplayer.data.telegram

import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.data.local.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates strict Anti-Ban & Rate-Limit protection for all Telegram TDLib metadata calls:
 * - Sequential execution via [metadataMutex] (max 1 concurrent metadata call at a time)
 * - Minimum inter-request spacing ([MIN_METADATA_REQUEST_GAP_MS] = 500ms, within 400-600ms)
 * - Minimum automatic background sync cooldown ([AUTO_SYNC_COOLDOWN_MS] = 12 minutes, within 10-15m)
 * - Persisted `429 / FLOOD_WAIT_X` backoff + safety buffer circuit breaker pausing background scans
 *   while keeping local Room DB browsing and cached/offline playback uninterrupted.
 */
class TelegramRateLimiter(
    private val settingsDataStore: SettingsDataStore,
    private val logger: ActivityLogger,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val minMetadataGapMs: Long = MIN_METADATA_REQUEST_GAP_MS,
    private val autoSyncCooldownMs: Long = AUTO_SYNC_COOLDOWN_MS
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val metadataMutex = Mutex()
    private val syncMutex = Mutex()

    @Volatile
    private var hydrated = false

    @Volatile
    private var floodWaitUntilMs: Long = 0L

    @Volatile
    private var lastAutoSyncAtMs: Long = 0L

    @Volatile
    private var lastMetadataCompletedAtMs: Long = 0L

    private suspend fun ensureHydrated() {
        if (hydrated) return
        val prefs = runCatching { settingsDataStore.preferencesFlow.first() }.getOrNull()
        if (prefs != null) {
            floodWaitUntilMs = maxOf(floodWaitUntilMs, prefs.floodWaitUntilEpochMs)
            lastAutoSyncAtMs = maxOf(lastAutoSyncAtMs, prefs.lastTelegramAutoSyncEpochMs)
        }
        hydrated = true
    }

    suspend fun getRemainingFloodWaitMs(): Long {
        ensureHydrated()
        val now = clockMs()
        return (floodWaitUntilMs - now).coerceAtLeast(0L)
    }

    suspend fun isCircuitBreakerOpen(): Boolean = getRemainingFloodWaitMs() > 0L

    fun isCircuitBreakerOpenFast(): Boolean = (floodWaitUntilMs - clockMs()) > 0L

    suspend fun canRunAutomaticSync(): Boolean {
        ensureHydrated()
        if (isCircuitBreakerOpen()) return false
        val elapsed = clockMs() - lastAutoSyncAtMs
        return lastAutoSyncAtMs == 0L || elapsed >= autoSyncCooldownMs
    }

    suspend fun getRemainingAutoSyncCooldownMs(): Long {
        ensureHydrated()
        if (lastAutoSyncAtMs == 0L) return 0L
        return (autoSyncCooldownMs - (clockMs() - lastAutoSyncAtMs)).coerceAtLeast(0L)
    }

    suspend fun markAutoSyncCompleted() {
        val now = clockMs()
        lastAutoSyncAtMs = now
        hydrated = true
        runCatching { settingsDataStore.setLastTelegramAutoSyncEpochMs(now) }
    }

    /**
     * Serializes metadata requests (GetChatHistory, GetForumTopics, GetForumTopicHistory,
     * GetChat, GetSupergroup, repair GetMessage) with >= [minMetadataGapMs] delay between calls.
     * Immediately throws [FloodWaitActiveException] if the circuit breaker is open.
     */
    suspend fun <T> withThrottledMetadataCall(block: suspend () -> T): T {
        ensureHydrated()
        val floodRemaining = getRemainingFloodWaitMs()
        if (floodRemaining > 0L) {
            throw FloodWaitActiveException((floodRemaining / 1000L).coerceAtLeast(1L))
        }
        return metadataMutex.withLock {
            val recheckFlood = getRemainingFloodWaitMs()
            if (recheckFlood > 0L) {
                throw FloodWaitActiveException((recheckFlood / 1000L).coerceAtLeast(1L))
            }
            val now = clockMs()
            val sinceLast = now - lastMetadataCompletedAtMs
            if (lastMetadataCompletedAtMs > 0L && sinceLast in 0L until minMetadataGapMs) {
                delay(minMetadataGapMs - sinceLast)
            }
            try {
                block()
            } finally {
                lastMetadataCompletedAtMs = clockMs()
            }
        }
    }

    /**
     * Ensures at most one Telegram source sync executes at a time.
     */
    suspend fun <T> withExclusiveSync(block: suspend () -> T): T {
        return syncMutex.withLock { block() }
    }

    /**
     * Synchronously trips the in-memory circuit breaker from TDLib callback threads and persists it.
     */
    fun reportTdErrorIfRateLimitedAsync(code: Int, message: String?): Long? {
        val waitSec = parseFloodWaitSeconds(code, message) ?: return null
        val totalWaitSec = waitSec + FLOOD_WAIT_SAFETY_BUFFER_SECONDS
        val until = clockMs() + (totalWaitSec * 1000L)
        if (until > floodWaitUntilMs) {
            floodWaitUntilMs = until
            hydrated = true
            scope.launch {
                runCatching { settingsDataStore.setTelegramFloodWaitUntil(until) }
                logger.log(
                    "ANTI_FLOOD",
                    "WARN",
                    "Telegram báo giới hạn tốc độ (${message ?: code}). Tạm ngưng quét nền trong ${totalWaitSec}s (giữ nguyên phát cục bộ)."
                )
            }
        }
        return totalWaitSec
    }

    suspend fun reportTdErrorIfRateLimited(code: Int, message: String?): Long? {
        val waitSec = parseFloodWaitSeconds(code, message) ?: return null
        val totalWaitSec = waitSec + FLOOD_WAIT_SAFETY_BUFFER_SECONDS
        val until = clockMs() + (totalWaitSec * 1000L)
        if (until > floodWaitUntilMs) {
            floodWaitUntilMs = until
            hydrated = true
            runCatching { settingsDataStore.setTelegramFloodWaitUntil(until) }
            logger.log(
                "ANTI_FLOOD",
                "WARN",
                "Telegram báo giới hạn tốc độ (${message ?: code}). Tạm ngưng quét nền trong ${totalWaitSec}s (giữ nguyên phát cục bộ)."
            )
        }
        return totalWaitSec
    }

    suspend fun clearFloodWaitForTesting() {
        floodWaitUntilMs = 0L
        lastAutoSyncAtMs = 0L
        hydrated = true
        runCatching {
            settingsDataStore.setTelegramFloodWaitUntil(0L)
            settingsDataStore.setLastTelegramAutoSyncEpochMs(0L)
        }
    }

    class FloodWaitActiveException(val remainingSeconds: Long) :
        IllegalStateException("Telegram đang tạm nghỉ chống Flood (${remainingSeconds}s còn lại)")

    companion object {
        const val MIN_METADATA_REQUEST_GAP_MS = 500L
        const val AUTO_SYNC_COOLDOWN_MS = 12L * 60L * 1000L // 12 minutes
        const val FLOOD_WAIT_SAFETY_BUFFER_SECONDS = 5L
        private const val DEFAULT_429_WAIT_SECONDS = 30L

        fun parseFloodWaitSeconds(code: Int, message: String?): Long? {
            val raw = message.orEmpty()
            val explicitPattern = Regex("(?:FLOOD_WAIT_|RETRY_AFTER_|retry after\\s+)(\\d+)", RegexOption.IGNORE_CASE)
            explicitPattern.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { sec ->
                return sec.coerceAtLeast(1L)
            }
            val isRateLimited = code == 429 ||
                raw.contains("FLOOD_WAIT", ignoreCase = true) ||
                raw.contains("Too Many Requests", ignoreCase = true)
            if (!isRateLimited) return null
            val fallbackNum = Regex("(\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
            return (fallbackNum ?: DEFAULT_429_WAIT_SECONDS).coerceAtLeast(1L)
        }
    }
}
