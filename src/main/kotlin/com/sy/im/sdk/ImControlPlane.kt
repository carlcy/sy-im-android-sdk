package com.sy.im.sdk

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * SY IM 控制面 REST 封装（非 OpenIM 原生 SDK）。
 *
 * - getToken → POST /api/user/im/token 或 /api/server/im/token
 * - friends / groups / send / history / revoke / conversations / unread → /api/user/im/…
 *
 * 实时收发仍依赖 OpenIM Android SDK（见 [OpenImBridge]）；本类负责控制面运维 API。
 * 不宣称腾讯云 TIM 全 API 对等。
 */
class ImControlPlane(
    private val apiBaseUrl: String,
    private val appId: String,
) {
    private val base = apiBaseUrl.trim().trimEnd('/')
    private val executor = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    /** User JWT（控制台路径） */
    @Volatile var userJwt: String? = null
    /** AppSecret（服务端路径；勿在生产客户端长期硬编码） */
    @Volatile var appSecret: String? = null

    /**
     * 拉取 IM Token。
     * 优先 User JWT → POST /api/user/im/token；否则 AppSecret → POST /api/server/im/token。
     * 返回 Map 含 token / imApiAddr / imWsAddr / openImUserId 等（以服务端为准）。
     */
    fun getToken(userId: String, callback: (Map<String, Any>?, Exception?) -> Unit) {
        executor.execute {
            try {
                val jwt = userJwt
                val secret = appSecret
                val (path, headers, body) = when {
                    !jwt.isNullOrBlank() -> Triple(
                        "/api/user/im/token",
                        mapOf(
                            "Authorization" to "Bearer $jwt",
                            "Content-Type" to "application/json",
                            "X-App-Id" to appId,
                        ),
                        JSONObject().put("appId", appId).put("userId", userId).toString(),
                    )
                    !secret.isNullOrBlank() -> Triple(
                        "/api/server/im/token",
                        mapOf(
                            "Content-Type" to "application/json",
                            "X-App-Id" to appId,
                            "X-App-Secret" to secret,
                        ),
                        JSONObject().put("appId", appId).put("userId", userId).toString(),
                    )
                    else -> throw Exception("set userJwt or appSecret before getToken")
                }
                val data = postJson(path, headers, body)
                main.post { callback(data, null) }
            } catch (e: Exception) {
                Log.e(TAG, "getToken failed", e)
                main.post { callback(null, e) }
            }
        }
    }

    fun addFriend(fromUserId: String, toUserId: String, reqMsg: String = "", callback: (Boolean, Exception?) -> Unit) =
        postUser("/api/user/im/friends/add", JSONObject()
            .put("appId", appId).put("fromUserId", fromUserId).put("toUserId", toUserId).put("reqMsg", reqMsg), callback)

    fun listFriends(ownerUserId: String, callback: (JSONArray?, Exception?) -> Unit) =
        postUserData("/api/user/im/friends/list", JSONObject().put("appId", appId).put("ownerUserId", ownerUserId), callback)

    fun createGroup(ownerUserId: String, groupName: String, memberUserIds: List<String> = emptyList(), callback: (Map<String, Any>?, Exception?) -> Unit) {
        val arr = JSONArray()
        memberUserIds.forEach { arr.put(it) }
        postUserMap("/api/user/im/groups/create", JSONObject()
            .put("appId", appId).put("ownerUserId", ownerUserId).put("groupName", groupName).put("memberUserIds", arr), callback)
    }

    fun listGroups(ownerUserId: String, callback: (JSONArray?, Exception?) -> Unit) =
        postUserData("/api/user/im/groups/list", JSONObject().put("appId", appId).put("ownerUserId", ownerUserId), callback)

    /** 控制面代发（OpenIM admin）；端侧实时发送优先用 [ImEngine.sendTextMessage]。 */
    fun send(
        fromUserId: String,
        toUserId: String? = null,
        groupId: String? = null,
        contentType: String = "text",
        content: Any,
        callback: (Map<String, Any>?, Exception?) -> Unit,
    ) {
        val body = JSONObject().put("appId", appId).put("fromUserId", fromUserId).put("contentType", contentType)
        if (!toUserId.isNullOrBlank()) body.put("toUserId", toUserId)
        if (!groupId.isNullOrBlank()) body.put("groupId", groupId)
        when (content) {
            is String -> body.put("content", content)
            is JSONObject -> body.put("content", content)
            is Map<*, *> -> body.put("content", JSONObject(content))
            else -> body.put("content", content.toString())
        }
        postUserMap("/api/user/im/send", body, callback)
    }

    fun history(
        userId: String,
        conversationId: String? = null,
        peerUserId: String? = null,
        groupId: String? = null,
        count: Int = 20,
        callback: (JSONArray?, Exception?) -> Unit,
    ) {
        val body = JSONObject().put("appId", appId).put("userId", userId).put("count", count)
        conversationId?.let { body.put("conversationId", it) }
        peerUserId?.let { body.put("peerUserId", it) }
        groupId?.let { body.put("groupId", it) }
        postUserData("/api/user/im/messages/history", body, callback)
    }

    fun revoke(
        userId: String,
        conversationId: String,
        seq: Long,
        callback: (Boolean, Exception?) -> Unit,
    ) = postUser(
        "/api/user/im/messages/revoke",
        JSONObject().put("appId", appId).put("userId", userId).put("conversationId", conversationId).put("seq", seq),
        callback,
    )

    /** 会话列表。`POST /api/user/im/conversations`。 */
    fun listConversations(userId: String, callback: (JSONArray?, Exception?) -> Unit) =
        postUserData(
            "/api/user/im/conversations",
            JSONObject().put("appId", appId).put("userId", userId),
            callback,
        )

    /**
     * 控制面标记已读。`POST /api/user/im/conversations/mark-read`。
     * 实时已读回执优先用 [ImEngine.markConversationAsRead]（需已登录 OpenIM）。
     */
    fun markConversationRead(
        userId: String,
        conversationId: String? = null,
        peerUserId: String? = null,
        groupId: String? = null,
        callback: (Boolean, Exception?) -> Unit,
    ) {
        val body = JSONObject().put("appId", appId).put("userId", userId)
        if (!conversationId.isNullOrBlank()) body.put("conversationId", conversationId)
        if (!peerUserId.isNullOrBlank()) body.put("peerUserId", peerUserId)
        if (!groupId.isNullOrBlank()) body.put("groupId", groupId)
        postUser("/api/user/im/conversations/mark-read", body, callback)
    }

    /**
     * 未读总数。`POST /api/user/im/unread`，body `{ appId, userId, mode:"auto" }`，读 `data.totalUnread`。
     */
    fun getUnreadCount(userId: String, callback: (Int?, Exception?) -> Unit) {
        postUserMap(
            "/api/user/im/unread",
            JSONObject().put("appId", appId).put("userId", userId).put("mode", "auto"),
        ) { data, err ->
            if (err != null) {
                callback(null, err)
                return@postUserMap
            }
            val count = when (val raw = data?.get("totalUnread")) {
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull()
                else -> null
            }
            if (count == null) callback(null, Exception("missing totalUnread"))
            else callback(count, null)
        }
    }

    /** 删除好友。`POST /api/user/im/friends/delete`。 */
    fun deleteFriend(ownerUserId: String, friendUserId: String, callback: (Boolean, Exception?) -> Unit) =
        postUser(
            "/api/user/im/friends/delete",
            JSONObject().put("appId", appId).put("ownerUserId", ownerUserId).put("friendUserId", friendUserId),
            callback,
        )

    /** 收到的好友申请。`POST /api/user/im/friends/apply/list`。 */
    fun listFriendApplications(userId: String, callback: (JSONArray?, Exception?) -> Unit) =
        postUserData(
            "/api/user/im/friends/apply/list",
            JSONObject().put("appId", appId).put("userId", userId),
            callback,
        )

    /** 同意好友申请。`POST /api/user/im/friends/apply/accept`。 */
    fun acceptFriendApplication(
        fromUserId: String,
        toUserId: String,
        handleMsg: String = "",
        callback: (Boolean, Exception?) -> Unit,
    ) = postUser(
        "/api/user/im/friends/apply/accept",
        JSONObject().put("appId", appId).put("fromUserId", fromUserId).put("toUserId", toUserId).put("handleMsg", handleMsg),
        callback,
    )

    /** 拒绝好友申请。`POST /api/user/im/friends/apply/refuse`。 */
    fun refuseFriendApplication(
        fromUserId: String,
        toUserId: String,
        handleMsg: String = "",
        callback: (Boolean, Exception?) -> Unit,
    ) = postUser(
        "/api/user/im/friends/apply/refuse",
        JSONObject().put("appId", appId).put("fromUserId", fromUserId).put("toUserId", toUserId).put("handleMsg", handleMsg),
        callback,
    )

    /** 群成员。`POST /api/user/im/groups/members`。 */
    fun listGroupMembers(groupId: String, callback: (JSONArray?, Exception?) -> Unit) =
        postUserData(
            "/api/user/im/groups/members",
            JSONObject().put("appId", appId).put("groupId", groupId),
            callback,
        )

    /** 邀请入群。`POST /api/user/im/groups/invite`。 */
    fun inviteGroupMembers(
        groupId: String,
        userIds: List<String>,
        reason: String = "",
        callback: (Boolean, Exception?) -> Unit,
    ) = postUser(
        "/api/user/im/groups/invite",
        JSONObject().put("appId", appId).put("groupId", groupId).put("userIds", jsonArray(userIds)).put("reason", reason),
        callback,
    )

    /** 踢出群成员。`POST /api/user/im/groups/kick`。 */
    fun kickGroupMembers(
        groupId: String,
        userIds: List<String>,
        reason: String = "",
        callback: (Boolean, Exception?) -> Unit,
    ) = postUser(
        "/api/user/im/groups/kick",
        JSONObject().put("appId", appId).put("groupId", groupId).put("userIds", jsonArray(userIds)).put("reason", reason),
        callback,
    )

    /** 群禁言。`POST /api/user/im/groups/mute`。`mute=true` 开启全员禁言。 */
    fun muteGroup(groupId: String, mute: Boolean, callback: (Boolean, Exception?) -> Unit) =
        postUser(
            "/api/user/im/groups/mute",
            JSONObject().put("appId", appId).put("groupId", groupId).put("mute", mute),
            callback,
        )

    // ---- 表情回应（lite）与会话标签 ----

    /**
     * 对一条消息加 / 取消表情回应。`POST /api/user/im/reaction`。
     *
     * 服务端以 Custom(110) 消息发出（`data` 里 `sy=reaction_lite`），对端按普通自定义消息收到，
     * 用 [ImReaction.parse] 解析。这不是 OpenIM 原生回应接口，也没有服务端聚合计数。
     * 单聊传 [toUserId]，群聊传 [groupId]；目标消息用 [targetClientMsgId] 或 [targetSeq] 指定。
     */
    fun reactToMessage(
        fromUserId: String,
        emoji: String,
        toUserId: String? = null,
        groupId: String? = null,
        targetClientMsgId: String? = null,
        targetSeq: Long = 0,
        targetSenderId: String? = null,
        add: Boolean = true,
        callback: (Map<String, Any>?, Exception?) -> Unit,
    ) = postUserMap(
        "/api/user/im/reaction",
        reactionBody(appId, fromUserId, emoji, toUserId, groupId, targetClientMsgId, targetSeq, targetSenderId, add),
        callback,
    )

    /**
     * 控制面已读花名册。`POST /api/user/im/messages/who-read`，body `{appId, conversationId, seq}`。
     * 返回去重后的已读者 uid（最近已读在前）。花名册只含调用过 [reportGroupMessagesRead] 的成员。
     */
    fun whoRead(conversationId: String, seq: Long, callback: (List<String>?, Exception?) -> Unit) {
        val body = JSONObject().put("appId", appId).put("conversationId", conversationId)
        if (seq > 0) body.put("seq", seq)
        executor.execute {
            try {
                val data = postJson("/api/user/im/messages/who-read", authHeaders(), body.toString())
                val arr = data?.get("list") as? JSONArray ?: JSONArray()
                val rows = (0 until arr.length()).mapNotNull { i ->
                    (arr.opt(i) as? JSONObject)?.let { o -> o.keys().asSequence().associateWith { k -> o.opt(k) } }
                }
                val readers = ImReadReceipts.readersFromWhoRead(rows)
                main.post { callback(readers, null) }
            } catch (e: Exception) {
                main.post { callback(null, e) }
            }
        }
    }

    /**
     * 把本端已读的群消息写入控制面花名册（供 [whoRead] / [ImEngine.getGroupMessageReadInfo]）。
     * `POST /api/user/im/conversations/mark-read`，`mode=msgs` + `seqs`；服务端同时向 OpenIM 标记这些消息已读。
     */
    fun reportGroupMessagesRead(
        userId: String,
        conversationId: String,
        seqs: List<Long>,
        callback: (Boolean, Exception?) -> Unit,
    ) {
        val body = JSONObject().put("appId", appId).put("userId", userId)
            .put("conversationId", conversationId).put("mode", "msgs").put("seqs", JSONArray(seqs.filter { it > 0 }))
        postUser("/api/user/im/conversations/mark-read", body, callback)
    }

    /** 新建会话标签（每个用户自己的分组，服务端 DB）。`POST /api/user/im/conversations/tags/create`。返回 `tag`。 */
    fun createConversationTag(
        ownerUserId: String,
        name: String,
        color: String = "",
        remark: String = "",
        callback: (Map<String, Any>?, Exception?) -> Unit,
    ) = postUserMap(
        "/api/user/im/conversations/tags/create",
        JSONObject().put("appId", appId).put("ownerUserId", ownerUserId)
            .put("name", name).put("color", color).put("remark", remark),
        callback,
    )

    /** 列出会话标签，每项含 `id` / `name` / `memberCount` / `members`（会话 id，最多 200）。 */
    fun listConversationTags(ownerUserId: String, callback: (JSONArray?, Exception?) -> Unit) =
        postUserData(
            "/api/user/im/conversations/tags/list",
            JSONObject().put("appId", appId).put("ownerUserId", ownerUserId),
            callback,
        )

    /** 删除会话标签。 */
    fun deleteConversationTag(ownerUserId: String, tagId: Long, callback: (Boolean, Exception?) -> Unit) =
        postUser(
            "/api/user/im/conversations/tags/delete",
            JSONObject().put("appId", appId).put("ownerUserId", ownerUserId).put("tagId", tagId),
            callback,
        )

    /** 把会话加入标签。 */
    fun addConversationsToTag(ownerUserId: String, tagId: Long, conversationIds: List<String>, callback: (Boolean, Exception?) -> Unit) =
        postUser("/api/user/im/conversations/tags/members", tagMembersBody(appId, ownerUserId, tagId, "add", conversationIds), callback)

    /** 把会话移出标签。 */
    fun removeConversationsFromTag(ownerUserId: String, tagId: Long, conversationIds: List<String>, callback: (Boolean, Exception?) -> Unit) =
        postUser("/api/user/im/conversations/tags/members", tagMembersBody(appId, ownerUserId, tagId, "remove", conversationIds), callback)

    // ---- internals ----

    private fun jsonArray(values: List<String>): JSONArray {
        val arr = JSONArray()
        values.forEach { arr.put(it) }
        return arr
    }

    private fun postUser(path: String, body: JSONObject, callback: (Boolean, Exception?) -> Unit) {
        executor.execute {
            try {
                postJson(path, authHeaders(), body.toString())
                main.post { callback(true, null) }
            } catch (e: Exception) {
                main.post { callback(false, e) }
            }
        }
    }

    private fun postUserMap(path: String, body: JSONObject, callback: (Map<String, Any>?, Exception?) -> Unit) {
        executor.execute {
            try {
                val data = postJson(path, authHeaders(), body.toString())
                main.post { callback(data, null) }
            } catch (e: Exception) {
                main.post { callback(null, e) }
            }
        }
    }

    private fun postUserData(path: String, body: JSONObject, callback: (JSONArray?, Exception?) -> Unit) {
        executor.execute {
            try {
                val data = postJson(path, authHeaders(), body.toString())
                val list = when (val raw = data?.get("list") ?: data?.get("friends") ?: data?.get("groups") ?: data?.get("messages")) {
                    is JSONArray -> raw
                    is List<*> -> JSONArray(raw)
                    else -> (data?.get("data") as? JSONArray) ?: JSONArray()
                }
                // Also accept whole data as array-ish via "list"
                val arr = when {
                    data == null -> JSONArray()
                    data["list"] is JSONArray -> data["list"] as JSONArray
                    data["data"] is JSONArray -> data["data"] as JSONArray
                    else -> {
                        // wrap map values if server returns list under unknown key — return empty rather than lie
                        val candidate = data.values.firstOrNull { it is JSONArray } as? JSONArray
                        candidate ?: JSONArray().also {
                            // put raw map as single element for debug
                        }
                    }
                }
                main.post { callback(arr, null) }
            } catch (e: Exception) {
                main.post { callback(null, e) }
            }
        }
    }

    private fun authHeaders(): Map<String, String> {
        val jwt = userJwt ?: throw Exception("userJwt required for /api/user/im/…")
        return mapOf(
            "Authorization" to "Bearer $jwt",
            "Content-Type" to "application/json",
            "X-App-Id" to appId,
        )
    }

    private fun postJson(path: String, headers: Map<String, String>, body: String): Map<String, Any>? {
        val url = URL("$base$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
        val json = parseBody(text)
        checkResponse(code, json, text)
        val data = json.opt("data")
        return when (data) {
            is JSONObject -> data.keys().asSequence().associateWith { data.get(it) }
            null -> emptyMap()
            else -> mapOf("value" to data)
        }
    }

    companion object {
        private const val TAG = "ImControlPlane"

        internal fun reactionBody(
            appId: String, fromUserId: String, emoji: String, toUserId: String?, groupId: String?,
            targetClientMsgId: String?, targetSeq: Long, targetSenderId: String?, add: Boolean,
        ): JSONObject {
            val body = JSONObject().put("appId", appId).put("fromUserId", fromUserId)
                .put("emoji", emoji).put("action", if (add) "add" else "remove")
            if (!groupId.isNullOrBlank()) body.put("groupId", groupId) else body.put("toUserId", toUserId ?: "")
            if (!targetClientMsgId.isNullOrBlank()) body.put("targetClientMsgId", targetClientMsgId)
            if (targetSeq > 0) body.put("targetSeq", targetSeq)
            if (!targetSenderId.isNullOrBlank()) body.put("targetSenderId", targetSenderId)
            return body
        }

        internal fun tagMembersBody(appId: String, ownerUserId: String, tagId: Long, action: String, ids: List<String>): JSONObject =
            JSONObject().put("appId", appId).put("ownerUserId", ownerUserId).put("tagId", tagId)
                .put("action", action).put("conversationIds", JSONArray(ids))

        internal fun parseBody(text: String): JSONObject =
            if (text.isBlank()) JSONObject() else try { JSONObject(text) } catch (_: Exception) { JSONObject() }

        /** 非 2xx 或业务码非 0 时抛 [ImControlPlaneException]。 */
        internal fun checkResponse(httpStatus: Int, json: JSONObject, rawText: String) {
            val ok = httpStatus in 200..299
            val biz = if (json.has("code")) json.optInt("code", -1) else if (ok) 0 else httpStatus
            if (ok && biz == 0) return
            val msg = json.optString("msg").ifBlank { "HTTP $httpStatus ${rawText.take(200)}".trim() }
            throw ImControlPlaneException(biz, httpStatus, msg)
        }
    }
}
