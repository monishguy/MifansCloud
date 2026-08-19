package com.monishguy.mifanscloud.data.remote

/**
 * 解包小米云签名中转 URL 返回的 JSONP 回调体。
 *
 * 上游形态（XiaomiAlbumSyncer XiaoMiApi / MiCloud gallery）：
 * `dl_callback({"url":"<下载地址>","meta":"<签名meta>"})`
 * 或 `cb({...})`；返回内层 JSON 字符串，由调用方用 org.json 解析。
 * （M2 时按范围纪律删除，M3 下载链路重新引入。）
 */
object JsonpParser {

    fun unwrap(body: String): String {
        val open = body.indexOf('(')
        require(open >= 0) { "JSONP 回调起始括号缺失: ${body.take(80)}" }
        val close = body.lastIndexOf(')')
        require(close > open) { "JSONP 回调结束括号缺失: ${body.take(80)}" }
        return body.substring(open + 1, close).trim()
    }
}
