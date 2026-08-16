package com.monishguy.mifanscloud.data.auth

/**
 * 把用户从 i.mi.com 复制的整段 Cookie 解析为 [XiaomiCredentials]。
 *
 * 规则（对齐 XiaomiAlbumSyncer / MiCloud 的认证契约）：
 * - `userId` 与 `passToken` 均必填；
 * - **serviceToken 不可替代 passToken**：换取链要求浏览器 `passToken`
 *   （`Cookie: passToken=...`），只有 serviceToken 说明未完成设备信任
 *   （需先访问相册页触发验证），此类 Cookie 无法换取新 token，返回 null；
 * - 容忍空白、顺序、Cookie 属性字段（Path=/、HttpOnly 等，$ 前缀属性忽略）。
 */
object CookieParser {

    fun parse(raw: String): XiaomiCredentials? {
        val fields = raw.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = part.substring(0, eq).trim()
            if (key.isEmpty() || key.startsWith("$")) return@mapNotNull null
            key to part.substring(eq + 1).trim()
        }.toMap()

        val userId = fields["userId"] ?: return null
        val passToken = fields["passToken"] ?: return null
        return XiaomiCredentials(userId = userId, passToken = passToken)
    }
}
