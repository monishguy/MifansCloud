package com.monishguy.mifanscloud.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CookieParser seam：把用户从 i.mi.com 复制的整段 Cookie 解析为认证凭证。
 * 规则：userId 必填；passToken 优先（换取链凭证），缺失时回退 serviceToken
 * （直连会话凭证，跳过换取链直接使用）。
 */
class CookieParserTest {

    @Test
    fun `解析完整 cookie 串，提取 userId 与 passToken`() {
        val raw = "userId=12345678; passToken=pt_abc123; deviceId=wb_xxx; locale=zh_CN"

        val credential = CookieParser.parse(raw)

        assertEquals(XiaomiCredential.PassToken("12345678", "pt_abc123"), credential)
    }

    @Test
    fun `字段顺序无关`() {
        val raw = "passToken=pt_xyz; userId=42; serviceToken=st_zzz"

        val credential = CookieParser.parse(raw)

        assertEquals(XiaomiCredential.PassToken("42", "pt_xyz"), credential)
    }

    @Test
    fun `passToken 与 serviceToken 同时存在时优先 passToken`() {
        val raw = "userId=42; serviceToken=st_zzz; passToken=pt_ppp"

        val credential = CookieParser.parse(raw)

        assertEquals("pt_ppp", (credential as XiaomiCredential.PassToken).passToken)
    }

    @Test
    fun `只有 serviceToken 返回直连会话凭证（跳过换取链）`() {
        val raw = "userId=42; serviceToken=st_only; i.mi.com_isvalid_servicetoken=true"

        val credential = CookieParser.parse(raw)

        assertEquals(XiaomiCredential.ServiceToken("42", "st_only"), credential)
    }

    @Test
    fun `容忍属性字段与空白`() {
        val raw = " userId = 42 ; passToken=pt_t ; Path=/ ; HttpOnly "

        val credential = CookieParser.parse(raw)

        assertEquals(XiaomiCredential.PassToken("42", "pt_t"), credential)
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
