package io.github.janipasanen.claudeagent.diff

import com.google.gson.JsonObject

/**
 * Pure reconstruction of the content an edit tool would produce, used to build a diff preview
 * without writing to disk. Mirrors the semantics of the Write / Edit / MultiEdit tools.
 */
object EditApplier {

    val PREVIEWABLE = setOf("Write", "Edit", "MultiEdit")

    fun canPreview(toolName: String?): Boolean = toolName in PREVIEWABLE

    /** @return the proposed file content, or null if this tool/input can't be previewed. */
    fun proposedContent(toolName: String?, current: String, input: JsonObject): String? = when (toolName) {
        "Write" -> input.string("content")
        "Edit" -> applyEdit(current, input)
        "MultiEdit" -> applyMultiEdit(current, input)
        else -> null
    }

    fun applyEdit(current: String, input: JsonObject): String {
        val old = input.string("old_string") ?: return current
        val new = input.string("new_string") ?: ""
        val replaceAll = input.bool("replace_all") ?: false
        return if (replaceAll) current.replace(old, new) else current.replaceFirst(old, new)
    }

    fun applyMultiEdit(current: String, input: JsonObject): String {
        var content = current
        input.getAsJsonArray("edits")?.forEach { element ->
            val edit = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val old = edit.string("old_string") ?: return@forEach
            val new = edit.string("new_string") ?: ""
            val replaceAll = edit.bool("replace_all") ?: false
            content = if (replaceAll) content.replace(old, new) else content.replaceFirst(old, new)
        }
        return content
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.bool(name: String): Boolean? =
        runCatching { get(name)?.asBoolean }.getOrNull()
}
