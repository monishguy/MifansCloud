package com.monishguy.mifanscloud.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JsonpParser seam：把小米云签名中转 URL 返回的 JSONP 回调体解包为内层 JSON 字符串。
 * 形如：dl_callback({"url":"...","meta":"..."})
 */
class JsonpParserTest {

    @Test
    fun `解包 dl_callback 回调体`() {
        val body = """dl_callback({"url":"http://cdn/x","meta":"sig_abc"})"""

        val json = JsonpParser.unwrap(body)

        assertEquals("""{"url":"http://cdn/x","meta":"sig_abc"}""", json)
    }

    @Test
    fun `解包自定义回调名 cb`() {
        val body = """cb({"url":"http://y","meta":"m"})"""

        assertEquals("""{"url":"http://y","meta":"m"}""", JsonpParser.unwrap(body))
    }

    @Test
    fun `容忍首尾空白`() {
        val body = "  dl_callback( { \"url\" : \"u\" } )  "

        assertEquals("""{ "url" : "u" }""", JsonpParser.unwrap(body))
    }

    @Test
    fun `缺少起始括号抛异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonpParser.unwrap("""{"url":"x"}""")
        }
    }

    @Test
    fun `缺少结束括号抛异常`() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonpParser.unwrap("dl_callback({")
        }
    }
}
