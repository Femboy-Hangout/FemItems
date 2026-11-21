# Claude Code Instructions

Follow [AGENTS.md](AGENTS.md) as the canonical engineering guide for this repository.

## Critical Summary

- **Stack:** Native **Paper 26.3 + Folia 26.3** plugin written in **Kotlin** targeting **Java 25**, built with **Maven Wrapper** (`./mvnw` / `.\mvnw.cmd`) and shaded via `maven-shade-plugin`.
- **Folia Safety:** Keep `folia-supported: true` in `src/main/resources/paper-plugin.yml`. Never use `Bukkit.getScheduler()` / `BukkitRunnable` or `entity.teleport(...)`. Always use `GlobalRegionScheduler`, `RegionScheduler`, `EntityScheduler`, `AsyncScheduler`, `entity.teleportAsync(...)`, and thread-safe state (`@Volatile` immutable snapshots / `Atomic*`).
- **Modern Paper API:** Use Adventure `Component` API instead of deprecated `ChatColor` / `setDisplayName` / `getCustomName`.
- **Version Bump on Every Change:** Increment `<version>` in `pom.xml` for every change (`paper-plugin.yml` filters `${project.version}` automatically).
- **Mandatory Live Paper + Folia Fake-Player Testing on Every Change:**
  1. Add/update unit tests (`src/test/kotlin/dev/femitems/plugin/`) and live fake-player assertions (`src/test/kotlin/dev/femitems/plugin/live/LiveServerFakePlayerIT.kt`) for every changed or new feature.
  2. Run `./mvnw -B clean verify -P live-test` (Windows: `.\mvnw.cmd -B clean verify -P live-test`) to build the shaded JAR, run JUnit 5 unit tests, and test live plugin functionality with headless MCProtocolLib fake players on **both Paper 26.3 and Folia 26.3**.
- **CI/CD:** Every GitHub push builds and uploads the JAR artifact via `.github/workflows/build.yml`, runs unit + live Paper/Folia fake-player tests, and auto-creates a GitHub Release when a new version is pushed to `main`.
