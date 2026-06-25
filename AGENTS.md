# AGENTS.md

Guidance for AI agents (and humans) working in this repository.
See [CLAUDE.md](CLAUDE.md) for the architecture, target IDE versions, and toolchain.

## Branching policy (IMPORTANT)

- `main` holds the stable / released baseline.
- **Commit all new development to the `development` branch — not to `main`.**
- Feature branches, if used, should branch off `development` and merge back into `development`.
- Promote `development` → `main` only via an explicit, deliberate merge/release.

## Build & test

```bash
./gradlew buildPlugin   # build the installable zip -> build/distributions/
./gradlew test          # run unit tests
./gradlew runIde        # launch a sandbox IDE with the plugin loaded
```

## Notes

- This plugin wraps the locally-installed `claude` CLI; it uses the user's Claude subscription
  (no API key, no JetBrains AI credits). Keep the process/protocol layer decoupled from the UI.
- Stay product-agnostic: depend only on `com.intellij.modules.platform` so the single artifact
  loads in Rider, WebStorm, and Android Studio.
