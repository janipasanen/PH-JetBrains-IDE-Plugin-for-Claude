package io.github.janipasanen.claudeagent.stream

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Parses a single line of NDJSON from the Claude Code CLI into a [ClaudeEvent].
 *
 * Robust by design: malformed or unrecognised lines become [ClaudeEvent.Unknown] instead of
 * throwing, so a protocol change never breaks the read loop.
 */
object ClaudeStreamParser {

    fun parse(line: String): ClaudeEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val root = runCatching { JsonParser.parseString(trimmed) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return ClaudeEvent.Unknown(null, null, trimmed)

        val sessionId = root.str("session_id")
        return when (root.str("type")) {
            "system" -> parseSystem(root, sessionId)
            "stream_event" -> parseStreamEvent(root, sessionId)
            "assistant" -> parseAssistant(root, sessionId)
            "user" -> parseUser(root, sessionId)
            "result" -> parseResult(root, sessionId)
            "control_request" -> parseControlRequest(root, sessionId)
            "control_response" -> null // ack to our outgoing control requests (initialize, etc.)
            "rate_limit_event" -> ClaudeEvent.RateLimit(sessionId)
            else -> ClaudeEvent.Unknown(sessionId, root.str("type"), trimmed)
        }
    }

    private fun parseSystem(root: JsonObject, sessionId: String?): ClaudeEvent {
        if (root.str("subtype") != "init") {
            return ClaudeEvent.SystemOther(sessionId, root.str("subtype"))
        }
        val tools = root.getAsJsonArray("tools")?.mapNotNull { it.asStringOrNull() } ?: emptyList()
        val slash = root.getAsJsonArray("slash_commands")?.mapNotNull { it.asStringOrNull() } ?: emptyList()
        return ClaudeEvent.SystemInit(
            sessionId = sessionId,
            cwd = root.str("cwd"),
            model = root.str("model"),
            permissionMode = root.str("permissionMode"),
            tools = tools,
            slashCommands = slash,
            apiKeySource = root.str("apiKeySource"),
            claudeCodeVersion = root.str("claude_code_version"),
        )
    }

    /** `stream_event` wraps a raw Anthropic streaming event; we only surface text deltas. */
    private fun parseStreamEvent(root: JsonObject, sessionId: String?): ClaudeEvent? {
        val event = root.obj("event") ?: return null
        if (event.str("type") != "content_block_delta") return null
        val delta = event.obj("delta") ?: return null
        if (delta.str("type") != "text_delta") return null
        val text = delta.str("text") ?: return null
        return ClaudeEvent.AssistantTextDelta(sessionId, text)
    }

    private fun parseAssistant(root: JsonObject, sessionId: String?): ClaudeEvent {
        val content = root.obj("message")?.getAsJsonArray("content")
        val textBuilder = StringBuilder()
        val toolUses = mutableListOf<ToolUse>()
        content?.forEach { element ->
            val block = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            when (block.str("type")) {
                "text" -> block.str("text")?.let { textBuilder.append(it) }
                "tool_use" -> toolUses += ToolUse(
                    id = block.str("id"),
                    name = block.str("name"),
                    input = block.obj("input"),
                )
            }
        }
        return ClaudeEvent.AssistantMessage(
            sessionId = sessionId,
            text = textBuilder.toString().ifEmpty { null },
            toolUses = toolUses,
        )
    }

    private fun parseUser(root: JsonObject, sessionId: String?): ClaudeEvent {
        val content = root.obj("message")?.getAsJsonArray("content")
        val results = mutableListOf<ToolResult>()
        content?.forEach { element ->
            val block = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (block.str("type") == "tool_result") {
                results += ToolResult(
                    toolUseId = block.str("tool_use_id"),
                    content = block.get("content")?.flattenText(),
                    isError = block.get("is_error")?.asBooleanOrNull() ?: false,
                )
            }
        }
        return ClaudeEvent.ToolResultMessage(sessionId, results)
    }

    private fun parseResult(root: JsonObject, sessionId: String?): ClaudeEvent {
        val denials = root.getAsJsonArray("permission_denials")?.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            PermissionDenial(o.str("tool_name"), o.get("tool_input")?.toString())
        } ?: emptyList()
        return ClaudeEvent.Result(
            sessionId = sessionId,
            isError = root.get("is_error")?.asBooleanOrNull() ?: (root.str("subtype") == "error"),
            text = root.str("result") ?: root.str("error"),
            totalCostUsd = root.get("total_cost_usd")?.asDoubleOrNull(),
            durationMs = root.get("duration_ms")?.asLongOrNull(),
            permissionDenials = denials,
        )
    }

    private fun parseControlRequest(root: JsonObject, sessionId: String?): ClaudeEvent {
        val request = root.obj("request")
        if (request?.str("subtype") != "can_use_tool") {
            return ClaudeEvent.Unknown(sessionId, "control_request", root.toString())
        }
        return ClaudeEvent.CanUseToolRequest(
            sessionId = sessionId,
            requestId = root.str("request_id") ?: "",
            toolName = request.str("tool_name"),
            input = request.obj("input"),
        )
    }

    // --- small JSON helpers (null-tolerant) ---

    private fun JsonObject.str(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive }?.asString

    private fun JsonElement.asBooleanOrNull(): Boolean? =
        runCatching { asBoolean }.getOrNull()

    private fun JsonElement.asDoubleOrNull(): Double? =
        runCatching { asDouble }.getOrNull()

    private fun JsonElement.asLongOrNull(): Long? =
        runCatching { asLong }.getOrNull()

    /** tool_result `content` may be a string or an array of `{type:text,text:…}` blocks. */
    private fun JsonElement.flattenText(): String = when {
        isJsonPrimitive -> asString
        isJsonArray -> asJsonArray.joinToString("\n") { el ->
            el.takeIf { it.isJsonObject }?.asJsonObject?.get("text")
                ?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        }.trim()
        else -> toString()
    }
}
