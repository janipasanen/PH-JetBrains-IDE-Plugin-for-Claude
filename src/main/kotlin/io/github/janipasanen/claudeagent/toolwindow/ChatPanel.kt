package io.github.janipasanen.claudeagent.toolwindow

import com.google.gson.JsonObject
import com.intellij.history.LocalHistory
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.ui.JBUI
import io.github.janipasanen.claudeagent.context.EditorContext
import io.github.janipasanen.claudeagent.diff.DiffPreviewService
import io.github.janipasanen.claudeagent.session.ClaudeSessionService
import io.github.janipasanen.claudeagent.settings.ClaudeSettings
import io.github.janipasanen.claudeagent.stream.ClaudeEvent
import io.github.janipasanen.claudeagent.ui.JcefTranscript
import io.github.janipasanen.claudeagent.ui.SwingTranscript
import io.github.janipasanen.claudeagent.ui.Transcript
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Chat controller for the tool window. Renders via a pluggable [Transcript] (Swing default, JCEF
 * opt-in), drives [ClaudeSessionService], and provides permission approval, diff preview,
 * editor-context attach, @-file insert, slash commands, checkpoints and session controls.
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, ClaudeSessionService.Listener {

    private val service = ClaudeSessionService.getInstance(project)
    private val settings = ClaudeSettings.getInstance()

    private val transcript: Transcript =
        if (settings.useRichUi && JBCefApp.isSupported()) JcefTranscript() else SwingTranscript()

    private val input = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop").apply { isEnabled = false }
    private val newChatButton = JButton("New Chat")
    private val atFileButton = JButton("@ File")
    private val slashButton = JButton("/")
    private val contextCheck = JBCheckBox("Attach open file, selection & problems", false)
    private val modeCombo = ComboBox(ClaudeSettings.PERMISSION_MODES.toTypedArray())
    private val status = JBLabel("Not started").apply { border = JBUI.Borders.empty(2, 6) }

    private val pendingPermissions = ArrayDeque<ClaudeEvent.CanUseToolRequest>()
    private var currentPermission: ClaudeEvent.CanUseToolRequest? = null
    private val permLabel = JBLabel().apply { border = JBUI.Borders.empty(2, 6) }
    private val approveButton = JButton("Approve")
    private val denyButton = JButton("Deny")
    private val viewDiffButton = JButton("View diff")
    private val permissionBar = JPanel(BorderLayout())

    private var slashCommands: List<String> = emptyList()
    private var streamedThisTurn = false
    private var uiReady = false

    init {
        border = JBUI.Borders.empty(4)
        add(buildNorth(), BorderLayout.NORTH)
        add(transcript.component, BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)

        wireActions()
        installInputKeybindings()
        service.addListener(this)
        uiReady = true

        transcript.note(
            "Welcome. This drives the Claude Code CLI with your Claude account, working in this project.\n" +
                "In 'default' mode Claude asks before editing files or running commands — approve below.\n",
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
            add(slashButton)
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
        slashButton.addActionListener { showSlashCommands() }
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

        // Checkpoint so the user can revert this turn's edits via Local History.
        runCatching { LocalHistory.getInstance().putUserLabel(project, "Claude: ${text.take(60)}") }

        transcript.user(text)
        if (context != null) transcript.note("  (attached: open file, selection & problems)")
        input.text = ""
        streamedThisTurn = false
        setBusy(true)

        if (!service.sendUserMessage(payload)) {
            transcript.error("Could not start Claude. See the notification / Settings.")
            setBusy(false)
        }
    }

    private fun newChat() {
        clearPermissions()
        service.newSession()
        transcript.clear()
        streamedThisTurn = false
        setBusy(false)
        transcript.note("New session started.")
    }

    private fun chooseFile() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
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

    private fun showSlashCommands() {
        if (slashCommands.isEmpty()) {
            transcript.note("No slash commands reported yet — start a session first.")
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(slashCommands.sorted())
            .setTitle("Slash commands")
            .setItemChosenCallback { command ->
                input.insert("/$command ", input.caretPosition)
                input.requestFocusInWindow()
            }
            .createPopup()
            .showUnderneathOf(slashButton)
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
        val line = "${if (allow) "✓ approved" else "✗ denied"} ${req.toolName}  ${summarize(req.input)}"
        if (allow) transcript.note("  $line") else transcript.error("  $line")
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
            is ClaudeEvent.SystemInit -> {
                status.text = "model: ${event.model ?: "?"}  ·  mode: ${event.permissionMode ?: "?"}"
                if (event.slashCommands.isNotEmpty()) slashCommands = event.slashCommands
            }

            is ClaudeEvent.AssistantTextDelta -> {
                transcript.assistant(event.text)
                streamedThisTurn = true
            }

            is ClaudeEvent.AssistantMessage -> {
                if (!streamedThisTurn && !event.text.isNullOrEmpty()) transcript.assistant(event.text)
                event.toolUses.forEach { transcript.tool("${it.name}  ${it.summary()}") }
            }

            is ClaudeEvent.ToolResultMessage ->
                event.toolResults.filter { it.isError }.forEach {
                    transcript.error("  ⚠ ${it.content?.take(300)?.replace('\n', ' ')}")
                }

            is ClaudeEvent.CanUseToolRequest -> {
                pendingPermissions.addLast(event)
                showNextPermission()
            }

            is ClaudeEvent.RateLimit -> transcript.note("  [rate limited — please wait]")

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
        transcript.error(message)
        setBusy(false)
    }

    private fun finishTurn(result: ClaudeEvent.Result) {
        if (result.isError && !result.text.isNullOrEmpty()) transcript.error("Error: ${result.text}")
        result.totalCostUsd?.let { transcript.note("  — turn cost: $%.4f".format(it)) }
        setBusy(false)
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

    override fun dispose() {
        service.removeListener(this)
        transcript.dispose()
    }
}
