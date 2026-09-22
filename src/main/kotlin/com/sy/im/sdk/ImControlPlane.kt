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
 * - friends / groups / send / history / revoke → /api/user/im/*
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

    // ---- internals ----

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
        val jwt = userJwt ?: throw Exception("userJwt required for /api/user/im/*")
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
        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (code !in 200..299 || json.optInt("code", if (code in 200..299) 0 else -1) != 0) {
            throw Exception(json.optString("msg", "HTTP $code $text"))
        }
        val data = json.opt("data")
        return when (data) {
            is JSONObject -> data.keys().asSequence().associateWith { data.get(it) }
            null -> emptyMap()
            else -> mapOf("value" to data)
        }
    }

    companion object {
        private const val TAG = "ImControlPlane"
    }
}
