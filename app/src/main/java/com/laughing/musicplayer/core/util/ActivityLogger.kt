package com.laughing.musicplayer.core.util

import com.laughing.musicplayer.core.model.ActivityLogItem
import com.laughing.musicplayer.data.local.ActivityLogDao
import com.laughing.musicplayer.data.local.ActivityLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityLogger(private val dao: ActivityLogDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sensitivePatterns = listOf(
        Regex("(?i)(api_hash|password|otp|code|token|secret|session)\\s*[:=]\\s*\\S+"),
        Regex("\\+?\\d{9,15}")
    )

    val recentLogs: Flow<List<ActivityLogItem>> = dao.observeRecentLogs().map { list ->
        list.map { it.toDomain() }
    }

    fun log(category: String, level: String = "INFO", message: String) {
        val sanitized = sanitize(message)
        scope.launch {
            runCatching {
                dao.insertLog(
                    ActivityLogEntity(
                        timestampMs = System.currentTimeMillis(),
                        category = category,
                        level = level,
                        message = sanitized
                    )
                )
                dao.trimToRingBufferLimit()
            }
        }
    }

    suspend fun exportFormattedLogs(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val logs = dao.getRecentLogsOnce().asReversed()
        return buildString {
            appendLine("=== Music Player Activity Logs (${logs.size} entries) ===")
            logs.forEach { entry ->
                appendLine("[${fmt.format(Date(entry.timestampMs))}] [${entry.level}] [${entry.category}] ${entry.message}")
            }
        }
    }

    suspend fun clear() {
        dao.clearLogs()
    }

    private fun sanitize(raw: String): String {
        var result = raw
        sensitivePatterns.forEach { regex ->
            result = regex.replace(result) { "[REDACTED]" }
        }
        return result.take(500)
    }
}
