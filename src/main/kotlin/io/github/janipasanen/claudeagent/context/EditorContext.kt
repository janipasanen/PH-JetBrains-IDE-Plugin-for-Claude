package io.github.janipasanen.claudeagent.context

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * Gathers the active file path, current selection, and the IDE's problems for that file, to attach
 * as prompt context. This gives Claude the same diagnostics an MCP `getDiagnostics` would. Call on the EDT.
 */
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

        val editor = manager.selectedTextEditor
        editor?.selectionModel?.selectedText?.takeIf { it.isNotBlank() }?.let { sel ->
            builder.append("Selected code:\n```\n").append(sel).append("\n```\n")
        }

        if (editor != null) {
            val problems = collectProblems(project, editor.document)
            if (problems.isNotEmpty()) {
                builder.append("Problems in this file (from the IDE):\n")
                problems.take(30).forEach { builder.append("- ").append(it).append('\n') }
            }
        }

        return builder.toString().ifBlank { null }
    }

    private fun collectProblems(project: Project, document: com.intellij.openapi.editor.Document): List<String> {
        val markup = DocumentMarkupModel.forDocument(document, project, false) ?: return emptyList()
        return markup.allHighlighters.mapNotNull { highlighter ->
            val info = highlighter.errorStripeTooltip as? HighlightInfo ?: return@mapNotNull null
            if (info.severity < HighlightSeverity.WARNING) return@mapNotNull null
            val description = info.description ?: return@mapNotNull null
            val line = document.getLineNumber(info.startOffset) + 1
            "L$line [${info.severity.name}] $description"
        }.distinct()
    }
}
