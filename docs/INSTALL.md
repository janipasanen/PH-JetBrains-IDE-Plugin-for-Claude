# Installing Claude Agent

## Prerequisites

1. **Claude Code CLI**, installed and logged in:
   ```bash
   claude --version      # should print a version
   claude                # run once and sign in with your Claude account, then quit
   ```
   The plugin reuses this login — it uses your Claude subscription, no API key and no JetBrains AI credits.
2. A supported IDE on platform branch **261 (2026.1)**: **Rider**, **WebStorm**, or **Android Studio**.

## Install from disk

1. Get the plugin zip:
   - download it from the repo [Releases](../../releases) (pick the build whose tag matches your IDE's
     platform branch — e.g. `+261` for 2026.1), **or**
   - build it yourself: `./gradlew buildPlugin` → `build/distributions/*.zip` (see [BUILD.md](BUILD.md)).
2. In your IDE: **Settings/Preferences → Plugins → ⚙ (gear) → Install Plugin from Disk…**
3. Select the `.zip`, then **Restart** the IDE.
4. Open the **Claude** tool window (right-hand side by default) and start chatting.

Repeat for each IDE (Rider, WebStorm, Android Studio) — the same `+261` artifact works in all three.

## First-run configuration

Open **Settings/Preferences → Tools → Claude Agent**:

- **Claude binary path** — leave blank to auto-detect from `PATH`; set it if the CLI isn't found.
- **Model** — e.g. `sonnet`, `opus`, `haiku`; blank uses the CLI default.
- **Permission mode**:
  - `default` *(recommended)* — Claude asks before editing files or running commands; approve/deny in
    the tool window (with a **View diff** preview for edits).
  - `acceptEdits` — auto-applies file edits; still asks for commands.
  - `bypassPermissions` — full autonomy, no prompts (use with care).
- **Extra CLI arguments** — passed verbatim, e.g. `--add-dir /some/extra/root`.

## Using it

- Type a request and press **Enter** (Shift+Enter for a newline).
- Tick **Attach open file & selection** to send the active editor file/selection as context.
- Use **@ File** to insert a file reference into your prompt.
- **New Chat** starts a fresh session; **Stop** interrupts the current turn.
- Files Claude edits refresh automatically in the editor.

## Troubleshooting

- *"Cannot find the 'claude' CLI"* — set the binary path in settings, or ensure `claude` is on `PATH`.
- *Edits seem blocked / "denied without prompt"* — check the permission mode; `default`/`acceptEdits`
  must be selected for edits to apply.
- Logs: **Help → Show Log in Finder/Explorer** (`idea.log`), category `ClaudeSessionService`.
