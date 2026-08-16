package com.monishguy.mifanscloud.data.note

import org.json.JSONObject

/**
 * 小米云笔记富文本 → Markdown 转换（纯函数，可单元测试）。
 *
 * 云端笔记真实结构（真机导出验证）：
 * - 标题：`extraInfo` JSON 的 `title` 字段（如 {"title":"怪猎荒野"}），
 *   `content` JSON 中也有 `title`；`subject` 字段恒为空；
 * - 正文：`snippet` 富文本 XML：`<text indent="N">内容</text>` 行、
 *   `<0/>` 图片占位（fileId 在 setting.data）、`☺ fileId<0/></>` 图片行。
 */
object NoteMarkdown {

    /** 解析标题：extraInfo.title → content.title → 空串。 */
    fun parseTitle(extraInfo: String?, content: String?): String {
        val fromExtra = parseTitleFromJson(extraInfo)
        if (fromExtra.isNotBlank()) return fromExtra
        return parseTitleFromJson(content)
    }

    private fun parseTitleFromJson(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString("title") }.getOrDefault("")
    }

    /** 把 snippet 富文本转换为 Markdown 正文。 */
    fun snippetToMarkdown(snippet: String): String {
        if (snippet.isBlank()) return ""
        val lines = mutableListOf<String>()
        for (rawLine in snippet.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val rendered = renderLine(line)
            if (rendered.isNotBlank()) lines += rendered
        }
        return lines.joinToString("\n\n")
    }

    /** 单行渲染：<text indent="N">…</text> / <0/> 图片 / 裸文本。 */
    private fun renderLine(line: String): String {
        // 图片行：☺ fileId<0/></> 或 <0/>
        if (line.contains("<0/>")) {
            val fileId = line
                .removePrefix("☺")
                .substringBefore("<0/>")
                .trim()
                .ifBlank { "附件" }
            return "![附件 $fileId]()"
        }
        // 正文行：<text indent="N">内容</text>
        val textMatch = Regex("""<text indent="(\d+)">(.*)</text>""").find(line)
        if (textMatch != null) {
            val indent = (textMatch.groupValues[1].toIntOrNull() ?: 1).coerceAtLeast(1)
            val content = unescapeHtml(textMatch.groupValues[2])
            if (content.isBlank()) return ""
            val prefix = if (indent > 1) "  ".repeat(indent - 1) else ""
            return prefix + content
        }
        // 其它标签行（list 等）：剥掉标签保留文本
        val textOnly = line
            .replace(Regex("""<[^>]+>"""), "")
            .trim()
        return unescapeHtml(textOnly)
    }

    /** 反转义 HTML 实体（富文本中的 &lt; &gt; &amp; &quot; &#39;）。 */
    private fun unescapeHtml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
