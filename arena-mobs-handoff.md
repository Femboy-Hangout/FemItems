# Arena Mobs Handoff

Use this document to continue the arena mob and wave-design work in another project. It captures the current summon commands, identifying tags, intended difficulty, wave roster, and FemItems loot changes.

## Context and current decisions

- Arena center: `109 75 -2788`.
- The five special mobs are Grunt (Zombie), Archer (Skeleton), Ravager (Vindicator), Dreadbound (Husk), and Champion (Wither Skeleton).
- Every FemItems mob rule requires a unique scoreboard tag as well as its other configured match conditions. A name alone must never grant custom drops.
- Use these exact tags in both the FemItems config's `entity-tag` and the summon command's `Tags` list:
  - Grunt: `forsaken.grunt`
  - Archer: `forsaken.archer`
  - Ravager: `forsaken.ravager`
  - Dreadbound: `forsaken.dreadbound`
  - Champion: `forsaken.champion`
- The Champion's existing fight was considered well balanced. Keep its stats and gear unchanged; improve its loot instead.
- The previous high-buff set (netherite gear, Sharpness V, Protection IV, 80–180 HP on regular mobs) was too strong: a player in maxed netherite barely survived the first wave. The commands below are a middle-ground rebalance and have not yet been play-tested in the arena.
- Commands use the current Java item-component format for enchanted equipment. Substitute each listed `X` and `Z` with the wave coordinates; Y remains `75`.

## Rebalanced summon command templates

### Grunt

```mcfunction
/summon minecraft:zombie X 75 Z {Tags:["forsaken.grunt"],CustomName:{text:"Forsaken Grunt",color:"dark_gray"},CustomNameVisible:true,PersistenceRequired:true,Health:40.0f,attributes:[{id:"minecraft:max_health",base:40.0},{id:"minecraft:attack_damage",base:5.5},{id:"minecraft:movement_speed",base:0.25}],equipment:{mainhand:{id:"minecraft:iron_sword",count:1,components:{"minecraft:enchantments":{"minecraft:sharpness":2}}},head:{id:"minecraft:iron_helmet",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},chest:{id:"minecraft:iron_chestplate",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},legs:{id:"minecraft:iron_leggings",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},feet:{id:"minecraft:iron_boots",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}}},drop_chances:{mainhand:0.0f,head:0.0f,chest:0.0f,legs:0.0f,feet:0.0f}}
```

### Archer

```mcfunction
/summon minecraft:skeleton X 75 Z {Tags:["forsaken.archer"],CustomName:{text:"Forsaken Archer",color:"green"},CustomNameVisible:true,PersistenceRequired:true,Health:30.0f,attributes:[{id:"minecraft:max_health",base:30.0},{id:"minecraft:movement_speed",base:0.24}],equipment:{mainhand:{id:"minecraft:bow",count:1,components:{"minecraft:enchantments":{"minecraft:power":2,"minecraft:punch":1}}},head:{id:"minecraft:chainmail_helmet",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},chest:{id:"minecraft:chainmail_chestplate",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},legs:{id:"minecraft:chainmail_leggings",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},feet:{id:"minecraft:chainmail_boots",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}}},drop_chances:{mainhand:0.0f,head:0.0f,chest:0.0f,legs:0.0f,feet:0.0f}}
```

### Ravager

```mcfunction
/summon minecraft:vindicator X 75 Z {Tags:["forsaken.ravager"],CustomName:{text:"Forsaken Ravager",color:"dark_red"},CustomNameVisible:true,PersistenceRequired:true,Health:65.0f,attributes:[{id:"minecraft:max_health",base:65.0},{id:"minecraft:attack_damage",base:10.0},{id:"minecraft:movement_speed",base:0.26}],equipment:{mainhand:{id:"minecraft:diamond_axe",count:1,components:{"minecraft:enchantments":{"minecraft:sharpness":2}}},head:{id:"minecraft:iron_helmet",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},chest:{id:"minecraft:iron_chestplate",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},legs:{id:"minecraft:iron_leggings",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},feet:{id:"minecraft:iron_boots",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}}},drop_chances:{mainhand:0.0f,head:0.0f,chest:0.0f,legs:0.0f,feet:0.0f}}
```

### Dreadbound

