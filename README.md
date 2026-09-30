# SY IM Android SDK

SY 即时通信 Android SDK。产品路径对齐腾讯云 IM（仓库、依赖、初始化、登录、发消息），数据面封装 **OpenIM Android SDK**。不是腾讯云 TIM 的全 API 对等。

**当前版本：0.5.0**（JitPack tag：`v0.5.0`）

客户只写一行坐标。OpenIM（`io.openim:android-sdk`、`io.openim:core-sdk`）和 Gson 写在本库 POM 里，Gradle 会从 Maven Central 自动带上，不必再下载或解压 AAR / zip。

## 快速接入

minSdk **24**。下面用 Kotlin DSL；Groovy 把双引号换成单引号即可。

### 1. 添加仓库

`settings.gradle.kts`：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`google()` 与 `mavenCentral()` 不能省：AndroidX 和 OpenIM 不在 JitPack 上。本库自己在 JitPack。

### 2. 添加依赖

`app/build.gradle.kts`：

```kotlin
dependencies {
    implementation("com.github.carlcy:sy-im-android-sdk:v0.5.0")
}
```

版本号是 Git tag（带 `v`）。把 `v0.5.0` 换成你要锁定的 tag。同步工程后即可编译，不要把 AAR 拷进 `libs/`。

### 3. 初始化

IM Token、`imApiAddr`、`imWsAddr` 由业务后端向 SY 控制面申请（`POST /api/user/im/token` 或 `POST /api/server/im/token`）。客户端不持有 AppSecret。

```kotlin
val engine = ImEngine.init(
    context = applicationContext,
    config = ImInitConfig(
        appId = "你的 AppId",
        apiBaseUrl = "http://47.105.48.196",
        useMock = false,
        imApiAddr = imApiAddrFromToken,
        imWsAddr = imWsAddrFromToken,
    ),
)

engine.setEventListener(object : ImEventListener {
    override fun onConnectSuccess() {}
    override fun onRecvNewMessage(
        msgId: String,
        fromUserId: String,
        groupId: String?,
        text: String?,
    ) {}
})
```

`useMock = true` 只用于本地把界面跑通，不会连接 OpenIM。

### 4. 使用 Token 登录

```kotlin
engine.login(userId = imUserId, token = imToken) { success, code, message ->
    // success == true 即可发消息
}
```

`imUserId` 一般为 `{appId}_{业务用户 ID}`，以 Token 响应里的 `imUserId` 为准。

### 5. 发送消息

```kotlin
val clientMsgId = engine.sendTextMessage(userId = peerImUserId, text = "hello")
// 群聊：engine.sendTextMessage(groupId = groupId, text = "hello")
```

调用前需要已经登录。收到的文本在 `onRecvNewMessage`。

## 已读、未读、好友申请、群管理

登录 OpenIM 之后（实时）：

```kotlin
engine.markConversationAsRead(userId = peerImUserId) { success, code, message -> }
engine.getUnreadCount { count, error -> }
```

单聊标记已读会发已读回执，回调 `ImEventListener.onRecvC2CReadReceipt`。未读会立刻刷新，不必等 OpenIM 回包：

```kotlin
engine.setUnreadListener { total, conversations ->
    // total：会话页签上的红点
    // conversations：每个会话自己的未读
}
```

`onTotalUnreadCountChanged` 与 `onConversationUnreadChanged` 是同一份数据。Demo 顶部「会话」标题旁的红点就是总数，下面列出每个会话的未读。勾选 useMock 后，Send 会让红点加一，点「标记已读」立刻归零。

对齐腾讯云 IM、且 OpenIM 3.8.3 已提供的能力（原有接口不变）：

