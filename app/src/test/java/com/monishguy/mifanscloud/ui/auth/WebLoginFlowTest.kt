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
    fun `游客会话仅 userId 不视为已登录（i_mi_com 会给游客下发 userId）`() {
        assertEquals(
            WebLoginFlow.Decision.KeepWaiting,
            WebLoginFlow.decide("userId=42; deviceId=wb_x; locale=zh_CN", galleryVisited = false),
        )
    }

    @Test
    fun `serviceToken 直连会话无需访问相册页直接提取`() {
        val raw = "userId=42; serviceToken=st_only; i.mi.com_isvalid_servicetoken=true"

        assertEquals(WebLoginFlow.Decision.Extracted(raw), WebLoginFlow.decide(raw, galleryVisited = false))
    }

    @Test
    fun `passToken 凭证且已访问相册页则提取原始 cookie`() {
        val raw = "userId=42; passToken=pt_abc; serviceToken=st_zzz"

        assertEquals(WebLoginFlow.Decision.Extracted(raw), WebLoginFlow.decide(raw, galleryVisited = true))
    }

    @Test
    fun `passToken 凭证但尚未访问相册页时跳转相册页触发设备验证`() {
        val raw = "userId=42; passToken=pt_abc"

        assertEquals(WebLoginFlow.Decision.NavigateToGallery, WebLoginFlow.decide(raw, galleryVisited = false))
    }

    @Test
    fun `已访问相册页仍只有 userId 时继续等待（不循环跳转）`() {
        assertEquals(
            WebLoginFlow.Decision.KeepWaiting,
            WebLoginFlow.decide("userId=42; deviceId=wb_x", galleryVisited = true),
        )
    }
}
