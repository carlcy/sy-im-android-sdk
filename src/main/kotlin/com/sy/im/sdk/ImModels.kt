package com.sy.im.sdk

/** 未读快照。总数和每个会话同时给出，供会话页徽章刷新。 */
data class UnreadSnapshot(
    val total: Int,
    val conversations: List<ImConversationUnread>,
)

data class ImConversationUnread(
    val conversationId: String,
    val unreadCount: Int,
    val userId: String? = null,
    val groupId: String? = null,
)

/**
 * 会话摘要。`doNotDisturb` 对应 OpenIM `recvMsgOpt != 0`（1 不接收，2 接收但不提醒）。
 */
data class ImConversation(
    val conversationId: String,
    val userId: String?,
    val groupId: String?,
    val showName: String?,
    val unreadCount: Int,
    val pinned: Boolean,
    val draft: String?,
    val doNotDisturb: Boolean,
)

data class ImSearchHit(
    val conversationId: String,
    val showName: String?,
    val messageCount: Int,
    val preview: String?,
)

/** 未读变化。OpenIM 回调和标记已读都会走到这里。 */
fun interface ImUnreadListener {
    fun onUnreadChanged(totalUnread: Int, conversations: List<ImConversationUnread>)
}
