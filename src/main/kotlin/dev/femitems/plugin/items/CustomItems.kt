package dev.femitems.plugin.items

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Which worn-armour slot a custom item occupies when equipped. */
enum class ArmorSlot(val configName: String, val materialSuffix: String) {
    HEAD("head", "_HELMET"),
    CHEST("chest", "_CHESTPLATE"),
    LEGS("legs", "_LEGGINGS"),
    FEET("feet", "_BOOTS");

    companion object {
        fun forMaterial(material: Material): ArmorSlot? = entries.firstOrNull { material.name.endsWith(it.materialSuffix) }
        fun fromConfig(name: String): ArmorSlot? = entries.firstOrNull { it.configName == name.lowercase(Locale.ROOT) }
    }
}

/** One attribute modifier (amount added to the base value) applied while the item is held or worn. */
data class AttributeSpec(val attribute: NamespacedKey, val amount: Double)

/**
 * A fully custom item: a vanilla [material] for behaviour/stats plus its own model and texture from the
 * generated resource pack (`item_model` = [id]).
 */
data class CustomItemDefinition(
    val namespace: String,
    val key: String,
    val material: Material,
    val name: String?,
    val lore: List<String>,
    /** Texture path inside the pack's `textures/` folder, without `.png` (e.g. `item/platinum_sword`). */
    val texture: String,
    /** Parent model: `minecraft:item/handheld` for tools/weapons, `minecraft:item/generated` otherwise. */
    val modelParent: String,
    val maxDurability: Int?,
    val maxStackSize: Int?,
    val unbreakable: Boolean,
    val enchants: Map<NamespacedKey, Int>,
    val attributes: List<AttributeSpec>,
    /** Equipment asset id (`namespace:name`) giving the worn texture, for armour. */
    val equipment: String?,
    val armorSlot: ArmorSlot?,
) {
    val id: String get() = "$namespace:$key"
}

/** Worn-armour textures: [layer1] (helmet, chestplate, boots) and [layer2] (leggings), as texture paths. */
data class EquipmentDefinition(val namespace: String, val key: String, val layer1: String?, val layer2: String?) {
    val id: String get() = "$namespace:$key"
}

/** A `contents/<namespace>/` folder: its YAML definitions plus the texture files they reference. */
data class ContentPack(
    val namespace: String,
    val folder: Path,
    val items: List<CustomItemDefinition>,
    val equipment: List<EquipmentDefinition>,
) {
    fun texturePath(texture: String): Path = folder.resolve("textures").resolve("$texture.png")
}

/** Thrown for a content-pack problem, naming the file so it can be reported with a line number. */
class ContentPackException(val file: File, message: String) : IllegalArgumentException(message)

/**
 * Loads every `contents/<namespace>/` folder. Pure file/YAML work (safe on the AsyncScheduler); injectable
 * validators keep it unit-testable without a live server registry.
 */
