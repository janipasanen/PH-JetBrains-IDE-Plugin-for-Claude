# CLAUDE.md — project notes for AI agents

Context for anyone (human or Claude) working in this repo.

## What this is

A JetBrains IDE plugin (Kotlin, IntelliJ Platform) that provides **agentic coding using the
user's own Claude subscription**, by wrapping the **Claude Code CLI** (`claude`). It is NOT an
Anthropic-API client — auth, the agent loop, and tool execution all come from the CLI, which the
user logs into with their Claude account. This sidesteps JetBrains AI credits entirely.

## Core architecture

- The plugin spawns `claude --print --input-format stream-json --output-format stream-json
  --verbose` with `cwd = project.basePath`.
- It writes user messages to the process **stdin** as stream-json, and reads the **stdout**
  NDJSON event stream (system/init, assistant, user, tool_use, tool_result, result,
  stream_event partial deltas).
- File edits are performed by the CLI on disk; the plugin refreshes the VFS so editors update.
- Permission prompts, diffs, and editor context are layered on via IntelliJ Platform APIs.

See the milestone breakdown in GitHub Issues. MVP = M1.

## Target environment (verified 2026-06-25 on this machine)

All three target IDEs are on platform branch **261 (2026.1)** — one artifact covers all:

| IDE            | Version   | Build                         | Code |
|----------------|-----------|-------------------------------|------|
| Rider          | 2026.1.3  | `261.25134.178`               | RD   |
| WebStorm       | 2026.1.3  | `261.25134.101`               | WS   |
| Android Studio | 2026.1.1  | `261.23567.138.2611.15646644` | AI   |

- Plugin compatibility: `since-build = 261`, `until-build = 261.*`.
- Depend ONLY on `com.intellij.modules.platform` so it loads in all IntelliJ-based IDEs
  (including Rider's frontend and Android Studio). Do not pull product/language modules.
- Build against IntelliJ IDEA Community `2026.1` as the common denominator.

Toolchain available on this machine: JDK 21, Gradle 9.6, Node 24, `claude` CLI 2.1.191, `gh`.

## Versioning

`vMAJOR.MINOR.PATCH+<branch>` tags (e.g. `v1.0.0+261`). When IDEs span multiple branches,
publish one artifact per branch via the Gradle build matrix.

## Branching policy (IMPORTANT)

- `main` holds the stable / released baseline.
- **Commit all new development to the `development` branch — not to `main`.**
- Feature branches branch off `development` and merge back into `development`.
- Promote `development` → `main` only via an explicit merge/release.

## Conventions

- Kotlin, IntelliJ Platform Gradle Plugin 2.x.
- Keep the CLI process layer (spawn/stream/parse) decoupled from UI so the UI can later move
  from Swing (MVP) to JCEF without touching the protocol code.
- Tolerate unknown stream event types/fields — the CLI protocol evolves.

## Repo

GitHub: `janipasanen/PH-JetBrains-IDE-Plugin-for-Claude` (public). Work is tracked in
Milestones M0–M4 and their Issues.
