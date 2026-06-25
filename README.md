# Claude Agent for JetBrains

Agentic coding with your **own Claude account**, inside JetBrains IDEs — no JetBrains AI
credits, no separate API key. This plugin is a thin frontend that drives the
[Claude Code CLI](https://docs.claude.com/en/docs/claude-code) (which you log in to with your
Claude subscription) and renders the conversation, tool activity, and file edits in a tool
window — bringing the VS Code–style agentic experience to Rider, WebStorm, and Android Studio.

> Independent, unofficial project. Not affiliated with Anthropic or JetBrains.

## Why

JetBrains' built-in AI Chat requires JetBrains AI credits *on top of* a model subscription, and
stops working when credits run out. This plugin instead reuses the `claude` CLI you already
authenticate with your Claude Pro/Max subscription — so the cost model is just your Claude plan.

## How it works

```
JetBrains IDE (this plugin, Kotlin)
        │  spawns
        ▼
  claude --print --input-format stream-json --output-format stream-json --verbose
        │  cwd = your open project   ·   auth = your Claude subscription
        ▼
  NDJSON event stream  ──►  parsed & rendered in the "Claude" tool window
        ▲                         │
        └── user prompts ─────────┘   (edits land on disk; IDE refreshes them)
```

Because the CLI runs with the project directory as its working directory, Claude can read and
edit your source exactly like Claude Code in the terminal — the plugin just gives it an IDE-native
chat, diff, and permission UI.

## Requirements

- **Claude Code CLI** installed and logged in: run `claude` once in a terminal and sign in with
  your Claude account. Verify with `claude --version`.
- One of: **Rider**, **WebStorm**, or **Android Studio** on platform branch **261 (2026.1)** or
  compatible (see the version/tag of the release you download).
- JDK 21 (only needed to build from source).

## Install (from disk)

1. Download the plugin `.zip` from the [Releases](../../releases) page (pick the build whose tag
   matches your IDE's platform branch, e.g. `+261`).
2. In your IDE: **Settings/Preferences → Plugins → ⚙ → Install Plugin from Disk…**, choose the zip.
3. Restart the IDE. Open the **Claude** tool window and start chatting.
4. If the CLI isn't auto-detected, set its path in **Settings → Tools → Claude**.

## Build from source

```bash
./gradlew buildPlugin     # produces build/distributions/*.zip
./gradlew runIde          # launches a sandbox IDE with the plugin loaded
```

(Exact toolchain — IntelliJ Platform Gradle Plugin version, target platform, Gradle/JDK — is
defined in `gradle.properties` / `build.gradle.kts`.)

## Versioning / IDE compatibility

Releases are tagged `vMAJOR.MINOR.PATCH+<platformBranch>` (e.g. `v1.0.0+261`). All of Rider,
WebStorm, and Android Studio 2026.1 share branch **261**, so one `+261` artifact covers them.
When the IDEs diverge across branches, separate artifacts are published per branch.

## Roadmap

Tracked in [Milestones](../../milestones) / [Issues](../../issues):

- **M0** Foundation & Build · **M1** CLI Integration (MVP) · **M2** Agentic Editing UX
- **M3** Multi-IDE & Distribution · **M4** Rich UI & Advanced

## License

[MIT](LICENSE).
