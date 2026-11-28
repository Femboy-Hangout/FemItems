package dev.femitems.plugin

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigParserTest {

    private val testMaxStackSize: (Material) -> Int = { material ->
        when (material) {
            Material.IRON_SWORD,
            Material.DIAMOND_SWORD,
            Material.BOW,
            Material.IRON_AXE,
            Material.DIAMOND_AXE,
            Material.SHIELD,
            Material.DIAMOND_PICKAXE,
            Material.IRON_BOOTS,
            Material.SADDLE,
            Material.ENCHANTED_BOOK,
            -> 1

            else -> 64
        }
    }

    private val knownEnchants: Set<NamespacedKey> = setOf(
        NamespacedKey.minecraft("power"),
        NamespacedKey.minecraft("fire_aspect"),
        NamespacedKey.minecraft("sharpness"),
        NamespacedKey.minecraft("unbreaking"),
        NamespacedKey.minecraft("efficiency"),
        NamespacedKey.minecraft("feather_falling"),
    )

    private val testEnchantmentValidator: (NamespacedKey) -> Boolean = { it in knownEnchants }

    @Test
    fun `bundled default config parses cleanly`() {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("config.yml")) {
            "Bundled config.yml should be present on test classpath"
        }
        val yaml = InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
            YamlConfiguration.loadConfiguration(reader)
        }

        val parsed = ConfigParser.parse(yaml, testMaxStackSize, testEnchantmentValidator)

        assertEquals(5, parsed.mobRules.size)
        assertEquals(8, parsed.recipes.size)
        assertEquals(
            listOf(
                "soulpiercer_bow",
                "emberblade",
                "warlords_axe",
                "aegis_shield",
                "prospectors_pick",
                "cloudstep_boots",
                "wayfarers_saddle",
                "heartwood_bow",
            ),
            parsed.recipes.map(RecipeDefinition::id),
        )

        val archer = parsed.mobRules.first { it.id == "archer" }
        assertEquals(EntityType.SKELETON, archer.type)
        assertEquals("Archer", archer.nameContains)
        assertEquals(4, archer.drops.size)

        val champion = parsed.mobRules.first { it.id == "champion" }
        assertNull(champion.type)
        assertEquals("Champion", champion.nameContains)
        val trophy = champion.drops.first { it.material == Material.ECHO_SHARD }
        assertEquals("&6&lChampion's Trophy", trophy.name)

        val soulpiercer = parsed.recipes.first { it.id == "soulpiercer_bow" } as ShapedRecipeDefinition
        assertEquals(Material.BOW, soulpiercer.result.material)
        assertEquals("&5Soulpiercer Bow", soulpiercer.result.name)
        assertEquals(listOf("A bow attuned to lost souls."), soulpiercer.result.lore)
        assertFalse(soulpiercer.result.unbreakable)
        assertEquals(mapOf(NamespacedKey.minecraft("power") to 2), soulpiercer.result.enchants)

        val saddle = parsed.recipes.first { it.id == "wayfarers_saddle" } as ShapedRecipeDefinition
        assertEquals(Material.SADDLE, saddle.result.material)
        assertTrue(saddle.result.enchants.isEmpty())
    }

    @Test
    fun `shaped and shapeless recipes parse properly with metadata`() {
        val yaml = YamlConfiguration().apply {
            loadFromString(
                """
                mobs: {}
                recipes:
                  arena_blade:
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
                      name: "&6Arena Blade"
                      lore:
                        - "&7Forged in the arena."
                        - "&eHandles heavy blows."
                      unbreakable: true
                      enchants:
                        sharpness: 10
                        minecraft:unbreaking: 5
                  gem_mix:
                    type: shapeless
                    ingredients: [DIAMOND, EMERALD]
                    result:
                      material: ECHO_SHARD
                      amount: 2
                """.trimIndent(),
            )
        }

        val parsed = ConfigParser.parse(yaml, testMaxStackSize, testEnchantmentValidator)

        assertEquals(2, parsed.recipes.size)

        val shaped = parsed.recipes[0] as ShapedRecipeDefinition
        assertEquals("arena_blade", shaped.id)
        assertEquals(listOf(" I ", " I ", " S "), shaped.shape)
        assertEquals(mapOf('I' to Ingredient.Vanilla(Material.IRON_INGOT), 'S' to Ingredient.Vanilla(Material.STICK)), shaped.ingredients)
        assertEquals(Material.IRON_SWORD, shaped.result.material)
        assertEquals(1, shaped.result.amount)
        assertEquals("&6Arena Blade", shaped.result.name)
        assertEquals(listOf("&7Forged in the arena.", "&eHandles heavy blows."), shaped.result.lore)
        assertTrue(shaped.result.unbreakable)
        assertEquals(
            mapOf(
                NamespacedKey.minecraft("sharpness") to 10,
                NamespacedKey.minecraft("unbreaking") to 5,
            ),
            shaped.result.enchants,
        )

        val shapeless = parsed.recipes[1] as ShapelessRecipeDefinition
        assertEquals("gem_mix", shapeless.id)
        assertEquals(listOf(Ingredient.Vanilla(Material.DIAMOND), Ingredient.Vanilla(Material.EMERALD)), shapeless.ingredients)
        assertEquals(Material.ECHO_SHARD, shapeless.result.material)
        assertEquals(2, shapeless.result.amount)
        assertNull(shapeless.result.name)
        assertTrue(shapeless.result.lore.isEmpty())
        assertFalse(shapeless.result.unbreakable)
        assertTrue(shapeless.result.enchants.isEmpty())
    }

    @Test
    fun `rejects invalid enchantment levels, malformed keys, unknown enchants, and oversized shapeless recipes`() {
        val zeroLevel = YamlConfiguration().apply {
            loadFromString(
                """
                recipes:
                  bad_level:
                    type: shapeless
                    ingredients: [DIAMOND]
                    result:
                      material: DIAMOND_SWORD
                      enchants:
                        sharpness: 0
                """.trimIndent(),
            )
        }
        val zeroEx = assertThrows<IllegalArgumentException> {
            ConfigParser.parse(zeroLevel, testMaxStackSize, testEnchantmentValidator)
        }
        assertTrue(zeroEx.message.orEmpty().contains("Enchant level must be at least 1"))

        val invalidKey = YamlConfiguration().apply {
            loadFromString(
                """
                recipes:
                  bad_key:
                    type: shapeless
                    ingredients: [DIAMOND]
                    result:
                      material: DIAMOND_SWORD
                      enchants:
                        "bad key!": 1
                """.trimIndent(),
            )
        }
        val keyEx = assertThrows<IllegalArgumentException> {
            ConfigParser.parse(invalidKey, testMaxStackSize, testEnchantmentValidator)
        }
        assertTrue(keyEx.message.orEmpty().contains("Invalid enchantment key"))

        val unknownEnchant = YamlConfiguration().apply {
            loadFromString(
                """
                recipes:
                  unknown_enchant:
                    type: shapeless
                    ingredients: [DIAMOND]
                    result:
                      material: ENCHANTED_BOOK
                      enchants:
                        not_a_real_enchant: 2
                """.trimIndent(),
            )
        }
        val unknownEx = assertThrows<IllegalArgumentException> {
            ConfigParser.parse(unknownEnchant, testMaxStackSize, testEnchantmentValidator)
        }
        assertTrue(unknownEx.message.orEmpty().contains("Unknown enchantment"))

        val tooManyIngredients = YamlConfiguration().apply {
            loadFromString(
                """
                recipes:
                  too_many:
                    type: shapeless
                    ingredients: [STICK, STICK, STICK, STICK, STICK, STICK, STICK, STICK, STICK, STICK]
                    result:
                      material: DIAMOND
                """.trimIndent(),
            )
        }
        assertThrows<IllegalArgumentException> {
            ConfigParser.parse(tooManyIngredients, testMaxStackSize, testEnchantmentValidator)
        }
    }

    @Test
    fun `entity ANY requires an entity tag`() {
        val yaml = YamlConfiguration().apply {
            loadFromString(
                """
                mobs:
                  invalid_any:
                    entity: ANY
                    name-contains: "   "
                    drops:
                      - { material: DIAMOND, min: 1, max: 1, chance: 1.0 }
                """.trimIndent(),
            )
        }

        val ex = assertThrows<IllegalArgumentException> {
            ConfigParser.parse(yaml, testMaxStackSize, testEnchantmentValidator)
        }
        assertTrue(ex.message.orEmpty().contains("entity-tag is required"))
    }

    @Test
    fun `rejects invalid drop chance and malformed shaped recipe`() {
        val badChance = YamlConfiguration().apply {
            loadFromString(
                """
                mobs:
                  bad:
                    entity: ZOMBIE
                    entity-tag: test.bad
                    drops:
                      - { material: COAL, min: 1, max: 2, chance: 1.5 }
                """.trimIndent(),
            )
        }
        assertThrows<IllegalArgumentException> {
            ConfigParser.parse(badChance, testMaxStackSize, testEnchantmentValidator)
        }

        val unevenShape = YamlConfiguration().apply {
            loadFromString(
                """
                recipes:
                  broken:
                    type: shaped
                    shape:
                      - "II"
                      - "S"
                    ingredients:
                      I: IRON_INGOT
                      S: STICK
                    result:
                      material: IRON_SWORD
                """.trimIndent(),
            )
        }
        assertThrows<IllegalArgumentException> {
            ConfigParser.parse(unevenShape, testMaxStackSize, testEnchantmentValidator)
        }

        val missingIngredientSymbol = YamlConfiguration().apply {
            loadFromString(
                """
                recipes:
                  missing_symbol:
                    type: shaped
                    shape:
                      - "IX"
                    ingredients:
                      I: IRON_INGOT
                    result:
                      material: IRON_SWORD
                """.trimIndent(),
            )
        }
        assertThrows<IllegalArgumentException> {
            ConfigParser.parse(missingIngredientSymbol, testMaxStackSize, testEnchantmentValidator)
        }
    }

    @Test
    fun `name matching is case-insensitive and strips legacy color codes`() {
        assertTrue(ConfigParser.matchesName("§6§lArena Champion", "champion"))
        assertTrue(ConfigParser.matchesName("&cDreadbound Knight", "DREADBOUND"))
        assertTrue(ConfigParser.matchesName("Anything", ""))
        assertTrue(ConfigParser.matchesName(null, ""))
        assertFalse(ConfigParser.matchesName(null, "Champion"))
        assertFalse(ConfigParser.matchesName("Skeleton Grunt", "Archer"))
    }

    @Test
    fun `rollDrops evaluates matching rules deterministically`() {
        val rules = listOf(
            MobRule(
                id = "boss",
                type = null,
                nameContains = "Champion",
                entityTag = "forsaken.champion",
                drops = listOf(
                    DropRule(Material.GOLD_INGOT, min = 3, max = 5, chance = 1.0, name = "&6Arena Gold"),
                    DropRule(Material.DIAMOND, min = 1, max = 1, chance = 0.0),
                ),
            ),
            MobRule(
                id = "skeleton_only",
                type = EntityType.SKELETON,
                nameContains = "",
                entityTag = "test.skeleton",
                drops = listOf(DropRule(Material.ARROW, min = 2, max = 2, chance = 1.0)),
            ),
        )

        val drops = ConfigParser.rollDrops(
            rules = rules,
            entityType = EntityType.ZOMBIE,
            customNamePlain = "§eFallen Champion",
            random = Random(42),
            scoreboardTags = setOf("forsaken.champion"),
        )

        assertEquals(1, drops.size)
        val drop = drops.single()
        assertEquals(Material.GOLD_INGOT, drop.material)
        assertTrue(drop.amount in 3..5)
        assertEquals("&6Arena Gold", drop.name)
    }

    @Test
    fun `parses and rolls random enchanted book drops`() {
        val yaml = YamlConfiguration().apply {
            loadFromString(
                """
                mobs:
                  dreadbound:
                    entity: ANY
                    entity-tag: forsaken.dreadbound
                    name-contains: Dreadbound
                    drops:
                      - { material: ENCHANTED_BOOK, min: 1, max: 1, chance: 1.0, random-enchantment: true }
                """.trimIndent(),
            )
        }

        val rule = ConfigParser.readMobRules(yaml).single()
        assertTrue(rule.drops.single().randomEnchantment)
        val rolled = ConfigParser.rollDrops(
            listOf(rule), EntityType.HUSK, "The Dreadbound", Random(4), setOf("forsaken.dreadbound"),
        ).single()
        assertEquals(Material.ENCHANTED_BOOK, rolled.material)
        assertTrue(rolled.randomEnchantment)
    }

    @Test
    fun `drops can carry fixed enchantments`() {
        val known = setOf("sharpness", "unbreaking", "looting", "mending")
        val validator = { key: NamespacedKey -> key.namespace == "minecraft" && key.key in known }
        fun rules(drops: String) = ConfigParser.readMobRules(
            YamlConfiguration().apply {
                loadFromString("mobs:\n  champion:\n    entity: ZOMBIE\n    entity-tag: forsaken.champion\n    drops:\n$drops")
            },
            enchantmentValidator = validator,
        )
        val rule = rules(
            """      - { material: DIAMOND_SWORD, min: 1, max: 1, chance: 1.0, name: "&6Champion's Blade", enchants: { sharpness: 4, unbreaking: "3", "minecraft:looting": 2 } }
      - { material: ENCHANTED_BOOK, chance: 1.0, enchants: { mending: 1 } }
      - { material: BONE, chance: 1.0 }
""",
        ).single()
        val expected = mapOf(NamespacedKey.minecraft("sharpness") to 4, NamespacedKey.minecraft("unbreaking") to 3, NamespacedKey.minecraft("looting") to 2)
        assertEquals(expected, rule.drops[0].enchants)
        assertEquals(mapOf(NamespacedKey.minecraft("mending") to 1), rule.drops[1].enchants)
        assertEquals(emptyMap(), rule.drops[2].enchants)
        val rolled = ConfigParser.rollDrops(listOf(rule), EntityType.ZOMBIE, null, Random(1), setOf("forsaken.champion"))
        assertEquals(expected, rolled.first { it.material == Material.DIAMOND_SWORD }.enchants, "rolled drops keep their enchantments")

        fun error(drop: String) = assertFailsWith<IllegalArgumentException> { rules("      - $drop\n") }.message!!
        assertContains(error("{ material: DIAMOND_SWORD, enchants: { sharpnes: 4 } }"), "Unknown enchantment at mobs.champion.drops.enchants.sharpnes")
        assertContains(error("{ material: DIAMOND_SWORD, enchants: { sharpness: 0 } }"), "Enchant level must be at least 1")
        assertContains(error("{ material: DIAMOND_SWORD, enchants: { sharpness: lots } }"), "must be a whole number")
        assertContains(error("{ material: DIAMOND_SWORD, enchants: sharpness }"), "enchants must be a map like { sharpness: 4 }")
        assertContains(error("{ material: ENCHANTED_BOOK, random-enchantment: true, enchants: { mending: 1 } }"), "either enchants or random-enchantment")
    }

    @Test
    fun `custom mob tag prevents renamed ordinary mobs from matching`() {
        val yaml = YamlConfiguration().apply {
            loadFromString(
                """
                mobs:
                  champion:
                    entity: ANY
                    entity-tag: forsaken.champion
                    name-contains: Champion
                    drops:
                      - { material: DIAMOND, chance: 1.0 }
                """.trimIndent(),
            )
        }
        val rule = ConfigParser.readMobRules(yaml).single()
        assertTrue(ConfigParser.matchesRule(rule, EntityType.WITHER_SKELETON, "The Forsaken Champion", setOf("forsaken.champion")))
        assertFalse(ConfigParser.matchesRule(rule, EntityType.WITHER_SKELETON, "The Forsaken Champion", emptySet()))
        assertFalse(ConfigParser.matchesRule(rule, EntityType.WOLF, "Champion", emptySet()))
    }

    @Test
    fun `random enchantment requires an enchanted book`() {
        val yaml = YamlConfiguration().apply {
            loadFromString(
                """
                mobs:
                  dreadbound:
                    entity: ANY
                    entity-tag: forsaken.dreadbound
                    name-contains: Dreadbound
                    drops:
                      - { material: BOOK, random-enchantment: true }
                """.trimIndent(),
            )
        }
        val error = assertThrows<IllegalArgumentException> { ConfigParser.readMobRules(yaml) }
        assertTrue(error.message.orEmpty().contains("requires material ENCHANTED_BOOK"))
    }

    @Test
    fun `semantic error line detection locates bad material, section fields, and invalid enchants`() {
        val contents = """
            mobs:
              archer:
                entity: SKELETON
                name-contains: "Archer"
                drops:
                  - { material: NOT_A_REAL_ITEM, min: 1, max: 2, chance: 0.5 }
            recipes:
              soulpiercer_bow:
                type: shapeless
                ingredients: [BOW]
                result:
                  material: BOW
                  enchants:
                    power: 2
                    not_real: 1
        """.trimIndent()

        val line = ConfigParser.findSemanticErrorLine(
            "Unknown drop material in mobs.archer: NOT_A_REAL_ITEM",
            contents,
        )
        assertEquals(6, line)

        val fieldLine = ConfigParser.findSemanticErrorLine(
            "mobs.archer.entity is not a valid entity type (or ANY)",
            contents,
        )
        assertEquals(3, fieldLine)

        val enchantLine = ConfigParser.findSemanticErrorLine(
            "Unknown enchantment at recipes.soulpiercer_bow.result.enchants.not_real",
            contents,
        )
        assertEquals(15, enchantLine)

        assertNotNull(ConfigParser.conciseError(IllegalArgumentException("  spaced \n error  ")))
        assertEquals("spaced error", ConfigParser.conciseError(IllegalArgumentException("  spaced \n error  ")))
    }

    @Test
    fun `rejects shaped ingredient symbols that never appear in the shape`() {
        val unusedSymbol = YamlConfiguration().apply {
            loadFromString(
                """
                recipes:
                  unused_symbol:
                    type: shaped
                    shape:
                      - "I"
                    ingredients:
                      I: IRON_INGOT
                      X: DIAMOND
                    result:
                      material: IRON_SWORD
                """.trimIndent(),
            )
        }
        val error = assertThrows<IllegalArgumentException> {
            ConfigParser.parse(unusedSymbol, testMaxStackSize, testEnchantmentValidator)
        }
        assertTrue(error.message!!.contains("'X' is not used in shape"), error.message)
    }

    @Test
    fun `quoted numeric drop values are parsed instead of silently ignored`() {
        val quoted = YamlConfiguration().apply {
            loadFromString(
                """
                mobs:
                  quoted:
                    entity: ZOMBIE
                    entity-tag: test.quoted
                    drops:
                      - { material: COAL, min: "2", max: "4", chance: "0.05" }
                """.trimIndent(),
            )
        }
        val drop = ConfigParser.parse(quoted, testMaxStackSize, testEnchantmentValidator).mobRules.single().drops.single()
        assertEquals(2, drop.min)
        assertEquals(4, drop.max)
        assertEquals(0.05, drop.chance)

        val garbage = YamlConfiguration().apply {
            loadFromString(
                """
                mobs:
                  garbage:
                    entity: ZOMBIE
                    entity-tag: test.garbage
                    drops:
                      - { material: COAL, min: 1, max: 1, chance: "often" }
                """.trimIndent(),
            )
        }
        val error = assertThrows<IllegalArgumentException> {
            ConfigParser.parse(garbage, testMaxStackSize, testEnchantmentValidator)
        }
        assertTrue(error.message!!.contains("mobs.garbage.drops.chance must be a number"), error.message)
    }

    @Test
    fun `splitIntoStacks never exceeds the max stack size`() {
        assertEquals(listOf(64, 36), ConfigParser.splitIntoStacks(100, 64))
        assertEquals(listOf(1, 1, 1), ConfigParser.splitIntoStacks(3, 1))
        assertEquals(listOf(16), ConfigParser.splitIntoStacks(16, 16))
        assertEquals(emptyList(), ConfigParser.splitIntoStacks(0, 64))
        assertEquals(listOf(1, 1), ConfigParser.splitIntoStacks(2, 0))
    }
}
