package com.sy.im.example

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sy.im.sdk.ImConversationUnread
import com.sy.im.sdk.ImEngine
import com.sy.im.sdk.ImEventListener
import com.sy.im.sdk.ImInitConfig
import com.sy.im.sdk.ImUnreadListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * IM Example：对接 rtc-backend-go。
 * - useMock=false（默认）：走 Maven OpenIM Android SDK（ImEngine → OpenImBridge）
 * - useMock=true：本地 mock；Token 回填 imApiAddr 时自动关掉 mock
 * - 「拉取 IM Token」：POST {apiBase}/api/user/im/token，Header Authorization: Bearer {User JWT}
 */
class MainActivity : AppCompatActivity() {
    private var engine: ImEngine? = null
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var unreadBadge: TextView
    private lateinit var conversationList: TextView

    private lateinit var checkMock: CheckBox
    private lateinit var inputAppId: EditText
    private lateinit var inputApiBase: EditText
    private lateinit var inputImApi: EditText
    private lateinit var inputImWs: EditText
    private lateinit var inputUserId: EditText
    private lateinit var inputUserJwt: EditText
    private lateinit var inputToken: EditText
    private lateinit var inputPeer: EditText
    private lateinit var inputText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        unreadBadge = findViewById(R.id.unreadBadge)
        conversationList = findViewById(R.id.conversationList)

        checkMock = findViewById(R.id.checkUseMock)
        inputAppId = findViewById(R.id.inputAppId)
        inputApiBase = findViewById(R.id.inputApiBase)
        inputImApi = findViewById(R.id.inputImApi)
        inputImWs = findViewById(R.id.inputImWs)
        inputUserId = findViewById(R.id.inputUserId)
        inputUserJwt = findViewById(R.id.inputUserJwt)
        inputToken = findViewById(R.id.inputToken)
        inputPeer = findViewById(R.id.inputPeer)
        inputText = findViewById(R.id.inputText)

        findViewById<Button>(R.id.btnFetchToken).setOnClickListener { fetchImTokenAsync() }

