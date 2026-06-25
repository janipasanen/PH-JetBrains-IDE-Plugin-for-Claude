package io.github.janipasanen.claudeagent.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level persisted settings for the plugin. Edited via [ClaudeSettingsConfigurable]
 * under Settings/Preferences → Tools → Claude Agent.
 */
@State(
    name = "ClaudeAgentSettings",
    storages = [Storage("claude-agent.xml")],
)
class ClaudeSettings : SimplePersistentStateComponent<ClaudeSettings.State>(State()) {

    class State : BaseState() {
        /** Explicit path to the `claude` binary; blank = auto-detect from PATH / common locations. */
        var claudePath by string("")

        /** `--model` value (e.g. "sonnet", "opus", "haiku"); blank = CLI default. */
        var model by string("")

        /** `--permission-mode`. "default" prompts for approval (handled in the tool window). */
        var permissionMode by string(DEFAULT_PERMISSION_MODE)

        /** Extra raw CLI arguments, whitespace-separated. */
        var extraArgs by string("")

        /** Emit token-level streaming (`--include-partial-messages`). */
        var streamPartialMessages by property(true)

        /** Use the JCEF (rich Markdown) transcript when available; falls back to Swing otherwise. */
        var useRichUi by property(false)

        /** Path to an MCP config file passed via `--mcp-config` (blank = none). */
        var mcpConfigPath by string("")
    }

    var claudePath: String
        get() = state.claudePath.orEmpty()
        set(value) { state.claudePath = value }

    var model: String
        get() = state.model.orEmpty()
        set(value) { state.model = value }

    var permissionMode: String
        get() = state.permissionMode.orEmpty().ifEmpty { DEFAULT_PERMISSION_MODE }
        set(value) { state.permissionMode = value }

    var extraArgs: String
        get() = state.extraArgs.orEmpty()
        set(value) { state.extraArgs = value }

    var streamPartialMessages: Boolean
        get() = state.streamPartialMessages
        set(value) { state.streamPartialMessages = value }

    var useRichUi: Boolean
        get() = state.useRichUi
        set(value) { state.useRichUi = value }

    var mcpConfigPath: String
        get() = state.mcpConfigPath.orEmpty()
        set(value) { state.mcpConfigPath = value }

    companion object {
        const val DEFAULT_PERMISSION_MODE = "default"

        /** Values accepted by `claude --permission-mode` (verified against CLI 2.1.x). */
        val PERMISSION_MODES = listOf(
            "default",
            "acceptEdits",
            "plan",
            "auto",
            "dontAsk",
            "bypassPermissions",
        )

        fun getInstance(): ClaudeSettings = service()
    }
}
