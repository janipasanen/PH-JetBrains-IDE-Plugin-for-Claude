package io.github.janipasanen.claudeagent.ui

import javax.swing.JComponent

/**
 * Abstraction over the chat transcript so the UI can render via Swing (default) or JCEF (opt-in)
 * without the controller caring which. Methods are called on the EDT.
 */
interface Transcript {
    val component: JComponent

    /** A user turn; also marks the boundary so the next [assistant] call starts a fresh block. */
    fun user(text: String)

    /** Append assistant text to the current assistant block (creating it if needed). */
    fun assistant(text: String)

    /** A tool-call activity line. */
    fun tool(label: String)

    /** A dim, italic informational line (status, costs, approvals). */
    fun note(text: String)

    /** An error line. */
    fun error(text: String)

    fun clear()

    fun dispose() {}
}
