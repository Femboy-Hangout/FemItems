package dev.femitems.plugin.items

import dev.femitems.plugin.ConfigParser
import dev.femitems.plugin.CookingMachine
import dev.femitems.plugin.CookingRecipeDefinition
import dev.femitems.plugin.Ingredient
import dev.femitems.plugin.Messages
import dev.femitems.plugin.ShapedRecipeDefinition
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomItemsTest {
    private val knownAttributes = setOf("armor", "armor_toughness", "attack_damage", "attack_speed")
    private val knownEnchants = setOf("sharpness", "unbreaking")

    private fun loader(contents: Path) = ContentPackLoader(
        contents,
        enchantmentExists = { it.namespace == "minecraft" && it.key in knownEnchants },
        attributeExists = { it.namespace == "minecraft" && it.key in knownAttributes },
    )

    /** Writes a content pack folder with the given items.yml and (fake) texture files. */
    private fun pack(contents: Path, namespace: String, yaml: String, vararg textures: String): Path {
        val folder = contents.resolve(namespace)
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("items.yml"), yaml.trimIndent() + "\n")
        for (texture in textures) {
            val file = folder.resolve("textures").resolve("$texture.png")
            Files.createDirectories(file.parent)
            Files.write(file, "png:$texture".toByteArray())
        }
        return folder
    }

    private val platinumYaml = """
        equipment:
          platinum:
            layer-1: armor/platinum_layer_1
            layer-2: armor/platinum_layer_2
        items:
          platinum_ingot:
            name: "Platinum Ingot"
            material: PAPER
            texture: item/platinum_ingot
          platinum_sword:
            name: "&fPlatinum Sword"
            lore: ["&7Shiny"]
            material: DIAMOND_SWORD
            texture: item/platinum_sword.png
            max-durability: 1800
            enchants: { sharpness: 2 }
          platinum_helmet:
            material: DIAMOND_HELMET
            texture: item/platinum_helmet
            max-durability: 468
            equipment: platinum
            attributes:
              armor: 3
              armor_toughness: "3.5"
    """

    private fun platinum(contents: Path) = pack(
        contents, "blossom_studios", platinumYaml,
        "item/platinum_ingot", "item/platinum_sword", "item/platinum_helmet", "armor/platinum_layer_1", "armor/platinum_layer_2",
    )

    // ------------------------------------------------------------------ content packs

    @Test
    fun `content packs load items, models, durability, attributes, and armour equipment`(@TempDir contents: Path) {
        platinum(contents)
        val packs = loader(contents).load()
        assertEquals(1, packs.size)
        val pack = packs.single()
        assertEquals("blossom_studios", pack.namespace)
        val items = pack.items.associateBy(CustomItemDefinition::key)

        val ingot = items.getValue("platinum_ingot")
        assertEquals("blossom_studios:platinum_ingot", ingot.id)
        assertEquals(Material.PAPER, ingot.material)
        assertEquals(ContentPackLoader.GENERATED, ingot.modelParent)
        assertNull(ingot.armorSlot)

        val sword = items.getValue("platinum_sword")
        assertEquals(ContentPackLoader.HANDHELD, sword.modelParent, "weapons are held like tools")
        assertEquals("item/platinum_sword", sword.texture, ".png is stripped")
        assertEquals(1800, sword.maxDurability)
        assertEquals(mapOf(NamespacedKey.minecraft("sharpness") to 2), sword.enchants)
        assertEquals(listOf("&7Shiny"), sword.lore)

        val helmet = items.getValue("platinum_helmet")
        assertEquals(ArmorSlot.HEAD, helmet.armorSlot, "slot comes from the armour material")
        assertEquals("blossom_studios:platinum", helmet.equipment)
        assertEquals(
            listOf(AttributeSpec(NamespacedKey.minecraft("armor"), 3.0), AttributeSpec(NamespacedKey.minecraft("armor_toughness"), 3.5)),
            helmet.attributes,
        )
        assertEquals(EquipmentDefinition("blossom_studios", "platinum", "armor/platinum_layer_1", "armor/platinum_layer_2"), pack.equipment.single())
    }

    @Test
    fun `no contents folder means no custom items`(@TempDir root: Path) {
        assertEquals(emptyList(), loader(root.resolve("contents")).load())
    }

    @Test
    fun `content pack errors name the file and the problem`(@TempDir root: Path) {
        fun error(namespace: String, yaml: String, vararg textures: String): String {
            val contents = root.resolve("c-${System.nanoTime()}")
            pack(contents, namespace, yaml, *textures)
            return assertFailsWith<ContentPackException> { loader(contents).load() }.message!!
        }
        assertContains(error("ns", "items:\n  a:\n    material: PAPER\n    texture: item/missing\n"), "textures/item/missing.png not found")
        assertContains(error("ns", "items:\n  a:\n    material: NOT_REAL\n    texture: t\n", "t"), "unknown item material 'NOT_REAL'")
        assertContains(error("ns", "items:\n  a:\n    material: PAPER\n", "t"), "texture is required")
        assertContains(error("ns", "items:\n  a:\n    material: PAPER\n    texture: t\n    attributes: { speediness: 1 }\n", "t"), "unknown attribute")
        assertContains(error("ns", "items:\n  a:\n    material: PAPER\n    texture: t\n    enchants: { nope: 1 }\n", "t"), "unknown enchantment")
        assertContains(error("ns", "items:\n  a:\n    material: DIAMOND_HELMET\n    texture: t\n    equipment: ghost\n", "t"), "equipment 'ns:ghost' is not defined")
        assertContains(error("ns", "items:\n  a:\n    material: PAPER\n    texture: t\n    equipment: x\n", "t"), "equipment needs an armour material")
        assertContains(error("ns", "items:\n  a:\n    material: PAPER\n    texture: t\n    max-durability: 10\n    max-stack-size: 16\n", "t"), "cannot stack")
        assertContains(error("ns", "items:\n  a:\n    material: PAPER\n    texture: ../../secret\n"), "invalid texture path")
        assertContains(error("Bad Name", "items: {}\n"), "lowercase namespace")
        assertContains(error("minecraft", "items: {}\n"), "not 'minecraft'")
        val brokenYaml = root.resolve("broken-yaml")
        pack(brokenYaml, "ns", "items:\n  a: [\n")
        assertEquals("items.yml", assertFailsWith<ContentPackException> { loader(brokenYaml).load() }.file.name)
    }

    @Test
    fun `texture paths are normalised and cannot escape the pack`() {
        assertEquals("item/sword", ContentPackLoader.texturePath("item\\Sword.png"))
        assertEquals("a/b", ContentPackLoader.texturePath("/a/b/"))
        assertFailsWith<IllegalArgumentException> { ContentPackLoader.texturePath("item/../../x") }
        assertFailsWith<IllegalArgumentException> { ContentPackLoader.texturePath("has space") }
    }

    // ------------------------------------------------------------------ resource pack

    @Test
    fun `resource pack contains item definitions, models, textures, and equipment`(@TempDir contents: Path) {
        platinum(contents)
        pack(contents, "weapons_pack_free", "items:\n  cosmic_sword:\n    material: DIAMOND_SWORD\n    texture: cosmic_sword\n", "cosmic_sword")
        val files = ResourcePackBuilder.entries(loader(contents).load()).mapValues { String(it.value, StandardCharsets.UTF_8) }

        assertContains(files.getValue("pack.mcmeta"), "\"pack_format\":97")
        assertEquals(
            """{"model":{"type":"minecraft:model","model":"blossom_studios:item/platinum_sword"}}""",
            files["assets/blossom_studios/items/platinum_sword.json"],
        )
        assertEquals(
            """{"parent":"minecraft:item/handheld","textures":{"layer0":"blossom_studios:item/platinum_sword"}}""",
            files["assets/blossom_studios/models/item/platinum_sword.json"],
        )
        assertEquals("png:item/platinum_sword", files["assets/blossom_studios/textures/item/platinum_sword.png"])
        // Textures outside item/ are moved under item/ so they are stitched into the item atlas.
        assertEquals("png:cosmic_sword", files["assets/weapons_pack_free/textures/item/cosmic_sword.png"])
        assertContains(files.getValue("assets/weapons_pack_free/models/item/cosmic_sword.json"), "\"weapons_pack_free:item/cosmic_sword\"")
        assertEquals(
            """{"layers":{"humanoid":[{"texture":"blossom_studios:platinum"}],"humanoid_leggings":[{"texture":"blossom_studios:platinum"}]}}""",
            files["assets/blossom_studios/equipment/platinum.json"],
        )
        assertEquals("png:armor/platinum_layer_1", files["assets/blossom_studios/textures/entity/equipment/humanoid/platinum.png"])
        assertEquals("png:armor/platinum_layer_2", files["assets/blossom_studios/textures/entity/equipment/humanoid_leggings/platinum.png"])
    }

    @Test
    fun `resource pack zip is deterministic and changes when content changes`(@TempDir contents: Path) {
        val folder = platinum(contents)
        val first = ResourcePackBuilder.build(loader(contents).load())
        val second = ResourcePackBuilder.build(loader(contents).load())
        assertEquals(first.sha1, second.sha1, "unchanged content must not force a re-download")
        assertEquals(40, first.sha1.length)

        val names = ZipInputStream(ByteArrayInputStream(first.bytes)).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        assertEquals(names.sorted(), names)
        assertTrue("pack.mcmeta" in names)

        Files.write(folder.resolve("textures/item/platinum_sword.png"), "png:retextured".toByteArray())
        assertNotEquals(first.sha1, ResourcePackBuilder.build(loader(contents).load()).sha1)
    }

    @Test
    fun `json strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\n\"", ResourcePackBuilder.quote("a\"b\\c\n"))
    }

    // ------------------------------------------------------------------ settings

    @Test
    fun `pack url uses the address the player joined with unless a public url is set`() {
        val pack = BuiltResourcePack(byteArrayOf(1, 2, 3))
        val defaults = ResourcePackSettings()
        assertEquals("http://play.example.com:8163/${pack.sha1}.zip", defaults.urlFor(pack, "play.example.com"))
        assertEquals("http://localhost:8163/${pack.sha1}.zip", defaults.urlFor(pack, null))
        assertEquals("http://[::1]:8163/${pack.sha1}.zip", defaults.urlFor(pack, "::1"))
        val custom = ResourcePackSettings(publicUrl = "https://cdn.example.com/packs/")
        assertEquals("https://cdn.example.com/packs/${pack.sha1}.zip", custom.urlFor(pack, "ignored"))
    }

    @Test
    fun `resource pack settings parse and validate`() {
        assertEquals(ResourcePackSettings(), ResourcePackSettings.from(null))
        val yaml = YamlConfiguration().apply {
            set("enabled", false)
            set("port", 9000)
            set("public-url", "http://pack.example.com")
            set("required", true)
        }
        assertEquals(ResourcePackSettings(false, 9000, "http://pack.example.com", true), ResourcePackSettings.from(yaml))
        assertFailsWith<IllegalArgumentException> { ResourcePackSettings.from(YamlConfiguration().apply { set("port", 70000) }) }
        assertFailsWith<IllegalArgumentException> { ResourcePackSettings.from(YamlConfiguration().apply { set("public-url", "ftp://x") }) }
    }

    // ------------------------------------------------------------------ recipes and drops using custom items

    private val customItems = mapOf(
        "blossom_studios:platinum_ingot" to Material.PAPER,
        "blossom_studios:raw_platinum" to Material.PAPER,
        "blossom_studios:platinum_sword" to Material.DIAMOND_SWORD,
    )

    private fun parse(yaml: String) = ConfigParser.parse(
        YamlConfiguration().apply { loadFromString(yaml.trimIndent()) },
        maxStackSizeProvider = { 64 },
        enchantmentValidator = { true },
        customItems = customItems,
        customStackSize = { id -> if (id.endsWith("sword")) 1 else 64 },
    )

    @Test
    fun `recipes accept custom item ingredients and results, and smelting recipes`() {
        val parsed = parse(
            """
            recipes:
              platinum_sword:
                type: shaped
                shape: ["I", "I", "S"]
                ingredients:
                  I: blossom_studios:platinum_ingot
                  S: minecraft:stick
                result:
                  item: blossom_studios:platinum_sword
              raw_platinum_smelt:
                type: smelting
                ingredient: blossom_studios:raw_platinum
                machines: [furnace, blast_furnace]
                experience: 0.7
                cook-time: 100
                result:
                  item: blossom_studios:platinum_ingot
                  amount: 2
            mobs:
              grunt:
                entity: ZOMBIE
                entity-tag: test.grunt
                drops:
                  - { item: blossom_studios:raw_platinum, min: 1, max: 2, chance: 0.5 }
            """,
        )
        val sword = parsed.recipes[0] as ShapedRecipeDefinition
        assertEquals(Ingredient.Custom("blossom_studios:platinum_ingot"), sword.ingredients['I'])
        assertEquals(Ingredient.Vanilla(Material.STICK), sword.ingredients['S'])
        assertEquals("blossom_studios:platinum_sword", sword.result.customItem)
        assertEquals(Material.DIAMOND_SWORD, sword.result.material)

        val smelt = parsed.recipes[1] as CookingRecipeDefinition
        assertEquals(Ingredient.Custom("blossom_studios:raw_platinum"), smelt.ingredient)
        assertEquals(listOf(CookingMachine.FURNACE, CookingMachine.BLAST_FURNACE), smelt.machines)
        assertEquals(0.7f, smelt.experience)
        assertEquals(100, smelt.cookTime)
        assertEquals(2, smelt.result.amount)

        val drop = parsed.mobRules.single().drops.single()
        assertEquals("blossom_studios:raw_platinum", drop.customItem)
        assertEquals(Material.PAPER, drop.material)
        val rolled = ConfigParser.rollDrops(parsed.mobRules, org.bukkit.entity.EntityType.ZOMBIE, null, kotlin.random.Random(1), setOf("test.grunt"))
        rolled.forEach { assertEquals("blossom_studios:raw_platinum", it.customItem) }
    }

    @Test
    fun `references to missing custom items and bad smelting recipes are errors`() {
        fun error(yaml: String) = assertFailsWith<IllegalArgumentException> { parse(yaml) }.message!!
        assertContains(error("recipes:\n  r:\n    type: shapeless\n    ingredients: [ghost:item]\n    result: { material: STONE }\n"), "Unknown custom item in recipes.r: ghost:item")
        assertContains(error("recipes:\n  r:\n    type: shapeless\n    ingredients: [STONE]\n    result: { item: ghost:item }\n"), "Unknown custom item")
        assertContains(error("recipes:\n  r:\n    type: shapeless\n    ingredients: [STONE]\n    result: { item: blossom_studios:platinum_sword, amount: 2 }\n"), "invalid amount")
        assertContains(error("recipes:\n  r:\n    type: smelting\n    result: { material: STONE }\n"), "ingredient is required")
        assertContains(error("recipes:\n  r:\n    type: smelting\n    ingredient: COBBLESTONE\n    machines: [oven]\n    result: { material: STONE }\n"), "unknown machine 'oven'")
        assertContains(error("recipes:\n  r:\n    type: cooking\n    result: { material: STONE }\n"), "shaped, shapeless or smelting")
        assertContains(
            error("mobs:\n  m:\n    entity: ZOMBIE\n    entity-tag: t\n    drops:\n      - { item: blossom_studios:raw_platinum, random-enchantment: true }\n"),
            "random-enchantment cannot be used with item",
        )
    }

    @Test
    fun `content pack errors point at the right line`(@TempDir contents: Path) {
        val folder = pack(contents, "ns", "items:\n  good:\n    material: PAPER\n    texture: t\n  broken:\n    material: PAPER\n    texture: t\n    attributes:\n      speediness: 1\n", "t")
        val error = assertFailsWith<ContentPackException> { loader(contents).load() }
        assertEquals(folder.resolve("items.yml").toFile(), error.file)
        // items.broken.attributes -> the broken item's attributes line (not "items.yml" from the path).
        assertEquals(8, ConfigParser.findErrorLine(error, error.file))
    }

    // ------------------------------------------------------------------ messages

    @Test
    fun `messages mention custom items only when there are some`() {
        val plain = PlainTextComponentSerializer.plainText()
        assertEquals("[FemItems] Config reloaded. (1 mob rules, 3 recipes)", plain.serialize(Messages.reloaded(1, 3)))
        assertEquals("[FemItems] Config reloaded. (1 mob rules, 3 recipes, 2 custom items)", plain.serialize(Messages.reloaded(1, 3, 2)))
        assertEquals("[FemItems] Config error at line 6. (hover for details)", plain.serialize(Messages.configError(6, "x")))
        assertEquals(
            "[FemItems] Content pack error in contents/ns/items.yml at line 4. (hover for details)",
            plain.serialize(Messages.configError(4, "x", "contents/ns/items.yml")),
        )
        assertEquals("[FemItems] Gave 2x ns:sword to Lucy.", plain.serialize(Messages.gaveCustomItem(2, "ns:sword", "Lucy")))
    }
}
