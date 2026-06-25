package io.github.janipasanen.claudeagent.process

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import java.io.File

/**
 * Locates the `claude` executable: explicit setting → PATH → common install locations.
 */
object ClaudeBinaryLocator {

    private val COMMON_LOCATIONS: List<String>
        get() {
            val home = System.getProperty("user.home") ?: return emptyList()
            return listOf(
                "$home/.local/bin/claude",
                "$home/.claude/local/claude",
                "$home/.bun/bin/claude",
                "/opt/homebrew/bin/claude",
                "/usr/local/bin/claude",
                "/usr/bin/claude",
            )
        }

    /**
     * @param explicitPath the configured override (may be blank).
     * @return an absolute path to an executable `claude`, or null if none found.
     */
    fun locate(explicitPath: String?): String? {
        if (!explicitPath.isNullOrBlank()) {
            val f = File(explicitPath)
            if (f.canExecute()) return f.absolutePath
        }

        PathEnvironmentVariableUtil.findInPath("claude")
            ?.takeIf { it.canExecute() }
            ?.let { return it.absolutePath }

        return COMMON_LOCATIONS.firstOrNull { File(it).canExecute() }
    }
}