| 能力 | 方法 |
|------|------|
| 撤回 | `revokeMessage`（iOS 同名别名 `recallMessage`） |
| @ | `sendAtTextMessage`（`atAll = true` 时带 atAllTag） |
| 搜索 | `searchLocalMessages` |
| 置顶 / 草稿 / 免打扰 | `pinConversation` / `setConversationDraft` / `setConversationDoNotDisturb` |
| 正在输入 | 发送：`sendTyping(conversationId, focus)`（与 iOS 同名，OpenIM `changeInputStates`）或旧的 `updateTyping`；接收：`onTypingStatus(ImTypingStatus)`（`typing` / `platformIds`），原样 JSON 仍走 `onTypingStatusChanged` |
| 自定义消息 | `sendCustomMessage` |
| 用户 / 群扩展资料 | `setSelfEx` / `setGroupEx`（iOS 同名别名 `setSelfCustomInfo` / `setGroupCustomInfo`） |
| 会话列表 / 总未读 | `getConversations`（OpenIM 本地会话）/ `getTotalUnreadCount`（= `getUnreadCount`） |
| 黑名单 | `addToBlacklist` / `removeFromBlacklist` / `getBlacklist` |

控制面 REST（先设置 `engine.controlPlane.userJwt`）与后台文档一致：

| 方法 | 路径 |
|------|------|
| `markConversationRead` | `POST /api/user/im/conversations/mark-read` |
| `getUnreadCount` | `POST /api/user/im/unread`（`data.totalUnread`） |
| `listConversations` | `POST /api/user/im/conversations` |
| `listFriendApplications` / `acceptFriendApplication` / `refuseFriendApplication` | `/api/user/im/friends/apply/list\|accept\|refuse` |
| `deleteFriend` | `POST /api/user/im/friends/delete` |
| `listGroupMembers` / `inviteGroupMembers` / `kickGroupMembers` / `muteGroup` | `/api/user/im/groups/members\|invite\|kick\|mute` |

原有 `getToken`、`addFriend`、`listFriends`、`createGroup`、`listGroups`、`send`、`history`、`revoke` 不变。

## 错误码

控制面（`ImControlPlane`，即 `/api/user/im/…`、`/api/server/im/token`）失败时，回调里的 `Exception` 是 `ImControlPlaneException`：`code` 为服务端业务码，`httpStatus` 为 HTTP 状态。取值在 `ImErrorCode`，与 iOS `SyImErrorCode`、Flutter `SyImErrorCode` 相同：

| code | 常量 | 含义 |
|---|---|---|
| 401 / 403 | `UNAUTHORIZED` / `FORBIDDEN` | JWT 无效 / 无权访问该应用 |
| 3001 | `IM_NOT_ENABLED` | 应用未开通 IM |
| 3003 / 3004 | `QUOTA_MAU` / `QUOTA_MESSAGES` | 月活 / 消息量超出套餐 |
| 4003 | `TRIAL_RETIRED` | 体验版已下线 |
| 4005 | `SENSITIVE_REJECTED` | 敏感词拦截 |
| 4006 | `CONTENT_REJECTED` | 发送前内容审核拒绝或审核服务不可达 |
| 4031 / 4032 / 4033 | `CREDENTIAL_SUSPENDED` / `REVOKED` / `EXPIRED` | AppId 访问凭证暂停 / 吊销 / 过期 |
| 4290 | `RATE_LIMITED` | 请求过于频繁 |

```kotlin
controlPlane.getToken(userId) { data, error ->
    val code = (error as? ImControlPlaneException)?.code
    if (code != null && ImErrorCode.isCredentialBlocked(code)) { /* 提示凭证不可用 */ }
}
```

OpenIM SDK 自己的错误（登录、实时收发，`ImCallback` / `onConnectFailed`）是 OpenIM 错误码，原样透传。

## 运行 Demo

Demo 用和客户一样的坐标，不下载 zip。

```bash
cd example
./gradlew :app:assembleDebug
```

维护者还没把当前 tag 推到 JitPack 时，用源码模块编译：

```bash
cd example
./gradlew -PuseLocalSdk=true :app:assembleDebug
```

