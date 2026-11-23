# FemItems — Agentic AI Engineering Instructions

This repository is a **native Paper + Folia Minecraft 26.3 plugin** written in **Kotlin** targeting **Java 25**, built with **Maven** (`./mvnw` / `.\mvnw.cmd`).

All AI coding agents working in this repository **must** follow these rules and workflows on every change.

---

## 1. Tech Stack & Build Invariants

- **Language:** **Kotlin** (`src/main/kotlin/`, `src/test/kotlin/`). Write all new production and test code in Kotlin; do not add new `.java` source files unless strictly required for interop.
- **JVM Target:** **Java 25** (`maven.compiler.release = 25`, bytecode major version 69).
- **Minecraft / Server API:** **Paper 26.3** (`io.papermc.paper:paper-api`, `provided` scope) with full **Folia 26.3** compatibility (`folia-supported: true` in `src/main/resources/paper-plugin.yml`).
- **Build Tool:** **Maven Wrapper** (`./mvnw` on Linux/macOS, `.\mvnw.cmd` on Windows). Never assume a global `mvn` binary is installed.
- **Packaging:** `maven-shade-plugin` (`3.6.2+` for Java 25 ASM support) bundles `kotlin-stdlib` into `target/femitems-<version>.jar`.
  - Keep `paper-api` in `<scope>provided</scope>` — **never** shade `paper-api`.
  - Keep `<createDependencyReducedPom>false</createDependencyReducedPom>` so `dependency-reduced-pom.xml` is never left in the repo.
- **Single Version Source of Truth:** The plugin version lives **only** in `pom.xml` (`<version>...</version>`). `src/main/resources/paper-plugin.yml` uses `version: '${project.version}'` via Maven resource filtering. Never hardcode the version string in `paper-plugin.yml` or Kotlin code.

---

## 2. Paper & Folia Compatibility Rules (Mandatory)

This plugin **must** run identically on both **Paper** and **Folia** from a single JAR.

1. **Plugin Descriptor:**
   - Keep `folia-supported: true` and `api-version: '26.3'` in `src/main/resources/paper-plugin.yml`.
   - Do not add a legacy Bukkit `plugin.yml`.
2. **Schedulers — NEVER use legacy Bukkit schedulers:**
   - **FORBIDDEN:** `Bukkit.getScheduler()`, `BukkitRunnable`, `BukkitTask` scheduling methods (these throw `UnsupportedOperationException` on Folia).
   - **REQUIRED:** Use Paper's Folia-compatible schedulers:
     - `server.globalRegionScheduler` — global server state (recipes, gamerules, weather, console/global tasks).
     - `server.regionScheduler` — block/world mutations tied to a specific `Location` or chunk.
     - `entity.scheduler` — tasks touching a specific `Entity` or `Player` (follows the entity across regions).
     - `server.asyncScheduler` — off-thread I/O, HTTP, or heavy computation (`TimeUnit`-based).
3. **Thread Safety Across Regions:**
   - On Folia, events such as `EntityDeathEvent` execute concurrently on each entity's owning region thread.
   - All shared plugin state read from listeners/commands must be thread-safe:
     - Publish immutable config snapshots via `@Volatile` (e.g., `@Volatile private var mobRules: List<MobRule>`).
     - Use `AtomicBoolean` / `AtomicReference` / `ConcurrentHashMap` for mutable flags or registries.
     - Never mutate entity/player state from `GlobalRegionScheduler` or `AsyncScheduler`; hop to `entity.scheduler` first.
     - Runtime recipe mutations (`Bukkit.addRecipe` / `Bukkit.removeRecipe`) during commands/reloads must execute on `server.globalRegionScheduler` (direct calls are only allowed during `onEnable()` / `onDisable()`).
4. **Other Folia & Modern Paper Rules:**
   - Always use `entity.teleportAsync(...)` instead of synchronous `entity.teleport(...)`.
   - Avoid deprecated Bukkit text APIs (`org.bukkit.ChatColor`, `entity.getCustomName()`, `meta.setDisplayName(...)`). Use **Adventure `Component` API** (`Component.text(...)`, `entity.customName()`, `PlainTextComponentSerializer`, `item.editMeta { ... }`).
   - Detect Folia at runtime via `ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"))` when conditional behavior is genuinely required.

---

