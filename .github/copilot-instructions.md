# GitHub Copilot Instructions

Follow [`AGENTS.md`](../AGENTS.md) as the canonical engineering guide for this repository.

## Key Rules

- **Kotlin + Java 25 + Maven Wrapper:** Write all production and test code in Kotlin under `src/main/kotlin/` and `src/test/kotlin/`, targeting Java 25 (`maven.compiler.release = 25`), and build using `./mvnw` (`.\mvnw.cmd` on Windows).
- **Paper 26.3 + Folia 26.3 Compatibility:** Keep `folia-supported: true` in `src/main/resources/paper-plugin.yml`. Never use `BukkitScheduler` / `BukkitRunnable` or sync `entity.teleport(...)`; always use Paper's regionized schedulers (`GlobalRegionScheduler`, `RegionScheduler`, `EntityScheduler`, `AsyncScheduler`), `teleportAsync(...)`, Adventure `Component` APIs, and thread-safe shared state.
- **Version Bump Required:** Bump `<version>` in `pom.xml` on every change (never hardcode version strings in `paper-plugin.yml`).
- **Live Paper & Folia Fake-Player Testing Required on Every Change:** Add/update JUnit 5 unit tests and live fake-player integration tests (`src/test/kotlin/dev/femitems/plugin/live/LiveServerFakePlayerIT.kt`), then run `./mvnw -B clean verify -P live-test` (Windows: `.\mvnw.cmd -B clean verify -P live-test`) to verify real in-game functionality with connected fake players on **both Paper 26.3 and Folia 26.3**.
- **Offline Fake-Player Testing Procedure:** Exercise every player-facing feature through offline-mode fake players performing real player actions (commands, block use/place/break, GUI clicks), with the console used only for setup. See [AGENTS.md](../AGENTS.md) section 5.
- **Automated Builds:** Every push to GitHub runs `.github/workflows/build.yml` to build and upload the shaded JAR artifact, execute unit + live Paper/Folia fake-player tests, and publish a GitHub Release when a new version lands on `main`.
