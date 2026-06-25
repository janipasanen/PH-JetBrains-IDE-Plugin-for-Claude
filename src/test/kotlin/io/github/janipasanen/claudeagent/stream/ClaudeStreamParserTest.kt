package io.github.janipasanen.claudeagent.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests driven by a real NDJSON capture from `claude --output-format stream-json`
 * (see src/test/resources/streams/hello.ndjson). Guards against schema drift.
 */
class ClaudeStreamParserTest {

    private fun fixture(name: String): List<String> =
        javaClass.getResourceAsStream("/streams/$name")!!
            .bufferedReader().readLines()

    @Test
    fun `parses a real hello stream without unknown events`() {
        val events = fixture("hello.ndjson").mapNotNull { ClaudeStreamParser.parse(it) }

        val unknown = events.filterIsInstance<ClaudeEvent.Unknown>()
        assertTrue("unexpected Unknown events: $unknown", unknown.isEmpty())

        val init = events.filterIsInstance<ClaudeEvent.SystemInit>().firstOrNull()
        assertNotNull("expected a system/init event", init)
        assertTrue("model should be a sonnet variant", init!!.model.orEmpty().contains("sonnet"))
        // apiKeySource=none confirms subscription auth (not an API key).
        assertEquals("none", init.apiKeySource)

        assertTrue(
            "expected token-level assistant deltas",
            events.any { it is ClaudeEvent.AssistantTextDelta },
        )

        val result = events.filterIsInstance<ClaudeEvent.Result>().last()
        assertFalse("turn should not be an error", result.isError)
        assertEquals("hello from claude", result.text)
        assertNotNull("result should carry a cost", result.totalCostUsd)
    }

    @Test
    fun `blank and malformed lines are tolerated`() {
        assertEquals(null, ClaudeStreamParser.parse(""))
        assertEquals(null, ClaudeStreamParser.parse("   "))
        val garbage = ClaudeStreamParser.parse("{not json")
        assertTrue(garbage is ClaudeEvent.Unknown)
    }

    @Test
    fun `can_use_tool request is parsed`() {
        val line = """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Write","input":{"file_path":"/a"}}}"""
        val event = ClaudeStreamParser.parse(line)
        assertTrue(event is ClaudeEvent.CanUseToolRequest)
        event as ClaudeEvent.CanUseToolRequest
        assertEquals("r1", event.requestId)
        assertEquals("Write", event.toolName)
        assertEquals("/a", event.input?.get("file_path")?.asString)
    }

    @Test
    fun `result carries cost and permission denials`() {
        val line = """{"type":"result","subtype":"success","is_error":false,"result":"done","total_cost_usd":0.01,"permission_denials":[{"tool_name":"Bash","tool_input":{"command":"rm"}}]}"""
        val result = ClaudeStreamParser.parse(line) as ClaudeEvent.Result
        assertFalse(result.isError)
        assertEquals("done", result.text)
        assertEquals(0.01, result.totalCostUsd!!, 1e-9)
        assertEquals(1, result.permissionDenials.size)
        assertEquals("Bash", result.permissionDenials[0].toolName)
    }

    @Test
    fun `tool_result is delivered inside a user event`() {
        val line = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tu1","content":"ok","is_error":false}]}}"""
        val event = ClaudeStreamParser.parse(line) as ClaudeEvent.ToolResultMessage
        assertEquals(1, event.toolResults.size)
        assertEquals("tu1", event.toolResults[0].toolUseId)
        assertEquals("ok", event.toolResults[0].content)
        assertFalse(event.toolResults[0].isError)
    }

    @Test
    fun `stream_event text delta is surfaced`() {
        val line = """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"hello"}}}"""
        val event = ClaudeStreamParser.parse(line) as ClaudeEvent.AssistantTextDelta
        assertEquals("hello", event.text)
    }

    @Test
    fun `non-text stream events and control_response are skipped`() {
        assertEquals(null, ClaudeStreamParser.parse("""{"type":"control_response","response":{"subtype":"success"}}"""))
        assertEquals(null, ClaudeStreamParser.parse("""{"type":"stream_event","event":{"type":"message_stop"}}"""))
    }

    @Test
    fun `assistant tool_use blocks are extracted with touched path`() {
        val line = """
            {"type":"assistant","session_id":"s1","message":{"role":"assistant","content":[
              {"type":"text","text":"editing"},
              {"type":"tool_use","id":"tu_1","name":"Edit","input":{"file_path":"/tmp/a.kt"}}
            ]}}
        """.trimIndent().replace("\n", "")

        val event = ClaudeStreamParser.parse(line)
        assertTrue(event is ClaudeEvent.AssistantMessage)
        val msg = event as ClaudeEvent.AssistantMessage
        assertEquals("editing", msg.text)
        assertEquals(1, msg.toolUses.size)
        assertEquals("Edit", msg.toolUses[0].name)
        assertEquals("/tmp/a.kt", msg.toolUses[0].touchedPath())
    }
}
