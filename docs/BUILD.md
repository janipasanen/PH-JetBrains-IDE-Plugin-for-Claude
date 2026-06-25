# Building Claude Agent

## Requirements

- **JDK 21** (branch 261 runs on JBR 21; bytecode must target 21).
- **Gradle 9+** (the bundled wrapper pins 9.6 — just use `./gradlew`).
- The IntelliJ Platform is downloaded automatically on first build.

## Common tasks

```bash
./gradlew buildPlugin   # -> build/distributions/*.zip  (installable, see docs/INSTALL.md)
./gradlew test          # unit tests
./gradlew runIde        # launch a sandbox IDE with the plugin loaded
./gradlew verifyPlugin  # IntelliJ Plugin Verifier (downloads IDEs; slow)
```

## Toolchain

- IntelliJ Platform Gradle Plugin **2.16.0**, building against the unified **IntelliJ IDEA (IU) 2026.1**.
- The plugin depends only on `com.intellij.modules.platform`, so one artifact loads in Rider,
  WebStorm, and Android Studio. Compatibility: `since-build 261`, `until-build 261.*`.

## Build matrix (other platform branches)

The platform target is parameterized in `gradle.properties` and overridable per build. Branch map:
`241=2024.1 · 251=2025.1 · 252=2025.2 · 253=2025.3 · 261=2026.1`. Note: IDEA **Community (IC)**
was discontinued after **252**; from **253** use the unified **IU** coordinate.

```bash
# 2026.1 / branch 261 (default)
./gradlew buildPlugin

# 2025.2 / branch 252 (IDEA Community still published)
./gradlew buildPlugin -PplatformType=IC -PplatformVersion=2025.2 \
  -PpluginSinceBuild=252 -PpluginUntilBuild=252.*
```

Each invocation produces a separate zip. For Marketplace uploads keep the since/until ranges
non-overlapping across branches.

## Releasing

Push a tag like `v0.1.0+261`; `.github/workflows/release.yml` builds the plugin and attaches the
zip to a GitHub Release. If the signing secrets `CERTIFICATE_CHAIN` / `PRIVATE_KEY` /
`PRIVATE_KEY_PASSWORD` are configured, the release artifact is signed.

## Branching

Development happens on `development`; `main` is the stable baseline. See [AGENTS.md](../AGENTS.md).
