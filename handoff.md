# Handoff context

## Goal

Maintain **FemItems**, a **Kotlin + Java 25** Maven native **Paper 26.3 & Folia 26.3** plugin that makes custom mob drops and crafting recipes editable and reloadable, with automated unit testing, Paper/Folia server smoke testing, and GitHub Actions CI/CD.

For mandatory coding standards, Folia rules, version bumping, and testing workflows, see [AGENTS.md](AGENTS.md).

**Current version:** `1.3.2` on `main` (async `/femitems reload`; recipe diffing so reloads trigger at most 2 server-wide resyncs, 0 if recipes are unchanged). The next plugin change should bump to `1.3.3` (fix) or `1.4.0` (feature).

## Current implementation

- **Build & Runtime:** Maven Wrapper (`./mvnw` / `.\mvnw.cmd`), Kotlin `2.4.21`, Java 25 (`maven.compiler.release = 25`), Paper API `26.3.build.255-stable`, `maven-shade-plugin:3.6.2` (bundles `kotlin-stdlib`; `paper-api` stays `provided`).
- **Descriptor:** `src/main/resources/paper-plugin.yml` declares `folia-supported: true`, `api-version: '26.3'`, and filters `version: '${project.version}'` from `pom.xml`.
- **Folia-safe Kotlin codebase (`src/main/kotlin/dev/femitems/plugin/`):**
  - `Messages.kt` (v1.3.0): Adventure/MiniMessage chat styling: pink→lavender gradient `[FemItems]` prefix, reload counts, config-error hover details, and the clickable `/femitems` info panel. Plain-text output is pinned by `MessagesTest` because live tests/smoke scripts match on it.
  - `FemItemsPlugin.kt`: Brigadier commands (`/femitems` & `/femi`: bare info panel, `reload`, and `debug`), all-or-nothing reloads (Bukkit recipes are built before any live state is swapped), oversized drop counts split into max-stack-size stacks, thread-safe `@Volatile` mob rule and recipe-key snapshots, `AtomicBoolean` debug flag, `GlobalRegionScheduler` for runtime recipe reloads, Folia-safe `entity.scheduler` recipe book discovery for online players on reload plus `PlayerJoinEvent` auto-unlock on join, Adventure `Component` API with `&` legacy color code support (no deprecated `ChatColor`), recipe result metadata (`lore`, `unbreakable`, unsafe/stored `enchants`), and Paper/Folia platform detection via `ServerBuildInfo`.
  - `ConfigModels.kt` & `ConfigParser.kt`: Pure Kotlin models, YAML parsing/validation (including recipe metadata & enchantments with injectable registry seam for unit tests), color-stripping name matching, drop rolling, and line-number error reporting.
- **Testing & CI:**
  - JUnit 5 unit tests under `src/test/kotlin/dev/femitems/plugin/` (15 tests: `ConfigParserTest`, `MessagesTest`, `PluginDescriptorTest`).
  - The live suite also covers: the bare `/femitems` info panel, reload counts, a 70-arrow drop arriving as 64 + 6 stacks with nothing above 64, and a failed reload (unused shaped symbol) leaving existing recipes registered.
  - Automated live Paper 26.3 & Folia 26.3 fake-player functional tests (`src/test/kotlin/dev/femitems/plugin/live/LiveServerFakePlayerIT.kt` using test-scoped MCProtocolLib `26.3-3`), run via `./mvnw -B verify -P live-test` (or `.\mvnw.cmd -B verify -P live-test`).
  - Interactive local dev server launchers (`scripts/run-server.sh` & `scripts/run-server.ps1`).
  - GitHub Actions CI/CD (`.github/workflows/build.yml`) builds and uploads the shaded JAR artifact on every push, runs unit + live Paper/Folia fake-player tests, and publishes a GitHub Release whenever a new version is pushed to `main`.
- **Default config (2.11.1):** 16 mob rules (the five loot tables plus the FemMobs bosses/minions; the six `custom:` boss weapon drops are commented out because a missing custom item fails all of config.yml on a fresh install), the 8 recipes, and the Platinum recipes commented out. Originally it mapped the five loot tables from `forsaken_loot.zip` (`archer`, `grunt`, `champion`, `dreadbound`, `ravager`) and includes 8 custom crafting recipes (`soulpiercer_bow`, `emberblade`, `warlords_axe`, `aegis_shield`, `prospectors_pick`, `cloudstep_boots`, `wayfarers_saddle`, `heartwood_bow`).

## Source archive findings

`forsaken_loot.zip` contains `archer.json`, `champion.json`, `grunt.json`, `ravager.json`, and `dreadbound.jason`. The latter is a typo and is not a valid datapack JSON table filename. The tables are stored at `data/forsaken/loot_table/`, not attached to entity loot tables, so the vanilla game will not select them for mob deaths. No recipes are present. `pack.mcmeta` declares data pack format 121.

The default plugin config preserves the listed items, random count ranges, drop probabilities, and colorized Champion's Trophy display name. Random enchantment behavior specified by two datapack entries is not implemented yet.

## v1.3.0 review fixes

