package dev.femitems.plugin

import org.bukkit.Material
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
            Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.BOW, Material.IRON_AXE -> 1
            else -> 64
        }
    }

    @Test
    fun `bundled default config parses cleanly`() {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("config.yml")) {
            "Bundled config.yml should be present on test classpath"
        }
        val yaml = InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
            YamlConfiguration.loadConfiguration(reader)
        }

        val parsed = ConfigParser.parse(yaml, testMaxStackSize)

        assertEquals(5, parsed.mobRules.size)
        assertTrue(parsed.recipes.isEmpty())

        val archer = parsed.mobRules.first { it.id == "archer" }
        assertEquals(EntityType.SKELETON, archer.type)
        assertEquals("Archer", archer.nameContains)
        assertEquals(4, archer.drops.size)

        val champion = parsed.mobRules.first { it.id == "champion" }
        assertNull(champion.type)
        assertEquals("Champion", champion.nameContains)
        val trophy = champion.drops.first { it.material == Material.ECHO_SHARD }
        assertEquals("Champion's Trophy", trophy.name)
    }

    @Test
    fun `shaped and shapeless recipes parse properly`() {
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
                      name: "Arena Blade"
                  gem_mix:
                    type: shapeless
                    ingredients: [DIAMOND, EMERALD]
                    result:
                      material: ECHO_SHARD
                      amount: 2
                """.trimIndent(),
            )
        }

        val parsed = ConfigParser.parse(yaml, testMaxStackSize)

        assertEquals(2, parsed.recipes.size)

        val shaped = parsed.recipes[0] as ShapedRecipeDefinition
        assertEquals("arena_blade", shaped.id)
        assertEquals(listOf(" I ", " I ", " S "), shaped.shape)
        assertEquals(mapOf('I' to Material.IRON_INGOT, 'S' to Material.STICK), shaped.ingredients)
        assertEquals(Material.IRON_SWORD, shaped.result.material)
        assertEquals(1, shaped.result.amount)
        assertEquals("Arena Blade", shaped.result.name)

        val shapeless = parsed.recipes[1] as ShapelessRecipeDefinition
        assertEquals("gem_mix", shapeless.id)
        assertEquals(listOf(Material.DIAMOND, Material.EMERALD), shapeless.ingredients)
        assertEquals(Material.ECHO_SHARD, shapeless.result.material)
        assertEquals(2, shapeless.result.amount)
        assertNull(shapeless.result.name)
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

        val ex = assertThrows<IllegalArgumentException> { ConfigParser.parse(yaml, testMaxStackSize) }
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
        assertThrows<IllegalArgumentException> { ConfigParser.parse(badChance, testMaxStackSize) }

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
        assertThrows<IllegalArgumentException> { ConfigParser.parse(unevenShape, testMaxStackSize) }

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
        assertThrows<IllegalArgumentException> { ConfigParser.parse(missingIngredientSymbol, testMaxStackSize) }
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
                    DropRule(Material.GOLD_INGOT, min = 3, max = 5, chance = 1.0, name = "Arena Gold"),
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
        assertEquals("Arena Gold", drop.name)
    }

    @Test
    fun `semantic error line detection locates bad material and section fields`() {
        val contents = """
            mobs:
              archer:
                entity: SKELETON
                name-contains: "Archer"
                drops:
                  - { material: NOT_A_REAL_ITEM, min: 1, max: 2, chance: 0.5 }
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
        assertNotNull(ConfigParser.conciseError(IllegalArgumentException("  spaced \n error  ")))
        assertEquals("spaced error", ConfigParser.conciseError(IllegalArgumentException("  spaced \n error  ")))
    }
}
