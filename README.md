# sy-im-android-sdk

SY 即时通信 Android 库。封装 **OpenIM Android SDK**（Maven Central），AppID / 控制面模型与 `rtc-android-sdk` 一致。

**当前版本：0.4.1**

## 安装（JitPack）

```gradle
repositories {
    mavenCentral()
    google()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.carlcy:sy-im-android-sdk:v0.4.1'
}
```

源码 zip：`https://syrtcapi.shengyuchenyao.cn/downloads/sy-im-android-0.4.1.zip`

## Maven 坐标（OpenIM 传递依赖）

已在本库 `build.gradle.kts` 声明：

```gradle
implementation("io.openim:android-sdk:3.8.3.5@aar")
implementation("io.openim:core-sdk:3.8.3-patch15@aar")
implementation("com.google.code.gson:gson:2.10.1")
```

仓库：`google()` + `mavenCentral()`。

## 公共 API

```kotlin
val engine = ImEngine.init(
    context = applicationContext,
    config = ImInitConfig(
        appId = "your_app_id",
        apiBaseUrl = "https://rtc-api.example.com",
        useMock = true, // 示例默认；false 则走真实 OpenIM
        imApiAddr = "http://10.0.2.2:10002",
        imWsAddr = "ws://10.0.2.2:10001",
    ),
)

engine.setEventListener(object : ImEventListener {
    override fun onRecvNewMessage(msgId: String, fromUserId: String, groupId: String?, text: String?) { }
})

engine.login(userId = "u1001", token = imTokenFromBackend) { ok, code, msg -> }
engine.sendTextMessage(userId = "u1002", text = "hello")
engine.logout()
```

| 方法 | 说明 | OpenIM |
|------|------|--------|
| `init(context, config)` / `init(..., useMock=)` | 初始化 | `initSDK` |
| `login(userId, token, callback?)` | 登录 | `login` |
| `logout(callback?)` | 登出 | `logout` |
| `sendTextMessage(userId\|groupId, text)` | 发文本 | `createTextMessage` + `sendMessage` |
| `setEventListener` | 事件 | OnConn / OnAdvanceMsg |

## Example

```bash
cd sy-im-android-sdk/example
./gradlew :app:assembleDebug
```

- **模拟器**：默认 `useMock=true`，无需 OpenIM 服务即可验证 UI / 回调（发送后有 mock-echo）。
- **真 OpenIM**：取消勾选 useMock；模拟器用 `10.0.2.2` 访问宿主机 `10002/10001`；Token 走 `POST /api/user/im/token`（见 `docs/IM_INTEGRATION.md`）。

minSdk **24**，targetSdk **35**。Debug 允许 cleartext（`network_security_config`）。

## 说明

- IM Token 由业务后端经 SY 控制面签发；客户端不持有 OpenIM secret。
- 不复制 `/Volumes/carlcy/open_im` 源码。
- `useMock=true` 时不调用 OpenIM native；`false` 时经 `OpenImBridge` 调用 Maven 依赖。
- Maven 坐标 `io.openim:android-sdk:3.8.3.5` / `core-sdk:3.8.3-patch15` 已验证可解析；无需 HTTP 降级客户端。


## 控制面 REST（ImControlPlane）

```kotlin
engine.controlPlane.userJwt = userJwt // or appSecret for /api/server/im/token
engine.getToken("u1001") { data, err ->
  val token = data?.get("token") as? String
  val api = data?.get("imApiAddr") as? String
  val ws = data?.get("imWsAddr") as? String
}
engine.controlPlane.listFriends("u1001") { ... }
engine.controlPlane.history(userId = "u1001", peerUserId = "u1002") { ... }
engine.controlPlane.revoke(userId = "u1001", conversationId = "...", seq = 1L) { ... }
```

实时收发仍走 OpenIM Android SDK；控制面负责 Token / 好友群 / 历史撤回。详见 `docs/SDK_IM.md`。生产基址：`http://47.105.48.196`。