class ContentPackLoader(
    private val contentsFolder: Path,
    private val enchantmentExists: (NamespacedKey) -> Boolean,
    private val attributeExists: (NamespacedKey) -> Boolean,
) {
    fun load(): List<ContentPack> {
        if (!Files.isDirectory(contentsFolder)) return emptyList()
        val folders = Files.list(contentsFolder).use { stream ->
            stream.filter(Files::isDirectory).sorted().toList()
        }
        return folders.map(::loadPack)
    }

    private fun loadPack(folder: Path): ContentPack {
        val namespace = folder.fileName.toString()
        val anyFile = folder.resolve("items.yml").toFile()
        if (!NAMESPACE.matches(namespace) || namespace == "minecraft") {
            throw ContentPackException(anyFile, "contents/$namespace: folder name must be a lowercase namespace (a-z, 0-9, _ . -), not 'minecraft'")
        }
        val yamlFiles = Files.list(folder).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase(Locale.ROOT).endsWith(".yml") }.sorted().toList()
        }
        val items = ArrayList<CustomItemDefinition>()
        val equipment = ArrayList<EquipmentDefinition>()
        for (path in yamlFiles) {
            val file = path.toFile()
            val yaml = YamlConfiguration()
            try {
                yaml.load(file)
            } catch (e: Exception) {
                throw ContentPackException(file, e.message ?: "invalid YAML")
            }
            val where = "contents/$namespace/${path.fileName}"
            yaml.getConfigurationSection("equipment")?.let { section ->
                for (key in section.getKeys(false)) {
                    equipment += readEquipment(section.getConfigurationSection(key), namespace, key, "$where equipment.$key", file)
                }
            }
            yaml.getConfigurationSection("items")?.let { section ->
                for (key in section.getKeys(false)) {
                    items += readItem(section.getConfigurationSection(key), namespace, key, "$where items.$key", file)
                }
            }
        }
        val pack = ContentPack(namespace, folder, items, equipment)
        validate(pack)
        return pack
    }

    private fun readEquipment(section: ConfigurationSection?, namespace: String, key: String, where: String, file: File): EquipmentDefinition {
        if (section == null || !KEY.matches(key)) throw ContentPackException(file, "$where: invalid equipment entry")
        val layer1 = section.getString("layer-1")?.let { checkedTexture(it, "$where.layer-1", file) }
        val layer2 = section.getString("layer-2")?.let { checkedTexture(it, "$where.layer-2", file) }
        if (layer1 == null && layer2 == null) throw ContentPackException(file, "$where needs layer-1 and/or layer-2")
        return EquipmentDefinition(namespace, key, layer1, layer2)
    }

    private fun readItem(section: ConfigurationSection?, namespace: String, key: String, where: String, file: File): CustomItemDefinition {
        if (section == null) throw ContentPackException(file, "$where must be a section")
        if (!KEY.matches(key)) throw ContentPackException(file, "$where: item id must be lowercase (a-z, 0-9, _ . -)")
        val materialName = section.getString("material").orEmpty()
        val material = Material.matchMaterial(materialName)?.takeIf { !it.name.endsWith("AIR") }
            ?: throw ContentPackException(file, "$where.material: unknown item material '$materialName'")
        val texture = section.getString("texture")?.let { checkedTexture(it, "$where.texture", file) }
            ?: throw ContentPackException(file, "$where.texture is required")
        val modelParent = when (val model = section.getString("model")?.trim()?.lowercase(Locale.ROOT)) {
            null, "" -> if (isHandheld(material)) HANDHELD else GENERATED
            "handheld" -> HANDHELD
            "generated" -> GENERATED
            else -> if (model.contains(':')) model else throw ContentPackException(file, "$where.model must be handheld, generated or a namespaced parent model")
        }
        val maxDurability = optionalInt(section, "max-durability", where, file)?.also {
            if (it < 1) throw ContentPackException(file, "$where.max-durability must be at least 1")
        }
        val maxStackSize = optionalInt(section, "max-stack-size", where, file)?.also {
            if (it !in 1..99) throw ContentPackException(file, "$where.max-stack-size must be 1 to 99")
        }
        if (maxDurability != null && (maxStackSize ?: 1) > 1) {
            throw ContentPackException(file, "$where: items with max-durability cannot stack")
        }
        val enchants = LinkedHashMap<NamespacedKey, Int>()
        section.getConfigurationSection("enchants")?.let { enchSection ->
            for (name in enchSection.getKeys(false)) {
                val enchantKey = minecraftKey(name) ?: throw ContentPackException(file, "$where.enchants.$name: invalid key")
                if (!enchantmentExists(enchantKey)) throw ContentPackException(file, "$where.enchants.$name: unknown enchantment")
                val level = enchSection.getInt(name, 0)
                if (level < 1) throw ContentPackException(file, "$where.enchants.$name: level must be at least 1")
                enchants[enchantKey] = level
            }
        }
        val attributes = ArrayList<AttributeSpec>()
        section.getConfigurationSection("attributes")?.let { attrSection ->
            for (name in attrSection.getKeys(false)) {
                val attributeKey = minecraftKey(name) ?: throw ContentPackException(file, "$where.attributes.$name: invalid key")
                if (!attributeExists(attributeKey)) throw ContentPackException(file, "$where.attributes.$name: unknown attribute")
                val value = attrSection.get(name)
                val amount = (value as? Number)?.toDouble() ?: value?.toString()?.trim()?.toDoubleOrNull()
                    ?: throw ContentPackException(file, "$where.attributes.$name must be a number")
                attributes += AttributeSpec(attributeKey, amount)
            }
        }
        val equipment = section.getString("equipment")?.trim()?.takeIf(String::isNotEmpty)?.let {
            if (':' in it) it.lowercase(Locale.ROOT) else "$namespace:${it.lowercase(Locale.ROOT)}"
        }
        val armorSlot = section.getString("slot")?.let {
            ArmorSlot.fromConfig(it) ?: throw ContentPackException(file, "$where.slot must be head, chest, legs or feet")
        } ?: ArmorSlot.forMaterial(material)
        if (equipment != null && armorSlot == null) {
            throw ContentPackException(file, "$where: equipment needs an armour material or slot: head|chest|legs|feet")
        }
        return CustomItemDefinition(
            namespace = namespace,
            key = key,
            material = material,
            name = section.getString("name"),
            lore = section.getStringList("lore"),
            texture = texture,
            modelParent = modelParent,
            maxDurability = maxDurability,
            maxStackSize = maxStackSize,
            unbreakable = section.getBoolean("unbreakable", false),
            enchants = enchants,
            attributes = attributes,
            equipment = equipment,
            armorSlot = armorSlot,
        )
    }

    private fun validate(pack: ContentPack) {
        val file = pack.folder.resolve("items.yml").toFile()
        val equipmentIds = pack.equipment.map(EquipmentDefinition::id).toSet()
        for (item in pack.items) {
            if (!Files.isRegularFile(pack.texturePath(item.texture))) {
                throw ContentPackException(file, "contents/${pack.namespace} items.${item.key}: texture file textures/${item.texture}.png not found")
            }
            val equipment = item.equipment
            if (equipment != null && equipment.startsWith("${pack.namespace}:") && equipment !in equipmentIds) {
                throw ContentPackException(file, "contents/${pack.namespace} items.${item.key}: equipment '$equipment' is not defined")
            }
        }
        for (equipment in pack.equipment) {
            for (layer in listOfNotNull(equipment.layer1, equipment.layer2)) {
                if (!Files.isRegularFile(pack.texturePath(layer))) {
                    throw ContentPackException(file, "contents/${pack.namespace} equipment.${equipment.key}: texture file textures/$layer.png not found")
                }
            }
        }
    }

    private fun checkedTexture(raw: String, where: String, file: File): String =
        try {
            texturePath(raw)
        } catch (e: IllegalArgumentException) {
            throw ContentPackException(file, "$where: ${e.message}")
        }

    private fun optionalInt(section: ConfigurationSection, path: String, where: String, file: File): Int? {
        val value = section.get(path) ?: return null
        return (value as? Number)?.toInt() ?: value.toString().trim().toIntOrNull()
            ?: throw ContentPackException(file, "$where.$path must be a whole number")
    }

    companion object {
        const val HANDHELD = "minecraft:item/handheld"
        const val GENERATED = "minecraft:item/generated"
        private val NAMESPACE = Regex("[a-z0-9_.-]+")
        private val KEY = Regex("[a-z0-9_.-]+")
        private val TEXTURE = Regex("[a-z0-9_./-]+")
        private val HANDHELD_SUFFIXES = listOf("_SWORD", "_AXE", "_PICKAXE", "_SHOVEL", "_HOE", "_SPEAR")

        fun isHandheld(material: Material): Boolean =
            material == Material.MACE || material == Material.TRIDENT || material == Material.STICK ||
                HANDHELD_SUFFIXES.any { material.name.endsWith(it) }

        /** Normalises `item/sword.png` / `item\sword` to `item/sword`; rejects paths escaping the folder. */
        fun texturePath(raw: String): String {
            val path = raw.trim().replace('\\', '/').removeSuffix(".png").trim('/').lowercase(Locale.ROOT)
            require(TEXTURE.matches(path) && path.split('/').none { it == ".." || it.isEmpty() }) {
                "invalid texture path '$raw'"
            }
            return path
        }

        fun minecraftKey(name: String): NamespacedKey? {
            val normalized = name.trim().lowercase(Locale.ROOT)
            return if (':' in normalized) NamespacedKey.fromString(normalized)
            else runCatching { NamespacedKey.minecraft(normalized) }.getOrNull()
        }
    }
}
