package com.sy.im.sdk

/** 异步结果回调（登录等）。 */
fun interface ImCallback {
    fun onResult(success: Boolean, code: Int, message: String?)
}
