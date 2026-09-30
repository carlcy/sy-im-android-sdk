package com.sy.im.sdk

/**
 * 已读回执（单聊 + 群聊统一）。三端字段、语义相同（iOS `SyImReadReceipt`、Flutter `SyImReadReceipt`）：
 *
 * - [conversationId]：OpenIM 会话 id（单聊 `si_<小 uid>_<大 uid>`，群 `sg_<groupId>`）；推导不出时为空串。
 * - [userId]：已读方。
 * - [groupId]：群聊为群 id，单聊为 null。
 * - [msgIds]：该已读方本次已读的客户端消息 id。
 * - [readTime]：毫秒；OpenIM 没给时为 0。
 */
data class ImReadReceipt(
    val conversationId: String,
    val userId: String,
    val groupId: String?,
    val msgIds: List<String>,
    val readTime: Long = 0,
) {
    val isGroup: Boolean get() = !groupId.isNullOrEmpty()
}

/**
 * 群消息已读概况。三端相同。
 *
 * - [readUserIds]：已读成员；[source] 说明来源：
 *   `openim`（OpenIM 返回了成员）、`controlPlane`（OpenIM 只有人数，成员来自控制面 who-read 花名册）、`none`（只有人数）。
 * - 控制面花名册只含调用过 `reportGroupMessagesRead`（mark-read 带 seqs）的成员，不是全员已读名单。
 */
data class ImGroupReadInfo(
    val clientMsgId: String,
    val hasReadCount: Int,
    val unreadCount: Int,
    val readUserIds: List<String>,
    val source: String,
) {
    companion object {
        const val SOURCE_OPENIM = "openim"
        const val SOURCE_CONTROL_PLANE = "controlPlane"
        const val SOURCE_NONE = "none"
    }
}

/** 已读回执的纯逻辑（可单测），三端规则相同。 */
object ImReadReceipts {
    fun singleConversationId(a: String, b: String): String =
        if (a.isEmpty() || b.isEmpty()) "" else "si_" + listOf(a, b).sorted().joinToString("_")

    fun groupConversationId(groupId: String): String = if (groupId.isEmpty()) "" else "sg_$groupId"

    /** `sg_xxx` → `xxx`；不是群会话返回 null。 */
    fun groupIdOf(conversationId: String): String? =
        conversationId.takeIf { it.startsWith("sg_") && it.length > 3 }?.substring(3)

    /**
     * Android OpenIM 的群回执是「每条消息 → 已读成员」，转成与 iOS 相同的「每个已读者 → 消息 id」。
     * 已读者按首次出现的顺序。
     */
    fun perReader(conversationId: String, perMessage: List<Pair<String, List<String>>>): List<ImReadReceipt> {
        val byReader = LinkedHashMap<String, MutableList<String>>()
        for ((msgId, readers) in perMessage) {
            if (msgId.isEmpty()) continue
            for (r in readers) {
                if (r.isEmpty()) continue
                val list = byReader.getOrPut(r) { mutableListOf() }
                if (msgId !in list) list.add(msgId)
            }
        }
        val gid = groupIdOf(conversationId)
        return byReader.map { (uid, ids) -> ImReadReceipt(conversationId, uid, gid, ids) }
    }

    /** 控制面 `who-read` 响应 `list[].readerUid` → 去重后的已读者（保持服务端顺序：最近已读在前）。 */
    fun readersFromWhoRead(list: List<Map<String, Any?>>): List<String> =
        list.mapNotNull { (it["readerUid"] as? String)?.takeIf { s -> s.isNotEmpty() } }.distinct()

    /**
     * 合并 OpenIM 与控制面：OpenIM 有成员 id 就用 OpenIM；否则用控制面花名册（[roster] 为 null 表示没查）。
     * 花名册人数多于 OpenIM 人数时以花名册为准，未读相应减少（不小于 0）。
     */
    fun merge(
        clientMsgId: String,
        hasReadCount: Int,
        unreadCount: Int,
        openImReaders: List<String>,
        roster: List<String>?,
    ): ImGroupReadInfo {
        if (openImReaders.isNotEmpty()) {
            return ImGroupReadInfo(clientMsgId, maxOf(hasReadCount, openImReaders.size), unreadCount, openImReaders, ImGroupReadInfo.SOURCE_OPENIM)
        }
        if (!roster.isNullOrEmpty()) {
            val read = maxOf(hasReadCount, roster.size)
            val unread = (unreadCount - (read - hasReadCount)).coerceAtLeast(0)
            return ImGroupReadInfo(clientMsgId, read, unread, roster, ImGroupReadInfo.SOURCE_CONTROL_PLANE)
        }
        return ImGroupReadInfo(clientMsgId, hasReadCount, unreadCount, emptyList(), ImGroupReadInfo.SOURCE_NONE)
    }
}
