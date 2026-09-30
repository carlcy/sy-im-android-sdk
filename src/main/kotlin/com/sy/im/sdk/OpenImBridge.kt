package com.sy.im.sdk

import android.app.Application
import android.util.Log
import io.openim.android.sdk.OpenIMClient
import io.openim.android.sdk.enums.ConversationType
import io.openim.android.sdk.listener.OnAdvanceMsgListener
import io.openim.android.sdk.listener.OnBase
import io.openim.android.sdk.listener.OnConnListener
import io.openim.android.sdk.listener.OnConversationListener
import io.openim.android.sdk.listener.OnMsgSendCallback
import io.openim.android.sdk.models.C2CReadReceiptInfo
import io.openim.android.sdk.models.ConversationInfo
import io.openim.android.sdk.models.ConversationReq
import io.openim.android.sdk.models.GroupInfo
import io.openim.android.sdk.models.GroupMessageReceipt
import io.openim.android.sdk.models.InitConfig
import io.openim.android.sdk.models.Message
import io.openim.android.sdk.models.OfflinePushInfo
import io.openim.android.sdk.models.RevokedInfo
import io.openim.android.sdk.models.SearchResult
import io.openim.android.sdk.models.UserInfo
import io.openim.android.sdk.models.UserInfoReq
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenIM Android SDK（Maven: io.openim:android-sdk / core-sdk）桥接层。
 * 仅在 [ImInitConfig.useMock] = false 时使用。
 */
