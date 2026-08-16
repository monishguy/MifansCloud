package com.monishguy.mifanscloud.data.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

/**
 * 小米云 serviceToken 认证服务。
 *
 * 实现 XiaomiAlbumSyncer `TokenManager.kt` 的三步换取链（契约经
 * MiCloud 双源验证）：
 * ① `GET {base}/api/user/login?ts&followUp&_locale=zh_CN`（Cookie:
 *    userId + deviceId + passToken）→ `data.loginUrl`
 * ② 手动跟随 loginUrl（不跟随重定向，读 `Location` 头）→ STS URL
 * ③ GET STS URL → 首个 `Set-Cookie: serviceToken=...`
 *
 * serviceToken 有效期极短：缓存按 **凭证对** 隔离 + 命中后 10 分钟内复用，
 * 超时或换凭证即重新换取（`invalidateAndRefresh` 供 401 场景强制重换）。
 *
 * 设备侧：`deviceId = "wb_" + UUID`（`wb_` 前缀是 Web 设备标记，服务端强校验）。
 */
class XiaomiAuthService(
    private val client: OkHttpClient,
    baseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val baseUrl: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    private class CachedToken(
        val credential: XiaomiCredential.PassToken,
        val session: SessionToken,
    )

    @Volatile
    private var cache: CachedToken? = null

    /** 获取有效 serviceToken。 */
    @Synchronized
    fun getServiceToken(credential: XiaomiCredential): SessionToken = when (credential) {
        // 直连会话：浏览器已登录的 serviceToken 直接用，无需换取、不可刷新
        is XiaomiCredential.ServiceToken -> SessionToken(credential.serviceToken, obtainedAt = 0L)

        is XiaomiCredential.PassToken -> getPassTokenSession(credential)
    }

    private fun getPassTokenSession(credential: XiaomiCredential.PassToken): SessionToken {
        val now = clock()
        cache?.let { cached ->
            if (cached.credential == credential &&
                now - cached.session.obtainedAt < REFRESH_INTERVAL_MS
            ) {
                return cached.session
            }
        }
        return exchange(credential).also { session ->
            cache = CachedToken(credential, session)
        }
    }

    /** 清空缓存并立即重新换取（401 会话失效时调用，仅 passToken 凭证可用）。 */
    @Synchronized
    fun invalidateAndRefresh(credential: XiaomiCredential.PassToken): SessionToken {
        invalidate()
        return getPassTokenSession(credential)
    }

    /**
     * AutoRenewal 续期（对齐 MiCloud `status/setting`）：用**原始整段 Cookie**
     * 请求续期端点，从 Set-Cookie 换取新 serviceToken。只要浏览器会话存活，
     * 就无需重新登录复制（解决「每几分钟要重新取 Cookie」）。
     *
     * @param credential 直连会话（需带 [XiaomiCredential.ServiceToken.rawCookie]）
     * @return 新会话；续期端点未返回 serviceToken 时抛 [XiaomiAuthException]
     */
    fun renewServiceToken(credential: XiaomiCredential.ServiceToken): SessionToken {
        val rawCookie = credential.rawCookie
            ?: throw XiaomiAuthException("缺少整段 Cookie，无法自动续期（请重新粘贴）")
        val url = baseUrl.newBuilder()
            .addPathSegments("status/lite/setting")
            .addQueryParameter("type", "AutoRenewal")
            .addQueryParameter("inactiveTime", "10")
            .addQueryParameter("ts", clock().toString())
            .build()
        get(url.toString(), rawCookie).use { resp ->
            if (!resp.isSuccessful) throw XiaomiAuthException("续期失败 HTTP ${resp.code}")
            val setCookie = resp.headers("Set-Cookie")
                .firstOrNull { it.startsWith("serviceToken=") }
                ?: throw XiaomiAuthException("续期端点未返回 serviceToken（浏览器会话可能已失效，请重新登录）")
            return SessionToken(
                serviceToken = setCookie.substringAfter('=').substringBefore(';'),
                obtainedAt = clock(),
            )
        }
    }

    /** 仅清空缓存，不发起网络请求。 */
    fun invalidate() {
        cache = null
    }

    /**
     * WebView 登录用：请求 `GET /api/user/login` 获取**小米服务器下发的合法登录链**
     * （含正确 callback 与 sign——手工拼接的 callback 会报「Callback 连接不合法 10025」）。
     * 游客态即可访问，无需凭证 Cookie。
     */
    fun fetchWebLoginUrl(): String = requestLoginUrl("")

    /** 无条件执行三步换取链（测试与手动刷新入口）。 */
    fun exchange(credential: XiaomiCredential.PassToken): SessionToken {
        val deviceId = "wb_" + UUID.randomUUID()
        val cookie = buildCookie(credential.userId, deviceId, credential.passToken)

        val loginUrl = requestLoginUrl(cookie)
        val stsUrl = followLoginUrl(loginUrl, cookie)
        val serviceToken = requestServiceToken(stsUrl, cookie)
        return SessionToken(serviceToken, clock())
    }

    private fun requestLoginUrl(cookie: String): String {
        val url = baseUrl.newBuilder()
            .addPathSegment("api").addPathSegment("user").addPathSegment("login")
            .addQueryParameter("ts", clock().toString())
            .addQueryParameter("followUp", baseUrl.toString() + "/")
            .addQueryParameter("_locale", "zh_CN")
            .build()
        get(url.toString(), cookie).use { resp ->
            if (!resp.isSuccessful) throw XiaomiAuthException("预登录失败 HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val loginUrl = json.optJSONObject("data")?.optString("loginUrl")
                ?.takeIf { it.isNotBlank() }
                ?: throw XiaomiAuthException("预登录响应缺少 loginUrl")
            return loginUrl
        }
    }

    private fun followLoginUrl(loginUrl: String, cookie: String): String {
        get(loginUrl, cookie).use { resp ->
            val location = resp.header("Location")
            if (location.isNullOrBlank()) {
                throw XiaomiAuthException("跟随登录 URL 未返回 Location（HTTP ${resp.code}）")
            }
            return location
        }
    }

    private fun requestServiceToken(stsUrl: String, cookie: String): String {
        get(stsUrl, cookie).use { resp ->
            val setCookie = resp.headers("Set-Cookie")
                .firstOrNull { it.startsWith("serviceToken=") }
                ?: throw XiaomiAuthException("STS 响应缺少 serviceToken Set-Cookie")
            return setCookie.substringAfter('=').substringBefore(';')
        }
    }

    private fun get(url: String, cookie: String): okhttp3.Response {
        val request = Request.Builder().url(url)
            .header(HEADER_USER_AGENT, UA)
            .header(HEADER_COOKIE, cookie)
            .get()
            .build()
        return client.newCall(request).execute()
    }

    private fun buildCookie(userId: String, deviceId: String, passToken: String): String =
        "userId=$userId; deviceId=$deviceId; passToken=$passToken;"

    companion object {
        /** serviceToken 强制刷新间隔：10 分钟（对齐 XiaomiAlbumSyncer TokenManager）。 */
        const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L

        /** 桌面 Chrome UA（小米服务对非浏览器 UA 敏感）。 */
        const val UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36 Edg/139.0.0.0"

        private const val HEADER_COOKIE = "Cookie"
        private const val HEADER_USER_AGENT = "User-Agent"
    }
}