        findViewById<Button>(R.id.btnInit).setOnClickListener {
            try {
                ImEngine.resetForTest()
                val config = ImInitConfig(
                    appId = inputAppId.text.toString().trim(),
                    apiBaseUrl = inputApiBase.text.toString().trim(),
                    useMock = checkMock.isChecked,
                    imApiAddr = inputImApi.text.toString().trim(),
                    imWsAddr = inputImWs.text.toString().trim(),
                )
                engine = ImEngine.init(this, config)
                engine?.setUnreadListener(ImUnreadListener { total, conversations ->
                    renderUnread(total, conversations)
                    appendLog("unread total=$total conv=${conversations.size}")
                })
                engine?.setEventListener(object : ImEventListener {
                    override fun onConnectSuccess() = appendLog("onConnectSuccess")
                    override fun onConnectFailed(code: Int, error: String) =
                        appendLog("onConnectFailed $code $error")
                    override fun onConnecting() = appendLog("onConnecting")
                    override fun onKickedOffline() = appendLog("onKickedOffline")
                    override fun onUserTokenExpired() = appendLog("onUserTokenExpired")
                    override fun onRecvNewMessage(
                        msgId: String,
                        fromUserId: String,
                        groupId: String?,
                        text: String?,
                    ) {
                        appendLog("RECV from=$fromUserId group=$groupId id=$msgId text=$text")
                        statusText.text = "收到: $text"
                    }
                })
                statusText.text = "Init OK useMock=${config.useMock}"
                appendLog("init useMock=${config.useMock} appId=${config.appId}")
                toast("Init OK")
            } catch (e: Exception) {
                statusText.text = "Init failed: ${e.message}"
                appendLog("init failed: ${e.message}")
            }
        }

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            val eng = engine
            if (eng == null) {
                toast("先 Init")
                return@setOnClickListener
            }
            val uid = inputUserId.text.toString().trim()
            val token = inputToken.text.toString().trim()
            eng.login(uid, token) { success, code, message ->
                runOnUiThread {
                    statusText.text = if (success) "Login OK" else "Login fail $code $message"
                    appendLog("login success=$success code=$code msg=$message")
                }
            }
        }

        findViewById<Button>(R.id.btnSend).setOnClickListener {
            val eng = engine
            if (eng == null || !eng.isLoggedIn()) {
                toast("先 Login")
                return@setOnClickListener
            }
            try {
                val id = eng.sendTextMessage(
                    userId = inputPeer.text.toString().trim(),
                    text = inputText.text.toString(),
                )
                statusText.text = "已发送 id=$id"
                appendLog("send id=$id")
            } catch (e: Exception) {
                statusText.text = "Send fail: ${e.message}"
                appendLog("send fail: ${e.message}")
            }
        }

        findViewById<Button>(R.id.btnMarkRead).setOnClickListener {
            val eng = engine
            if (eng == null || !eng.isLoggedIn()) {
                toast("先 Login")
                return@setOnClickListener
            }
            eng.markConversationAsRead(userId = inputPeer.text.toString().trim()) { success, code, message ->
                runOnUiThread {
                    appendLog("markRead success=$success code=$code msg=$message")
                    statusText.text = if (success) "已读" else "已读失败 $code"
                }
            }
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            engine?.logout { success, code, message ->
                runOnUiThread {
                    statusText.text = if (success) "Logout OK" else "Logout fail $code"
                    appendLog("logout success=$success code=$code msg=$message")
                }
            }
        }

        appendLog("IM Example 就绪。默认 useMock=false（可勾选切回 mock）。")
        appendLog("流程：填 User JWT → 拉取 IM Token（会回填地址并关闭 mock）→ Init → Login → Send。")
        appendLog("模拟器 apiBase 默认 http://10.0.2.2:8080；真机改局域网 IP。")
    }

    private fun fetchImTokenAsync() {
        val jwt = inputUserJwt.text.toString().trim()
        if (jwt.isEmpty()) {
            toast("请先填写 User JWT（控制面登录 accessToken）")
            appendLog("填写 User JWT 后才能请求 /api/user/im/token")
            return
        }
        setStatus("正在拉取 IM Token…")
        appendLog("POST /api/user/im/token …")
        Thread {
            try {
                val result = fetchImTokenFromServer(jwt)
                runOnUiThread {
                    inputToken.setText(result.token)
                    if (result.imApiAddr.isNotBlank()) {
                        inputImApi.setText(result.imApiAddr)
                        // Token 带回 OpenIM 地址时默认走真连，保留勾选框可切回 mock
                        checkMock.isChecked = false
                    }
                    if (result.imWsAddr.isNotBlank()) inputImWs.setText(result.imWsAddr)
                    setStatus("IM Token 已填入 useMock=${checkMock.isChecked}")
                    appendLog("im/token ok len=${result.token.length} api=${result.imApiAddr} ws=${result.imWsAddr} useMock=${checkMock.isChecked}")
                    toast("IM Token 已获取")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("拉 IM Token 失败: ${e.message}")
                    appendLog("im/token failed: ${e.message}")
                    toast("拉 Token 失败")
                }
            }
        }.start()
    }

    private data class ImTokenResult(
        val token: String,
        val imApiAddr: String,
        val imWsAddr: String,
    )

    private fun fetchImTokenFromServer(jwt: String): ImTokenResult {
        val apiBase = inputApiBase.text.toString().trim().trimEnd('/')
        val appId = inputAppId.text.toString().trim()
        val userId = inputUserId.text.toString().trim()
        val url = "$apiBase/api/user/im/token"
        val bodyJson = JSONObject()
            .put("appId", appId)
            .put("userId", userId)
            .toString()
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Authorization", "Bearer $jwt")
            .addHeader("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("empty body")
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                throw Exception(json.optString("msg", "unknown"))
            }
            val data = json.optJSONObject("data") ?: throw Exception("no data")
            val token = data.optString("token", "").trim()
            if (token.isEmpty()) throw Exception("empty token")
            return ImTokenResult(
                token = token,
                imApiAddr = data.optString("imApiAddr", ""),
                imWsAddr = data.optString("imWsAddr", ""),
            )
        }
    }

    private fun renderUnread(total: Int, conversations: List<ImConversationUnread>) {
        if (total > 0) {
            unreadBadge.visibility = View.VISIBLE
            unreadBadge.text = if (total > 99) "99+" else total.toString()
        } else {
            unreadBadge.visibility = View.GONE
        }
        conversationList.text = if (conversations.isEmpty()) {
            "暂无会话"
        } else {
            conversations.joinToString("\n") { row ->
                val who = row.groupId ?: row.userId ?: row.conversationId
                "$who    未读 ${row.unreadCount}"
            }
        }
    }

    private fun setStatus(msg: String) {
        statusText.text = msg
    }

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logText.append("[$ts] $msg\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
