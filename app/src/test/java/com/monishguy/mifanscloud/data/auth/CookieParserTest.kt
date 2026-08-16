package com.monishguy.mifanscloud.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CookieParser seam：把用户从 i.mi.com 复制的整段 Cookie 解析为凭证对。
 * 规则：userId 与 passToken 均必填；serviceToken 不可替代 passToken。
 */
class CookieParserTest {

    @Test
    fun `解析完整 cookie 串，提取 userId 与 passToken`() {
        val raw = "userId=12345678; passToken=pt_abc123; deviceId=wb_xxx; locale=zh_CN"

        val credentials = CookieParser.parse(raw)

        assertEquals(XiaomiCredentials("12345678", "pt_abc123"), credentials)
    }

    @Test
    fun `字段顺序无关`() {
        val raw = "passToken=pt_xyz; userId=42; serviceToken=st_zzz"

        val credentials = CookieParser.parse(raw)

        assertEquals(XiaomiCredentials("42", "pt_xyz"), credentials)
    }

    @Test
    fun `passToken 与 serviceToken 同时存在时优先 passToken`() {
        val raw = "userId=42; serviceToken=st_zzz; passToken=pt_ppp"

        val credentials = CookieParser.parse(raw)

        assertEquals("pt_ppp", credentials?.passToken)
    }

    @Test
    fun `只有 serviceToken 没有 passToken 返回 null（换取链必需 passToken）`() {
        assertNull(CookieParser.parse("userId=42; serviceToken=st_only"))
    }

    @Test
    fun `容忍属性字段与空白`() {
        val raw = " userId = 42 ; passToken=pt_t ; Path=/ ; HttpOnly "

        val credentials = CookieParser.parse(raw)

        assertEquals(XiaomiCredentials("42", "pt_t"), credentials)
    }

    @Test
    fun `缺少 userId 返回 null`() {
        assertNull(CookieParser.parse("passToken=pt_abc"))
    }

    @Test
    fun `没有任何令牌返回 null`() {
        assertNull(CookieParser.parse("userId=42; deviceId=wb_xxx"))
    }

    @Test
    fun `空串与乱码返回 null`() {
        assertNull(CookieParser.parse(""))
        assertNull(CookieParser.parse(";;;===;;;"))
    }
}
