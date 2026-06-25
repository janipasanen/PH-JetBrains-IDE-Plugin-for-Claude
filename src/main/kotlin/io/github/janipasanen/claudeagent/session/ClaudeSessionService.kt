package io.github.janipasanen.claudeagent.session

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.execution.ParametersListUtil
import io.github.janipasanen.claudeagent.process.ClaudeBinaryLocator
import io.github.janipasanen.claudeagent.settings.ClaudeSettings
import io.github.janipasanen.claudeagent.stream.ClaudeEvent
import io.github.janipasanen.claudeagent.stream.ClaudeStreamParser
import io.github.janipasanen.claudeagent.stream.ToolUse
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the long-lived `claude` child process for a project: spawns it in bidirectional stream-json
 * mode, reads + parses stdout into [ClaudeEvent]s, writes user turns to stdin, and refreshes the VFS
 * for files the agent edits. One process per project.
 */
@Service(Service.Level.PROJECT)
class ClaudeSessionService(private val project: Project) : Disposable {

    interface Listener {
        fun onEvent(event: ClaudeEvent) {}
        fun onProcessTerminated(exitCode: Int) {}
        fun onError(message: String) {}
    }

    private val log = thisLogger()
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val stdinLock = Any()
    private val stdoutBuffer = StringBuilder()

    /** tool_use id → file path it touches, so a following tool_result can scope the VFS refresh. */
    private val toolPaths = ConcurrentHashMap<String, String>()

    @Volatile
    private var processHandler: KillableProcessHandler? = null

    @Volatile
    var sessionId: String? = null
        private set

    private val workDir: String
        get() = project.basePath ?: System.getProperty("user.dir")

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun isRunning(): Boolean = processHandler?.let { !it.isProcessTerminated } ?: false

    @Synchronized
    fun start(): Boolean {
        if (isRunning()) return true

        val binary = ClaudeBinaryLocator.locate(ClaudeSettings.getInstance().claudePath)
        if (binary == null) {
            notifyError(
                "Cannot find the 'claude' CLI. Install Claude Code and run 'claude' once to log in, " +
                    "or set its path in Settings → Tools → Claude Agent.",
            )
            return false
        }

        sessionId = UUID.randomUUID().toString()
        stdoutBuffer.setLength(0)
        toolPaths.clear()

        val handler = try {
            KillableProcessHandler(buildCommandLine(binary))
        } catch (e: ExecutionException) {
            notifyError("Failed to launch Claude: ${e.message}")
            return false
        }
        handler.addProcessListener(processListener())
        processHandler = handler
        handler.startNotify()
        // Establish the bidirectional control channel so the CLI routes can_use_tool to us.
        writeLine(initializeJson())
        log.info("Started claude session $sessionId in $workDir")
        return true
    }

    fun stop() {
        processHandler?.let { if (!it.isProcessTerminated) it.destroyProcess() }
    }

    fun restart() {
        stop()
        start()
    }

    /** Send a user turn; starts the process if needed. Returns false if it could not be started. */
    fun sendUserMessage(text: String): Boolean {
        if (!isRunning() && !start()) return false
        writeLine(userMessageJson(text))
        return true
    }

    /** Reply to a `can_use_tool` request. [updatedInput] (when allowing) defaults to the original. */
    fun respondPermission(requestId: String, allow: Boolean, updatedInput: JsonObject?, denyMessage: String? = null) {
        if (!isRunning()) return
        writeLine(permissionResponseJson(requestId, allow, updatedInput, denyMessage))
    }

    /** Change the permission mode live (default / acceptEdits / plan / bypassPermissions / …). */
    fun setPermissionMode(mode: String) {
        if (!isRunning()) return
        val req = JsonObject().apply {
            addProperty("subtype", "set_permission_mode")
            addProperty("mode", mode)
        }
        writeLine(controlRequestJson(req))
    }

    /** Change the model live. */
    fun setModel(model: String) {
        if (!isRunning()) return
        val req = JsonObject().apply {
            addProperty("subtype", "set_model")
            addProperty("model", model)
        }
        writeLine(controlRequestJson(req))
    }

    /** Interrupt the current turn (softer than killing the process). */
    fun interrupt() {
        if (!isRunning()) {
            stop()
            return
        }
        val req = JsonObject().apply { addProperty("subtype", "interrupt") }
        writeLine(controlRequestJson(req))
    }

    /** Stop the current process and start a brand-new session. */
    fun newSession() {
        stop()
        start()
    }

    override fun dispose() {
        stop()
        listeners.clear()
    }

    // --- process wiring ---

    private fun buildCommandLine(binary: String): GeneralCommandLine {
        val settings = ClaudeSettings.getInstance()
        val cmd = GeneralCommandLine(binary)
            .withWorkDirectory(workDir)
            .withCharset(StandardCharsets.UTF_8)
            // Load the user's login shell environment so `claude` can find node, etc.
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        cmd.addParameters("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose")
        if (settings.streamPartialMessages) cmd.addParameter("--include-partial-messages")
        // Route tool-approval requests back to us over stdio (paired with the initialize handshake).
        cmd.addParameters("--permission-prompt-tool", "stdio")
        cmd.addParameters("--permission-mode", settings.permissionMode)
        if (settings.model.isNotBlank()) cmd.addParameters("--model", settings.model)
        sessionId?.let { cmd.addParameters("--session-id", it) }
        if (settings.extraArgs.isNotBlank()) cmd.addParameters(ParametersListUtil.parse(settings.extraArgs))
        return cmd
    }

