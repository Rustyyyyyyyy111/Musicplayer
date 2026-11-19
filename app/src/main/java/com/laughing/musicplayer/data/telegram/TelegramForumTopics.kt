package com.laughing.musicplayer.data.telegram

import com.laughing.musicplayer.core.model.TelegramTopicItem
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.data.local.ForumTopicEntity
import com.laughing.musicplayer.data.local.TelegramDao
import com.laughing.musicplayer.data.local.TrackDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.drinkless.tdlib.TdApi

class TelegramForumTopics(
    private val core: TelegramCore,
    private val telegramDao: TelegramDao,
    private val trackDao: TrackDao,
    private val logger: ActivityLogger,
    private val rateLimiter: TelegramRateLimiter? = null
) {
    private suspend fun <T> throttled(block: suspend () -> T): T {
        return rateLimiter?.withThrottledMetadataCall(block) ?: block()
    }
    fun observeAllTopics(): Flow<List<TelegramTopicItem>> =
        telegramDao.observeAllTopics().map { list -> list.map { it.toDomain() } }

    fun observeTopicsForChat(chatId: Long): Flow<List<TelegramTopicItem>> =
        telegramDao.observeTopicsForChat(chatId).map { list -> list.map { it.toDomain() } }

    suspend fun isForumSupergroup(chat: TdApi.Chat): Boolean {
        val sgType = chat.type as? TdApi.ChatTypeSupergroup ?: return false
        val sg = runCatching {
            throttled { core.sendQuery(TdApi.GetSupergroup(sgType.supergroupId)).getOrNull() }
        }.getOrNull() ?: return false
        return sg.isForum
    }

    suspend fun fetchForumTopics(chatId: Long): List<TelegramTopicItem> {
        if (rateLimiter?.isCircuitBreakerOpen() == true) {
            logger.log("ANTI_FLOOD", "WARN", "Bỏ qua tải Forum Topics do đang trong thời gian chờ FLOOD_WAIT")
            return emptyList()
        }
        val collected = mutableListOf<TelegramTopicItem>()
        var offsetDate = 0
        var offsetMessageId = 0L
        var offsetForumTopicId = 0

        try {
            while (true) {
                val result = throttled {
                    core.sendQuery(
                        TdApi.GetForumTopics(
                            chatId,
                            "",
                            offsetDate,
                            offsetMessageId,
                            offsetForumTopicId,
                            100
                        )
                    )
                }.getOrElse { err ->
                    if (err is TelegramCore.TdLibException) {
                        rateLimiter?.reportTdErrorIfRateLimited(err.code, err.message)
                    }
                    null
                } ?: break

            val topics = result.topics
            if (topics.isEmpty()) break

            for (topic in topics) {
                val info = topic.info
                val forumTopicId = info.forumTopicId
                val threadId = forumTopicId.toLong()
                val iconFileId = info.icon?.customEmojiId
                val existingCount = trackDao.countDistinctTelegramTracksForTopic(chatId, threadId)
                collected += TelegramTopicItem(
                    chatId = chatId,
                    messageThreadId = threadId,
                    topicId = threadId,
                    name = info.name.ifBlank { "Chủ đề #$forumTopicId" },
                    iconUrl = iconFileId?.takeIf { it != 0L }?.let { "tg_emoji://$it" },
                    trackCount = existingCount
                )
            }

            if (result.nextOffsetDate == 0 && result.nextOffsetForumTopicId == 0) break
            offsetDate = result.nextOffsetDate
            offsetMessageId = result.nextOffsetMessageId
            offsetForumTopicId = result.nextOffsetForumTopicId
        }
        } catch (_: TelegramRateLimiter.FloodWaitActiveException) {
            logger.log("ANTI_FLOOD", "WARN", "Tạm dừng tải Forum Topics do FLOOD_WAIT")
        }

        if (collected.isNotEmpty()) {
            telegramDao.upsertTopics(
                collected.map {
                    ForumTopicEntity(
                        chatId = it.chatId,
                        messageThreadId = it.messageThreadId,
                        topicId = it.topicId,
                        name = it.name,
                        iconUrl = it.iconUrl,
                        trackCount = it.trackCount
                    )
                }
            )
            logger.log("TG_TOPICS", "INFO", "Đã tải ${collected.size} Forum Topics cho nhóm $chatId")
        }
        return collected
    }

    suspend fun fetchMessagesForTopic(
        chatId: Long,
        forumTopicId: Int,
        fromMessageId: Long = 0L,
        limit: Int = 50
    ): List<TdApi.Message> {
        if (rateLimiter?.isCircuitBreakerOpen() == true) return emptyList()
        val response = try {
            throttled {
                core.sendQuery(
                    TdApi.GetForumTopicHistory(chatId, forumTopicId, fromMessageId, 0, limit)
                )
            }
        } catch (_: TelegramRateLimiter.FloodWaitActiveException) {
            return emptyList()
        }.getOrElse { err ->
            if (err is TelegramCore.TdLibException) {
                rateLimiter?.reportTdErrorIfRateLimited(err.code, err.message)
            }
            logger.log("TG_TOPICS", "WARN", "Lỗi lấy tin nhắn topic id=$forumTopicId: ${err.message}")
            return emptyList()
        }
        return response.messages.toList()
    }
}
