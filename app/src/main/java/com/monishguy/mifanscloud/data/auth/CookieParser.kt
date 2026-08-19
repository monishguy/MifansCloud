package com.monishguy.mifanscloud.data.auth

/**
 * 把用户从 i.mi.com 复制的整段 Cookie 解析为 [XiaomiCredential]。
 *
 * 规则（对齐 XiaomiAlbumSyncer / MiCloud 的认证契约）：
 * - `userId` 必填；
 * - 令牌取 `passToken`（换取链凭证），缺失时回退 `serviceToken`
 *   （**直连会话凭证**：浏览器已登录的现成会话，直接使用、跳过换取链）——
 *   很多用户的 Cookie 只有 serviceToken（passToken 需设备信任才下发）；
 * - 容忍空白、顺序、Cookie 属性字段（Path=/、HttpOnly 等，$ 前缀属性忽略）。
 */
object CookieParser {

    fun parse(raw: String): XiaomiCredential? {
        val fields = raw.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = part.substring(0, eq).trim()
            if (key.isEmpty() || key.startsWith("$")) return@mapNotNull null
            key to part.substring(eq + 1).trim()
        }.toMap()

        val userId = fields["userId"] ?: return null
        val passToken = fields["passToken"]
        val serviceToken = fields["serviceToken"]
        return when {
            passToken != null -> XiaomiCredential.PassToken(userId, passToken)
            serviceToken != null -> XiaomiCredential.ServiceToken(userId, serviceToken, raw)
            else -> null
        }
    }
}
