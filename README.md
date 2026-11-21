# FemItems

A native **Paper & Folia** plugin for **Minecraft 26.3** written in **Kotlin** targeting **Java 25**. It adds configurable, reloadable custom mob drops and shaped or shapeless crafting recipes, and can be reloaded live with `/femitems reload` (or `/femi reload`).

> **AI Agents:** See [AGENTS.md](AGENTS.md) (also referenced by [CLAUDE.md](CLAUDE.md) and [.github/copilot-instructions.md](.github/copilot-instructions.md)) for mandatory coding standards, Folia thread-safety rules, version bumping, and testing steps.

## Build and install

Build with the bundled Maven Wrapper and Java 25:

```bash
# Linux / macOS
./mvnw clean verify

# Windows (PowerShell)
.\mvnw.cmd clean verify
```

Copy the shaded JAR `target/femitems-<version>.jar` into the `plugins` directory of a **Paper 26.3** or **Folia 26.3** server (running Java 25) and start it. On first startup, edit `plugins/FemItems/config.yml`, then run `/femitems reload` (or `/femi reload`). The command requires `femitems.reload` (operators by default).

The project compiles against Paper API 26.3, declares `folia-supported: true` in `paper-plugin.yml`, bundles `kotlin-stdlib` via `maven-shade-plugin`, and registers commands through Paper's Brigadier lifecycle API. Use `/femitems reload` for configuration updates and a server restart for plugin JAR updates.

## Automated CI/CD builds

Every push to GitHub runs [`.github/workflows/build.yml`](.github/workflows/build.yml):
1. Compiles Kotlin (Java 25 bytecode) and executes the JUnit 5 unit test suite.
2. Packages and uploads `target/femitems-<version>.jar` as a downloadable GitHub Actions workflow artifact.
3. Boots live **Paper 26.3** and **Folia 26.3** servers with headless MCProtocolLib fake players (`LiveServerFakePlayerIT`) to test permissions, player-initiated `/femitems reload`, custom shaped/shapeless recipes, `/femitems debug`, live custom-named mob kills & drop pickups, and invalid-config reload resilience.
4. On pushes to `main` (or `v*` tags) with a new `<version>` in `pom.xml`, automatically tags `v<version>` and publishes a GitHub Release with the JAR attached.

## Testing workflow

- **Unit tests (JUnit 5):**
  ```bash
  ./mvnw clean verify          # Linux / macOS
  .\mvnw.cmd clean verify      # Windows
  ```
- **Live Paper 26.3 & Folia 26.3 fake-player functional tests (required on every change):**
  ```bash
  ./mvnw -B verify -P live-test      # Linux / macOS
  .\mvnw.cmd -B verify -P live-test  # Windows
  ```
  *(Pass `"-Dlive.platform=paper"` or `"-Dlive.platform=folia"` to run a single server platform while iterating.)*
- **Interactive local dev server (Paper or Folia 26.3):**
  ```bash
  ./scripts/run-server.sh --platform paper                                # Linux / macOS
  powershell -ExecutionPolicy Bypass -File .\scripts\run-server.ps1 -Platform paper # Windows
  ```

## Mob drop configuration

Each `mobs` entry has an `entity` (Bukkit entity type, or `ANY`), optional `name-contains` (case-insensitive substring of the mob's custom name), and a list of `drops`. `ANY` skips only the base entity-type check; the name filter still applies, and it must be non-empty for `ANY`. For example, `entity: ANY` with `name-contains: "Champion"` matches any entity type whose custom name contains “Champion.” Drops are added to vanilla drops. Each drop supports `material`, `min`, `max`, `chance` (0.0 through 1.0), and optional `name`.

The supplied archive's five loot tables have been converted into the default config. The assumed entity mappings are Skeleton/Archer and Zombie/Grunt; Champion, Dreadbound, and Ravager use `ANY` plus their custom-name substring because their base entity types were not specified. Change these matches to fit the arena's actual mobs. The archive did not specify those mappings.

If a drop does not fire, use `/femitems debug` to toggle mob-death diagnostics, kill one affected mob, then check the server console for its actual entity type, custom name, and matching rule IDs. Run `/femitems debug` again to turn diagnostics off. Set `entity: ANY` when the reported type differs from the configured one, and adjust `name-contains` to match the reported custom name. Reload config with `/femitems reload`.

## Crafting recipe configuration

The archive contains no crafting recipes, so `recipes` starts empty. Add recipes in this form:

```yaml
recipes:
  iron_sword:
    type: shaped
    shape:
      - " I "
      - " I "
      - " S "
    ingredients:
      I: IRON_INGOT
      S: STICK
    result:
      material: IRON_SWORD
      amount: 1
      name: "Arena Blade"
```

Each string in `shape` is one crafting-grid row, read left to right. Every character is one slot. Here, `I` is an iron ingot, `S` is a stick, and a space means the slot is empty:

```text
 I
 I
 S
```

The `ingredients` section tells the plugin which material each letter represents. The `result` section tells it what item to craft. For a normal iron sword, the three rows are ingot, ingot, stick; the centered spaces make that pattern line up like a sword in the 3×3 crafting grid.

For a shapeless recipe, use `type: shapeless` and make `ingredients` a list of material names:

```yaml
  example_mix:
    type: shapeless
    ingredients: [DIAMOND, EMERALD]
    result:
      material: ECHO_SHARD
      amount: 1
```

Recipe IDs should be unique and use lowercase letters, digits, underscores, or hyphens. Ingredients and results currently use vanilla Bukkit materials; custom plugin items are not implemented.
