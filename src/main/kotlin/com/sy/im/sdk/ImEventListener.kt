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
}
