package com.sy.im.sdk

/**
 * SY 控制面（`/api/user/im/…`、`/api/server/im/token`）返回的业务码。
 * Android `ImErrorCode`、iOS `SyImErrorCode`、Flutter `SyImErrorCode` 取值相同，
 * 与服务端 `errcode` 包一致。OpenIM SDK 自己的错误码（登录、收发）不在此列，原样透传。
 */
object ImErrorCode {
    /** 未登录或 User JWT 无效。 */
    const val UNAUTHORIZED = 401
    /** 无权访问该应用。 */
    const val FORBIDDEN = 403
    /** 应用未开通 IM。 */
    const val IM_NOT_ENABLED = 3001
    /** 月活超出套餐。 */
    const val QUOTA_MAU = 3003
    /** 消息量超出套餐。 */
    const val QUOTA_MESSAGES = 3004
    /** 体验版已下线。 */
    const val TRIAL_RETIRED = 4003
    /** 敏感词拦截（拒绝模式）。 */
    const val SENSITIVE_REJECTED = 4005
    /** 发送前内容审核拒绝，或审核服务不可达（阻断模式）。 */
    const val CONTENT_REJECTED = 4006
    /** AppId 的访问凭证已暂停。 */
    const val CREDENTIAL_SUSPENDED = 4031
    /** AppId 的访问凭证已吊销。 */
    const val CREDENTIAL_REVOKED = 4032
    /** AppId 的访问凭证已过期。 */
    const val CREDENTIAL_EXPIRED = 4033
    /** 请求过于频繁。 */
    const val RATE_LIMITED = 4290

    fun isCredentialBlocked(code: Int): Boolean =
        code == CREDENTIAL_SUSPENDED || code == CREDENTIAL_REVOKED || code == CREDENTIAL_EXPIRED

    /** 消息被内容策略拦截（敏感词或发送前审核）。 */
    fun isContentRejected(code: Int): Boolean = code == SENSITIVE_REJECTED || code == CONTENT_REJECTED
}

/**
 * 控制面请求失败。[code] 为响应体的业务码（见 [ImErrorCode]）；响应体没有 code 时为 HTTP 状态码。
 * 仍是 [Exception]，已有的 `Exception?` 回调无需改动，按需 `as? ImControlPlaneException` 取码。
 */
class ImControlPlaneException(
    val code: Int,
    val httpStatus: Int,
    message: String,
) : Exception(message) {
    val isCredentialBlocked: Boolean get() = ImErrorCode.isCredentialBlocked(code)
    val isContentRejected: Boolean get() = ImErrorCode.isContentRejected(code)

    override fun toString(): String = "ImControlPlaneException(code=$code, http=$httpStatus): $message"
}
