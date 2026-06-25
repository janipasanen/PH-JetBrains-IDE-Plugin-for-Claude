package io.github.janipasanen.claudeagent.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownToHtmlTest {

    @Test
    fun `inline formatting`() {
        val html = MarkdownToHtml.toHtml("This is **bold**, *italic* and `code`.")
        assertTrue(html.contains("<b>bold</b>"))
        assertTrue(html.contains("<i>italic</i>"))
        assertTrue(html.contains("<code>code</code>"))
    }

    @Test
    fun `fenced code block is escaped`() {
        val html = MarkdownToHtml.toHtml("```kotlin\nval x = a < b && c > d\n```")
        assertTrue(html.contains("<pre><code"))
        assertTrue(html.contains("a &lt; b &amp;&amp; c &gt; d"))
        assertFalse("raw angle brackets must be escaped", html.contains("a < b"))
    }

    @Test
    fun `headings and lists`() {
        val html = MarkdownToHtml.toHtml("# Title\n\n- one\n- two")
        assertTrue(html.contains("<h1>Title</h1>"))
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<li>one</li>"))
        assertTrue(html.contains("<li>two</li>"))
    }

    @Test
    fun `links and html escaping`() {
        val html = MarkdownToHtml.toHtml("see [docs](https://x.test) and <script>")
        assertTrue(html.contains("<a href=\"https://x.test\">docs</a>"))
        assertTrue("stray html must be escaped", html.contains("&lt;script&gt;"))
    }
}
