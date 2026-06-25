package io.github.janipasanen.claudeagent.protocol

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Builds the NDJSON lines the plugin writes to the `claude` process stdin: user turns and the
 * control protocol (initialize handshake, permission responses, set_permission_mode / set_model /
 * interrupt). Pure and side-effect-free so the wire format can be unit-tested against the shapes
 * verified empirically (claude 2.1.x / @anthropic-ai/claude-agent-sdk 0.3.193).
 */
object ClaudeControlProtocol {

    private val gson = Gson()

    fun userMessage(text: String): String {
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

    /** Opens the control channel so the CLI routes `can_use_tool` back to us. */
    fun initialize(requestId: String): String {
        val request = JsonObject().apply {
            addProperty("subtype", "initialize")
            add("hooks", JsonObject())
            add("sdkMcpServers", JsonArray())
        }
        return controlRequest(request, requestId)
    }

    fun permissionResponse(
        requestId: String,
        allow: Boolean,
        updatedInput: JsonObject?,
        denyMessage: String? = null,
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

    fun setPermissionMode(mode: String, requestId: String): String =
        controlRequest(JsonObject().apply {
            addProperty("subtype", "set_permission_mode")
            addProperty("mode", mode)
        }, requestId)

    fun setModel(model: String, requestId: String): String =
        controlRequest(JsonObject().apply {
            addProperty("subtype", "set_model")
            addProperty("model", model)
        }, requestId)

    fun interrupt(requestId: String): String =
        controlRequest(JsonObject().apply { addProperty("subtype", "interrupt") }, requestId)

    fun controlRequest(request: JsonObject, requestId: String): String {
        val root = JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("type", "control_request")
            add("request", request)
        }
        return gson.toJson(root)
    }
}
