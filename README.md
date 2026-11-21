# FemItems

A native Paper plugin for Minecraft 26.3 that adds configurable mob drops and shaped or shapeless crafting recipes. It targets Java 25 and can be reloaded with `/femitems reload`. Native Paper plugins are experimental in Paper's current documentation.

## Build and install

Build with Maven and Java 25:

```text
mvn clean package
```

Copy `target/femitems-1.0.0.jar` into the `plugins` directory of a Paper 26.3 server and start it. On first startup, edit `plugins/FemItems/config.yml`. Use `/femitems reload` (or `/femi reload`) after editing. The command requires `femitems.reload` (operators by default).

The project compiles against the Paper 26.3 API and uses Paper's native plugin descriptor and command lifecycle API. Paper documents native Paper plugins as loading through Paper's plugin loader. PlugManX compatibility for this native plugin format is unverified and should not be assumed; use the in-game reload command for configuration changes. A server restart is the recommended way to apply plugin code updates.

## Mob drop configuration

Each `mobs` entry has an `entity` (Bukkit entity type), optional `name-contains` (case-insensitive substring of the mob's custom name), and a list of `drops`. Set `name-contains: ""` to match all mobs of that entity type. Drops are added to vanilla drops. Each drop supports `material`, `min`, `max`, `chance` (0.0 through 1.0), and optional `name`.

The supplied archive's five loot tables have been converted into the default config. The assumed entity mappings are Skeleton/Archer, Zombie/Grunt, Pillager/Champion, Zombie/Dreadbound, and Ravager (any custom name). Change these entity and name matches to match the arena's actual mobs. The archive did not specify those mappings.

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
