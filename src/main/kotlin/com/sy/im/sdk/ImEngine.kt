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
    @Volatile
    private var unreadListener: ImUnreadListener? = null
    private var openIm: OpenImBridge? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracker = UnreadTracker()
    private val local = ImLocalSession { publish(it) }
    private var lastUnreadById: Map<String, Int> = emptyMap()

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
            openIm = OpenImBridge(
                app,
                config,
                { eventListener },
                { publish(tracker.upsert(it)) },
                { publish(tracker.applyServerTotal(it)) },
            )
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

    /** 未读总数和每个会话。OpenIM 会话监听、总数监听，以及标记已读都会立刻回调。 */
    fun setUnreadListener(listener: ImUnreadListener?) {
        unreadListener = listener
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
            publish(local.snapshot())
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
                refreshUnread(null)
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
            val id = local.sendText(userId, groupId, text)
            Log.i(TAG, "mock sendText id=$id to user=$userId group=$groupId text=$text")
            mainHandler.post {
                eventListener?.onRecvNewMessage(
                    msgId = "echo_$id",
                    fromUserId = userId ?: currentUserId ?: "self",
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
        val cid = resolveConversationId(conversationId, userId, groupId)
        if (config.useMock) {
            Log.i(TAG, "mock markRead conversation=$cid")
            local.markRead(cid)
            mainHandler.post { callback?.onResult(true, 0, "mock-read-ok") }
            return
        }
        publish(tracker.markRead(cid))
        val bridge = openIm ?: run {
            callback?.onResult(false, -1, "OpenIM not initialized")
            return
        }
        bridge.markConversationAsRead(cid, ImCallback { success, code, message ->
            refreshUnread(cid)
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
            local.markRead(conversationId)
            mainHandler.post { callback?.onResult(true, 0, "mock-read-ok") }
            return
        }
        publish(tracker.markRead(conversationId))
        val bridge = openIm ?: run {
            callback?.onResult(false, -1, "OpenIM not initialized")
            return
        }
        bridge.markMessagesAsRead(conversationId, clientMsgIds, ImCallback { success, code, message ->
            refreshUnread(conversationId)
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
            if (count != null) publish(tracker.applyServerTotal(count))
            mainHandler.post {
                if (count != null) callback(count, null)
                else callback(null, Exception(message ?: "unread failed ($code)"))
            }
        }
    }

    /** 撤回消息。OpenIM `revokeMessageV2`。 */
    fun revokeMessage(conversationId: String, clientMsgId: String, callback: ImCallback? = null) {
        check(loggedIn) { "login required" }
        require(conversationId.isNotBlank()) { "conversationId blank" }
        require(clientMsgId.isNotBlank()) { "clientMsgId blank" }
        if (config.useMock) {
            val ok = local.revoke(conversationId, clientMsgId)
            mainHandler.post {
                if (ok) {
                    eventListener?.onMessageRevoked(clientMsgId, currentUserId ?: "")
                    callback?.onResult(true, 0, "mock-revoke-ok")
                } else {
                    callback?.onResult(false, -1, "revoke rejected")
                }
            }
            return
        }
        openIm?.revokeMessage(conversationId, clientMsgId, ImCallback { success, code, message ->
            mainHandler.post { callback?.onResult(success, code, message) }
        }) ?: callback?.onResult(false, -1, "OpenIM not initialized")
    }

    /**
     * 群 @ 文本。 [atAll] 为 true 时附带 OpenIM atAllTag。
     * @return clientMsgID
     */
    fun sendAtTextMessage(
        groupId: String,
        text: String,
        atUserIds: List<String> = emptyList(),
        atAll: Boolean = false,
    ): String {
        check(loggedIn) { "login required" }
        require(groupId.isNotBlank()) { "groupId blank" }
        require(text.isNotBlank()) { "text blank" }
        require(atUserIds.isNotEmpty() || atAll) { "atUserIds empty" }
        if (config.useMock) return local.sendAt(groupId, text, atUserIds, atAll)
        return openIm?.sendAtTextMessage(groupId, text, atUserIds, atAll)
            ?: error("OpenIM not initialized")
    }

    /** 自定义消息。OpenIM `createCustomMessage`。 */
    fun sendCustomMessage(
        userId: String? = null,
        groupId: String? = null,
        data: String,
        extension: String = "",
        description: String = "",
    ): String {
        check(loggedIn) { "login required" }
        require(data.isNotBlank()) { "data blank" }
        require(!userId.isNullOrBlank() || !groupId.isNullOrBlank()) { "provide userId or groupId" }
        if (config.useMock) return local.sendCustom(userId, groupId, data, description)
        return openIm?.sendCustomMessage(userId, groupId, data, extension, description)
            ?: error("OpenIM not initialized")
    }

    /** 本地消息搜索。OpenIM `searchLocalMessages`。 */
    fun searchLocalMessages(
        keyword: String,
        conversationId: String? = null,
        callback: (List<ImSearchHit>?, Exception?) -> Unit,
    ) {
        require(keyword.isNotBlank()) { "keyword blank" }
        if (config.useMock) {
            mainHandler.post { callback(local.search(keyword, conversationId), null) }
            return
        }
        openIm?.searchLocalMessages(keyword, conversationId) { hits, code, message ->
            mainHandler.post {
                if (hits != null) callback(hits, null)
                else callback(null, Exception(message ?: "search failed ($code)"))
            }
        } ?: mainHandler.post { callback(null, IllegalStateException("OpenIM not initialized")) }
    }

    fun pinConversation(conversationId: String, pinned: Boolean, callback: ImCallback? = null) {
        check(loggedIn) { "login required" }
        if (config.useMock) {
            local.pin(conversationId, pinned)
            mainHandler.post { callback?.onResult(true, 0, "mock-pin-ok") }
            return
        }
        openIm?.pinConversation(conversationId, pinned, mainCallback(callback))
            ?: callback?.onResult(false, -1, "OpenIM not initialized")
    }

    fun setConversationDraft(conversationId: String, draft: String, callback: ImCallback? = null) {
        check(loggedIn) { "login required" }
        if (config.useMock) {
            local.draft(conversationId, draft)
            mainHandler.post { callback?.onResult(true, 0, "mock-draft-ok") }
            return
        }
        openIm?.setConversationDraft(conversationId, draft, mainCallback(callback))
            ?: callback?.onResult(false, -1, "OpenIM not initialized")
    }

    /** 会话免打扰。OpenIM `recvMsgOpt=2` 为接收但不提醒，`0` 为正常接收。 */
    fun setConversationDoNotDisturb(conversationId: String, enabled: Boolean, callback: ImCallback? = null) {
        check(loggedIn) { "login required" }
        if (config.useMock) {
            local.setDoNotDisturb(conversationId, enabled)
            mainHandler.post { callback?.onResult(true, 0, "mock-dnd-ok") }
            return
        }
        openIm?.setConversationDoNotDisturb(conversationId, enabled, mainCallback(callback))
            ?: callback?.onResult(false, -1, "OpenIM not initialized")
    }

    /** 正在输入。同时调用 OpenIM `typingStatusUpdate` 与 `changeInputStates`。 */
    @JvmOverloads
    fun updateTyping(
        userId: String,
        typing: Boolean,
        conversationId: String? = null,
        callback: ImCallback? = null,
    ) {
        check(loggedIn) { "login required" }
        require(userId.isNotBlank()) { "userId blank" }
        if (config.useMock) {
            local.typing(userId, typing)
            mainHandler.post {
                eventListener?.onTypingStatusChanged(if (typing) userId else "")
                eventListener?.onTypingStatus(
                    ImTypingStatus(conversationId ?: local.conversationId(userId, null), userId, if (typing) listOf(2) else emptyList())
                )
                callback?.onResult(true, 0, "mock-typing-ok")
            }
            return
        }
        openIm?.updateTyping(userId, conversationId, typing, mainCallback(callback))
            ?: callback?.onResult(false, -1, "OpenIM not initialized")
    }

    /**
     * 与 iOS `sendTyping(conversationId:focus:)` 同名。只调 OpenIM `changeInputStates`，
     * 对端收到 [ImEventListener.onTypingStatus]。单聊会话 ID 形如 `si_<a>_<b>`。
     */
    @JvmOverloads
    fun sendTyping(conversationId: String, focus: Boolean, callback: ImCallback? = null) {
        check(loggedIn) { "login required" }
        require(conversationId.isNotBlank()) { "conversationId blank" }
        if (config.useMock) {
            mainHandler.post {
                val me = currentUserId ?: ""
                eventListener?.onTypingStatus(ImTypingStatus(conversationId, me, if (focus) listOf(2) else emptyList()))
                callback?.onResult(true, 0, "mock-typing-ok")
            }
            return
        }
        openIm?.changeInputStates(conversationId, focus, mainCallback(callback))
            ?: callback?.onResult(false, -1, "OpenIM not initialized")
    }

    /** 与 iOS / Flutter `getTotalUnreadCount` 同名，等同 [getUnreadCount]。 */
    fun getTotalUnreadCount(callback: (count: Int?, error: Exception?) -> Unit) = getUnreadCount(callback)

    /** 与 iOS `recallMessage` 同名，等同 [revokeMessage]。 */
    @JvmOverloads
    fun recallMessage(conversationId: String, clientMsgId: String, callback: ImCallback? = null) =
        revokeMessage(conversationId, clientMsgId, callback)

    /**
     * 本地会话列表（OpenIM `getAllConversationList`），与 iOS / Flutter `getConversations` 同名。
     * Mock 模式返回本地会话（按未读快照）。
     */
    fun getConversations(callback: (List<ImConversation>?, Exception?) -> Unit) {
        if (config.useMock) {
            val list = local.snapshot().conversations.map {
                ImConversation(
                    conversationId = it.conversationId,
                    userId = it.userId,
                    groupId = it.groupId,
                    showName = null,
                    unreadCount = it.unreadCount,
                    pinned = local.isPinned(it.conversationId),
                    draft = local.draftOf(it.conversationId),
                    doNotDisturb = local.isDoNotDisturb(it.conversationId),
                )
            }
            mainHandler.post { callback(list, null) }
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
        bridge.getAllConversations { list, code, message ->
            mainHandler.post {
                if (list != null) callback(list, null) else callback(null, Exception(message ?: "conversations failed ($code)"))
            }
        }
    }

    /** 与 iOS `setSelfCustomInfo` 同名，等同 [setSelfEx]。 */
    @JvmOverloads
    fun setSelfCustomInfo(ex: String, callback: ImCallback? = null) = setSelfEx(ex, callback)

    /** 与 iOS / Flutter `setGroupCustomInfo` 同名，等同 [setGroupEx]。 */
    @JvmOverloads
    fun setGroupCustomInfo(groupId: String, ex: String, callback: ImCallback? = null) = setGroupEx(groupId, ex, callback)

    /** 自己的扩展资料。OpenIM `UserInfo.ex`。 */
    fun setSelfEx(ex: String, callback: ImCallback? = null) {
        check(loggedIn) { "login required" }
        if (config.useMock) {
            local.setSelfEx(ex)
            mainHandler.post { callback?.onResult(true, 0, "mock-ex-ok") }
            return
        }
        openIm?.setSelfEx(ex, mainCallback(callback))
            ?: callback?.onResult(false, -1, "OpenIM not initialized")
    }

    /** 群扩展资料。OpenIM `GroupInfo.ex`。 */
    fun setGroupEx(groupId: String, ex: String, callback: ImCallback? = null) {
        check(loggedIn) { "login required" }
        require(groupId.isNotBlank()) { "groupId blank" }
        if (config.useMock) {
            local.setGroupEx(groupId, ex)
            mainHandler.post { callback?.onResult(true, 0, "mock-ex-ok") }
            return
        }
        openIm?.setGroupEx(groupId, ex, mainCallback(callback))
            ?: callback?.onResult(false, -1, "OpenIM not initialized")
    }

    fun addToBlacklist(userId: String, callback: ImCallback? = null) {
        check(loggedIn) { "login required" }
        require(userId.isNotBlank()) { "userId blank" }
        if (config.useMock) {
            local.addBlack(userId)
            mainHandler.post { callback?.onResult(true, 0, "mock-black-ok") }
            return
        }
        openIm?.addToBlacklist(userId, mainCallback(callback))
            ?: callback?.onResult(false, -1, "OpenIM not initialized")
    }

    fun removeFromBlacklist(userId: String, callback: ImCallback? = null) {
        check(loggedIn) { "login required" }
        if (config.useMock) {
            local.removeBlack(userId)
            mainHandler.post { callback?.onResult(true, 0, "mock-black-ok") }
            return
        }
        openIm?.removeFromBlacklist(userId, mainCallback(callback))
            ?: callback?.onResult(false, -1, "OpenIM not initialized")
    }

    fun getBlacklist(callback: (List<String>?, Exception?) -> Unit) {
        if (config.useMock) {
            mainHandler.post { callback(local.blacklist(), null) }
            return
        }
        openIm?.getBlacklist { ids, code, message ->
            mainHandler.post {
                if (ids != null) callback(ids, null)
                else callback(null, Exception(message ?: "blacklist failed ($code)"))
            }
        } ?: mainHandler.post { callback(null, IllegalStateException("OpenIM not initialized")) }
    }

    private fun mainCallback(callback: ImCallback?): ImCallback = ImCallback { success, code, message ->
        mainHandler.post { callback?.onResult(success, code, message) }
    }

    private fun resolveConversationId(conversationId: String?, userId: String?, groupId: String?): String {
        if (!conversationId.isNullOrBlank()) return conversationId
        if (config.useMock) return local.conversationId(userId, groupId)
        val bridge = openIm ?: error("OpenIM not initialized")
        return if (!groupId.isNullOrBlank()) bridge.conversationId(groupId, group = true)
        else bridge.conversationId(userId!!, group = false)
    }

    private fun refreshUnread(conversationId: String?) {
        val bridge = openIm ?: return
        bridge.getTotalUnreadCount { count, _, _ ->
            if (count != null) publish(tracker.applyServerTotal(count))
        }
        if (conversationId.isNullOrBlank()) {
            bridge.getAllConversations { list, _, _ ->
                if (list != null) publish(tracker.upsert(list))
            }
        } else {
            bridge.getConversationsById(listOf(conversationId)) { list, _, _ ->
                if (list != null) publish(tracker.upsert(list))
            }
        }
    }

    private fun publish(snapshot: UnreadSnapshot) {
        val previous: Map<String, Int>
        val current = snapshot.conversations.associate { it.conversationId to it.unreadCount }
        synchronized(this) {
            previous = lastUnreadById
            lastUnreadById = current
        }
        val deliver = Runnable {
            unreadListener?.onUnreadChanged(snapshot.total, snapshot.conversations)
            eventListener?.onTotalUnreadCountChanged(snapshot.total)
            for (id in previous.keys + current.keys) {
                val now = current[id]
                if (now != null && now != previous[id]) {
                    eventListener?.onConversationUnreadChanged(id, now)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run() else mainHandler.post(deliver)
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
