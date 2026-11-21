# Handoff context

## Goal

Build a Java 25 Maven native Paper plugin named FemItems for Minecraft 26.3 that makes custom mob drops and crafting recipes editable and reloadable. The original PlugManX request remains unresolved because compatibility with native Paper plugin loading has not been verified.

## Current implementation

- Maven project targets Java 25 and Paper API `26.3.build.255-stable`.
- Uses `paper-plugin.yml` (no Bukkit `plugin.yml`) and registers commands with Paper's lifecycle command API.
- `FemItemsPlugin` adds configured drops when a matching entity dies, while preserving vanilla drops.
- `/femitems reload` and `/femi reload` reload `config.yml` and recipes. Permission: `femitems.reload`.
- Shaped and shapeless recipes are supported, removed on plugin disable/reload, and namespaced under this plugin.
- Mob rules accept `entity: ANY` to ignore base entity type. `/femitems debug` toggles console output with actual death entity type, custom name, and matching mob rule IDs.
- Default `config.yml` maps the five archive tables to arena entities based on inferred entity types/custom names. Verify those assumptions against the server's actual mob types/names.
- `README.md` contains build, install, config, and recipe examples.

## Source archive findings

`forsaken_loot.zip` contains `archer.json`, `champion.json`, `grunt.json`, `ravager.json`, and `dreadbound.jason`. The latter is a typo and is not a valid datapack JSON table filename. The tables are stored at `data/forsaken/loot_table/`, not attached to entity loot tables, so the vanilla game will not select them for mob deaths. No recipes are present. `pack.mcmeta` declares data pack format 121.

The default plugin config preserves the listed items, random count ranges, drop probabilities, and Champion's Trophy display name. Random enchantment behavior specified by two datapack entries is not implemented yet; the README states this limitation.

## Next actions

1. Use `/femitems debug` on the server to confirm entity types/custom names for arena mobs, then adjust the server's generated config if needed. Editing bundled defaults does not overwrite an existing `plugins/FemItems/config.yml`.
2. Decide whether the enchanted book/sword drops need true random vanilla enchantments; if so, implement against the exact 26.3 API and add configurable enchantment options.
3. Build using Java 25/Maven and install on a 26.3 Paper test server. The current work was not built or tested.
4. If PlugManX hot reload is still required, investigate whether PlugManX can reload a native Paper plugin. Native Paper plugins use Paper's separate experimental plugin loader; do not assume standard Bukkit plugin-manager reload is supported.
5. Add desired recipes under `recipes` in the generated `plugins/FemItems/config.yml`.

## Important paths

- `pom.xml`
- `src/main/java/dev/femitems/plugin/FemItemsPlugin.java`
- `src/main/resources/paper-plugin.yml`
- `src/main/resources/config.yml`
- `README.md`
- `forsaken_loot.zip` (original source archive)
