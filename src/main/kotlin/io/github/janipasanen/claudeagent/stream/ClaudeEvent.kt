package io.github.janipasanen.claudeagent.stream

import com.google.gson.JsonObject

/**
 * Typed model of the NDJSON events emitted by `claude --output-format stream-json`.
 *
 * The schema is intentionally tolerant: unknown event types or fields map to [Unknown] rather than
 * failing, because the Claude Code protocol evolves. Only the fields the plugin actually uses are
 * modelled. See CLAUDE.md / the research spec for the full wire schema.
 */
sealed interface ClaudeEvent {
    val sessionId: String?

    /** First event of a session (`type:system, subtype:init`). */
    data class SystemInit(
        override val sessionId: String?,
        val cwd: String?,
        val model: String?,
        val permissionMode: String?,
        val tools: List<String>,
        val apiKeySource: String?,
        val claudeCodeVersion: String?,
    ) : ClaudeEvent

    /** Any other `type:system` event (status, thinking_tokens, …). */
    data class SystemOther(override val sessionId: String?, val subtype: String?) : ClaudeEvent

    /** Token-level assistant text delta (requires `--include-partial-messages`). */
    data class AssistantTextDelta(override val sessionId: String?, val text: String) : ClaudeEvent

    /** Consolidated assistant message; carries the final text and any tool_use blocks. */
    data class AssistantMessage(
        override val sessionId: String?,
        val text: String?,
        val toolUses: List<ToolUse>,
    ) : ClaudeEvent

    /** A `type:user` event — this is where tool_result blocks are delivered. */
    data class ToolResultMessage(
        override val sessionId: String?,
        val toolResults: List<ToolResult>,
    ) : ClaudeEvent

    /** Terminal event of a turn. */
    data class Result(
        override val sessionId: String?,
        val isError: Boolean,
        val text: String?,
        val totalCostUsd: Double?,
        val durationMs: Long?,
        val permissionDenials: List<PermissionDenial>,
    ) : ClaudeEvent

    /** CLI is asking the driver to approve a tool call (`control_request, subtype:can_use_tool`). */
    data class CanUseToolRequest(
        override val sessionId: String?,
        val requestId: String,
        val toolName: String?,
        val input: JsonObject?,
    ) : ClaudeEvent

    /** Rate-limit notification. */
    data class RateLimit(override val sessionId: String?) : ClaudeEvent

    /** Anything we don't model yet; [raw] preserves the original line for logging. */
    data class Unknown(override val sessionId: String?, val type: String?, val raw: String) : ClaudeEvent
}

data class ToolUse(val id: String?, val name: String?, val input: JsonObject?) {
    /** Best-effort single-line summary for the UI, e.g. the file path or command. */
    fun summary(): String {
        val obj = input ?: return ""
        for (key in listOf("file_path", "path", "command", "pattern", "url", "prompt")) {
            obj.get(key)?.takeIf { it.isJsonPrimitive }?.let { return it.asString }
        }
        return ""
    }

    /** Absolute/relative path this tool writes to, if any (for scoped VFS refresh). */
    fun touchedPath(): String? {
        val obj = input ?: return null
        for (key in listOf("file_path", "path", "notebook_path")) {
            obj.get(key)?.takeIf { it.isJsonPrimitive }?.let { return it.asString }
        }
        return null
    }
}

data class ToolResult(val toolUseId: String?, val content: String?, val isError: Boolean)

data class PermissionDenial(val toolName: String?, val toolInput: String?)