本地 `publishToMavenLocal` 之后，也可以按坐标验证：

```bash
./gradlew publishToMavenLocal
cd example && ./gradlew -PpreferMavenLocal=true :app:assembleDebug
```

- 模拟器快速看 UI：勾选 useMock，Token 任意非空，Init → Login → Send。
- 真链路：填写 User JWT，点「拉取 IM Token」，Init → Login → Send。模拟器访问宿主机用 `10.0.2.2`。

## 维护者发版

推荐路径是 **JitPack**（仓库已公开，不用搭 Maven 仓库，也不用签名）。`v0.4.1` 已在 JitPack 构建成功。

1. 改 `VERSION`（只写 `0.5.0` 这种，不要带 `v`）。`build.gradle.kts` 会发布成 `v0.5.0`。
2. 同步 `example/gradle.properties` 的 `syImSdkVersion=v0.5.0`，以及本 README 依赖行。
3. 本地确认：
   ```bash
   ./gradlew assembleRelease publishToMavenLocal
   cd example && ./gradlew -PuseLocalSdk=true :app:assembleDebug
   cd example && ./gradlew -PpreferMavenLocal=true :app:assembleDebug
   ```
   发布物在 `~/.m2/repository/com/github/carlcy/sy-im-android-sdk/v0.5.0/`。打开 POM，确认有 `io.openim:android-sdk` 和 `io.openim:core-sdk`，且没有 `groupId=*` 的 exclusion。
4. 合并到 `main` 并推送。
5. 打 tag 并推送（tag 必须带 `v`，且与依赖版本一致）：
   ```bash
   git tag v0.5.0
   git push origin v0.5.0
   ```
6. 打开 [jitpack.io/#carlcy/sy-im-android-sdk](https://jitpack.io/#carlcy/sy-im-android-sdk)，对 `v0.5.0` 点 Get it，或等客户第一次请求时触发构建。日志：`https://jitpack.io/com/github/carlcy/sy-im-android-sdk/v0.5.0/build.log`。状态为 ok 后，客户依赖就是：
   ```kotlin
   implementation("com.github.carlcy:sy-im-android-sdk:v0.5.0")
   ```

JitPack 使用仓库根目录的 `jitpack.yml`（JDK 17 + Android 35）和 `maven-publish`。不要改回 `@aar` 依赖写法，否则 POM 会丢掉 OpenIM 传递依赖。

### 可选：Maven Central

JitPack 不需要这一步。若要发到 Maven Central，维护者自己准备：

1. 在 [Central Portal](https://central.sonatype.com/) 注册你拥有的命名空间（groupId）。
2. 生成 GPG 密钥，构建时提供 `SIGNING_KEY`（私钥 ASCII armor）和 `SIGNING_PASSWORD`。未设置时不会签名，避免 JitPack 构建失败。
3. 提供 `OSSRH_USERNAME` / `OSSRH_PASSWORD`（或 `-PossrhUsername` / `-PossrhPassword`）。
4. 用你的 groupId 发布（不要用 `com.github.carlcy` 传 Central）：
   ```bash
   ./gradlew publishReleasePublicationToOssrhRepository \
     -Pgroup=你的.groupId -Pversion=0.5.0
   ```
5. 在 Central Portal 关闭并发布该 staging。仓库地址目前指向 `s01.oss.sonatype.org`；若 Portal 要求新的上传 URL，改 `build.gradle.kts` 里 `ossrh` 仓库的 `url`。

POM 已含名称、描述、MIT、SCM（`github.com/carlcy/sy-im-android-sdk`）。

## 说明

- 实时收发走 OpenIM；Token、好友、群、历史、撤回、已读和未读也可以走 `ImControlPlane`。
- `useMock=true` 不加载 OpenIM native。
- 生产控制面基址：`http://47.105.48.196`。OpenIM 地址以 Token 响应的 `imApiAddr` / `imWsAddr` 为准。
