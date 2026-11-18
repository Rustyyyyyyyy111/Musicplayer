package com.laughing.musicplayer.data.telegram

import com.laughing.musicplayer.core.model.TelegramChatItem
import com.laughing.musicplayer.core.model.TelegramChatType
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.data.local.TelegramDao
import com.laughing.musicplayer.data.local.TelegramSourceEntity
import com.laughing.musicplayer.data.local.TrackDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.drinkless.tdlib.TdApi

class TelegramChatBrowser(
    private val core: TelegramCore,
    private val forumTopics: TelegramForumTopics,
    private val telegramDao: TelegramDao,
    private val trackDao: TrackDao,
    private val logger: ActivityLogger
) {
    fun observeChats(): Flow<List<TelegramChatItem>> =
        telegramDao.observeSources().map { list -> list.map { it.toDomain() } }

    suspend fun refreshChatList(limit: Int = 200): Result<List<TelegramChatItem>> {
        core.sendQuery(TdApi.LoadChats(TdApi.ChatListMain(), limit))
        val chatsResult = core.sendQuery(TdApi.GetChats(TdApi.ChatListMain(), limit))
        val chatIds = chatsResult.getOrElse { err ->
            logger.log("TG_CHATS", "ERROR", "Không thể tải danh sách hội thoại: ${err.message}")
            return Result.failure(err)
        }.chatIds

        val meId = core.sendQuery(TdApi.GetMe()).getOrNull()?.id
        val existingMap = telegramDao.getSelectedSources().associateBy { it.chatId }
        val entities = mutableListOf<TelegramSourceEntity>()

        for (chatId in chatIds) {
            val chat = core.sendQuery(TdApi.GetChat(chatId)).getOrNull() ?: continue
            val (resolvedType, isForum) = classifyChatType(chat, meId)
            val prev = telegramDao.getSource(chatId)
            val topicsCount = if (isForum) {
                forumTopics.fetchForumTopics(chatId).size
            } else 0
            val distinctAudioCount = trackDao.countDistinctTelegramTracksForChat(chatId)

            val title = if (resolvedType == TelegramChatType.SAVED_MESSAGES) {
                "Tin nhắn đã lưu (Saved Messages)"
            } else {
                chat.title.ifBlank { "Hội thoại #$chatId" }
            }

            entities += TelegramSourceEntity(
                chatId = chatId,
                title = title,
                chatType = resolvedType.name,
                isForum = isForum,
                isSelected = prev?.isSelected ?: existingMap.containsKey(chatId),
                audioCount = distinctAudioCount,
                topicCount = if (isForum) topicsCount else 0,
                lastSyncedMessageId = prev?.lastSyncedMessageId ?: 0L,
                lastSyncedEpochMs = prev?.lastSyncedEpochMs ?: 0L
            )
        }

        if (entities.isNotEmpty()) {
            telegramDao.upsertSources(entities)
        }
        logger.log("TG_CHATS", "INFO", "Đã tải ${entities.size} hội thoại Telegram")
        return Result.success(entities.map { it.toDomain() })
    }

    suspend fun toggleChatSelection(chatId: Long, selected: Boolean) {
        telegramDao.setSelected(chatId, selected)
    }

    suspend fun selectAll() {
        telegramDao.setAllSelected(true)
    }

    suspend fun deselectAll() {
        telegramDao.setAllSelected(false)
    }

    private suspend fun classifyChatType(chat: TdApi.Chat, meId: Long?): Pair<TelegramChatType, Boolean> {
        return when (val type = chat.type) {
            is TdApi.ChatTypePrivate -> {
                if (meId != null && type.userId == meId) {
                    TelegramChatType.SAVED_MESSAGES to false
                } else {
                    TelegramChatType.PRIVATE_CHAT to false
                }
            }
            is TdApi.ChatTypeBasicGroup -> {
                val bg = core.sendQuery(TdApi.GetBasicGroup(type.basicGroupId)).getOrNull()
                val hasInvite = bg != null && bg.isActive
                if (hasInvite) TelegramChatType.PRIVATE_GROUP to false
                else TelegramChatType.PUBLIC_GROUP to false
            }
            is TdApi.ChatTypeSupergroup -> {
                val sg = core.sendQuery(TdApi.GetSupergroup(type.supergroupId)).getOrNull()
                val hasUsername = sg?.usernames?.activeUsernames?.isNotEmpty() == true
                when {
                    sg?.isForum == true -> TelegramChatType.FORUM_GROUP to true
                    type.isChannel -> {
                        if (hasUsername) TelegramChatType.PUBLIC_CHANNEL to false
                        else TelegramChatType.PRIVATE_CHANNEL to false
                    }
                    else -> {
                        if (hasUsername) TelegramChatType.PUBLIC_GROUP to false
                        else TelegramChatType.SUPERGROUP to false
                    }
                }
            }
            is TdApi.ChatTypeSecret -> TelegramChatType.PRIVATE_CHAT to false
            else -> TelegramChatType.PRIVATE_CHAT to false
        }
    }
}
