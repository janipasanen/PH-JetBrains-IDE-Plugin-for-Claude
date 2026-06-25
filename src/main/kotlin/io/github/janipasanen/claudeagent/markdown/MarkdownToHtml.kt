package io.github.janipasanen.claudeagent.markdown

/**
 * Minimal, dependency-free Markdown → HTML converter for the JCEF transcript. Covers the subset
 * that shows up in chat: fenced code, inline code, bold/italic, headings, lists, links, paragraphs.
 * Intentionally small (the platform-only classpath has no Markdown library).
 */
object MarkdownToHtml {

    fun toHtml(markdown: String): String {
        val out = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        var i = 0
        var inUl = false
        var inOl = false

        fun closeLists() {
            if (inUl) { out.append("</ul>"); inUl = false }
            if (inOl) { out.append("</ol>"); inOl = false }
        }

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block.
            val fence = Regex("^```(.*)$").find(line.trimEnd())
            if (fence != null) {
                closeLists()
                val lang = fence.groupValues[1].trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimEnd().startsWith("```")) {
                    code.append(escape(lines[i])).append('\n')
                    i++
                }
                i++ // consume closing fence
                val cls = if (lang.isNotEmpty()) " class=\"lang-${escape(lang)}\"" else ""
                out.append("<pre><code$cls>").append(code.toString().trimEnd('\n')).append("</code></pre>")
                continue
            }

            // Headings.
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
            if (heading != null) {
                closeLists()
                val level = heading.groupValues[1].length
                out.append("<h$level>").append(inline(heading.groupValues[2])).append("</h$level>")
                i++
                continue
            }

            // Unordered list.
            val ul = Regex("^\\s*[-*+]\\s+(.*)$").find(line)
            if (ul != null) {
                if (inOl) { out.append("</ol>"); inOl = false }
                if (!inUl) { out.append("<ul>"); inUl = true }
                out.append("<li>").append(inline(ul.groupValues[1])).append("</li>")
                i++
                continue
            }

            // Ordered list.
            val ol = Regex("^\\s*\\d+\\.\\s+(.*)$").find(line)
            if (ol != null) {
                if (inUl) { out.append("</ul>"); inUl = false }
                if (!inOl) { out.append("<ol>"); inOl = true }
                out.append("<li>").append(inline(ol.groupValues[1])).append("</li>")
                i++
                continue
            }

            // Blank line ends a paragraph/list.
            if (line.isBlank()) {
                closeLists()
                i++
                continue
            }

            // Paragraph (merge consecutive non-blank, non-special lines).
            closeLists()
            val para = StringBuilder(line)
            i++
            while (i < lines.size && lines[i].isNotBlank() &&
                !lines[i].trimEnd().startsWith("```") &&
                Regex("^(#{1,6})\\s+").find(lines[i]) == null &&
                Regex("^\\s*([-*+]|\\d+\\.)\\s+").find(lines[i]) == null
            ) {
                para.append('\n').append(lines[i])
                i++
            }
            out.append("<p>").append(inline(para.toString()).replace("\n", "<br>")).append("</p>")
        }
        closeLists()
        return out.toString()
    }

    /** Escape HTML, then apply inline formatting: code, bold, italic, links. */
    private fun inline(raw: String): String {
        var s = escape(raw)
        // inline code first so its content isn't reformatted
        s = Regex("`([^`]+)`").replace(s) { "<code>${it.groupValues[1]}</code>" }
        s = Regex("\\*\\*([^*]+)\\*\\*").replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)").replace(s) { "<i>${it.groupValues[1]}</i>" }
        s = Regex("\\[([^\\]]+)]\\(([^)]+)\\)").replace(s) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        return s
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
