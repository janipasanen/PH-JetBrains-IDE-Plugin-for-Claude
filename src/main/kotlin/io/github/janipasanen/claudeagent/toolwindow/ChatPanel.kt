package io.github.janipasanen.claudeagent.toolwindow

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.janipasanen.claudeagent.context.EditorContext
import io.github.janipasanen.claudeagent.diff.DiffPreviewService
import io.github.janipasanen.claudeagent.session.ClaudeSessionService
import io.github.janipasanen.claudeagent.settings.ClaudeSettings
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
 * Swing chat UI (M1 + M2). Streams the transcript, drives [ClaudeSessionService], and adds
 * interactive permission approval, diff preview, editor-context attach, @-file insert and
 * session controls. Rich markdown/JCEF rendering is M4.
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, ClaudeSessionService.Listener {

    private val service = ClaudeSessionService.getInstance(project)
    private val settings = ClaudeSettings.getInstance()

    private val transcript = JTextPane().apply { isEditable = false }
    private val input = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop").apply { isEnabled = false }
    private val newChatButton = JButton("New Chat")
    private val atFileButton = JButton("@ File")
    private val contextCheck = JBCheckBox("Attach open file & selection", false)
    private val modeCombo = ComboBox(ClaudeSettings.PERMISSION_MODES.toTypedArray())
    private val status = JBLabel("Not started").apply { border = JBUI.Borders.empty(2, 6) }

    // Permission approval
    private val pendingPermissions = ArrayDeque<ClaudeEvent.CanUseToolRequest>()
    private var currentPermission: ClaudeEvent.CanUseToolRequest? = null
    private val permLabel = JBLabel().apply { border = JBUI.Borders.empty(2, 6) }
    private val approveButton = JButton("Approve")
    private val denyButton = JButton("Deny")
    private val viewDiffButton = JButton("View diff")
    private val permissionBar = JPanel(BorderLayout())

    private var assistantHeaderShown = false
    private var streamedThisTurn = false
    private var uiReady = false

    init {
        border = JBUI.Borders.empty(4)
        add(buildNorth(), BorderLayout.NORTH)
        add(JBScrollPane(transcript), BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)

        wireActions()
        installInputKeybindings()
        service.addListener(this)
        uiReady = true

        appendStyled(
            "Welcome. This drives the Claude Code CLI with your Claude account, working in this project.\n" +
                "In 'default' mode Claude asks before editing files or running commands — approve below.\n\n",
            STYLE_SYSTEM,
        )
    }

    private fun buildNorth(): JPanel {
        modeCombo.selectedItem = settings.permissionMode
        modeCombo.toolTipText = "Permission mode"
        val controls = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(contextCheck)
            add(JBLabel("Mode:"))
            add(modeCombo)
            add(newChatButton)
        }
        return JPanel(BorderLayout()).apply {
            add(status, BorderLayout.WEST)
            add(controls, BorderLayout.EAST)
        }
    }

    private fun buildSouth(): JPanel {
        // Permission approval bar (hidden until a request arrives).
        permissionBar.border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(4),
        )
        permissionBar.isVisible = false
        permissionBar.add(permLabel, BorderLayout.CENTER)
        permissionBar.add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(viewDiffButton)
                add(denyButton)
                add(approveButton)
            },
            BorderLayout.EAST,
        )

        val inputButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(atFileButton)
            add(stopButton)
            add(sendButton)
        }
        val inputPanel = JPanel(BorderLayout(4, 4)).apply {
            border = JBUI.Borders.emptyTop(6)
            add(JBScrollPane(input).apply { preferredSize = Dimension(0, JBUI.scale(72)) }, BorderLayout.CENTER)
            add(inputButtons, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            add(permissionBar, BorderLayout.NORTH)
            add(inputPanel, BorderLayout.CENTER)
        }
    }

    private fun wireActions() {
        sendButton.addActionListener { send() }
        stopButton.addActionListener { service.interrupt() }
        newChatButton.addActionListener { newChat() }
        atFileButton.addActionListener { chooseFile() }
        approveButton.addActionListener { resolvePermission(true) }
        denyButton.addActionListener { resolvePermission(false) }
        viewDiffButton.addActionListener {
            currentPermission?.let { DiffPreviewService.showPreview(project, it.toolName, it.input) }
        }
        modeCombo.addActionListener {
            if (!uiReady) return@addActionListener
            val mode = modeCombo.selectedItem as? String ?: return@addActionListener
            settings.permissionMode = mode
            service.setPermissionMode(mode)
        }
    }

    private fun installInputKeybindings() {
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "claude-send")
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break")
        input.actionMap.put("claude-send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = send()
        })
    }

    private fun send() {
        val text = input.text.trim()
        if (text.isEmpty() || !sendButton.isEnabled) return

        val context = if (contextCheck.isSelected) EditorContext.gather(project) else null
        val payload = if (context != null) "$context\n$text" else text

        appendStyled("You\n", STYLE_USER_LABEL)
        appendStyled("$text\n", STYLE_USER)
        if (context != null) appendStyled("  (attached: open file & selection)\n", STYLE_SYSTEM)
        input.text = ""
        assistantHeaderShown = false
        streamedThisTurn = false
        setBusy(true)

        if (!service.sendUserMessage(payload)) {
            appendStyled("Could not start Claude. See the notification / Settings.\n", STYLE_ERROR)
            setBusy(false)
        }
    }

    private fun newChat() {
        clearPermissions()
        service.newSession()
        transcript.text = ""
        assistantHeaderShown = false
        streamedThisTurn = false
        setBusy(false)
        appendStyled("New session started.\n\n", STYLE_SYSTEM)
    }

    private fun chooseFile() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Add File To Prompt")
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        FileChooser.chooseFile(descriptor, project, base) { file ->
            val ref = if (base != null && file.path.startsWith(base.path)) {
                file.path.removePrefix(base.path).trimStart('/')
            } else {
                file.path
            }
            input.insert("@$ref ", input.caretPosition)
            input.requestFocusInWindow()
        }
    }

    // --- permission approval ---

    private fun showNextPermission() {
        if (currentPermission != null) return
        val next = pendingPermissions.removeFirstOrNull()
        if (next == null) {
            permissionBar.isVisible = false
            revalidate(); repaint()
            return
        }
        currentPermission = next
        permLabel.text = "Allow ${next.toolName}?  ${summarize(next.input)}"
        viewDiffButton.isVisible = DiffPreviewService.canPreview(next.toolName)
        permissionBar.isVisible = true
        revalidate(); repaint()
    }

    private fun resolvePermission(allow: Boolean) {
        val req = currentPermission ?: return
        service.respondPermission(req.requestId, allow, req.input.takeIf { allow })
        appendStyled(
            "  ${if (allow) "✓ approved" else "✗ denied"} ${req.toolName}  ${summarize(req.input)}\n",
            if (allow) STYLE_SYSTEM else STYLE_ERROR,
        )
        currentPermission = null
        showNextPermission()
    }

    private fun clearPermissions() {
        pendingPermissions.clear()
        currentPermission = null
        permissionBar.isVisible = false
    }

    // --- ClaudeSessionService.Listener (dispatched on the EDT) ---

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
                event.toolUses.forEach { appendStyled("\n  ⚙ ${it.name}  ${it.summary()}\n", STYLE_TOOL) }
            }

            is ClaudeEvent.ToolResultMessage ->
                event.toolResults.filter { it.isError }.forEach {
                    appendStyled("  ⚠ ${it.content?.take(300)?.replace('\n', ' ')}\n", STYLE_ERROR)
                }

            is ClaudeEvent.CanUseToolRequest -> {
                pendingPermissions.addLast(event)
                showNextPermission()
            }

            is ClaudeEvent.RateLimit -> appendStyled("\n  [rate limited — please wait]\n", STYLE_SYSTEM)

            is ClaudeEvent.Result -> finishTurn(event)

            else -> {}
        }
    }

    override fun onProcessTerminated(exitCode: Int) {
        setBusy(false)
        clearPermissions()
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

    private fun summarize(input: JsonObject?): String {
        val obj = input ?: return ""
        for (key in listOf("file_path", "command", "path", "pattern", "url")) {
            obj.get(key)?.takeIf { it.isJsonPrimitive }?.let { return it.asString }
        }
        return ""
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
