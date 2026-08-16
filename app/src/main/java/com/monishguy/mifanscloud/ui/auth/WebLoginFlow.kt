package com.monishguy.mifanscloud.ui.auth

import com.monishguy.mifanscloud.data.auth.CookieParser

/**
 * WebView 内嵌登录的决策状态机（纯逻辑，可单元测试）。
 *
 * 依据文档 §2.1 的 gotcha：`passToken` 只有在完成设备信任后才会下发，
 * 而设备信任由「访问相册页」触发——因此登录后需要自动跳一次相册页。
 *
 * 判定规则：
 * - Cookie 可解析出 userId+passToken 且已访问相册页 → [Decision.Extracted]；
 * - **已登录**（存在 passToken 或 serviceToken；仅 userId 是游客会话，
 *   i.mi.com 会给游客也下发 userId）且未访问相册页 → [Decision.NavigateToGallery]；
 * - 其余情况 → [Decision.KeepWaiting]（含访问过相册页仍缺 passToken 的情形，
 *   此时等待用户完成验证或手动触发，避免无限跳转）。
 */
object WebLoginFlow {

    const val HOME_URL = "https://i.mi.com/"
    const val GALLERY_URL = "https://i.mi.com/gallery/h5#/"

    sealed interface Decision {
        data object KeepWaiting : Decision
        data object NavigateToGallery : Decision
        data class Extracted(val rawCookie: String) : Decision
    }

    fun decide(rawCookie: String?, galleryVisited: Boolean): Decision {
        if (rawCookie.isNullOrBlank()) return Decision.KeepWaiting
        val parsed = CookieParser.parse(rawCookie)
        val loggedIn = hasField(rawCookie, KEY_PASS_TOKEN) || hasField(rawCookie, KEY_SERVICE_TOKEN)
        return when {
            parsed != null && galleryVisited -> Decision.Extracted(rawCookie)
            loggedIn && !galleryVisited -> Decision.NavigateToGallery
            else -> Decision.KeepWaiting
        }
    }

    private fun hasField(raw: String, name: String): Boolean =
        raw.split(';').any { part ->
            val eq = part.indexOf('=')
            eq > 0 && part.substring(0, eq).trim() == name
        }

    private const val KEY_PASS_TOKEN = "passToken"
    private const val KEY_SERVICE_TOKEN = "serviceToken"
}
