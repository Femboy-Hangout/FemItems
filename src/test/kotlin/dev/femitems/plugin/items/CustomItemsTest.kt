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
    private val knownAttributes = setOf("armor", "armor_toughness", "attack_damage", "attack_speed", "movement_speed", "max_health")
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
    fun `headline stats and detailed attributes are read`(@TempDir contents: Path) {
        pack(
            contents, "ns",
            """
            items:
              blade:
                material: DIAMOND_SWORD
                texture: t
                attack-damage: 12
                attack-speed: "1.8"
                attributes:
                  movement_speed: { amount: 0.1, operation: multiply_base, slot: any }
                  max_health: 4
              cap:
                material: DIAMOND_HELMET
                texture: t
                armor: 5
                armor-toughness: 2.5
                knockback-resistance: 0.1
            """,
            "t",
        )
        val items = loader(contents).load().single().items.associateBy(CustomItemDefinition::key)
        val blade = items.getValue("blade")
        assertEquals(mapOf(ItemStat.ATTACK_DAMAGE to 12.0, ItemStat.ATTACK_SPEED to 1.8), blade.stats)
        assertEquals(11.0, ItemStat.ATTACK_DAMAGE.modifierAmount(12.0), "the player's base 1 damage is part of the shown total")
        assertEquals(-2.2, ItemStat.ATTACK_SPEED.modifierAmount(1.8), 1e-9)
        assertEquals(
            listOf(
                AttributeSpec(NamespacedKey.minecraft("movement_speed"), 0.1, ModifierOperation.MULTIPLY_BASE, "any"),
                AttributeSpec(NamespacedKey.minecraft("max_health"), 4.0),
            ),
            blade.attributes,
        )
        assertEquals(
            mapOf(ItemStat.ARMOR to 5.0, ItemStat.ARMOR_TOUGHNESS to 2.5, ItemStat.KNOCKBACK_RESISTANCE to 0.1),
            items.getValue("cap").stats,
        )
        assertTrue(items.getValue("cap").attributes.isEmpty())
    }

    @Test
    fun `stat and attribute mistakes are reported with a hint`(@TempDir root: Path) {
        fun error(body: String): String {
            val contents = root.resolve("c-${System.nanoTime()}")
            pack(contents, "ns", "items:\n  a:\n    material: DIAMOND_SWORD\n    texture: t\n$body", "t")
            return assertFailsWith<ContentPackException> { loader(contents).load() }.message!!
        }
        assertContains(error("    damage: 10\n"), "items.a.damage: unknown option (did you mean attack-damage?)")
        assertContains(error("    sharpness: 2\n"), "unknown option (options: name, lore, material")
        assertContains(error("    attack-damage: lots\n"), "attack-damage must be a number")
        assertContains(error("    attack-damage: -1\n"), "attack-damage must be 0 to 2048")
        assertContains(error("    attack-speed: 0\n"), "attack-speed must be 0.01 to 1024")
        assertContains(error("    knockback-resistance: 2\n"), "knockback-resistance must be 0 to 1")
        assertContains(error("    attributes:\n      armor: { amount: 1, operation: times }\n"), "operation must be add, multiply_base or multiply_total")
        assertContains(error("    attributes:\n      armor: { amount: 1, slot: pocket }\n"), "slot must be one of mainhand")
        assertContains(error("    attributes:\n      armor: { value: 1 }\n"), "attributes.armor.value: unknown option")
        assertContains(error("    attributes:\n      armor: { operation: add }\n"), "attributes.armor.amount must be a number")
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
    fun `self-host pack url uses the address the player joined with unless a public url is set`() {
        val pack = BuiltResourcePack(byteArrayOf(1, 2, 3))
        val defaults = ResourcePackSettings()
        assertEquals("http://play.example.com:8163/${pack.sha1}.zip", defaults.selfHostUrlFor(pack, "play.example.com"))
        assertEquals("http://localhost:8163/${pack.sha1}.zip", defaults.selfHostUrlFor(pack, null))
        assertEquals("http://[::1]:8163/${pack.sha1}.zip", defaults.selfHostUrlFor(pack, "::1"))
        val custom = ResourcePackSettings(selfHostPublicUrl = "https://cdn.example.com/packs/")
        assertEquals("https://cdn.example.com/packs/${pack.sha1}.zip", custom.selfHostUrlFor(pack, "ignored"))
    }

    @Test
    fun `resource pack settings default to external hosting and validate`() {
        assertEquals(ResourcePackSettings(mode = PackMode.EXTERNAL), ResourcePackSettings.from(null))
        val external = ResourcePackSettings.from(YamlConfiguration().apply {
            set("mode", "external")
            set("url", "https://download.example.net/pack/abc.zip")
            set("required", true)
        })
        assertEquals(PackMode.EXTERNAL, external.mode)
        assertEquals("https://download.example.net/pack/abc.zip", external.url)
        assertTrue(external.required)
        val selfHost = ResourcePackSettings.from(YamlConfiguration().apply {
            set("mode", "self-host")
            set("self-host.port", 9000)
            set("self-host.public-url", "http://pack.example.com")
        })
        assertEquals(ResourcePackSettings(mode = PackMode.SELF_HOST, selfHostPort = 9000, selfHostPublicUrl = "http://pack.example.com"), selfHost)
        assertEquals(PackMode.OFF, ResourcePackSettings.from(YamlConfiguration().apply { set("mode", "off") }).mode)
        assertEquals(PackMode.OFF, ResourcePackSettings.from(YamlConfiguration().apply { set("enabled", false) }).mode)
        // 2.2.0 configs (enabled/port/public-url at the top level) keep self-hosting.
        val legacy = ResourcePackSettings.from(YamlConfiguration().apply { set("enabled", true); set("port", 8163) })
        assertEquals(PackMode.SELF_HOST, legacy.mode)
        assertFailsWith<IllegalArgumentException> { ResourcePackSettings.from(YamlConfiguration().apply { set("mode", "cloud") }) }
        assertFailsWith<IllegalArgumentException> { ResourcePackSettings.from(YamlConfiguration().apply { set("url", "ftp://x") }) }
        assertFailsWith<IllegalArgumentException> { ResourcePackSettings.from(YamlConfiguration().apply { set("self-host.port", 70000) }) }
    }

    @Test
    fun `mode off written as plain yaml is read as off, not rejected`() {
        // YAML 1.1 turns unquoted off/no into the boolean false; that used to reject the whole config.yml.
        for (text in listOf("mode: off", "mode: no", "mode: false", "mode: \"off\"", "mode: OFF")) {
            val section = YamlConfiguration().apply { loadFromString(text) }
            assertEquals(PackMode.OFF, ResourcePackSettings.from(section).mode, "for '$text'")
        }
        val external = YamlConfiguration().apply { loadFromString("mode: external\nurl: https://x/p.zip") }
        assertEquals(PackMode.EXTERNAL, ResourcePackSettings.from(external).mode)
    }

    @Test
    fun `uploaded pack is verified by downloading it and comparing sha1`() {
        val pack = BuiltResourcePack("current pack".toByteArray())
        val served = java.util.concurrent.atomic.AtomicReference("current pack".toByteArray())
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/pack.zip") { exchange ->
            val body = served.get()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        try {
            val url = "http://127.0.0.1:${http.address.port}/pack.zip"
            assertEquals(ExternalPackStatus.Verified(url, pack.sha1), ExternalPackVerifier.verify(url, pack.sha1))

            served.set("an older pack".toByteArray())
            val outdated = ExternalPackVerifier.verify(url, pack.sha1)
            assertTrue(outdated is ExternalPackStatus.Outdated, "$outdated")
            assertEquals(BuiltResourcePack("an older pack".toByteArray()).sha1, (outdated as ExternalPackStatus.Outdated).remoteSha1)

            val missing = ExternalPackVerifier.verify("http://127.0.0.1:${http.address.port}/gone.zip", pack.sha1)
            assertEquals(ExternalPackStatus.Unreachable("http://127.0.0.1:${http.address.port}/gone.zip", "HTTP 404"), missing)
        } finally {
            http.stop(0)
        }
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        assertTrue(ExternalPackVerifier.verify("http://127.0.0.1:$closedPort/pack.zip", pack.sha1) is ExternalPackStatus.Unreachable)
        assertEquals(ExternalPackStatus.NotConfigured, ExternalPackVerifier.verify("", pack.sha1))
    }

    @Test
    fun `pack checks leave no http client threads behind`() {
        fun clientThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith("HttpClient-") }
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/pack.zip") { exchange ->
            exchange.sendResponseHeaders(200, 3)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
        }
        http.start()
        val before = clientThreads()
        try {
            repeat(5) { ExternalPackVerifier.verify("http://127.0.0.1:${http.address.port}/pack.zip", "x") }
        } finally {
            http.stop(0)
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (clientThreads() > before && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(before, clientThreads(), "pack checks leaked HttpClient threads (would pin the plugin class loader)")
    }

    @Test
    fun `pack status messages tell the admin what to do`() {
        val plain = PlainTextComponentSerializer.plainText()
        assertEquals(
            "[FemItems] Resource pack not sent yet. Upload plugins/FemItems/resourcepack/femitems-items.zip to mc-packs.net and put its download link in resource-pack.url, then /femitems pack.",
            plain.serialize(Messages.packStatus(ExternalPackStatus.NotConfigured, "femitems-items.zip")),
        )
        assertEquals(
            "[FemItems] Resource pack verified; players get it from https://x/p.zip",
            plain.serialize(Messages.packStatus(ExternalPackStatus.Verified("https://x/p.zip", "ab"), "f.zip")),
        )
        assertContains(plain.serialize(Messages.packStatus(ExternalPackStatus.Outdated("u", "ab"), "f.zip")), "Uploaded resource pack is outdated.")
        assertContains(plain.serialize(Messages.packStatus(ExternalPackStatus.Unreachable("u", "HTTP 404"), "f.zip")), "Could not download the resource pack: HTTP 404")
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

    // ------------------------------------------------------------------ item browser

    @Test
    fun `browser groups items automatically and by custom category, in a stable order`(@TempDir contents: Path) {
        pack(
            contents, "ns",
            """
            items:
              ingot: { material: PAPER, texture: t }
              gem: { material: PAPER, texture: t, category: Gems }
              blade: { material: NETHERITE_SWORD, texture: t }
              axe: { material: NETHERITE_AXE, texture: t }
              war_axe: { material: NETHERITE_AXE, texture: t, category: weapons }
              helmet: { material: NETHERITE_HELMET, texture: t }
              hat: { material: PAPER, texture: t, slot: head }
              shard: { material: PAPER, texture: t, category: gems }
              bow: { material: BOW, texture: t }
            """,
            "t",
        )
        val categories = ItemCategories.group(loader(contents).load().single().items)
        assertEquals(
            listOf(
                ItemCategory("Weapons", listOf("ns:blade", "ns:war_axe", "ns:bow")),
                ItemCategory("Tools", listOf("ns:axe")),
                ItemCategory("Armor", listOf("ns:helmet", "ns:hat")),
                ItemCategory("Gems", listOf("ns:gem", "ns:shard")),
                ItemCategory("Other", listOf("ns:ingot")),
            ),
            categories,
            "fixed categories first, then custom ones (case-insensitive, first spelling wins), Other last",
        )
    }

    @Test
    fun `browser pages hold 45 items`() {
        val ids = (1..100).map { "ns:item$it" }
        assertEquals(3, ItemCategories.pageCount(ids.size))
        assertEquals(1, ItemCategories.pageCount(0))
        assertEquals((1..45).map { "ns:item$it" }, ItemCategories.page(ids, 0))
        assertEquals((91..100).map { "ns:item$it" }, ItemCategories.page(ids, 2))
        assertEquals(ItemCategories.page(ids, 2), ItemCategories.page(ids, 99), "out-of-range pages clamp to the last")
        assertEquals(ItemCategories.page(ids, 0), ItemCategories.page(ids, -1))
    }

    @Test
    fun `category option is validated`(@TempDir root: Path) {
        val contents = root.resolve("c")
        pack(contents, "ns", "items:\n  a:\n    material: PAPER\n    texture: t\n    category: ${"x".repeat(33)}\n", "t")
        assertContains(assertFailsWith<ContentPackException> { loader(contents).load() }.message!!, "category must be at most 32 characters")
    }

    // ------------------------------------------------------------------ resourcepack/include

    private fun write(root: Path, path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Test
    fun `included files (music disc sounds, lang) are added to the pack as-is`(@TempDir root: Path) {
        platinum(root.resolve("contents"))
        val include = root.resolve("include")
        write(include, "assets/minecraft/sounds/records/pigstep.ogg", "ogg-bytes")
        write(include, "assets/minecraft/sounds.json", """{"music_disc.pigstep":{"sounds":[{"name":"records/pigstep","stream":true}]}}""")
        write(include, "assets/minecraft/lang/en_us.json", """{"item.minecraft.music_disc_pigstep":"My Song"}""")
        write(include, "pack.mcmeta", "{}")      // the source pack's own metadata: ignored, FemItems writes its own
        write(include, "mctools.json", "{}")
        val included = IncludedFiles.load(include)
        assertEquals(listOf("mctools.json", "pack.mcmeta"), included.ignored)
        assertEquals(3, included.files.size)

        val packs = loader(root.resolve("contents")).load()
        val files = ResourcePackBuilder.entries(packs, extra = included)
        assertEquals("ogg-bytes", String(files.getValue("assets/minecraft/sounds/records/pigstep.ogg")))
        assertContains(String(files.getValue("assets/minecraft/sounds.json")), "music_disc.pigstep")
        assertContains(String(files.getValue("pack.mcmeta")), "\"pack_format\":97", message = "FemItems' own pack.mcmeta is kept")
        assertTrue(files.containsKey("assets/blossom_studios/items/platinum_sword.json"), "custom items are still in the pack")

        val withSounds = ResourcePackBuilder.build(packs, extra = included)
        assertEquals(withSounds.sha1, ResourcePackBuilder.build(packs, extra = IncludedFiles.load(include)).sha1, "deterministic")
        assertNotEquals(ResourcePackBuilder.build(packs).sha1, withSounds.sha1)
        assertEquals(IncludedFiles.EMPTY.files, IncludedFiles.load(root.resolve("missing")).files)
    }

    @Test
    fun `included files must be loadable and must not overwrite generated item files`(@TempDir root: Path) {
        val upper = root.resolve("upper")
        write(upper, "assets/minecraft/sounds/records/MySong.ogg", "x")
        assertContains(assertFailsWith<ContentPackException> { IncludedFiles.load(upper) }.message!!, "only loads lowercase paths")

        platinum(root.resolve("contents"))
        val clash = root.resolve("clash")
        write(clash, "assets/blossom_studios/items/platinum_sword.json", "{}")
        val error = assertFailsWith<ContentPackException> {
            ResourcePackBuilder.entries(loader(root.resolve("contents")).load(), extra = IncludedFiles.load(clash))
        }
        assertContains(error.message!!, "assets/blossom_studios/items/platinum_sword.json clashes with a file FemItems generates")
    }
}
