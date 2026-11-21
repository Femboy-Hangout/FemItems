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
import kotlin.test.assertEquals
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
        assertEquals("&6Champion's Trophy", trophy.name)

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
        assertEquals(mapOf('I' to Material.IRON_INGOT, 'S' to Material.STICK), shaped.ingredients)
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
        assertEquals(listOf(Material.DIAMOND, Material.EMERALD), shapeless.ingredients)
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
    fun `entity ANY requires non-empty name-contains filter`() {
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
        assertTrue(ex.message.orEmpty().contains("requires a non-empty name-contains filter"))
    }

    @Test
    fun `rejects invalid drop chance and malformed shaped recipe`() {
        val badChance = YamlConfiguration().apply {
            loadFromString(
                """
                mobs:
                  bad:
                    entity: ZOMBIE
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
                drops = listOf(
                    DropRule(Material.GOLD_INGOT, min = 3, max = 5, chance = 1.0, name = "&6Arena Gold"),
                    DropRule(Material.DIAMOND, min = 1, max = 1, chance = 0.0),
                ),
            ),
            MobRule(
                id = "skeleton_only",
                type = EntityType.SKELETON,
                nameContains = "",
                drops = listOf(DropRule(Material.ARROW, min = 2, max = 2, chance = 1.0)),
            ),
        )

        val drops = ConfigParser.rollDrops(
            rules = rules,
            entityType = EntityType.ZOMBIE,
            customNamePlain = "§eFallen Champion",
            random = Random(42),
        )

        assertEquals(1, drops.size)
        val drop = drops.single()
        assertEquals(Material.GOLD_INGOT, drop.material)
        assertTrue(drop.amount in 3..5)
        assertEquals("&6Arena Gold", drop.name)
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
}
