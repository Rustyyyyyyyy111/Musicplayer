package com.laughing.musicplayer.data.telegram

import com.laughing.musicplayer.core.model.TelegramTopicItem
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.data.local.ForumTopicEntity
import com.laughing.musicplayer.data.local.TelegramDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.drinkless.tdlib.TdApi

class TelegramForumTopics(
    private val core: TelegramCore,
    private val telegramDao: TelegramDao,
    private val logger: ActivityLogger
) {
    fun observeAllTopics(): Flow<List<TelegramTopicItem>> =
        telegramDao.observeAllTopics().map { list -> list.map { it.toDomain() } }

    fun observeTopicsForChat(chatId: Long): Flow<List<TelegramTopicItem>> =
        telegramDao.observeTopicsForChat(chatId).map { list -> list.map { it.toDomain() } }

    suspend fun isForumSupergroup(chat: TdApi.Chat): Boolean {
        val sgType = chat.type as? TdApi.ChatTypeSupergroup ?: return false
        val sg = core.sendQuery(TdApi.GetSupergroup(sgType.supergroupId)).getOrNull() ?: return false
        return sg.isForum
    }

    suspend fun fetchForumTopics(chatId: Long): List<TelegramTopicItem> {
        val collected = mutableListOf<TelegramTopicItem>()
        var offsetDate = 0
        var offsetMessageId = 0L
        var offsetForumTopicId = 0

        while (true) {
            val result = core.sendQuery(
                TdApi.GetForumTopics(
                    chatId,
                    "",
                    offsetDate,
                    offsetMessageId,
                    offsetForumTopicId,
                    100
                )
            ).getOrNull() ?: break

            val topics = result.topics
            if (topics.isEmpty()) break

            topics.forEach { topic ->
                val info = topic.info
                val forumTopicId = info.forumTopicId
                val threadId = forumTopicId.toLong()
                val iconFileId = info.icon?.customEmojiId
                collected += TelegramTopicItem(
                    chatId = chatId,
                    messageThreadId = threadId,
                    topicId = threadId,
                    name = info.name.ifBlank { "Chủ đề #$forumTopicId" },
                    iconUrl = iconFileId?.takeIf { it != 0L }?.let { "tg_emoji://$it" },
                    trackCount = 0
                )
            }

            if (result.nextOffsetDate == 0 && result.nextOffsetForumTopicId == 0) break
            offsetDate = result.nextOffsetDate
            offsetMessageId = result.nextOffsetMessageId
            offsetForumTopicId = result.nextOffsetForumTopicId
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
        val response = core.sendQuery(
            TdApi.GetForumTopicHistory(chatId, forumTopicId, fromMessageId, 0, limit)
        ).getOrElse { err ->
            logger.log("TG_TOPICS", "WARN", "Lỗi lấy tin nhắn topic id=$forumTopicId: ${err.message}")
            return emptyList()
        }
        return response.messages.toList()
    }
}
