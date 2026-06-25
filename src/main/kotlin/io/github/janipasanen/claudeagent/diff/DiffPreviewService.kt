package io.github.janipasanen.claudeagent.diff

import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Shows a before/after preview of a proposed file edit in the IDE diff viewer. The proposed content
 * is reconstructed by [EditApplier]; nothing is written to disk.
 */
object DiffPreviewService {

    fun canPreview(toolName: String?): Boolean = EditApplier.canPreview(toolName)

    fun showPreview(project: Project, toolName: String?, input: JsonObject?) {
        if (input == null) return
        val path = input.get("file_path")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val file = if (File(path).isAbsolute) File(path) else File(project.basePath.orEmpty(), path)
        val current = if (file.exists()) file.readText() else ""
        val proposed = EditApplier.proposedContent(toolName, current, input) ?: return

        val factory = DiffContentFactory.getInstance()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.name)
        val request = SimpleDiffRequest(
            "Claude · proposed change to ${file.name}",
            factory.create(project, current, fileType),
            factory.create(project, proposed, fileType),
            "Current",
            "Proposed",
        )
        DiffManager.getInstance().showDiff(project, request)
    }
}