- A shaped recipe that defined an ingredient letter not present in `shape` passed validation, then threw inside the `GlobalRegionScheduler` task, after old recipes were removed and after the player was told "Config reloaded." The parser now rejects it, and reloads build every Bukkit recipe before changing live state.
- Quoted drop numbers (`chance: "0.05"`) used to silently fall back to defaults (a 5% drop became 100%). They are now parsed, and non-numeric values are config errors.
- Drop counts above an item's max stack size are split into valid stacks.
- Champion's Trophy is now gold **and bold** (`&6&l`), matching `champion.json`.
- `femitems.reload` is declared in `paper-plugin.yml` (default `op`). Reload failures now log the actual reason, not just the line number, and the doubled `[FemItems] [FemItems]` log prefix is gone.

## Live-test gotcha

On Folia 26.3, the console command `clear <player> <item> 0` replies `Found 1 matching item(s)` no matter how many items are actually there. Don't assert exact counts with it. The existing echo shard / gold ingot `clear` checks only prove the item is present, not how many there are. Assert stack sizes from the fake client's inventory packets instead (`FakePlayerClient.receivedItemsSnapshot()` / `awaitItemMatching`).

## Repo hygiene notes

- IDE files (`.idea/`, `*.iml`) are no longer tracked; `.gitignore` excludes them. Keep IntelliJ project config local. Pulling that change deletes previously tracked `.idea` files from a checkout, so IntelliJ may need a one-time Maven project re-import.

## Open gameplay follow-ups

1. Use `/femitems debug` on the production server to confirm entity types/custom names for arena mobs, then adjust `plugins/FemItems/config.yml` if needed.
2. Decide whether the enchanted book/sword drops need configurable random vanilla enchantments against Paper 26.3's API.
3. Adjust or expand the default crafting recipes under `recipes` in `plugins/FemItems/config.yml` as desired.

## Missing settings report (2026-11-25)

`MissingKeys.kt` (same behaviour as the `MissingKeys` class in FemCore, FemSkills, FemSkyblock and the other Fem plugins): `loadContent()` checks `config.yml` (own lists `mobs`, `recipes`) and `JukeboxFeature.reload()` checks `jukebox/config.yml`, against the jar's copies, on startup and every reload. Report only (`fill = false`): the code already falls back to the same defaults, and filling `resource-pack.mode` would break the 2.2.0 self-host detection. When you add a setting to either file, nothing else is needed; a NEW yml file should call `MissingKeys.check(...)` where it is loaded. Covered by `MissingKeysTest` and the live suite (fresh install reports nothing; a config without `resource-pack` and a jukebox config without `bossbar-color` are reported on reload). Released in 2.5.1.

## Recipe resync gotcha (Paper/Folia 26.3)

Every recipe-manager change ends in `RecipeManager.finalizeRecipeLoading()` → `PlayerList.reloadResources()`, which for **every online player** re-reads advancements from disk and re-sends all advancements, tags and the full recipe book. `Bukkit.addRecipe(recipe, false)` / `removeRecipe(key, false)` **ignore** the `resend` flag (they're default interface methods), so per-recipe calls cause one full resync per recipe (8 recipes → 16 resyncs per reload, visible client-side as repeated "Loaded 885 advancements"). Always use `Bukkit.addRecipes(...)` / `Bukkit.removeRecipes(...)` (one resync per call) and diff definitions so unchanged recipes aren't touched. The live test counts `ClientboundUpdateRecipesPacket`s to guard this.

## messages.yml (2.10.0)

Every chat message (FemItems and jukebox) is a MiniMessage text in `src/main/resources/messages.yml`, read by `MessageTexts.kt` (one `@Volatile` immutable map, overlaid on the jar's copy; unknown keys ignored, unreadable texts fall back to the default). Loaded in `loadContent()` (startup + `/femitems reload`) and in `JukeboxFeature.reloadAsync()` (`/femjukebox reload`), with a `MissingKeys` report. `Messages.kt` / `JukeboxMessages.kt` only pick a key and pass placeholders; values are always `Placeholder.unparsed`. When adding a message, add its key to messages.yml (with a comment listing its placeholders) instead of hard-coding text. Default texts render the same plain text as before, so live-test matches still hold. Covered by `MessagesTest` and live step 5d.

## Item perks (2.11.0)

`ItemPerkService.kt`: **refresh** (items carry `item_version` = `CustomItemService.fingerprint(definition)`, a hash of the data class `toString()`; `CustomItemService.refreshed()` rebuilds an out-of-date copy from the template, keeping amount, enchants, damage, repair cost, trim, an anvil rename (detected via `item_base_name`), and foreign PDC) on join, after reload (`refreshOnline`, per-player scheduler), held, pickup, inventory close; **soulbound** (`PlayerDeathEvent.itemsToKeep`); **on-hit** (light, capped options; full swing read on `PrePlayerAttackEntityEvent` because vanilla resets the charge before the damage event). `announce: true` broadcasts `items.rare-drop` from `onMobDeath`. Item names/lore accept MiniMessage when a line contains a tag. Fake client now auto-respawns (`deathCount()`). Live steps C13–C16.

## Missing custom items are skipped (2.12.0)

A drop or recipe naming a custom item that isn't loaded throws `MissingCustomItemException` (in `ConfigParser.customItemId`); `ConfigParser.parse` catches it per drop / per recipe and records it in `ParsedConfig.skipped` instead of failing all of config.yml (which, at startup, used to leave no recipes, drops or custom items at all). `readMobRules`/`readRecipes` called without a skip list still throw. Skips are logged and reported to the reloading sender (`items.skipped-missing`, hover lists them). Other config errors still fail the whole reload. Live step C17.