## 3. Repository Structure

| Path | Purpose |
| :--- | :--- |
| `pom.xml` | Maven build, Kotlin 2.4.x + Java 25 config, JUnit 5, Shade plugin, and canonical plugin `<version>` |
| `mvnw`, `mvnw.cmd`, `.mvn/` | Maven Wrapper scripts (use these for all builds) |
| `src/main/kotlin/dev/femitems/plugin/FemItemsPlugin.kt` | Paper/Folia `JavaPlugin` entrypoint, Brigadier commands, event listener, recipe registration |
| `src/main/kotlin/dev/femitems/plugin/ConfigModels.kt` | Immutable Kotlin data models for rules, recipes, and rolled drops |
| `src/main/kotlin/dev/femitems/plugin/Messages.kt` | Styled Adventure/MiniMessage chat output (gradient prefix, hover details, clickable commands). Keep each message's plain-text form stable — live tests and smoke scripts match on it |
| `src/main/kotlin/dev/femitems/plugin/ConfigParser.kt` | Pure YAML config parsing, validation, drop rolling, and line-number error reporting |
| `src/main/kotlin/dev/femitems/plugin/jukebox/` | Jukebox / NBS music feature (formerly FemJukebox): pure song readers (`NbsReader`, `GnbsReader`, `NbsToGnbsConverter`, `SongCatalog`, `SongClock`), `MusicService` (playback state mutated only on the global region thread; sounds/bossbars via each player's `EntityScheduler`), `JukeboxMenu` (block events + GUI; block data via `RegionScheduler`), `JukeboxCommands` (`/femjukebox`). PDC keys deliberately keep the `femjukebox:` namespace for compatibility |
| `src/main/kotlin/dev/femitems/plugin/items/` | Custom items (ItemsAdder-style): `ContentPackLoader` (pure parsing of `contents/<namespace>/*.yml` + texture checks), `ResourcePackBuilder` (pure, deterministic pack zip + SHA-1), `PackServer.kt` (`ResourcePackSettings` with modes external/self-host/off, `ExternalPackVerifier` that downloads the uploaded pack off-thread and compares SHA-1 before anything is sent, and the optional built-in HTTP host), `CustomItemService` (item stacks with `item_model`/equippable/durability/attributes, pack sending on join and on change, vanilla-recipe guards) |
| `src/main/resources/jukebox/` | Default `jukebox/config.yml` and `resource-pack-discs.yml`, saved to `plugins/FemItems/jukebox/` |
| `src/main/resources/paper-plugin.yml` | Native Paper/Folia plugin descriptor (`folia-supported: true`, filtered version) |
| `src/main/resources/config.yml` | Default mob drops and recipes configuration |
| `src/test/kotlin/dev/femitems/plugin/` | Fast JUnit 5 unit tests (`*Test.kt`) |
| `src/test/kotlin/dev/femitems/plugin/live/` | Live Paper 26.3 & Folia 26.3 end-to-end integration tests with real MCProtocolLib fake players (`LiveServerFakePlayerIT.kt`, `FakePlayerClient.kt`, `LiveServerHarness.kt`) |
| `scripts/smoke-test.sh`, `scripts/smoke-test.ps1` | Lightweight console-only headless Paper 26.3 + Folia 26.3 smoke test scripts |
| `scripts/run-server.sh`, `scripts/run-server.ps1` | Interactive local Paper 26.3 or Folia 26.3 dev server launcher |
| `.github/workflows/build.yml` | GitHub Actions CI/CD: builds JAR on every push, runs unit + live Paper/Folia fake-player tests, uploads artifact, and auto-releases new versions on `main` |

---

## 4. Mandatory Workflow for Every Change

Follow these steps **in order** whenever making a change to the plugin:

### Step 1: Bump the Version (`pom.xml`)
Every functional or packaging change **must** include a small version bump in `pom.xml`:
- **Patch bump (`x.y.Z` → `x.y.(Z+1)`):** Bug fixes, config tweaks, small improvements, refactors, test/CI updates tied to plugin changes.
- **Minor bump (`x.Y.z` → `x.(Y+1).0`):** New commands, new config features, new gameplay mechanics.
- **Major bump (`X.y.z` → `(X+1).0.0`):** Breaking config format or Minecraft API major upgrades.

Because `paper-plugin.yml` uses `${project.version}` and CI creates a GitHub Release automatically when a new version lands on `main`, bumping `pom.xml` automatically propagates everywhere.

### Step 2: Write / Update Unit Tests & Live Fake-Player Tests
- Keep parsing, matching, rolling, and business logic in pure Kotlin classes (like `ConfigParser`) separate from `JavaPlugin` runtime calls.
- **Paper 26.3 `RegistryAccess` caveat:** Outside a live Paper/Folia server, calling certain `Material` methods (such as `Material.isAir()` or `Material.maxStackSize`) triggers `IllegalStateException: No RegistryAccess implementation found`. Keep pure seams/injectable providers (see `ConfigParser.parse(..., maxStackSizeProvider)`) so JUnit 5 tests stay fast and isolated without needing a mocked server registry.
- Add or update unit tests under `src/test/kotlin/dev/femitems/plugin/` for every new behavior or bug fix.
- **Add or update live fake-player tests** in `src/test/kotlin/dev/femitems/plugin/live/LiveServerFakePlayerIT.kt` whenever you add or change commands, permissions, listeners, mob drops, recipes, or any in-game plugin behavior. Use `FakePlayerClient` (backed by `org.geysermc.mcprotocollib:protocol`, test-scoped only — never shade it) to connect real headless fake players and assert chat feedback, inventory packets, permissions, and server state.
- Follow the **Offline Fake-Player Testing Procedure** in [section 5](#5-offline-fake-player-testing-procedure-standard-for-every-feature): every feature is exercised through player actions (commands, right-clicks, placing/breaking, GUI clicks) by offline-mode fake players, never only through console shortcuts.

### Step 3: Build & Run Unit Tests
Run the fast Maven verification lifecycle using the Maven Wrapper:

- **Linux / macOS:**
  ```bash
  ./mvnw -B clean verify
  ```
- **Windows (PowerShell):**
  ```powershell
  .\mvnw.cmd -B clean verify
  ```

Verify that:
- All JUnit 5 unit tests pass with zero failures/errors.
- `target/femitems-<version>.jar` is created and shaded cleanly.

### Step 4: Run Live Paper 26.3 & Folia 26.3 Fake-Player Functional Tests (Mandatory on Every Change)
After **every** change to the plugin (code, commands, permissions, listeners, recipes, `config.yml`, `paper-plugin.yml`, or `pom.xml`), run the full end-to-end integration test suite against **both a live Paper 26.3 server and a live Folia 26.3 server with connected fake players**:

- **Linux / macOS:**
  ```bash
  ./mvnw -B verify -P live-test
  ```
- **Windows (PowerShell):**
  ```powershell
  .\mvnw.cmd -B verify -P live-test
  ```
  *(To iterate on a single platform before running both, pass `"-Dlive.platform=paper"` or `"-Dlive.platform=folia"`. Always run the full `all` suite before finishing.)*

What `LiveServerFakePlayerIT` verifies automatically on **both Paper 26.3 and Folia 26.3**:
1. Downloads and caches (with SHA-256 verification) the latest Paper 26.3 and Folia 26.3 server JARs from PaperMC Fill v3 API.
2. Boots each live server with the freshly built shaded `target/femitems-<version>.jar` and fresh player/ops state (`world/players` reset each run).
3. Connects real headless fake players (`FakeOp` and non-OP `FakeGuest`) over native Minecraft 26.3 TCP protocol via MCProtocolLib.
4. Verifies non-OP permission denial (`FakeGuest` cannot run `/femitems reload`).
5. Verifies player-initiated `/femitems reload` (exercising player region thread → `GlobalRegionScheduler` recipe updates on Folia) and live registration of both shaped and shapeless custom recipes in Minecraft's `RecipeManager`.
6. Verifies player-initiated `/femitems debug`, summoning & killing a custom-named mob in the live world, and receiving the expected custom drops (including custom Adventure display names and exact stack counts) directly in `FakeOp`'s Minecraft client inventory packets.
7. Verifies invalid config reload error-line reporting without crashing, and that a failed reload (including a shaped ingredient symbol missing from the shape) keeps previously registered recipes intact.
   Also verifies the bare `/femitems` info panel, reload counts, and that oversized drops (70 arrows) arrive split into valid stacks.
8. Verifies custom items: a content pack is turned into a resource pack; in `mode: external` nothing is sent before the zip is uploaded to a test pack host or while the upload is outdated, and after `/femitems pack` verifies it both fake players download it from that host and hash-check it; `mode: self-host` serves it from FemItems' own web server; `/femitems items` and `give` (with non-op refusal); crafting a custom result from a custom ingredient at a crafting table by slot clicks; wearing a custom helmet by right-click (and the other player seeing it); smelting custom ore in a furnace by slot clicks; and a tagged mob dropping a custom item.
9. Verifies the jukebox: `/femjukebox reload` of dropped-in `.gnbs` songs, non-admin restrictions, personal playback heard by **both** fake players (sound packets), pause/resume/stop (stop-sound packets), `give`/`disc` items, inserting a disc into a Custom Jukebox block, automatic disc ejection and pickup, the GUI opening instead of replacing an inserted disc, and — driven purely by player actions — placing a Custom Jukebox from its item, GUI clicks (volume with sound-volume check, song browser, pause/resume/stop, admin page, disc eject), `/femjukebox map`, and survival-mining a playing jukebox (music stops, jukebox item and disc drop).
10. Disconnects fake players cleanly, shuts down each server with exit code `0`, and fails on any `FemItems` exception, `UnsupportedOperationException`, or Folia region-thread violation.

### Step 5: Interactive In-Game Testing (When Needed)
For manual visual/gameplay verification with a real Minecraft 26.3 client:
- **Linux / macOS:** `./scripts/run-server.sh --platform paper` (or `--platform folia`)
- **Windows:** `powershell -ExecutionPolicy Bypass -File .\scripts\run-server.ps1 -Platform paper` (or `-Platform folia`)

### Step 6: Hygiene, Commit, and CI Verification
- Never commit `target/`, `.mc-test/`, `dependency-reduced-pom.xml`, or IDE files (all covered by `.gitignore`).
- Ensure shell scripts (`mvnw`, `scripts/*.sh`) preserve LF line endings (`.gitattributes` enforces this).
- On every `git push` to GitHub, `.github/workflows/build.yml` automatically:
  1. Compiles Kotlin/Java 25 and runs JUnit 5 unit tests.
  2. Builds and uploads `target/femitems-<version>.jar` as a downloadable GitHub Actions workflow artifact.
  3. Runs `LiveServerFakePlayerIT` against **both live Paper 26.3 and live Folia 26.3** servers with connected fake players.
  4. On pushes to `main` (or `v*` tags) with a new `pom.xml` version, automatically tags `v<version>` and publishes a GitHub Release with the built JAR attached.

---

## 5. Offline Fake-Player Testing Procedure (Standard for Every Feature)

Every player-facing feature is tested by **offline-mode fake players doing exactly what a human player would do**, on both Paper and Folia, in `LiveServerFakePlayerIT`. The test servers run with `online-mode=false`, so `FakePlayerClient` (MCProtocolLib) logs in under any name without a Mojang account. This is the standard way to prove a feature works; "it compiles" and unit tests are not enough.

### 5.1 Rules
1. **Act through the player, not the console.** Trigger the feature with the same packets a real client sends: commands typed by the player, right-clicks on blocks, placing blocks from real items, mining, and clicking GUI slots. The console (or an op player's vanilla commands) is only for **setup and cleanup**: `op`, `gamemode`, `effect`, `clear`, `give` of vanilla items, `tp`, `kill`, `setblock air`. Never use `setblock`/NBT to fake a state the feature itself is supposed to create (e.g. place a custom block from its item so the place listener runs).
2. **Assert on what the client receives**: plain-text chat, inventory items and their data components, sounds (name/volume/pitch), stop-sound packets, opened screen titles, plus server-side checks via `/execute if block …` when needed. Finally `stopAndAssertClean()` must find no plugin exception, `UnsupportedOperationException`, or Folia `off owning region` error.
3. **Cover both permission sides**: `FakeOp` (operator) for the feature, `FakeGuest` (non-op) to prove admin parts are refused or hidden and nothing is granted.
4. **Cover every interaction path you add or change**: each command and subcommand, each GUI button and page, place / use / break of custom blocks, and multi-player effects (e.g. a second player within range also receives sounds).
5. **Run the full suite on both platforms** (`.\mvnw.cmd -B clean verify -P live-test`). `-Dlive.platform=paper|folia` is only for iterating. When fixing a flaky step, find the cause and run the full suite at least twice before calling it fixed.
6. **Report gaps honestly.** Anything that cannot be exercised by a headless client (e.g. whether resource-pack audio is audible) must be named as untested in your final summary.

### 5.2 `FakePlayerClient` actions and captures

| Action | Method |
| :--- | :--- |
| Run a command as the player | `sendCommand("femjukebox play livetest")` |
| Right-click a block's top face (use / place held block on it) | `useItemOnBlock(x, y, z)` |
| Mine a block | `breakBlock(x, y, z, hold)` (survival: give Haste or hold long enough; creative breaks instantly) |
| Click a slot in a server-opened GUI | `clickContainerSlot(screen, slot)` with `screen` from `awaitScreenTitleContaining(...)` |
| Close a GUI (Esc) | `closeContainer(screen)` |
| Right-click with the held item in the air (equip armour, eat, ...) | `useHeldItem()` |
| Accept a server resource pack (download, verify SHA-1, report status) | automatic; inspect with `awaitPackOffer(...)` |

| Capture | Count / await |
| :--- | :--- |
| System chat (plain text) | `chatCount()`, `awaitChatContaining(...)`, `assertNoChatContaining(...)` |
| Inventory items (with components) | `receivedItemCount()`, `awaitItemMatching(...)` |
| Sounds played / stopped | `soundCount()`, `awaitSoundContaining(name) { it.volume ... }`, `stoppedSoundCount()`, `awaitStoppedSoundContaining(...)` |
| Opened screens | `openedScreenCount()`, `awaitScreenTitleContaining(...)` |
| Individual slots (container id, slot index, item) incl. full resyncs | `slotUpdateCount()`, `awaitSlotUpdate(...)` |
| Equipment seen on other entities | `seenEquipmentCount()`, `awaitSeenEquipmentContaining(...)` |
| Resource pack offers | `offeredPackCount()`, `awaitPackOffer(...)` |
| Recipe resyncs | `recipeResyncCount()` |

Always take the count **before** triggering an action and pass it as `sinceIndex`, so earlier events cannot satisfy the assertion. To add a new action or capture, extend `FakePlayerClient` with the matching MCProtocolLib packet (test scope only; never shade MCProtocolLib).

### 5.3 Known pitfalls (all hit in practice)
- **Flat-world slimes**: they kill fake players (a dead player gets `chat.disabled.options` and picks nothing up) and **block placement** when standing in the target space; killing big slimes only splits them. Minecraft 26.x ignores the old `spawn-monsters` server property, so `LiveServerHarness` sets the `spawn_monsters` game rule to `false` at boot and deletes the world's saved entities before every run. Both players also get infinite Resistance on join.
- **Any entity blocks block placement, including the other fake player.** Players spawn at random spots near world spawn, so pin them with `tp` before placing blocks, and probe the target space with `/execute if entity @e[x=..,y=..,z=..,dx=0,dy=0,dz=0]` (see `assertNoEntityIn`) so a failure names its cause.
- **The world persists between runs** (only player data and entities are reset): clear leftover blocks (`setblock … air`) at the start of a step, and don't assert on block states the world changes by itself (grass turns to dirt under a block).
- **Folia has no `/data` command**, and translatable feedback is captured as its key only (e.g. `commands.execute.conditional.pass`). Verify through gameplay or `/execute if block`.
- **Item physics**: dropped items bounce and roll. Teleport them to the player (`tp @e[type=minecraft:item,distance=..8] @s`) instead of relying on where they land.
- **Reach**: keep targets within ~4 blocks of the player's eyes; `tp` the player to a known spot first.
- **Close GUIs** (`closeContainer`) before moving on, as a real player would.
- **Prove preconditions, then diagnose failures**: before a player action, assert what it depends on (e.g. `execute if items entity @s weapon.mainhand minecraft:jukebox` before placing), and when an outcome check fails, probe the state (held item, entities in the space, current block) and put it in the assertion message. A bare "expected X" for a flaky step is not acceptable.
- **Don't race the feature's timing**: e.g. wait until a note has actually sounded before asserting that Stop sends stop-sound packets; use the `await*` helpers instead of fixed sleeps wherever possible.
