package com.monishguy.mifanscloud.data.note

import org.junit.Assert.assertEquals
import org.junit.Test

/** 小米云笔记富文本 → Markdown 转换（真机导出结构验证）。 */
class NoteMarkdownTest {

    @Test
    fun `标题取自 extraInfo 的 title 字段`() {
        val extra = """{"title":"怪猎荒野","note_content_type":"common"}"""
        val content = """{"title":"","mind_content":""}"""

        assertEquals("怪猎荒野", NoteMarkdown.parseTitle(extra, content))
    }

    @Test
    fun `extraInfo 无标题时回退 content 的 title`() {
        val content = """{"title":"178","note_content_type":"common"}"""

        assertEquals("178", NoteMarkdown.parseTitle(null, content))
    }

    @Test
    fun `snippet 正文行转换为 Markdown 段落`() {
        val snippet = "<text indent=\"1\">CICF2-PHFXC-03BMQ</text>\n" +
            "<text indent=\"1\">第二行内容</text>"

        val md = NoteMarkdown.snippetToMarkdown(snippet)

        assertEquals("CICF2-PHFXC-03BMQ\n\n第二行内容", md)
    }

    @Test
    fun `缩进层级映射为 Markdown 缩进`() {
        val snippet = "<text indent=\"1\">顶层</text>\n<text indent=\"2\">子项</text>"

        val md = NoteMarkdown.snippetToMarkdown(snippet)

        assertEquals("顶层\n\n  子项", md)
    }

    @Test
    fun `图片占位行渲染为图片引用`() {
        val snippet = "☺ 2335557485.abc<0/></>"

        val md = NoteMarkdown.snippetToMarkdown(snippet)

        assertEquals("![附件 2335557485.abc]()", md)
    }

    @Test
    fun `HTML 实体反转义`() {
        val snippet = "<text indent=\"1\">a &lt; b &amp; c &gt; d</text>"

        val md = NoteMarkdown.snippetToMarkdown(snippet)

        assertEquals("a < b & c > d", md)
    }

    @Test
    fun `空行与空白内容被忽略`() {
        val snippet = "<text indent=\"1\"></text>\n\n<text indent=\"1\">有内容</text>"

        val md = NoteMarkdown.snippetToMarkdown(snippet)

        assertEquals("有内容", md)
    }
}
