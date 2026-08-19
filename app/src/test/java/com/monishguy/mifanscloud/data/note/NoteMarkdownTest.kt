package com.monishguy.mifanscloud.data.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `图片占位行往返转换保真（保存不毁附件）`() {
        // 云端 → Markdown
        val snippet = "☺ 2335557485.abc<0/></>\n<text indent=\"1\">正文</text>"
        val md = NoteMarkdown.snippetToMarkdown(snippet)
        assertEquals("![附件 2335557485.abc]()\n\n正文", md)

        // Markdown → 云端（还原图片标记，不能变成纯文本）
        val back = NoteMarkdown.markdownToSnippet(md)
        assertTrue("图片行应还原为云端标记", back.startsWith("☺ 2335557485.abc<0/></>"))
        assertTrue("正文行应转回 text 标记", back.contains("<text indent=\"1\">正文</text>"))
        assertFalse("不能把图片变成纯文本", back.contains("!["))
    }

    @Test
    fun `提取附件 fileId 列表`() {
        val md = "![附件 a.b]()\n![附件 c.d]()"
        assertEquals(listOf("a.b", "c.d"), NoteMarkdown.extractFileIds(md))
    }
}
