package io.github.janipasanen.claudeagent.context

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/** Gathers the active file path and current selection to attach as prompt context. Call on the EDT. */
object EditorContext {

    fun gather(project: Project): String? {
        val manager = FileEditorManager.getInstance(project)
        val builder = StringBuilder()

        manager.selectedFiles.firstOrNull()?.let { file ->
            val base = project.basePath
            val shown = if (base != null && file.path.startsWith(base)) {
                file.path.removePrefix(base).trimStart('/')
            } else {
                file.path
            }
            builder.append("Active file: ").append(shown).append('\n')
        }

        manager.selectedTextEditor?.selectionModel?.selectedText?.takeIf { it.isNotBlank() }?.let { sel ->
            builder.append("Selected code:\n```\n").append(sel).append("\n```\n")
        }

        return builder.toString().ifBlank { null }
    }
}
