package io.github.janipasanen.claudeagent.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import javax.swing.JComponent
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/** Default transcript: a styled [JTextPane] with incremental append (good for token streaming). */
class SwingTranscript : Transcript {

    private val pane = JTextPane().apply { isEditable = false }
    override val component: JComponent = JBScrollPane(pane)

    private var assistantOpen = false

    override fun user(text: String) {
        assistantOpen = false
        append("You\n", USER_LABEL)
        append("$text\n", USER)
    }

    override fun assistant(text: String) {
        if (!assistantOpen) {
            append("Claude\n", ASSISTANT_LABEL)
            assistantOpen = true
        }
        append(text, ASSISTANT)
    }

    override fun tool(label: String) = append("\n  ⚙ $label\n", TOOL)

    override fun note(text: String) = append(text, SYSTEM)

    override fun error(text: String) = append(text, ERROR)

    override fun clear() {
        pane.text = ""
        assistantOpen = false
    }

    private fun append(text: String, attrs: SimpleAttributeSet) {
        val doc = pane.styledDocument
        doc.insertString(doc.length, text, attrs)
        pane.caretPosition = doc.length
    }

    companion object {
        private fun style(block: SimpleAttributeSet.() -> Unit) = SimpleAttributeSet().apply(block)

        private val USER_LABEL = style {
            StyleConstants.setBold(this, true)
            StyleConstants.setForeground(this, JBColor(0x2D6CC0, 0x589DF6))
        }
        private val USER = style {}
        private val ASSISTANT_LABEL = style {
            StyleConstants.setBold(this, true)
            StyleConstants.setForeground(this, JBColor(0x3A8A3A, 0x6FBF6F))
        }
        private val ASSISTANT = style {}
        private val TOOL = style {
            StyleConstants.setForeground(this, JBColor.GRAY)
            StyleConstants.setFontFamily(this, "monospaced")
        }
        private val SYSTEM = style {
            StyleConstants.setForeground(this, JBColor.GRAY)
            StyleConstants.setItalic(this, true)
        }
        private val ERROR = style {
            StyleConstants.setForeground(this, JBColor(0xC0392B, 0xE06C5B))
        }
    }
}
