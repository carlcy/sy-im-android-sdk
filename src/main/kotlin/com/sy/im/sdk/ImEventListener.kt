package com.sy.im.sdk

/**
 * IM 事件回调（对齐腾讯/即构风格命名，映射 OpenIM OnConnListener / OnAdvanceMsgListener）。
 */
interface ImEventListener {
    fun onConnectSuccess() {}
    fun onConnectFailed(code: Int, error: String) {}
    fun onConnecting() {}
    fun onKickedOffline() {}
    fun onUserTokenExpired() {}

    /**
     * 收到新消息。
     * @param msgId 客户端消息 ID
     * @param fromUserId 发送方
     * @param groupId 群 ID（单聊为 null）
     * @param text 文本内容（非文本可为 null）
     */
    fun onRecvNewMessage(
        msgId: String,
        fromUserId: String,
        groupId: String?,
        text: String?,
    ) {}

    /**
     * 单聊已读回执。对方调用标记已读后触发。
     * @param readUserId 已读方用户 ID
     * @param msgIds 已读的客户端消息 ID
     */
    fun onRecvC2CReadReceipt(readUserId: String, msgIds: List<String>) {}

    /** 群已读回执（OpenIM 群回执；仅消除/更新已读，不宣称腾讯群回执全量对等）。 */
    fun onRecvGroupReadReceipt(conversationId: String) {}

    /** 全部会话未读总数变化。标记已读后会立刻回调，不等待 OpenIM 回包。 */
    fun onTotalUnreadCountChanged(count: Int) {}

    /** 单个会话未读数变化。 */
    fun onConversationUnreadChanged(conversationId: String, unreadCount: Int) {}

    /** 消息被撤回。 */
    fun onMessageRevoked(clientMsgId: String, revokerId: String) {}

    /** 对方正在输入。内容为 OpenIM 原样字符串。 */
    fun onTypingStatusChanged(data: String) {}
}
