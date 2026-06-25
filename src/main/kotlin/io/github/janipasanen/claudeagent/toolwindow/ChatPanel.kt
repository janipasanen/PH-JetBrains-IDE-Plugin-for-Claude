package io.github.janipasanen.claudeagent.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.janipasanen.claudeagent.session.ClaudeSessionService
import io.github.janipasanen.claudeagent.stream.ClaudeEvent
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Minimal Swing chat UI (M1). Renders the streamed transcript and drives [ClaudeSessionService].
 * Richer rendering (markdown, diffs, inline permission prompts) arrives in M2/M4.
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, ClaudeSessionService.Listener {

    private val service = ClaudeSessionService.getInstance(project)

    private val transcript = JTextPane().apply { isEditable = false }
    private val input = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ask Claude to build, fix, or explain code in this project…"
    }
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop").apply { isEnabled = false }
    private val status = JBLabel("Not started").apply { border = JBUI.Borders.empty(2, 6) }

    private var assistantHeaderShown = false
    private var streamedThisTurn = false

    init {
        border = JBUI.Borders.empty(4)
        add(status, BorderLayout.NORTH)
        add(JBScrollPane(transcript), BorderLayout.CENTER)
        add(buildInputPanel(), BorderLayout.SOUTH)

        sendButton.addActionListener { send() }
        stopButton.addActionListener { service.stop() }
        installInputKeybindings()

        service.addListener(this)
        appendStyled(
            "Welcome. This drives the Claude Code CLI with your Claude account, working in this project.\n" +
                "Permission mode is set in Settings → Tools → Claude Agent (default: acceptEdits).\n\n",
            STYLE_SYSTEM,
        )
    }

    private fun buildInputPanel(): JPanel {
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(stopButton)
            add(sendButton)
        }
        return JPanel(BorderLayout(4, 4)).apply {
            border = JBUI.Borders.emptyTop(6)
            add(JBScrollPane(input).apply { preferredSize = Dimension(0, JBUI.scale(72)) }, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    private fun installInputKeybindings() {
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "claude-send")
        input.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            "insert-break", // built-in JTextArea newline action
        )
        input.actionMap.put("claude-send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = send()
        })
    }

    private fun send() {
        val text = input.text.trim()
        if (text.isEmpty() || !sendButton.isEnabled) return

        appendStyled("You\n", STYLE_USER_LABEL)
        appendStyled("$text\n", STYLE_USER)
        input.text = ""
        assistantHeaderShown = false
        streamedThisTurn = false
        setBusy(true)

        if (!service.sendUserMessage(text)) {
            appendStyled("Could not start Claude. See the notification / Settings.\n", STYLE_ERROR)
            setBusy(false)
        }
    }

    // --- ClaudeSessionService.Listener (already dispatched on the EDT) ---

    override fun onEvent(event: ClaudeEvent) {
        when (event) {
            is ClaudeEvent.SystemInit ->
                status.text = "model: ${event.model ?: "?"}  ·  mode: ${event.permissionMode ?: "?"}"

            is ClaudeEvent.AssistantTextDelta -> {
                ensureAssistantHeader()
                appendStyled(event.text, STYLE_ASSISTANT)
                streamedThisTurn = true
            }

            is ClaudeEvent.AssistantMessage -> {
                if (!streamedThisTurn && !event.text.isNullOrEmpty()) {
                    ensureAssistantHeader()
                    appendStyled(event.text, STYLE_ASSISTANT)
                }
                event.toolUses.forEach {
                    appendStyled("\n  ⚙ ${it.name}  ${it.summary()}\n", STYLE_TOOL)
                }
            }

            is ClaudeEvent.ToolResultMessage ->
                event.toolResults.filter { it.isError }.forEach {
                    appendStyled("  ⚠ ${it.content?.take(300)?.replace('\n', ' ')}\n", STYLE_ERROR)
                }

            is ClaudeEvent.CanUseToolRequest ->
                appendStyled(
                    "\n  ⚠ ${event.toolName} needs approval. Interactive approval arrives in M2; " +
                        "for now set the permission mode in Settings.\n",
                    STYLE_SYSTEM,
                )

            is ClaudeEvent.RateLimit -> appendStyled("\n  [rate limited — please wait]\n", STYLE_SYSTEM)

            is ClaudeEvent.Result -> finishTurn(event)

            else -> {}
        }
    }

    override fun onProcessTerminated(exitCode: Int) {
        setBusy(false)
        status.text = "Stopped (exit $exitCode)"
    }

    override fun onError(message: String) {
        appendStyled("$message\n", STYLE_ERROR)
        setBusy(false)
    }

    private fun finishTurn(result: ClaudeEvent.Result) {
        appendStyled("\n", STYLE_ASSISTANT)
        if (result.isError && !result.text.isNullOrEmpty()) {
            appendStyled("Error: ${result.text}\n", STYLE_ERROR)
        }
        if (result.permissionDenials.isNotEmpty()) {
            val denied = result.permissionDenials.mapNotNull { it.toolName }.distinct().joinToString(", ")
            appendStyled("  (denied without prompt: $denied — change permission mode in Settings)\n", STYLE_SYSTEM)
        }
        result.totalCostUsd?.let { appendStyled("  — turn cost: $%.4f\n".format(it), STYLE_SYSTEM) }
        appendStyled("\n", STYLE_ASSISTANT)
        setBusy(false)
    }

    private fun ensureAssistantHeader() {
        if (!assistantHeaderShown) {
            appendStyled("Claude\n", STYLE_ASSISTANT_LABEL)
            assistantHeaderShown = true
        }
    }

    private fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy
        stopButton.isEnabled = busy
    }

    private fun appendStyled(text: String, attrs: SimpleAttributeSet) {
        val doc = transcript.styledDocument
        doc.insertString(doc.length, text, attrs)
        transcript.caretPosition = doc.length
    }

    override fun dispose() {
        service.removeListener(this)
    }

    companion object {
        private fun style(block: SimpleAttributeSet.() -> Unit) = SimpleAttributeSet().apply(block)

        private val STYLE_USER_LABEL = style {
            StyleConstants.setBold(this, true)
            StyleConstants.setForeground(this, JBColor(0x2D6CC0, 0x589DF6))
        }
        private val STYLE_USER = style {}
        private val STYLE_ASSISTANT_LABEL = style {
            StyleConstants.setBold(this, true)
            StyleConstants.setForeground(this, JBColor(0x3A8A3A, 0x6FBF6F))
        }
        private val STYLE_ASSISTANT = style {}
        private val STYLE_TOOL = style {
            StyleConstants.setForeground(this, JBColor.GRAY)
            StyleConstants.setFontFamily(this, "monospaced")
        }
        private val STYLE_SYSTEM = style {
            StyleConstants.setForeground(this, JBColor.GRAY)
            StyleConstants.setItalic(this, true)
        }
        private val STYLE_ERROR = style {
            StyleConstants.setForeground(this, JBColor(0xC0392B, 0xE06C5B))
        }
    }
}
