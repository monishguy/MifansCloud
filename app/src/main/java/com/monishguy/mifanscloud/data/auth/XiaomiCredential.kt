package com.monishguy.mifanscloud.data.auth

/**
 * 小米云 Web 端认证凭证（两种形态）。
 *
 * - [PassToken]：浏览器 Cookie 中的 `passToken`（需经三步换取链换取 serviceToken，
 *   支持 10 分钟周期刷新与 401 自动重试）；
 * - [ServiceToken]：浏览器 Cookie 中的 `serviceToken`（**已登录的现成会话**，
 *   跳过换取链直接使用）。若保留 [rawCookie]（粘贴的整段 Cookie），可通过
 *   AutoRenewal 端点**自动续期**，无需重新登录复制。
 *
 * 用户从 i.mi.com 复制的 Cookie 往往只有 serviceToken（passToken 需完成
 * 设备信任才下发），因此两种形态都必须支持。
 */
sealed interface XiaomiCredential {

    val userId: String

    data class PassToken(
        override val userId: String,
        val passToken: String,
    ) : XiaomiCredential

    data class ServiceToken(
        override val userId: String,
        val serviceToken: String,
        /** 粘贴的整段 Cookie（用于 AutoRenewal 续期；WebView 提取时也可保留）。 */
        val rawCookie: String? = null,
    ) : XiaomiCredential
}
