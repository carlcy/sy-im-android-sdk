# SY IM Android Demo

Demo 用客户同款坐标引入 SDK，不下载、不解压 AAR 或 zip。

```kotlin
implementation("com.github.carlcy:sy-im-android-sdk:v0.5.0")
```

仓库：`google()`、`mavenCentral()`、`https://jitpack.io`（见 `settings.gradle.kts`）。版本在 `gradle.properties` 的 `syImSdkVersion`。

```bash
./gradlew :app:assembleDebug
```

当前 tag 还没推到 JitPack 时，改用源码模块：

```bash
./gradlew -PuseLocalSdk=true :app:assembleDebug
```

作者刚执行过 `publishToMavenLocal` 时：

```bash
./gradlew -PpreferMavenLocal=true :app:assembleDebug
```

## 真机 / 模拟器

| 配置项 | 模拟器 | 真机（同一局域网） |
|--------|--------|------------------|
| apiBaseUrl | `http://10.0.2.2:8080` 或生产 `http://47.105.48.196` | `http://<电脑 LAN IP>:8080` |
| imApiAddr / imWsAddr | Token 响应回填；本机 OpenIM 用 `10.0.2.2:10002` / `10001` | 同左，换成 LAN IP |

1. 勾选 useMock：Init → Login（token 任意非空）→ Send。顶部「会话」红点加一，点「标记已读」立刻归零。日志里有 mock-echo。
2. 真链路：填写 User JWT → **拉取 IM Token**（`POST /api/user/im/token`）→ Init → Login → Send。

User JWT 来自控制面登录 `POST /api/user/auth/login` 的 `accessToken`。响应 `data` 含 `token`、`imApiAddr`、`imWsAddr`。

minSdk **24**，compileSdk / targetSdk **35**。Debug 允许明文（`network_security_config`）。
