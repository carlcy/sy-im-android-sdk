package com.sy.im.sdk

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * SY IM 引擎（Android）。
 *
 * 公共 API 对齐腾讯 IM / ZEGO；底层封装 OpenIM Android SDK（Maven Central）：
 * - [init] → OpenIMClient.initSDK（[ImInitConfig.useMock]=false 时）
 * - [login] → OpenIMClient.login
 * - [logout] → OpenIMClient.logout
 * - [sendTextMessage] → createTextMessage + sendMessage
 * - [markConversationAsRead] → markConversationMessageAsRead（单聊同时发已读回执）
 * - [getUnreadCount] → getTotalUnreadMsgCount
 * - [setEventListener] → OnConnListener / OnAdvanceMsgListener / OnConversationListener
 *
 * 示例默认 [ImInitConfig.useMock]=true，无需真实 OpenIM；切 false 并配置
 * [ImInitConfig.imApiAddr]/[ImInitConfig.imWsAddr] 即可走真链路。
 */
class ImEngine private constructor(
    private val app: Application,
    private val config: ImInitConfig,
) {
    private var loggedIn: Boolean = false
    private var currentUserId: String? = null
    @Volatile
    private var eventListener: ImEventListener? = null
    private var openIm: OpenImBridge? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val useMock: Boolean get() = config.useMock
    fun appId(): String = config.appId
    fun apiBaseUrl(): String = config.apiBaseUrl
    fun isLoggedIn(): Boolean = loggedIn
    fun currentUserId(): String? = currentUserId

    /** 控制面 REST（getToken / friends / groups / send / history / revoke）。 */
    val controlPlane: ImControlPlane by lazy {
        ImControlPlane(config.apiBaseUrl, config.appId)
    }

    /**
     * 拉取 IM Token（控制面）。成功后可用返回的 imApiAddr/imWsAddr 配置 OpenIM。
     * 需先 [ImControlPlane.userJwt] 或 [ImControlPlane.appSecret]。
     */
    fun getToken(userId: String, callback: (Map<String, Any>?, Exception?) -> Unit) {
        controlPlane.getToken(userId, callback)
    }


    init {
        if (!config.useMock) {
            openIm = OpenImBridge(app, config) { eventListener }
            val ok = openIm?.initSdk() == true
            if (!ok) {
                Log.e(TAG, "OpenIM initSDK failed; login/send will error until fixed")
            }
        } else {
            Log.i(TAG, "ImEngine mock mode appId=${config.appId}")
        }
    }

    fun setEventListener(listener: ImEventListener?) {
        eventListener = listener
        Log.d(TAG, "setEventListener registered=${listener != null} mock=${config.useMock}")
    }

    /**
     * 登录。
     * @param userId 业务用户 ID
     * @param token IM User Token（服务端签发；mock 模式下任意非空即可）
     * @param callback 可选；OpenIM 为异步，建议传入。mock 模式会在主线程立即回调。
     */
    @JvmOverloads
    fun login(userId: String, token: String, callback: ImCallback? = null) {
        require(userId.isNotBlank()) { "userId blank" }
        require(token.isNotBlank()) { "token blank" }

        if (config.useMock) {
            currentUserId = userId
            loggedIn = true
            Log.i(TAG, "mock login ok userId=$userId")
            mainHandler.post {
                eventListener?.onConnectSuccess()
                callback?.onResult(true, 0, "mock-login-ok")
            }
            return
        }

        val bridge = openIm ?: run {
            callback?.onResult(false, -1, "OpenIM not initialized")
            return
        }
        bridge.login(userId, token, ImCallback { success, code, message ->
            if (success) {
                currentUserId = userId
                loggedIn = true
            }
            mainHandler.post { callback?.onResult(success, code, message) }
        })
    }

    @JvmOverloads
    fun logout(callback: ImCallback? = null) {
        if (config.useMock) {
            loggedIn = false
            currentUserId = null
            Log.i(TAG, "mock logout")
            mainHandler.post { callback?.onResult(true, 0, "mock-logout-ok") }
            return
        }
        openIm?.logout(ImCallback { success, code, message ->
            if (success) {
                loggedIn = false
                currentUserId = null
            }
            mainHandler.post { callback?.onResult(success, code, message) }
        }) ?: callback?.onResult(false, -1, "OpenIM not initialized")
    }

    /**
     * 发送文本。单聊 [userId]，群聊 [groupId]（二选一）。
     * @return clientMsgID（mock 为 stub_msg_*；OpenIM 为 createTextMessage 生成的 id）
     */
    fun sendTextMessage(
        userId: String? = null,
        groupId: String? = null,
        text: String,
    ): String {
        check(loggedIn) { "login required" }
        require(text.isNotBlank()) { "text blank" }
        require(!userId.isNullOrBlank() || !groupId.isNullOrBlank()) {
            "provide userId or groupId"
        }

        if (config.useMock) {
            val id = "stub_msg_${System.currentTimeMillis()}"
            Log.i(TAG, "mock sendText id=$id to user=$userId group=$groupId text=$text")
            // 可选：本地回显，方便 example 验证收消息 UI
            mainHandler.post {
                eventListener?.onRecvNewMessage(
                    msgId = "echo_$id",
                    fromUserId = currentUserId ?: "self",
                    groupId = groupId,
                    text = "[mock-echo] $text",
                )
            }
            return id
        }

        return openIm?.sendTextMessage(userId, groupId, text)
            ?: error("OpenIM not initialized")
    }

    /**
     * 将会话标为已读。单聊会向对端发送已读回执；群聊只清未读。
     * 传 [conversationId]，或传 [userId] / [groupId] 由 OpenIM 换算会话 ID。
     * 控制面 REST 等价接口是 [ImControlPlane.markConversationRead]（`POST /api/user/im/conversations/mark-read`）。
     */
    @JvmOverloads
    fun markConversationAsRead(
        conversationId: String? = null,
        userId: String? = null,
        groupId: String? = null,
        callback: ImCallback? = null,
    ) {
        check(loggedIn) { "login required" }
        require(!conversationId.isNullOrBlank() || !userId.isNullOrBlank() || !groupId.isNullOrBlank()) {
            "provide conversationId, userId, or groupId"
        }
        if (config.useMock) {
            Log.i(TAG, "mock markRead conversation=$conversationId user=$userId group=$groupId")
            mainHandler.post { callback?.onResult(true, 0, "mock-read-ok") }
            return
        }
        val bridge = openIm ?: run {
            callback?.onResult(false, -1, "OpenIM not initialized")
            return
        }
        val cid = when {
            !conversationId.isNullOrBlank() -> conversationId
            !groupId.isNullOrBlank() -> bridge.conversationId(groupId, group = true)
            else -> bridge.conversationId(userId!!, group = false)
        }
        bridge.markConversationAsRead(cid, ImCallback { success, code, message ->
            mainHandler.post { callback?.onResult(success, code, message) }
        })
    }

    /**
     * 按消息 ID 标记已读（单聊已读回执）。
     */
    fun markMessagesAsRead(
        conversationId: String,
        clientMsgIds: List<String>,
        callback: ImCallback? = null,
    ) {
        check(loggedIn) { "login required" }
        require(conversationId.isNotBlank()) { "conversationId blank" }
        require(clientMsgIds.isNotEmpty()) { "clientMsgIds empty" }
        if (config.useMock) {
            mainHandler.post { callback?.onResult(true, 0, "mock-read-ok") }
            return
        }
        val bridge = openIm ?: run {
            callback?.onResult(false, -1, "OpenIM not initialized")
            return
        }
        bridge.markMessagesAsRead(conversationId, clientMsgIds, ImCallback { success, code, message ->
            mainHandler.post { callback?.onResult(success, code, message) }
        })
    }

    /**
     * 当前登录用户的未读总数（OpenIM）。
     * 控制面 REST： [ImControlPlane.getUnreadCount] → `POST /api/user/im/unread`，响应 `totalUnread`。
     */
    fun getUnreadCount(callback: (count: Int?, error: Exception?) -> Unit) {
        if (config.useMock) {
            mainHandler.post { callback(0, null) }
            return
        }
        if (!loggedIn) {
            mainHandler.post { callback(null, IllegalStateException("login required")) }
            return
        }
        val bridge = openIm ?: run {
            mainHandler.post { callback(null, IllegalStateException("OpenIM not initialized")) }
            return
        }
        bridge.getTotalUnreadCount { count, code, message ->
            mainHandler.post {
                if (count != null) callback(count, null)
                else callback(null, Exception(message ?: "unread failed ($code)"))
            }
        }
    }

    companion object {
        private const val TAG = "SyImEngine"

        @Volatile
        private var instance: ImEngine? = null

        @JvmStatic
        fun init(context: Context, config: ImInitConfig): ImEngine {
            return instance ?: synchronized(this) {
                instance ?: ImEngine(context.applicationContext as Application, config).also {
                    instance = it
                }
            }
        }

        @JvmStatic
        @JvmOverloads
        fun init(
            context: Context,
            appId: String,
            apiBaseUrl: String,
            useMock: Boolean = true,
            imApiAddr: String = "http://127.0.0.1:10002",
            imWsAddr: String = "ws://127.0.0.1:10001",
        ): ImEngine = init(
            context,
            ImInitConfig(
                appId = appId,
                apiBaseUrl = apiBaseUrl,
                useMock = useMock,
                imApiAddr = imApiAddr,
                imWsAddr = imWsAddr,
            ),
        )

        @JvmStatic
        @Deprecated("Use init(context, appId, apiBaseUrl)", ReplaceWith("init(context, appId, apiBaseUrl)"))
        fun create(context: Context, appId: String, apiBaseUrl: String): ImEngine =
            init(context, appId, apiBaseUrl)

        @JvmStatic
        fun getInstance(): ImEngine? = instance

        /** 仅测试用：重置单例。 */
        @JvmStatic
        fun resetForTest() {
            synchronized(this) { instance = null }
        }
    }
}
