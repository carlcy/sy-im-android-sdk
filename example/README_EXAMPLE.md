# SY IM Android Example（对接 rtc-backend-go）

示例路径：`sy-im-android-sdk/example`  
依赖上级源码模块 `:sy-im-android-sdk`（见 `settings.gradle.kts`）。

后端：`rtc-backend-go` 默认监听 **`:8080`**。

| 配置项 | 模拟器 | 真机（同局域网） |
|--------|--------|------------------|
| apiBaseUrl | `http://10.0.2.2:8080` | `http://<电脑LAN_IP>:8080` |
| imApiAddr | `http://10.0.2.2:10002`（或 Token 回填） | `http://<LAN_IP>:10002` |
| imWsAddr | `ws://10.0.2.2:10001`（或 Token 回填） | `ws://<LAN_IP>:10001` |
| IM Token | `POST {apiBaseUrl}/api/user/im/token`，Header：`Authorization: Bearer {User JWT}`，Body：`{ "appId", "userId" }` | 同左 |

响应 `data`：`{ appId, token, imApiAddr, imWsAddr }`。User JWT 来自控制面登录 `POST /api/user/auth/login` 的 `accessToken`。

Cleartext：`AndroidManifest` `usesCleartextTraffic=true` + `res/xml/network_security_config.xml`。

## 功能

- 可编辑 appId / apiBaseUrl / imApiAddr / imWsAddr / userId / User JWT / IM token / 对端 / 文本
- **拉取 IM Token**（需 User JWT）或 mock 下任意非空 token
- Init → Login → Send 文本 → Logout
- useMock 勾选：本地 mock（发送后有 mock-echo）；取消则走真实 OpenIM Android SDK

## 模拟器步骤

1. 启动 `rtc-backend-go`（`make run`，`:8080`）。确认 App 已开通 IM。
2. 启动 AVD（API 24+）。Android Studio 打开 `example/`，Run `app`。
3. **快速验证 UI**（无需 OpenIM）：勾选 useMock → Init → Login（token 任意非空）→ Send → 日志出现 mock-echo。
4. **真链路**：取消 useMock；填 User JWT → **拉取 IM Token**（会回填 token / imApi / imWs）→ Init → Login → Send。
5. **预期**：mock 可完整走通；真 OpenIM 需宿主机 `10002/10001` 可达且后端 OpenIM 已配置。

## 真机步骤

1. 手机与电脑同一 Wi-Fi；查电脑 LAN IP（如 `192.168.1.8`）。
2. 将 apiBaseUrl（及必要时 imApi/imWs）改为局域网地址。
3. USB 安装或 `./gradlew :app:installDebug`。
4. 同模拟器：mock 或真 Token 流程。

## OpenIM 依赖说明

本库 `build.gradle.kts` 已使用 Maven Central 坐标（已验证可解析并打入 APK `lib/*/libgojni.so`）：

```gradle
implementation("io.openim:android-sdk:3.8.3.5@aar")
implementation("io.openim:core-sdk:3.8.3-patch15@aar")
```

`ImEngine`：`useMock=false` 时经 `OpenImBridge` 调用真实 OpenIM；Maven 不可用时勿强行改坐标，可保持 `useMock=true` 做 UI 联调（无需自建 HTTP 客户端替代）。

## 构建

```bash
cd sy-im-android-sdk/example
./gradlew :app:assembleDebug
```

minSdk **24**，targetSdk / compileSdk **35**。不要 `git push`。
