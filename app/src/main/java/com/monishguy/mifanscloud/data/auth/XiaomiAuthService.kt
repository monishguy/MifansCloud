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
        val credentials: XiaomiCredentials,
        val session: SessionToken,
    )

    @Volatile
    private var cache: CachedToken? = null

    /** 获取有效 serviceToken：同凭证 10 分钟内复用缓存，否则重新换取。 */
    @Synchronized
    fun getServiceToken(credentials: XiaomiCredentials): SessionToken {
        val now = clock()
        cache?.let { cached ->
            if (cached.credentials == credentials &&
                now - cached.session.obtainedAt < REFRESH_INTERVAL_MS
            ) {
                return cached.session
            }
        }
        return exchange(credentials).also { session ->
            cache = CachedToken(credentials, session)
        }
    }

    /** 清空缓存并立即重新换取（401 会话失效时调用）。 */
    @Synchronized
    fun invalidateAndRefresh(credentials: XiaomiCredentials): SessionToken {
        invalidate()
        return getServiceToken(credentials)
    }

    /** 仅清空缓存，不发起网络请求。 */
    fun invalidate() {
        cache = null
    }

    /** 无条件执行三步换取链（测试与手动刷新入口）。 */
    fun exchange(credentials: XiaomiCredentials): SessionToken {
        val deviceId = "wb_" + UUID.randomUUID()
        val cookie = buildCookie(credentials.userId, deviceId, credentials.passToken)

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