internal class OpenImBridge(
    private val app: Application,
    private val config: ImInitConfig,
    private val listenerProvider: () -> ImEventListener?,
    private val onConversations: (List<ImConversation>) -> Unit,
    private val onTotalUnread: (Int) -> Unit,
) {
    private val tag = "SyOpenImBridge"
    private val inited = AtomicBoolean(false)

    fun initSdk(): Boolean {
        if (inited.get()) return true
        val dataDir = File(app.filesDir, "sy_openim").apply { mkdirs() }.absolutePath
        val initConfig = InitConfig(config.imApiAddr, config.imWsAddr, dataDir).apply {
            isLogStandardOutput = true
        }
        val ok = OpenIMClient.getInstance().initSDK(
            app,
            initConfig,
            object : OnConnListener {
                override fun onConnecting() {
                    listenerProvider()?.onConnecting()
                }

                override fun onConnectSuccess() {
                    listenerProvider()?.onConnectSuccess()
                }

                override fun onConnectFailed(code: Int, error: String?) {
                    listenerProvider()?.onConnectFailed(code, error ?: "connect failed")
                }

                override fun onKickedOffline() {
                    listenerProvider()?.onKickedOffline()
                }

                override fun onUserTokenExpired() {
                    listenerProvider()?.onUserTokenExpired()
                }
            },
        )
        if (ok) {
            OpenIMClient.getInstance().messageManager.setAdvancedMsgListener(
                object : OnAdvanceMsgListener {
                    override fun onRecvNewMessage(msg: Message?) {
                        if (msg == null) return
                        val text = msg.textElem?.content
                        val groupId = msg.groupID?.takeIf { it.isNotBlank() }
                        val atText = msg.atTextElem?.text
                        val customText = msg.customElem?.description ?: msg.customElem?.data
                        listenerProvider()?.onRecvNewMessage(
                            msg.clientMsgID ?: "",
                            msg.sendID ?: "",
                            groupId,
                            text ?: atText ?: customText,
                        )
                    }

                    override fun onRecvMessageRevokedV2(info: RevokedInfo?) {
                        if (info == null) return
                        listenerProvider()?.onMessageRevoked(info.clientMsgID ?: "", info.revokerID ?: "")
                    }

                    override fun onRecvC2CReadReceipt(list: MutableList<C2CReadReceiptInfo>?) {
                        list.orEmpty().forEach { info ->
                            listenerProvider()?.onRecvC2CReadReceipt(
                                info.userID ?: "",
                                info.msgIDList ?: emptyList(),
                            )
                        }
                    }

                    override fun onRecvGroupMessageReadReceipt(receipt: GroupMessageReceipt?) {
                        if (receipt == null) return
                        listenerProvider()?.onRecvGroupReadReceipt(receipt.conversationID ?: "")
                    }
                },
            )
            OpenIMClient.getInstance().conversationManager.setOnConversationListener(
                object : OnConversationListener {
                    override fun onTotalUnreadMessageCountChanged(count: Int) {
                        onTotalUnread(count)
                    }

                    override fun onConversationChanged(list: MutableList<ConversationInfo>?) {
                        onConversations(list.orEmpty().map { it.toIm() })
                    }

                    override fun onNewConversation(list: MutableList<ConversationInfo>?) {
                        onConversations(list.orEmpty().map { it.toIm() })
                    }

                    override fun onConversationUserInputStatusChanged(data: String?) {
                        listenerProvider()?.onTypingStatusChanged(data ?: "")
                    }
                },
            )
            inited.set(true)
            Log.i(tag, "OpenIM initSDK ok api=${config.imApiAddr} ws=${config.imWsAddr}")
        } else {
            Log.e(tag, "OpenIM initSDK returned false")
        }
        return ok
    }

    fun login(userId: String, token: String, callback: ImCallback?) {
        OpenIMClient.getInstance().login(
            object : OnBase<String> {
                override fun onSuccess(data: String?) {
                    callback?.onResult(true, 0, data)
                }

                override fun onError(code: Int, error: String?) {
                    callback?.onResult(false, code, error)
                }
            },
            userId,
            token,
        )
    }

    fun logout(callback: ImCallback?) {
        OpenIMClient.getInstance().logout(
            object : OnBase<String> {
                override fun onSuccess(data: String?) {
                    callback?.onResult(true, 0, data)
                }

                override fun onError(code: Int, error: String?) {
                    callback?.onResult(false, code, error)
                }
            },
        )
    }

    fun sendTextMessage(userId: String?, groupId: String?, text: String): String {
        val msg = OpenIMClient.getInstance().messageManager.createTextMessage(text)
        return dispatchSend(msg, userId, groupId, text)
    }

    fun sendAtTextMessage(groupId: String, text: String, atUserIds: List<String>, atAll: Boolean): String {
        val ids = ArrayList<String>(atUserIds.size + 1)
        ids.addAll(atUserIds)
        if (atAll) {
            val tag = OpenIMClient.getInstance().conversationManager.atAllTag
            if (!tag.isNullOrBlank()) ids.add(tag)
        }
        val msg = OpenIMClient.getInstance().messageManager.createTextAtMessage(text, ids, null, null)
        return dispatchSend(msg, null, groupId, text)
    }

    fun sendCustomMessage(
        userId: String?,
        groupId: String?,
        data: String,
        extension: String,
        description: String,
    ): String {
        val msg = OpenIMClient.getInstance().messageManager.createCustomMessage(data, extension, description)
        return dispatchSend(msg, userId, groupId, description.ifBlank { data })
    }

    private fun dispatchSend(msg: Message, userId: String?, groupId: String?, preview: String): String {
        val clientId = msg.clientMsgID ?: "openim_${System.currentTimeMillis()}"
        val push = OfflinePushInfo().apply {
            title = "新消息"
            desc = preview.take(64)
        }
        OpenIMClient.getInstance().messageManager.sendMessage(
            object : OnMsgSendCallback {
                override fun onSuccess(message: Message?) {
                    Log.d(tag, "send ok clientMsgID=${message?.clientMsgID}")
                }

                override fun onError(code: Int, error: String?) {
                    Log.e(tag, "send failed code=$code error=$error")
                }

                override fun onProgress(progress: Long) {}
            },
            msg,
            userId.orEmpty(),
            groupId.orEmpty(),
            push,
        )
        return clientId
    }

    /** 单聊 sessionType=1；OpenIM 3.8 群会话为超级群 sessionType=3。 */
    fun conversationId(sourceId: String, group: Boolean): String {
        val sessionType = if (group) ConversationType.SUPER_GROUP_CHAT else ConversationType.SINGLE_CHAT
        return OpenIMClient.getInstance().conversationManager
            .getConversationIDBySessionType(sourceId, sessionType)
    }

    fun markConversationAsRead(conversationId: String, callback: ImCallback?) {
        OpenIMClient.getInstance().messageManager.markConversationMessageAsRead(
            conversationId,
            object : OnBase<String> {
                override fun onSuccess(data: String?) {
                    callback?.onResult(true, 0, data)
                }

                override fun onError(code: Int, error: String?) {
                    callback?.onResult(false, code, error)
                }
            },
        )
    }

    @Suppress("DEPRECATION")
    fun markMessagesAsRead(conversationId: String, clientMsgIds: List<String>, callback: ImCallback?) {
        OpenIMClient.getInstance().messageManager.markMessagesAsReadByMsgID(
            conversationId,
            clientMsgIds,
            object : OnBase<String> {
                override fun onSuccess(data: String?) {
                    callback?.onResult(true, 0, data)
                }

                override fun onError(code: Int, error: String?) {
                    callback?.onResult(false, code, error)
                }
            },
        )
    }

    fun getTotalUnreadCount(callback: (count: Int?, code: Int, message: String?) -> Unit) {
        OpenIMClient.getInstance().conversationManager.getTotalUnreadMsgCount(
            object : OnBase<String> {
                override fun onSuccess(data: String?) {
                    callback(data?.trim()?.toIntOrNull(), 0, data)
                }

                override fun onError(code: Int, error: String?) {
                    callback(null, code, error)
                }
            },
        )
    }

    fun getAllConversations(callback: (List<ImConversation>?, Int, String?) -> Unit) {
        OpenIMClient.getInstance().conversationManager.getAllConversationList(
            object : OnBase<List<ConversationInfo>> {
                override fun onSuccess(data: List<ConversationInfo>?) {
                    callback(data.orEmpty().map { it.toIm() }, 0, null)
                }

                override fun onError(code: Int, error: String?) {
                    callback(null, code, error)
                }
            },
        )
    }

    fun getConversationsById(conversationIds: List<String>, callback: (List<ImConversation>?, Int, String?) -> Unit) {
        OpenIMClient.getInstance().conversationManager.getMultipleConversation(
            object : OnBase<List<ConversationInfo>> {
                override fun onSuccess(data: List<ConversationInfo>?) {
                    callback(data.orEmpty().map { it.toIm() }, 0, null)
                }

                override fun onError(code: Int, error: String?) {
                    callback(null, code, error)
                }
            },
            conversationIds,
        )
    }

    fun revokeMessage(conversationId: String, clientMsgId: String, callback: ImCallback?) {
        OpenIMClient.getInstance().messageManager.revokeMessageV2(stringBase(callback), conversationId, clientMsgId)
    }

    fun searchLocalMessages(
        keyword: String,
        conversationId: String?,
        callback: (List<ImSearchHit>?, Int, String?) -> Unit,
    ) {
        OpenIMClient.getInstance().messageManager.searchLocalMessages(
            object : OnBase<SearchResult> {
                override fun onSuccess(data: SearchResult?) {
                    val items = data?.searchResultItems ?: data?.findResultItems ?: emptyList()
                    callback(
                        items.map { item ->
                            val first = item.messageList?.firstOrNull()
                            ImSearchHit(
                                conversationId = item.conversationID ?: "",
                                showName = item.showName,
                                messageCount = item.messageCount,
                                preview = first?.textElem?.content ?: first?.atTextElem?.text ?: first?.customElem?.description,
                            )
                        },
                        0,
                        null,
                    )
                }

                override fun onError(code: Int, error: String?) {
                    callback(null, code, error)
                }
            },
            conversationId,
            listOf(keyword),
            2,
            emptyList(),
            emptyList(),
            0,
            0,
            1,
            20,
        )
    }

    fun pinConversation(conversationId: String, pinned: Boolean, callback: ImCallback?) {
        val req = ConversationReq().apply { setPinned(pinned) }
        OpenIMClient.getInstance().conversationManager.setConversation(stringBase(callback), conversationId, req)
    }

    fun setConversationDraft(conversationId: String, draft: String, callback: ImCallback?) {
        OpenIMClient.getInstance().conversationManager.setConversationDraft(stringBase(callback), conversationId, draft)
    }

    fun setConversationDoNotDisturb(conversationId: String, enabled: Boolean, callback: ImCallback?) {
        val req = ConversationReq().apply { recvMsgOpt = if (enabled) 2 else 0 }
        OpenIMClient.getInstance().conversationManager.setConversation(stringBase(callback), conversationId, req)
    }

    fun updateTyping(userId: String, conversationId: String?, typing: Boolean, callback: ImCallback?) {
        OpenIMClient.getInstance().messageManager.typingStatusUpdate(
            stringBase(callback),
            userId,
            if (typing) "yes" else "no",
        )
        if (!conversationId.isNullOrBlank()) {
            OpenIMClient.getInstance().conversationManager.changeInputStates(stringBase(null), conversationId, typing)
        }
    }

    fun setSelfEx(ex: String, callback: ImCallback?) {
        val req = UserInfoReq().apply { this.ex = ex }
        OpenIMClient.getInstance().userInfoManager.setSelfInfo(stringBase(callback), req)
    }

    fun setGroupEx(groupId: String, ex: String, callback: ImCallback?) {
        val info = GroupInfo().apply {
            setGroupID(groupId)
            setEx(ex)
        }
        OpenIMClient.getInstance().groupManager.setGroupInfo(info, stringBase(callback))
    }

    fun addToBlacklist(userId: String, callback: ImCallback?) {
        OpenIMClient.getInstance().friendshipManager.addBlacklist(stringBase(callback), userId)
    }

    fun removeFromBlacklist(userId: String, callback: ImCallback?) {
        OpenIMClient.getInstance().friendshipManager.removeBlacklist(stringBase(callback), userId)
    }

    fun getBlacklist(callback: (List<String>?, Int, String?) -> Unit) {
        OpenIMClient.getInstance().friendshipManager.getBlacklist(
            object : OnBase<List<UserInfo>> {
                override fun onSuccess(data: List<UserInfo>?) {
                    callback(data.orEmpty().mapNotNull { it.userID?.takeIf { id -> id.isNotBlank() } }, 0, null)
                }

                override fun onError(code: Int, error: String?) {
                    callback(null, code, error)
                }
            },
        )
    }

    private fun stringBase(callback: ImCallback?): OnBase<String> = object : OnBase<String> {
        override fun onSuccess(data: String?) {
            callback?.onResult(true, 0, data)
        }

        override fun onError(code: Int, error: String?) {
            callback?.onResult(false, code, error)
        }
    }

    private fun ConversationInfo.toIm(): ImConversation = ImConversation(
        conversationId = conversationID ?: "",
        userId = userID?.takeIf { it.isNotBlank() },
        groupId = groupID?.takeIf { it.isNotBlank() },
        showName = showName,
        unreadCount = unreadCount,
        pinned = isPinned,
        draft = draftText,
        doNotDisturb = recvMsgOpt != 0,
    )
}
