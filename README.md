# FemItems

A native **Paper & Folia** plugin for **Minecraft 26.3** written in **Kotlin** targeting **Java 25**. It adds **fully custom items with their own textures** (ItemsAdder-style: weapons, tools, armour with worn textures, materials), configurable, reloadable custom mob drops, and shaped, shapeless or smelting recipes, and can be reloaded live with `/femitems reload` (or `/femi reload`). FemItems builds and hosts the resource pack for custom items itself. See [Custom items](#custom-items). It also includes the **jukebox** feature (formerly the standalone FemJukebox plugin): `.nbs`/`.gnbs` note-block music, Custom Jukebox blocks, and custom music discs. See [Jukebox and music](#jukebox-and-music).

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

## Commands

All commands require `femitems.reload` (operators by default). `/femi` is an alias for `/femitems`.

| Command | What it does |
| :--- | :--- |
| `/femitems` | Shows an info panel: plugin version, platform (Paper/Folia), loaded mob rules & recipes, debug state, and clickable command shortcuts. |
| `/femitems reload` | Reloads `config.yml`. On success it reports the loaded counts; on failure it reports the line number, and hovering the message shows the exact error. A failed reload leaves the previous config fully active. |
| `/femitems debug` | Toggles mob-death diagnostics in the console. |
| `/femitems items` | Lists all custom item IDs. |
| `/femitems give <player> <namespace:item> [amount]` | Gives a custom item. Requires `femitems.give` (operators by default). |

The project compiles against Paper API 26.3, declares `folia-supported: true` in `paper-plugin.yml`, bundles `kotlin-stdlib` via `maven-shade-plugin`, and registers commands through Paper's Brigadier lifecycle API. Use `/femitems reload` for configuration updates and a server restart for plugin JAR updates.

## Automated CI/CD builds

Every push to GitHub runs [`.github/workflows/build.yml`](.github/workflows/build.yml):
1. Compiles Kotlin (Java 25 bytecode) and executes the JUnit 5 unit test suite.
2. Packages and uploads `target/femitems-<version>.jar` as a downloadable GitHub Actions workflow artifact.
3. Boots live **Paper 26.3** and **Folia 26.3** servers with headless MCProtocolLib fake players (`LiveServerFakePlayerIT`) to test permissions, player-initiated `/femitems reload`, custom shaped/shapeless recipes, `/femitems debug`, live custom-named mob kills & drop pickups, invalid-config reload resilience, and the jukebox (song reload, playback heard by nearby players, pause/resume/stop, disc insertion/ejection, and the GUI).
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

## Custom items

FemItems can add fully custom items with their own textures, like ItemsAdder or Nexo. Each custom item is a vanilla base item (for its behaviour: a sword still attacks, a helmet still protects, paper does nothing) with its own model and texture, delivered to players through a resource pack that FemItems generates, hosts and sends automatically.

### Content packs

Each set of items is a folder in `plugins/FemItems/contents/<namespace>/`. The folder name is the namespace, so items get IDs like `blossom_studios:platinum_sword`. Any `.yml` file in it is read; textures go in its `textures/` folder.

```
plugins/FemItems/contents/blossom_studios/
  items.yml
  textures/item/platinum_sword.png        (16x16 item icons; any size works)
  textures/armor/platinum_layer_1.png     (64x32 worn armour: helmet, chestplate, boots)
  textures/armor/platinum_layer_2.png     (64x32 worn armour: leggings)
```

```yaml
equipment:                       # worn-armour textures, referenced by armour items
  platinum:
    layer-1: armor/platinum_layer_1
    layer-2: armor/platinum_layer_2

items:
  platinum_ingot:
    name: "Platinum Ingot"       # & colour codes allowed
    lore: ["&7A rare metal"]     # optional
    material: PAPER              # base item: decides behaviour and default stats
    texture: item/platinum_ingot # textures/item/platinum_ingot.png

  platinum_sword:
    name: "&fPlatinum Sword"
    material: DIAMOND_SWORD
    texture: item/platinum_sword
    max-durability: 1800         # optional: custom durability
    enchants: { sharpness: 2 }   # optional
    # model: handheld            # optional: handheld (tools/weapons, the default for them) or generated

  platinum_helmet:
    name: "Platinum Helmet"
    material: DIAMOND_HELMET
    texture: item/platinum_helmet
    max-durability: 468
    equipment: platinum          # worn texture; the slot comes from the material (or set slot: head)
    attributes:                  # optional; replaces the base item's attributes (armour, damage...)
      armor: 3
      armor_toughness: 3
```

Other item options: `unbreakable: true`, `max-stack-size: 1-99` (not with durability), and `slot: head|chest|legs|feet` for wearables that are not armour. `attributes` takes any attribute (`attack_damage`, `attack_speed`, `movement_speed`, ...); amounts are added to the player's base value, and setting any attribute replaces all of the base item's default attributes.

### Using custom items in recipes and drops

Anywhere `config.yml` takes a material, a custom item works too:

```yaml
recipes:
  platinum_sword:
    type: shaped
    shape: ["I", "I", "S"]
    ingredients:
      I: blossom_studios:platinum_ingot   # custom ingredient
      S: STICK
    result:
      item: blossom_studios:platinum_sword  # custom result (instead of material:)

  raw_platinum_smelt:
    type: smelting                         # new: furnace / blast furnace / smoker / campfire
    ingredient: blossom_studios:raw_platinum
    machines: [furnace, blast_furnace]     # default: [furnace]
    experience: 0.7
    cook-time: 200                         # ticks (200 = 10 s)
    result:
      item: blossom_studios:platinum_ingot

mobs:
  champion:
    # ...
    drops:
      - { item: blossom_studios:raw_platinum, min: 1, max: 3, chance: 0.5 }
```

Custom items never work as their base item in vanilla recipes or furnaces (a platinum ingot is paper underneath, but cannot be crafted into a book).

### The resource pack

On `/femitems reload` (and at startup) FemItems builds all content packs into a resource pack, serves it from a small built-in web server, and sends it to every player when they join and whenever it changes. It is sent alongside any other server pack (such as the jukebox discs' pack), not instead of it. Unchanged content keeps the same pack, so players don't re-download it.

```yaml
resource-pack:          # in config.yml
  enabled: true
  port: 8163            # open/forward this TCP port like the game port
  public-url: ""        # blank = http://<address the player joined with>:8163/
  required: false       # true = kick players who decline the pack
  prompt: "&dFemItems custom item textures"
```

If players join through a proxy, a hosting panel, or a different address than the web server is reachable on, set `public-url` (for example `http://203.0.113.5:8163` or a domain).

### Converting ItemsAdder packs

Most marketplace packs ship an ItemsAdder version. `import-itemsadder.py` (kept outside this repository, with the converted Platinum Set and Weapons Pack as templates) converts an ItemsAdder `contents/<namespace>` folder into a FemItems content pack plus a `recipes.yml` to paste into `config.yml`. Placeable custom blocks and ore generation are not supported yet; such blocks are imported as plain items.

## Jukebox and music

The jukebox feature (formerly the standalone **FemJukebox** plugin) plays Note Block Studio music and resource-pack disc tracks. Its files live in `plugins/FemItems/jukebox/`:

| File / folder | Purpose |
| :--- | :--- |
| `config.yml` | Bossbar on/off and color, hearing `radius` (default 32 blocks), `default-volume`, and `tick-interval`. |
| `convert/` | Drop `.nbs` files here. They are converted to `.gnbs` in `gnbs/` (the originals are kept). |
| `gnbs/` | GMusic-format `.gnbs` songs (timed rows, repeats, reusable parts, note stoppers, custom instruments). |
| `gnbs-sounds.yml` | Maps custom-instrument sample paths (with or without `.ogg`) to resource-pack sound events. |
| `resource-pack-discs.yml` | Resource-pack disc tracks (`pack-<id>`), for example the bundled Lucy's Mixtape Pigstep, Ward, Stal, and Relic. Players need the resource pack to hear them. |

New or changed files in `convert/` and `gnbs/` are picked up automatically; `/femjukebox reload` also reloads `config.yml` and the mapping files.

**Upgrading from the standalone FemJukebox plugin:** remove `FemJukebox.jar`. On first start FemItems copies `plugins/FemJukebox/` into `plugins/FemItems/jukebox/`. Existing Custom Jukebox blocks/items, inserted discs, per-jukebox volumes, and mapped discs keep working (their data keys are unchanged). If both plugins are installed, FemItems leaves its jukebox disabled and logs an error.

| Command | Permission | What it does |
| :--- | :--- | :--- |
| `/femjukebox` | — | Shows the command list. |
| `/femjukebox gui` | — | Opens playback controls and the song list. |
| `/femjukebox songs` | — | Lists songs (admins also see resource-pack disc tracks). |
| `/femjukebox play <song>` | — | Plays a song to you and players within the radius. Disc-only tracks require `femjukebox.admin`. |
| `/femjukebox pause` / `resume` / `stop` / `shuffle` | — | Controls your playback; shuffle picks a random song when one ends. |
| `/femjukebox give [player] [amount]` | `femjukebox.admin` | Gives Custom Jukebox items. |
| `/femjukebox disc <song> [player]` | `femjukebox.admin` | Gives a disc mapped to a song. |
| `/femjukebox map <song>` | `femjukebox.admin` | Maps the item in your main hand to a song, keeping its look and other data. |
| `/femjukebox reload` | `femjukebox.admin` | Converts NBS imports and reloads songs and jukebox settings. |

`femjukebox.admin` defaults to operators. Song IDs with spaces must be quoted (`/femjukebox play "My Song"`); tab completion does this for you.

Place a Custom Jukebox item, then right-click it while holding a mapped disc (or a configured resource-pack disc) to insert it and start playback for everyone nearby. Right-clicking a jukebox that already holds a disc opens the GUI instead of replacing the disc. The disc pops out on top of the jukebox when the song ends, playback is stopped, or another song is chosen; the GUI's **Deposited discs** page ejects it into your inventory. The GUI's **Volume -/+** buttons change that jukebox's shared volume (0–200%), saved on the block. Breaking the jukebox stops playback and drops both the jukebox item and its disc. Ordinary jukeboxes keep vanilla behaviour. Resource-pack tracks cannot seek, so resuming one restarts it.

## Mob drop configuration

Each `mobs` entry has an `entity` (Bukkit entity type, or `ANY`), a required `entity-tag` scoreboard tag, optional `name-contains` (case-insensitive substring of the mob's custom name), and a list of `drops`. Every rule requires its unique tag, so renaming an ordinary mob cannot trigger custom drops. Add the same tag to each special mob with the summon command's `Tags` field. For example, a champion uses `entity: ANY`, `entity-tag: "forsaken.champion"`, and `name-contains: "Champion"`, while its summon NBT includes `Tags:["forsaken.champion"]`. Drops are added to vanilla drops. Each drop supports `material`, `min`, `max`, `chance` (0.0 through 1.0), and optional `name` (with `&` color codes). Numbers may be quoted (`chance: "0.05"`); non-numeric values are reported as config errors. Counts above an item's max stack size (for example 100 arrows) drop as several valid stacks.

The supplied archive's five loot tables have been converted into the default config. The assumed entity mappings are Skeleton/Archer and Zombie/Grunt; Champion, Dreadbound, and Ravager use `ANY` plus their custom-name substring because their base entity types were not specified. Change these matches to fit the arena's actual mobs. The archive did not specify those mappings.

If a drop does not fire, use `/femitems debug` to toggle mob-death diagnostics, kill one affected mob, then check the server console for its actual entity type, custom name, and matching rule IDs. Run `/femitems debug` again to turn diagnostics off. Check that the summon command includes the rule's configured scoreboard tag. Reload config with `/femitems reload`.

## Crafting recipe configuration

The datapack archive contains no crafting recipes. FemItems' default config now includes Soulpiercer Bow, Emberblade, Warlord's Axe, Aegis Shield, Prospector's Pick, Cloudstep Boots, Wayfarer's Saddle, and Heartwood Bow. You can add other recipes in this form:

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
      name: "Tempered Blade"
```

Each string in `shape` is one crafting-grid row, read left to right. Every character is one slot. Here, `I` is an iron ingot, `S` is a stick, and a space means the slot is empty:

```text
 I
 I
 S
```

The `ingredients` section tells the plugin which material each letter represents. Every letter in `shape` must be defined in `ingredients`, and every letter in `ingredients` must appear in `shape`. The `result` section tells it what item to craft. For a normal iron sword, the three rows are ingot, ingot, stick; the centered spaces make that pattern line up like a sword in the 3×3 crafting grid.

Recipe results can also have `lore` (a list of text lines), `enchants` (enchantment IDs mapped to levels), and `unbreakable: true`. Use Minecraft color codes with `&` in names and lore, such as `&5` for purple or `&l` for bold. Enchantment IDs use Minecraft names such as `power`, or fully namespaced IDs such as `minecraft:power`. Levels are applied as configured, including levels above the normal maximum. Enchanted book results store the configured enchantments as book enchantments.

The default config uses a shaped recipe for Soulpiercer Bow. It takes an existing bow, two echo shards, two sticks, and four string, then crafts a named bow with lore and Power II:

```yaml
recipes:
  soulpiercer_bow:
    type: shaped
    shape:
      - "ETS"
      - "TBT"
      - "STE"
    ingredients:
      E: ECHO_SHARD
      T: STRING
      S: STICK
      B: BOW
    result:
      material: BOW
      amount: 1
      name: "Soulpiercer Bow"
      lore:
        - "A bow attuned to lost souls."
      enchants:
        power: 2
```

For a shapeless recipe, use `type: shapeless` and make `ingredients` a list of material names. Repeated names represent separate crafting-grid slots; a recipe can use up to nine ingredients:

```yaml
  example_mix:
    type: shapeless
    ingredients: [DIAMOND, EMERALD]
    result:
      material: ECHO_SHARD
      amount: 1
```

Recipe IDs should be unique and use lowercase letters, digits, underscores, or hyphens. Ingredients and results currently use vanilla Bukkit materials; custom plugin items are not implemented.

FemItems automatically unlocks its configured recipes in each player's recipe book when they join. Players already online see new or reloaded recipes unlocked immediately.
