package com.sy.im.sdk

import org.json.JSONObject

/**
 * 对方输入状态（OpenIM `onConversationUserInputStatusChanged`）。
 *
 * OpenIM 推的是「该用户当前在哪些端处于输入中」：`platformIds` 非空即正在输入，空列表表示停止。
 * 字段与 iOS `SyImTypingStatus`、Flutter `SyImTypingStatus` 相同。
 */
data class ImTypingStatus(
    val conversationId: String,
    val userId: String,
    val platformIds: List<Int>,
) {
    val typing: Boolean get() = platformIds.isNotEmpty()

    companion object {
        /** 解析 OpenIM 原样 JSON。不是对象或缺 userID 时返回 null。 */
        @JvmStatic
        fun parse(json: String?): ImTypingStatus? {
            if (json.isNullOrBlank()) return null
            val obj = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            val userId = obj.optString("userID", "")
            if (userId.isEmpty()) return null
            val arr = obj.optJSONArray("platformIDs")
            val platforms = buildList {
                if (arr != null) for (i in 0 until arr.length()) add(arr.optInt(i))
            }
            return ImTypingStatus(obj.optString("conversationID", ""), userId, platforms)
        }
    }
}
