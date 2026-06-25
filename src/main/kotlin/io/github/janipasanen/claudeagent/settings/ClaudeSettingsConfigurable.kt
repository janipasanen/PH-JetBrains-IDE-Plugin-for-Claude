package io.github.janipasanen.claudeagent.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

/** Settings/Preferences → Tools → Claude Agent. */
class ClaudeSettingsConfigurable : Configurable {

    private val settings = ClaudeSettings.getInstance()
    private var ui: DialogPanel? = null

    override fun getDisplayName(): String = "Claude Agent"

    override fun createComponent(): JComponent {
        val dialogPanel = panel {
            row("Claude binary path:") {
                textField()
                    .bindText(settings::claudePath)
                    .align(AlignX.FILL)
                    .comment("Absolute path to the <code>claude</code> executable. Leave blank to auto-detect from PATH.")
            }
            row("Model:") {
                textField()
                    .bindText(settings::model)
                    .comment("e.g. <code>sonnet</code>, <code>opus</code>, <code>haiku</code>. Blank = CLI default.")
            }
            row("Permission mode:") {
                comboBox(ClaudeSettings.PERMISSION_MODES)
                    .bindItem({ settings.permissionMode }, { settings.permissionMode = it ?: ClaudeSettings.DEFAULT_PERMISSION_MODE })
                    .comment(
                        "<b>acceptEdits</b>: auto-apply file edits (recommended). " +
                            "<b>bypassPermissions</b>: also run commands without asking. " +
                            "Interactive per-tool approval arrives in a later version.",
                    )
            }
            row("Extra CLI arguments:") {
                textField()
                    .bindText(settings::extraArgs)
                    .align(AlignX.FILL)
                    .comment("Passed verbatim to <code>claude</code> (e.g. <code>--add-dir /path</code>).")
            }
            row {
                checkBox("Stream partial messages (token-by-token rendering)")
                    .bindSelected(settings::streamPartialMessages)
            }
        }
        ui = dialogPanel
        return dialogPanel
    }

    override fun isModified(): Boolean = ui?.isModified() ?: false

    override fun apply() {
        ui?.apply()
    }

    override fun reset() {
        ui?.reset()
    }

    override fun disposeUIResources() {
        ui = null
    }
}
