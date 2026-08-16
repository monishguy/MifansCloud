package com.monishguy.mifanscloud.data.remote

import com.monishguy.mifanscloud.data.auth.SessionToken
import com.monishguy.mifanscloud.data.auth.XiaomiAuthService
import com.monishguy.mifanscloud.data.auth.XiaomiCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 带认证的 i.mi.com 客户端。
 *
 * 为每个请求注入 `Cookie: userId=...; serviceToken=...;`（对齐
 * XiaomiAlbumSyncer `authHeader`）；收到 401 时刷新 serviceToken 并
 * **重发一次**（对齐 MiCloud 的 401 自动重登）。M3 起各数据模块统一走此客户端。
 */
class XiaomiApiClient(
    private val client: OkHttpClient,
    private val auth: XiaomiAuthService,
    private val credentialsProvider: () -> XiaomiCredentials,
) {

    /**
     * 执行请求；调用方负责 close 返回的 Response。
     * 返回 401 之外的原始响应，或刷新后重发的响应。
     */
    fun execute(request: Request): Response {
        val credentials = credentialsProvider()
        val first = callWithToken(request, credentials, auth.getServiceToken(credentials))
        if (first.code != 401) return first
        first.close()

        val refreshed = auth.invalidateAndRefresh(credentials)
        return callWithToken(request, credentials, refreshed)
    }

    private fun callWithToken(
        request: Request,
        credentials: XiaomiCredentials,
        token: SessionToken,
    ): Response {
        val authenticated = request.newBuilder()
            .header(HEADER_USER_AGENT, XiaomiAuthService.UA)
            .header(HEADER_COOKIE, "userId=${credentials.userId}; serviceToken=${token.serviceToken};")
            .build()
        return client.newCall(authenticated).execute()
    }

    private companion object {
        const val HEADER_COOKIE = "Cookie"
        const val HEADER_USER_AGENT = "User-Agent"
    }
}
