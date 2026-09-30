package com.sy.im.sdk

import org.json.JSONObject

/**
 * 表情回应（lite）。服务端 `POST /api/user/im/reaction` 发出的 Custom(110) 消息，
 * `data` 为 `{"sy":"reaction_lite","action":"add|remove","emoji":"👍","target":{"seq":..,"clientMsgId":..,"senderId":..}}`。
 * 三端（Android `ImReaction`、iOS `SyImReaction`、Flutter `SyImReaction`）解析规则相同。
 */
data class ImReaction(
    val emoji: String,
    val added: Boolean,
    val targetClientMsgId: String,
    val targetSeq: Long,
    val targetSenderId: String,
) {
    companion object {
        const val DESCRIPTION = "sy_reaction_lite"

        /** 解析自定义消息的 `data` 字符串；不是回应消息时返回 null。 */
        fun parse(customData: String?): ImReaction? {
            if (customData.isNullOrBlank()) return null
            val obj = try { JSONObject(customData) } catch (_: Exception) { return null }
            if (obj.optString("sy") != "reaction_lite") return null
            val emoji = obj.optString("emoji").trim()
            if (emoji.isEmpty()) return null
            val target = obj.optJSONObject("target") ?: JSONObject()
            return ImReaction(
                emoji = emoji,
                added = obj.optString("action", "add") != "remove",
                targetClientMsgId = target.optString("clientMsgId"),
                targetSeq = target.optLong("seq", 0),
                targetSenderId = target.optString("senderId"),
            )
        }
    }
}
