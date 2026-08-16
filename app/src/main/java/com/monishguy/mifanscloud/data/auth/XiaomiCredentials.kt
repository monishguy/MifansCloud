package com.monishguy.mifanscloud.data.auth

/**
 * 小米云 Web 端凭证对。
 *
 * @param userId   浏览器 Cookie 中的 userId
 * @param passToken 浏览器 Cookie 中的 passToken（换取 serviceToken 的原料；
 *                  请求链中作为 `Cookie: passToken=...` 发送）
 */
data class XiaomiCredentials(
    val userId: String,
    val passToken: String,
)
