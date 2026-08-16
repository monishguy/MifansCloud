package com.monishguy.mifanscloud.data.auth

/**
 * 小米云认证链失败（凭证无效、预登录失败、缺少 Location、
 * 缺少 serviceToken Set-Cookie 等）。UI 层据此提示用户重新配置凭证。
 */
class XiaomiAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
