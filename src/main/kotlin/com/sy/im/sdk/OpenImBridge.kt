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
import io.openim.android.sdk.models.GroupMessageReceipt
import io.openim.android.sdk.models.InitConfig
import io.openim.android.sdk.models.Message
import io.openim.android.sdk.models.OfflinePushInfo
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
                        listenerProvider()?.onRecvNewMessage(
                            msg.clientMsgID ?: "",
                            msg.sendID ?: "",
                            groupId,
                            text,
                        )
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
                        listenerProvider()?.onTotalUnreadCountChanged(count)
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
        val clientId = msg.clientMsgID ?: "openim_${System.currentTimeMillis()}"
        val recv = userId.orEmpty()
        val group = groupId.orEmpty()
        val push = OfflinePushInfo().apply {
            title = "新消息"
            desc = text.take(64)
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
            recv,
            group,
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
}
