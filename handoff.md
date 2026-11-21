# Handoff context

## Goal

Maintain **FemItems**, a **Kotlin + Java 25** Maven native **Paper 26.3 & Folia 26.3** plugin that makes custom mob drops and crafting recipes editable and reloadable, with automated unit testing, Paper/Folia server smoke testing, and GitHub Actions CI/CD.

For mandatory coding standards, Folia rules, version bumping, and testing workflows, see [AGENTS.md](AGENTS.md).

## Current implementation

- **Build & Runtime:** Maven Wrapper (`./mvnw` / `.\mvnw.cmd`), Kotlin `2.4.21`, Java 25 (`maven.compiler.release = 25`), Paper API `26.3.build.255-stable`, `maven-shade-plugin:3.6.2` (bundles `kotlin-stdlib`; `paper-api` stays `provided`).
- **Descriptor:** `src/main/resources/paper-plugin.yml` declares `folia-supported: true`, `api-version: '26.3'`, and filters `version: '${project.version}'` from `pom.xml`.
- **Folia-safe Kotlin codebase (`src/main/kotlin/dev/femitems/plugin/`):**
  - `FemItemsPlugin.kt`: Brigadier commands (`/femitems` & `/femi` with `reload` and `debug`), thread-safe `@Volatile` mob rule snapshots and `AtomicBoolean` debug flag, `GlobalRegionScheduler` for runtime recipe reloads, Adventure `Component` API (no deprecated `ChatColor`), and Paper/Folia platform detection via `ServerBuildInfo`.
  - `ConfigModels.kt` & `ConfigParser.kt`: Pure Kotlin models, YAML parsing/validation, color-stripping name matching, drop rolling, and line-number error reporting.
- **Testing & CI:**
  - JUnit 5 unit tests under `src/test/kotlin/dev/femitems/plugin/`.
  - Automated live Paper 26.3 & Folia 26.3 fake-player functional tests (`src/test/kotlin/dev/femitems/plugin/live/LiveServerFakePlayerIT.kt` using test-scoped MCProtocolLib `26.3-3`), run via `./mvnw -B verify -P live-test` (or `.\mvnw.cmd -B verify -P live-test`).
  - Interactive local dev server launchers (`scripts/run-server.sh` & `scripts/run-server.ps1`).
  - GitHub Actions CI/CD (`.github/workflows/build.yml`) builds and uploads the shaded JAR artifact on every push, runs unit + live Paper/Folia fake-player tests, and publishes a GitHub Release whenever a new version is pushed to `main`.
- **Default config:** `src/main/resources/config.yml` maps the five loot tables from `forsaken_loot.zip` (`archer`, `grunt`, `champion`, `dreadbound`, `ravager`).

## Source archive findings

`forsaken_loot.zip` contains `archer.json`, `champion.json`, `grunt.json`, `ravager.json`, and `dreadbound.jason`. The latter is a typo and is not a valid datapack JSON table filename. The tables are stored at `data/forsaken/loot_table/`, not attached to entity loot tables, so the vanilla game will not select them for mob deaths. No recipes are present. `pack.mcmeta` declares data pack format 121.

The default plugin config preserves the listed items, random count ranges, drop probabilities, and Champion's Trophy display name. Random enchantment behavior specified by two datapack entries is not implemented yet.

## Open gameplay follow-ups

1. Use `/femitems debug` on the production server to confirm entity types/custom names for arena mobs, then adjust `plugins/FemItems/config.yml` if needed.
2. Decide whether the enchanted book/sword drops need configurable random vanilla enchantments against Paper 26.3's API.
3. Add desired crafting recipes under `recipes` in `plugins/FemItems/config.yml`.
