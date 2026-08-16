package com.monishguy.mifanscloud.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WebLoginFlow seam：根据当前 i.mi.com Cookie 与「是否已访问相册页」决定
 * WebView 登录流程的下一步（继续等待 / 跳相册页触发设备验证 / 提取凭证）。
 */
class WebLoginFlowTest {

    @Test
    fun `无 cookie 时继续等待`() {
        assertEquals(WebLoginFlow.Decision.KeepWaiting, WebLoginFlow.decide(null, galleryVisited = false))
        assertEquals(WebLoginFlow.Decision.KeepWaiting, WebLoginFlow.decide("", galleryVisited = false))
    }

    @Test
    fun `未登录的普通页面 cookie 继续等待`() {
        assertEquals(
            WebLoginFlow.Decision.KeepWaiting,
            WebLoginFlow.decide("locale=zh_CN; foo=bar", galleryVisited = false),
        )
    }

    @Test
    fun `已登录但缺 passToken 时跳转相册页触发设备验证`() {
        assertEquals(
            WebLoginFlow.Decision.NavigateToGallery,
            WebLoginFlow.decide("userId=42; serviceToken=st_only", galleryVisited = false),
        )
    }

    @Test
    fun `完整凭证但尚未访问相册页仍跳转相册页`() {
        assertEquals(
            WebLoginFlow.Decision.NavigateToGallery,
            WebLoginFlow.decide("userId=42; passToken=pt_abc", galleryVisited = false),
        )
    }

    @Test
    fun `完整凭证且已访问相册页则提取原始 cookie`() {
        val raw = "userId=42; passToken=pt_abc; serviceToken=st_zzz"

        assertEquals(WebLoginFlow.Decision.Extracted(raw), WebLoginFlow.decide(raw, galleryVisited = true))
    }

    @Test
    fun `已访问相册页后仍缺 passToken 继续等待（不循环跳转）`() {
        assertEquals(
            WebLoginFlow.Decision.KeepWaiting,
            WebLoginFlow.decide("userId=42; serviceToken=st_only", galleryVisited = true),
        )
    }
}
