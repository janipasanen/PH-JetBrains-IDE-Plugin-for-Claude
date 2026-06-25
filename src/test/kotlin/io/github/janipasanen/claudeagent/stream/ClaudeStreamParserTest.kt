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