```mcfunction
/summon minecraft:husk X 75 Z {Tags:["forsaken.dreadbound"],CustomName:{text:"The Dreadbound",color:"dark_purple"},CustomNameVisible:true,PersistenceRequired:true,Health:110.0f,attributes:[{id:"minecraft:max_health",base:110.0},{id:"minecraft:attack_damage",base:8.0},{id:"minecraft:movement_speed",base:0.27}],equipment:{mainhand:{id:"minecraft:diamond_sword",count:1,components:{"minecraft:enchantments":{"minecraft:sharpness":3}}},head:{id:"minecraft:diamond_helmet",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},chest:{id:"minecraft:diamond_chestplate",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},legs:{id:"minecraft:diamond_leggings",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}},feet:{id:"minecraft:diamond_boots",count:1,components:{"minecraft:enchantments":{"minecraft:protection":2}}}},active_effects:[{id:"minecraft:strength",amplifier:0,duration:-1}],drop_chances:{mainhand:0.0f,head:0.0f,chest:0.0f,legs:0.0f,feet:0.0f}}
```

### Champion (keep the existing stats)

```mcfunction
/summon minecraft:wither_skeleton 109 75 -2788 {Tags:["forsaken.champion"],CustomName:{text:"The Forsaken Champion",color:"gold",bold:true},CustomNameVisible:true,DeathLootTable:"forsaken:entities/champion",PersistenceRequired:true,Health:250.0f,attributes:[{id:"minecraft:max_health",base:250.0},{id:"minecraft:attack_damage",base:12.0},{id:"minecraft:movement_speed",base:0.28},{id:"minecraft:knockback_resistance",base:0.5}],equipment:{mainhand:{id:"minecraft:diamond_sword",count:1},head:{id:"minecraft:diamond_helmet",count:1},chest:{id:"minecraft:diamond_chestplate",count:1},legs:{id:"minecraft:diamond_leggings",count:1},feet:{id:"minecraft:diamond_boots",count:1}},active_effects:[{id:"minecraft:resistance",amplifier:0,duration:-1}],drop_chances:{mainhand:0.0f}}
```

## Suggested waves

Coordinates below are `(X, Z)`; use Y=`75`. Spawn the next wave after the previous one is cleared.

1. **Opening patrol:** 2 Grunts `(106,-2788)`, `(112,-2788)`; 1 Archer `(109,-2792)`.
2. **Crossfire:** 2 Grunts `(105,-2788)`, `(113,-2788)`; 2 Archers `(106,-2791)`, `(112,-2785)`; 1 Ravager `(109,-2788)`.
3. **Heavy fighters:** 2 Dreadbound `(106,-2791)`, `(112,-2785)`; 2 Ravagers `(106,-2785)`, `(112,-2791)`; 2 Archers `(109,-2792)`, `(109,-2784)`.
4. **Final assault:** 3 Dreadbound `(105,-2788)`, `(113,-2788)`, `(109,-2792)`; 2 Ravagers `(106,-2791)`, `(112,-2785)`; 3 Archers `(109,-2784)`, `(106,-2785)`, `(112,-2791)`.
5. **Champion:** Champion alone at `(109,75,-2788)`.

## FemItems loot configuration updates

`src/main/resources/config.yml` is version 2.0.4's default. The server config must also have all five `entity-tag` values listed above; existing server configs are not automatically overwritten. The Archer's random enchanted-book drop was removed as too powerful.

Current Champion drops:

```yaml
drops:
  - { material: GOLD_INGOT, min: 5, max: 8, chance: 1.0 }
  - { material: DIAMOND, min: 2, max: 4, chance: 0.75 }
  - { material: EMERALD, min: 4, max: 8, chance: 0.65 }
  - { material: ENCHANTED_BOOK, min: 1, max: 1, chance: 0.20, random-enchantment: true }
  - { material: DIAMOND_SWORD, min: 1, max: 1, chance: 0.15 }
  - { material: NETHERITE_SCRAP, min: 1, max: 1, chance: 0.15 }
  - { material: ECHO_SHARD, min: 1, max: 1, chance: 0.10, name: "&6&lChampion's Trophy" }
```

The bundled config also has moderately improved Grunt, Archer, Ravager, and Dreadbound drops. Keep the Champion's loot rewarding, but note that random enchant books can roll any registered enchantment and may need a restricted-enchantment option if playtesting shows them too strong.

## Next steps in the receiving project

1. Confirm its Minecraft version accepts the summon-command NBT and item-component syntax; test one mob of each type before spawning waves.
2. Copy the five `entity-tag` fields into the server's FemItems config and add matching tags to every summon command.
3. Playtest with the actual maxed diamond/netherite gear and custom enchants. The proposed middle-ground wave balance has not been tested by players yet.
4. If adjusting difficulty, change regular mob health/equipment and wave counts first. Do not raise Champion stats unless its solo fight also proves too easy.