    private fun processListener() = object : ProcessAdapter() {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            when (outputType) {
                ProcessOutputTypes.STDOUT -> appendStdout(event.text)
                ProcessOutputTypes.STDERR -> log.info("[claude stderr] ${event.text.trimEnd()}")
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            processHandler = null
            val code = event.exitCode
            log.info("claude session $sessionId terminated (exit $code)")
            onEdt { listeners.forEach { runCatching { it.onProcessTerminated(code) } } }
        }
    }

    /** STDOUT arrives on a single reader thread, so buffering here needs no extra synchronization. */
    private fun appendStdout(text: String) {
        stdoutBuffer.append(text)
        while (true) {
            val nl = stdoutBuffer.indexOf("\n")
            if (nl < 0) break
            val line = stdoutBuffer.substring(0, nl)
            stdoutBuffer.delete(0, nl + 1)
            handleLine(line)
        }
    }

    private fun handleLine(line: String) {
        val event = ClaudeStreamParser.parse(line) ?: return
        when (event) {
            is ClaudeEvent.AssistantMessage -> rememberToolPaths(event.toolUses)
            is ClaudeEvent.ToolResultMessage -> refreshTouchedFiles(event)
            else -> {}
        }
        onEdt { listeners.forEach { runCatching { it.onEvent(event) } } }
    }

    // --- stdin ---

    private fun writeLine(json: String) {
        val handler = processHandler ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val os = handler.processInput ?: return@executeOnPooledThread
                synchronized(stdinLock) {
                    os.write((json + "\n").toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }
            } catch (e: IOException) {
                log.warn("Failed to write to claude stdin", e)
            }
        }
    }

    private fun userMessageJson(text: String): String {
        val message = JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", text)
        }
        val root = JsonObject().apply {
            addProperty("type", "user")
            add("message", message)
        }
        return gson.toJson(root)
    }

    /** The handshake that opens the control channel (mirrors @anthropic-ai/claude-agent-sdk). */
    private fun initializeJson(): String {
        val request = JsonObject().apply {
            addProperty("subtype", "initialize")
            add("hooks", JsonObject())
            add("sdkMcpServers", com.google.gson.JsonArray())
        }
        return controlRequestJson(request, requestId = "init-1")
    }

    private fun controlRequestJson(request: JsonObject, requestId: String = randomRequestId()): String {
        val root = JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("type", "control_request")
            add("request", request)
        }
        return gson.toJson(root)
    }

    private fun permissionResponseJson(
        requestId: String,
        allow: Boolean,
        updatedInput: JsonObject?,
        denyMessage: String?,
    ): String {
        val decision = JsonObject().apply {
            if (allow) {
                addProperty("behavior", "allow")
                add("updatedInput", updatedInput ?: JsonObject())
            } else {
                addProperty("behavior", "deny")
                addProperty("message", denyMessage ?: "User rejected this action")
                addProperty("interrupt", false)
            }
        }
        val response = JsonObject().apply {
            addProperty("subtype", "success")
            addProperty("request_id", requestId)
            add("response", decision)
        }
        val root = JsonObject().apply {
            addProperty("type", "control_response")
            add("response", response)
        }
        return gson.toJson(root)
    }

    private fun randomRequestId(): String = UUID.randomUUID().toString()

    // --- VFS refresh after edits ---

    private fun rememberToolPaths(uses: List<ToolUse>) {
        uses.forEach { use ->
            val id = use.id ?: return@forEach
            use.touchedPath()?.let { toolPaths[id] = it }
        }
    }

    private fun refreshTouchedFiles(msg: ClaudeEvent.ToolResultMessage) {
        val files = msg.toolResults
            .mapNotNull { it.toolUseId?.let { id -> toolPaths.remove(id) } }
            .map { resolve(it) }
            .filter { it.exists() }
        try {
            if (files.isNotEmpty()) {
                LocalFileSystem.getInstance().refreshIoFiles(files, true, false, null)
            } else {
                project.basePath
                    ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                    ?.let { VfsUtil.markDirtyAndRefresh(true, true, false, it) }
            }
        } catch (e: Exception) {
            log.warn("VFS refresh failed", e)
        }
    }

    private fun resolve(path: String): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(workDir, path)
    }

    // --- helpers ---

    private fun notifyError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Claude Agent", message, NotificationType.ERROR)
            .notify(project)
        onEdt { listeners.forEach { runCatching { it.onError(message) } } }
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block) { project.isDisposed }
    }

    companion object {
        const val NOTIFICATION_GROUP = "Claude Agent"
        fun getInstance(project: Project): ClaudeSessionService = project.service()
    }
}
