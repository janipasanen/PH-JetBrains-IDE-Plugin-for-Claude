package io.github.janipasanen.claudeagent.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the stdin wire format matches what the CLI expects (validated empirically). */
class ClaudeControlProtocolTest {

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `user message escapes content`() {
        val o = obj(ClaudeControlProtocol.userMessage("hi \"q\"\nline2"))
        assertEquals("user", o.get("type").asString)
        val message = o.getAsJsonObject("message")
        assertEquals("user", message.get("role").asString)
        assertEquals("hi \"q\"\nline2", message.get("content").asString)
    }

    @Test
    fun `initialize opens the control channel`() {
        val o = obj(ClaudeControlProtocol.initialize("init-1"))
        assertEquals("control_request", o.get("type").asString)
        assertEquals("init-1", o.get("request_id").asString)
        val request = o.getAsJsonObject("request")
        assertEquals("initialize", request.get("subtype").asString)
        assertTrue(request.has("hooks"))
        assertTrue(request.get("sdkMcpServers").isJsonArray)
    }

    @Test
    fun `permission allow echoes request id and updated input`() {
        val input = JsonObject().apply { addProperty("file_path", "/a.kt") }
        val o = obj(ClaudeControlProtocol.permissionResponse("req-1", allow = true, updatedInput = input))
        assertEquals("control_response", o.get("type").asString)
        val response = o.getAsJsonObject("response")
        assertEquals("success", response.get("subtype").asString)
        assertEquals("req-1", response.get("request_id").asString)
        val decision = response.getAsJsonObject("response")
        assertEquals("allow", decision.get("behavior").asString)
        assertEquals("/a.kt", decision.getAsJsonObject("updatedInput").get("file_path").asString)
    }

    @Test
    fun `permission deny carries message and non-interrupt`() {
        val o = obj(ClaudeControlProtocol.permissionResponse("req-2", allow = false, updatedInput = null, denyMessage = "nope"))
        val decision = o.getAsJsonObject("response").getAsJsonObject("response")
        assertEquals("deny", decision.get("behavior").asString)
        assertEquals("nope", decision.get("message").asString)
        assertFalse(decision.get("interrupt").asBoolean)
    }

    @Test
    fun `set permission mode, set model and interrupt`() {
        val mode = obj(ClaudeControlProtocol.setPermissionMode("acceptEdits", "r")).getAsJsonObject("request")
        assertEquals("set_permission_mode", mode.get("subtype").asString)
        assertEquals("acceptEdits", mode.get("mode").asString)

        val model = obj(ClaudeControlProtocol.setModel("opus", "r")).getAsJsonObject("request")
        assertEquals("set_model", model.get("subtype").asString)
        assertEquals("opus", model.get("model").asString)

        val interrupt = obj(ClaudeControlProtocol.interrupt("r")).getAsJsonObject("request")
        assertEquals("interrupt", interrupt.get("subtype").asString)
    }
}
