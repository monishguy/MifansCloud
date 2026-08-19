package com.monishguy.mifanscloud.ui.auth

import com.monishguy.mifanscloud.data.auth.CookieParser
import com.monishguy.mifanscloud.data.auth.XiaomiCredential

/**
 * WebView 内嵌登录的决策状态机（纯逻辑，可单元测试）。
 *
 * - **serviceToken 直连会话**：浏览器已登录的现成会话，直接提取
 *   （无需访问相册页——相册页只为触发设备验证换取 passToken，直连模式不需要）；
 * - **passToken 凭证**：需访问相册页触发设备信任（§2.1 gotcha：passToken
 *   只有在设备信任后才下发），访问后即可提取，并可自动刷新；
 * - 其余情况（未登录 / 游客 userId）→ [Decision.KeepWaiting]，
 *   已访问相册页仍无凭证时也保持等待，避免无限跳转。
 */
object WebLoginFlow {

    const val HOME_URL = "https://i.mi.com/"

    /** 小米云登录链（i.mi.com 主页右上角「登录」的真实跳转目标）：登录成功后 302 回 i.mi.com。 */
    const val LOGIN_URL =
        "https://account.xiaomi.com/pass/serviceLogin" +
            "?sid=i.mi.com" +
            "&callback=https%3A%2F%2Fi.mi.com%2F" +
            "&_locale=zh_CN"

    const val GALLERY_URL = "https://i.mi.com/gallery/h5#/"

    sealed interface Decision {
        data object KeepWaiting : Decision
        data object NavigateToGallery : Decision
        data class Extracted(val rawCookie: String) : Decision
    }

    fun decide(rawCookie: String?, galleryVisited: Boolean): Decision {
        if (rawCookie.isNullOrBlank()) return Decision.KeepWaiting
        return when (val parsed = CookieParser.parse(rawCookie)) {
            is XiaomiCredential.ServiceToken -> Decision.Extracted(rawCookie)
            is XiaomiCredential.PassToken ->
                if (galleryVisited) Decision.Extracted(rawCookie) else Decision.NavigateToGallery
            null -> Decision.KeepWaiting
        }
    }
}
