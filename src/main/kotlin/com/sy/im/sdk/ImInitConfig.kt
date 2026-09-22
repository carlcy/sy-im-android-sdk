package com.sy.im.sdk

/**
 * IM 初始化配置。
 *
 * @param appId SY 控制台 AppId（与 RTC 共用）
 * @param apiBaseUrl SY 控制面 Base URL（发 Token / 拉配置），不是 OpenIM 地址
 * @param useMock true：本地 mock，无需 OpenIM 服务；false：走 Maven 依赖的 OpenIM Android SDK
 * @param imApiAddr OpenIM API，如 http://10.0.2.2:10002
 * @param imWsAddr OpenIM WS，如 ws://10.0.2.2:10001
 */
data class ImInitConfig(
    val appId: String,
    val apiBaseUrl: String,
    val useMock: Boolean = true,
    val imApiAddr: String = "http://127.0.0.1:10002",
    val imWsAddr: String = "ws://127.0.0.1:10001",
)
