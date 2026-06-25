package io.github.janipasanen.claudeagent.ui

import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.UIUtil
import io.github.janipasanen.claudeagent.markdown.MarkdownToHtml
import java.awt.Color
import javax.swing.JComponent
import javax.swing.Timer

/**
 * Opt-in rich transcript backed by JCEF (embedded Chromium). Renders assistant text as Markdown.
 * Only constructed when [com.intellij.ui.jcef.JBCefApp.isSupported] is true; the Swing transcript is
 * the fallback. Re-renders are coalesced on a timer to avoid thrash during token streaming.
 */
class JcefTranscript : Transcript {

    private enum class Kind { USER, ASSISTANT, TOOL, NOTE, ERROR }
    private class Msg(val kind: Kind, var text: String)

    private val browser = JBCefBrowser()
    override val component: JComponent get() = browser.component

    private val messages = mutableListOf<Msg>()
    private var assistantOpen = false
    private var dirty = true
    private val timer = Timer(150) { if (dirty) { dirty = false; doRender() } }.apply {
        isRepeats = true
        start()
    }

    override fun user(text: String) {
        assistantOpen = false
        messages.add(Msg(Kind.USER, text))
        dirty = true
    }

    override fun assistant(text: String) {
        val last = messages.lastOrNull()
        if (assistantOpen && last?.kind == Kind.ASSISTANT) {
            last.text += text
        } else {
            messages.add(Msg(Kind.ASSISTANT, text))
            assistantOpen = true
        }
        dirty = true
    }

    override fun tool(label: String) { messages.add(Msg(Kind.TOOL, label)); dirty = true }
    override fun note(text: String) { messages.add(Msg(Kind.NOTE, text)); dirty = true }
    override fun error(text: String) { messages.add(Msg(Kind.ERROR, text)); dirty = true }

    override fun clear() {
        messages.clear()
        assistantOpen = false
        dirty = true
    }

    override fun dispose() {
        timer.stop()
        Disposer.dispose(browser)
    }

    private fun doRender() = browser.loadHTML(page(buildBody()))

    private fun buildBody(): String = buildString {
        for (msg in messages) {
            when (msg.kind) {
                Kind.USER -> append("<div class='user'><div class='role'>You</div><div>")
                    .append(escape(msg.text).replace("\n", "<br>")).append("</div></div>")
                Kind.ASSISTANT -> append("<div class='assistant'><div class='role'>Claude</div><div>")
                    .append(MarkdownToHtml.toHtml(msg.text)).append("</div></div>")
                Kind.TOOL -> append("<div class='tool'>⚙ ").append(escape(msg.text)).append("</div>")
                Kind.NOTE -> append("<div class='note'>").append(escape(msg.text)).append("</div>")
                Kind.ERROR -> append("<div class='error'>").append(escape(msg.text)).append("</div>")
            }
        }
    }

    private fun page(body: String): String {
        val bg = hex(UIUtil.getPanelBackground())
        val fg = hex(UIUtil.getLabelForeground())
        val codeBg = hex(if (UIUtil.isUnderDarcula()) Color(0x2B, 0x2B, 0x2B) else Color(0xF2, 0xF2, 0xF2))
        return """
            <!doctype html><html><head><meta charset="utf-8"><style>
            body{font-family:sans-serif;font-size:13px;color:$fg;background:$bg;margin:8px;line-height:1.45;}
            .role{font-weight:bold;margin-top:12px;}
            .user .role{color:#589DF6;} .assistant .role{color:#6FBF6F;}
            pre{background:$codeBg;padding:8px;border-radius:4px;overflow:auto;}
            code{font-family:monospace;background:$codeBg;padding:1px 3px;border-radius:3px;}
            pre code{background:transparent;padding:0;}
            .tool{color:#888;font-family:monospace;white-space:pre-wrap;margin-top:4px;}
            .note{color:#888;font-style:italic;} .error{color:#E06C5B;}
            a{color:#589DF6;}
            </style></head><body>$body
            <script>window.scrollTo(0,document.body.scrollHeight);</script>
            </body></html>
        """.trimIndent()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun hex(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)
}
