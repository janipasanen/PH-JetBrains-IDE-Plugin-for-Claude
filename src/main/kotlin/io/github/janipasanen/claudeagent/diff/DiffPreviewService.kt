package io.github.janipasanen.claudeagent.diff

import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Builds a before/after preview of a proposed file edit and shows it in the IDE diff viewer,
 * by reconstructing the proposed content from the tool input (no changes are written to disk).
 */
object DiffPreviewService {

    private val PREVIEWABLE = setOf("Write", "Edit", "MultiEdit")

    fun canPreview(toolName: String?): Boolean = toolName in PREVIEWABLE

    fun showPreview(project: Project, toolName: String?, input: JsonObject?) {
        if (input == null) return
        val path = input.string("file_path") ?: return
        val file = if (File(path).isAbsolute) File(path) else File(project.basePath.orEmpty(), path)
        val current = if (file.exists()) file.readText() else ""

        val proposed = when (toolName) {
            "Write" -> input.string("content") ?: return
            "Edit" -> applyEdit(current, input)
            "MultiEdit" -> applyMultiEdit(current, input)
            else -> return
        }

        val factory = DiffContentFactory.getInstance()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        val left = factory.create(project, current, fileType)
        val right = factory.create(project, proposed, fileType)
        val request = SimpleDiffRequest(
            "Claude · proposed change to ${file.name}",
            left,
            right,
            "Current",
            "Proposed",
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun applyEdit(current: String, input: JsonObject): String {
        val old = input.string("old_string") ?: return current
        val new = input.string("new_string") ?: ""
        val replaceAll = input.bool("replace_all") ?: false
        return if (replaceAll) current.replace(old, new) else current.replaceFirst(old, new)
    }

    private fun applyMultiEdit(current: String, input: JsonObject): String {
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
