package com.sy.im.sdk

/**
 * 未读计数。会话回调只更新对应会话；总数以服务端回调或本地增量为准，避免用「部分会话列表」把总数算重。
 * [markRead] 会立刻把该会话未读清零并扣减总数，不等待 OpenIM 回包。
 */
internal class UnreadTracker {
    private val rows = LinkedHashMap<String, ImConversationUnread>()
    private var total: Int = 0

    fun snapshot(): UnreadSnapshot = UnreadSnapshot(total, rows.values.toList())

    fun upsert(items: List<ImConversation>): UnreadSnapshot {
        for (item in items) {
            if (item.conversationId.isBlank()) continue
            rows[item.conversationId] = ImConversationUnread(
                conversationId = item.conversationId,
                unreadCount = item.unreadCount.coerceAtLeast(0),
                userId = item.userId,
                groupId = item.groupId,
            )
        }
        return snapshot()
    }

    fun applyServerTotal(count: Int): UnreadSnapshot {
        total = count.coerceAtLeast(0)
        return snapshot()
    }

    fun markRead(conversationId: String): UnreadSnapshot {
        val prev = rows[conversationId]
        val old = prev?.unreadCount ?: 0
        rows[conversationId] = ImConversationUnread(
            conversationId = conversationId,
            unreadCount = 0,
            userId = prev?.userId,
            groupId = prev?.groupId,
        )
        total = (total - old).coerceAtLeast(0)
        return snapshot()
    }

    fun incoming(conversationId: String, userId: String?, groupId: String?): UnreadSnapshot {
        val prev = rows[conversationId]
        rows[conversationId] = ImConversationUnread(
            conversationId = conversationId,
            unreadCount = (prev?.unreadCount ?: 0) + 1,
            userId = userId ?: prev?.userId,
            groupId = groupId ?: prev?.groupId,
        )
        total += 1
        return snapshot()
    }
}
