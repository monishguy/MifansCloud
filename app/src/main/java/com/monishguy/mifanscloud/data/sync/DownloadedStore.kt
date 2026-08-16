package com.monishguy.mifanscloud.data.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「已下载到本工具」清单持久化（JSON 文件，app 私有目录）。
 *
 * 记录 云端资产 id → 本地文件名，用于：
 * - [CloudLocalMatcher] 的 DOWNLOADED 状态（优先于「本机已有」）；
 * - 下载去重（已下载不再重复下载）。
 *
 * 10k 级条目 JSON 文件无压力；未来如需强类型查询可平滑替换为 Room（文档 §9.5）。
 */
class DownloadedStore(private val file: File) {

    @Volatile
    private var cache: Map<String, String>? = null

    /** 记录一次下载完成。 */
    @Synchronized
    fun add(id: String, fileName: String) {
        val updated = entries().toMutableMap()
        updated[id] = fileName
        cache = updated
        write(updated)
    }

    /** 已下载的云端资产 id 集合。 */
    @Synchronized
    fun ids(): Set<String> = entries().keys

    /** 已下载资产对应的本地文件名。 */
    @Synchronized
    fun fileNameOf(id: String): String? = entries()[id]

    private fun entries(): Map<String, String> {
        cache?.let { return it }
        if (!file.exists()) return emptyMap()
        val map = mutableMapOf<String, String>()
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("id")] = o.optString("fileName")
            }
        }
        cache = map
        return map
    }

    private fun write(map: Map<String, String>) {
        val arr = JSONArray()
        map.forEach { (id, name) ->
            arr.put(JSONObject().put("id", id).put("fileName", name))
        }
        file.parentFile?.mkdirs()
        file.writeText(arr.toString())
    }
}
