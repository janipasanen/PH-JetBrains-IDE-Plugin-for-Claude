package io.github.janipasanen.claudeagent.diff

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the proposed-content reconstruction used to preview Write/Edit/MultiEdit as a diff. */
class EditApplierTest {

    private fun input(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `write returns the new content`() {
        assertEquals("new content", EditApplier.proposedContent("Write", "old", input("""{"content":"new content"}""")))
    }

    @Test
    fun `edit replaces first occurrence by default`() {
        val result = EditApplier.applyEdit("foo bar foo", input("""{"old_string":"foo","new_string":"X"}"""))
        assertEquals("X bar foo", result)
    }

    @Test
    fun `edit replace_all replaces every occurrence`() {
        val result = EditApplier.applyEdit("foo bar foo", input("""{"old_string":"foo","new_string":"X","replace_all":true}"""))
        assertEquals("X bar X", result)
    }

    @Test
    fun `multi edit applies edits in order`() {
        val result = EditApplier.applyMultiEdit(
            "a b c",
            input("""{"edits":[{"old_string":"a","new_string":"1"},{"old_string":"c","new_string":"3"}]}"""),
        )
        assertEquals("1 b 3", result)
    }

    @Test
    fun `only edit tools are previewable`() {
        assertTrue(EditApplier.canPreview("Edit"))
        assertTrue(EditApplier.canPreview("Write"))
        assertFalse(EditApplier.canPreview("Bash"))
        assertNull(EditApplier.proposedContent("Bash", "x", input("{}")))
    }
}
