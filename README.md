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

Each `mobs` entry has an `entity` (Bukkit entity type, or `ANY`), optional `name-contains` (case-insensitive substring of the mob's custom name), and a list of `drops`. `ANY` skips only the base entity-type check; the name filter still applies, and it must be non-empty for `ANY`. For example, `entity: ANY` with `name-contains: "Champion"` matches any entity type whose custom name contains “Champion.” Drops are added to vanilla drops. Each drop supports `material`, `min`, `max`, `chance` (0.0 through 1.0), and optional `name`.

The supplied archive's five loot tables have been converted into the default config. The assumed entity mappings are Skeleton/Archer and Zombie/Grunt; Champion, Dreadbound, and Ravager use `ANY` plus their custom-name substring because their base entity types were not specified. Change these matches to fit the arena's actual mobs. The archive did not specify those mappings.

If a drop does not fire, use `/femitems debug` to toggle mob-death diagnostics, kill one affected mob, then check the server console for its actual entity type, custom name, and matching rule IDs. Run `/femitems debug` again to turn diagnostics off. Set `entity: ANY` when the reported type differs from the configured one, and adjust `name-contains` to match the reported custom name. Reload config with `/femitems reload`.

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

The `ingredients` section tells the plugin which material each letter represents. The `result` section tells it what item to craft. For a normal iron sword, the three rows are ingot, ingot, stick; the centered spaces make that pattern line up like a sword in the 3×3 crafting grid.

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
