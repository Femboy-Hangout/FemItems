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

/** How an attribute modifier combines with the player's value (vanilla `add_value`, `add_multiplied_base`, `add_multiplied_total`). */
enum class ModifierOperation(val configName: String) {
    ADD("add"),
    MULTIPLY_BASE("multiply_base"),
    MULTIPLY_TOTAL("multiply_total");

    companion object {
        fun fromConfig(name: String): ModifierOperation? = entries.firstOrNull { it.configName == name.trim().lowercase(Locale.ROOT) }
    }
}

/**
 * One extra attribute modifier applied while the item is held or worn, on top of the base item's own
 * stats. [slot] is a vanilla slot group name (`mainhand`, `head`, `armor`, `any`...); null means the
 * item's natural slot (its armour slot, otherwise the main hand).
 */
data class AttributeSpec(
    val attribute: NamespacedKey,
    val amount: Double,
    val operation: ModifierOperation = ModifierOperation.ADD,
    val slot: String? = null,
)

/** What an item's right-click ability does, and which extra options it takes. */
enum class AbilityType(val configName: String, val options: List<String>) {
    /** Dash [ItemAbility.distance] blocks forward (horizontally), stopping at walls. */
    TELEPORT("teleport", listOf("distance")),
    /** Launch forward and up with [ItemAbility.power]. */
    LEAP("leap", listOf("power")),
    /** Restore [ItemAbility.amount] health (2 = one heart). */
    HEAL("heal", listOf("amount")),
    /** Give yourself [ItemAbility.effect] at [ItemAbility.level] for [ItemAbility.durationSeconds]. */
    EFFECT("effect", listOf("effect", "level", "duration")),
    /** Shoot a blaze fireball; [ItemAbility.incendiary] also sets blocks on fire. */
    FIREBALL("fireball", listOf("incendiary")),
    /** Strike lightning at the block you look at, up to [ItemAbility.distance] away. */
    LIGHTNING("lightning", listOf("distance", "damage", "fire")),
    /** Give a potion effect to every mob (and optionally player) within [ItemAbility.radius]. */
    AURA("aura", listOf("effect", "level", "duration", "radius", "players")),
    /** Freeze (powder-snow frost + Slowness) everything within [ItemAbility.radius]. */
    FREEZE("freeze", listOf("level", "duration", "radius", "players")),
    /** Damage everything within [ItemAbility.radius] and heal yourself for [ItemAbility.healPercent] of it. */
    DRAIN("drain", listOf("damage", "heal-percent", "radius", "players")),
    /** Knock everything within [ItemAbility.radius] away from you, optionally with damage. */
    SHOCKWAVE("shockwave", listOf("power", "damage", "radius", "players")),
    /** Shoot [ItemAbility.count] arrows in a spread. */
    VOLLEY("volley", listOf("count"));

    companion object {
        fun fromConfig(name: String): AbilityType? = entries.firstOrNull { it.configName == name.trim().lowercase(Locale.ROOT) }
    }
}

/** A right-click ability with a per-player cooldown; [cooldownMessage] may use `{seconds}`. */
data class ItemAbility(
    val type: AbilityType,
    val cooldownSeconds: Double,
    val cooldownMessage: String = DEFAULT_COOLDOWN_MESSAGE,
    val distance: Double = 0.0,
    val power: Double = 0.0,
    val amount: Double = 0.0,
    /** Potion effect id (e.g. `minecraft:speed`), for [AbilityType.EFFECT]. */
    val effect: NamespacedKey? = null,
    val level: Int = 1,
    val durationSeconds: Double = 0.0,
    val incendiary: Boolean = false,
    /** Lightning: true = real lightning (damage), false = harmless visual strike. */
    val damage: Boolean = true,
    /** Lightning: may it set blocks on fire. */
    val fire: Boolean = false,
    /** Area abilities: how far around the user they reach. */
    val radius: Double = 0.0,
    /** Area abilities: also affect other players (only where PvP is on). */
    val players: Boolean = false,
    /** Drain / shockwave: damage dealt to each target. */
    val damageAmount: Double = 0.0,
    /** Drain: share of the damage dealt that heals the user, 0-100. */
    val healPercent: Double = 0.0,
    /** Volley: number of arrows. */
    val count: Int = 0,
) {
    companion object {
        const val DEFAULT_COOLDOWN_MESSAGE = "&cWait {seconds} seconds"
    }
}

/**
 * Headline stats, written as the total the tooltip shows (`attack-damage: 12` = "12 Attack Damage").
 * Each one replaces the base item's own value for that attribute. [playerBase] is the player's base
 * value, which vanilla adds to the item modifier for display, so the modifier is `value - playerBase`.
 */
enum class ItemStat(val configName: String, val attribute: String, val playerBase: Double, val range: ClosedFloatingPointRange<Double>) {
    ATTACK_DAMAGE("attack-damage", "attack_damage", 1.0, 0.0..2048.0),
    ATTACK_SPEED("attack-speed", "attack_speed", 4.0, 0.01..1024.0),
    ARMOR("armor", "armor", 0.0, 0.0..30.0),
    ARMOR_TOUGHNESS("armor-toughness", "armor_toughness", 0.0, 0.0..20.0),
    KNOCKBACK_RESISTANCE("knockback-resistance", "knockback_resistance", 0.0, 0.0..1.0);

    /** The modifier amount that makes the tooltip and the player's value show exactly [value]. */
    fun modifierAmount(value: Double): Double = value - playerBase
}

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
    /** Headline stats replacing the base item's own values (see [ItemStat]). */
    val stats: Map<ItemStat, Double> = emptyMap(),
    /** Browser category (`/femitems gui`); null = automatic from the material (see [ItemCategories]). */
    val category: String? = null,
    /** Right-click ability (see [ItemAbility]); null = none. */
    val ability: ItemAbility? = null,
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
    private val effectExists: (NamespacedKey) -> Boolean = { true },
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
        for (option in section.getKeys(false)) {
            if (option in ITEM_OPTIONS) continue
            val hint = OPTION_HINTS[option.lowercase(Locale.ROOT)]?.let { " (did you mean $it?)" }
                ?: " (options: ${ITEM_OPTIONS.joinToString(", ")})"
            throw ContentPackException(file, "$where.$option: unknown option$hint")
        }
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
        val stats = LinkedHashMap<ItemStat, Double>()
        for (stat in ItemStat.entries) {
            val value = section.get(stat.configName) ?: continue
            val amount = number(value) ?: throw ContentPackException(file, "$where.${stat.configName} must be a number")
            if (amount !in stat.range) {
                throw ContentPackException(file, "$where.${stat.configName} must be ${fmt(stat.range.start)} to ${fmt(stat.range.endInclusive)}")
            }
            stats[stat] = amount
        }
        val attributes = ArrayList<AttributeSpec>()
        section.getConfigurationSection("attributes")?.let { attrSection ->
            for (name in attrSection.getKeys(false)) {
                val attributeKey = minecraftKey(name) ?: throw ContentPackException(file, "$where.attributes.$name: invalid key")
                if (!attributeExists(attributeKey)) throw ContentPackException(file, "$where.attributes.$name: unknown attribute")
                attributes += readAttribute(attrSection, name, attributeKey, "$where.attributes.$name", file)
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
            stats = stats,
            category = section.getString("category")?.trim()?.takeIf(String::isNotEmpty)?.also {
                if (it.length > 32) throw ContentPackException(file, "$where.category must be at most 32 characters")
            },
            ability = section.get("ability")?.let { readAbility(section.getConfigurationSection("ability"), "$where.ability", file) },
            equipment = equipment,
            armorSlot = armorSlot,
        )
    }

    private fun readAbility(section: ConfigurationSection?, where: String, file: File): ItemAbility {
        val types = AbilityType.entries.joinToString(", ") { it.configName }
        if (section == null) throw ContentPackException(file, "$where must be a section with a type ($types)")
        val typeName = section.getString("type") ?: throw ContentPackException(file, "$where.type is required ($types)")
        val type = AbilityType.fromConfig(typeName) ?: throw ContentPackException(file, "$where.type: unknown ability '$typeName' ($types)")
        val allowed = ABILITY_OPTIONS + type.options
        for (option in section.getKeys(false)) {
            if (option !in allowed) throw ContentPackException(file, "$where.$option: not an option for ${type.configName} (options: ${allowed.joinToString(", ")})")
        }
        fun ranged(path: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
            val value = section.get(path) ?: return default
            val number = number(value) ?: throw ContentPackException(file, "$where.$path must be a number")
            if (number !in range) throw ContentPackException(file, "$where.$path must be ${fmt(range.start)} to ${fmt(range.endInclusive)}")
            return number
        }
        val cooldown = ranged("cooldown", 10.0, 0.0..86400.0)
        val message = section.getString("cooldown-message") ?: ItemAbility.DEFAULT_COOLDOWN_MESSAGE
        val base = ItemAbility(type, cooldown, message)
        fun effectKey(example: String): NamespacedKey {
            val name = section.getString("effect") ?: throw ContentPackException(file, "$where.effect is required (e.g. $example)")
            val key = minecraftKey(name) ?: throw ContentPackException(file, "$where.effect: invalid key '$name'")
            if (!effectExists(key)) throw ContentPackException(file, "$where.effect: unknown effect '$name'")
            return key
        }
        fun radius() = ranged("radius", 5.0, 1.0..16.0)
        fun players() = section.getBoolean("players", false)
        return when (type) {
            AbilityType.TELEPORT -> base.copy(distance = ranged("distance", 5.0, 1.0..32.0))
            AbilityType.LEAP -> base.copy(power = ranged("power", 1.5, 0.1..4.0))
            AbilityType.HEAL -> base.copy(amount = ranged("amount", 6.0, 0.5..1024.0))
            AbilityType.EFFECT -> base.copy(
                effect = effectKey("speed, strength, regeneration"),
                level = ranged("level", 1.0, 1.0..255.0).toInt(),
                durationSeconds = ranged("duration", 10.0, 0.05..3600.0),
            )
            AbilityType.FIREBALL -> base.copy(incendiary = section.getBoolean("incendiary", false))
            AbilityType.LIGHTNING -> base.copy(
                distance = ranged("distance", 20.0, 1.0..32.0),
                damage = section.getBoolean("damage", true),
                fire = section.getBoolean("fire", false),
            )
            AbilityType.AURA -> base.copy(
                effect = effectKey("poison, slowness, weakness, wither, glowing"),
                level = ranged("level", 1.0, 1.0..255.0).toInt(),
                durationSeconds = ranged("duration", 5.0, 0.05..3600.0),
                radius = radius(),
                players = players(),
            )
            AbilityType.FREEZE -> base.copy(
                level = ranged("level", 2.0, 1.0..10.0).toInt(),
                durationSeconds = ranged("duration", 5.0, 0.5..60.0),
                radius = radius(),
                players = players(),
            )
            AbilityType.DRAIN -> base.copy(
                damageAmount = ranged("damage", 4.0, 0.5..100.0),
                healPercent = ranged("heal-percent", 50.0, 0.0..100.0),
                radius = radius(),
                players = players(),
            )
            AbilityType.SHOCKWAVE -> base.copy(
                power = ranged("power", 1.2, 0.1..4.0),
                damageAmount = ranged("damage", 0.0, 0.0..100.0),
                radius = radius(),
                players = players(),
            )
            AbilityType.VOLLEY -> base.copy(count = ranged("count", 5.0, 1.0..15.0).toInt())
        }
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

    /** `name: 4` (added in the item's natural slot) or `name: { amount: 4, operation: multiply_base, slot: any }`. */
    private fun readAttribute(parent: ConfigurationSection, name: String, key: NamespacedKey, where: String, file: File): AttributeSpec {
        val detail = parent.getConfigurationSection(name)
            ?: return AttributeSpec(key, number(parent.get(name)) ?: throw ContentPackException(file, "$where must be a number"))
        for (option in detail.getKeys(false)) {
            if (option !in ATTRIBUTE_OPTIONS) throw ContentPackException(file, "$where.$option: unknown option (options: amount, operation, slot)")
        }
        val amount = number(detail.get("amount")) ?: throw ContentPackException(file, "$where.amount must be a number")
        val operation = detail.getString("operation")?.let {
            ModifierOperation.fromConfig(it) ?: throw ContentPackException(file, "$where.operation must be add, multiply_base or multiply_total")
        } ?: ModifierOperation.ADD
        val slot = detail.getString("slot")?.trim()?.lowercase(Locale.ROOT)?.also {
            if (it !in SLOT_GROUPS) throw ContentPackException(file, "$where.slot must be one of ${SLOT_GROUPS.joinToString(", ")}")
        }
        return AttributeSpec(key, amount, operation, slot)
    }

    private fun number(value: Any?): Double? =
        ((value as? Number)?.toDouble() ?: value?.toString()?.trim()?.toDoubleOrNull())?.takeIf { it.isFinite() }

    private fun fmt(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else value.toString()

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
        /** Every option an item section may have; anything else is a typo and is reported. */
        val ITEM_OPTIONS: List<String> = listOf(
            "name", "lore", "material", "texture", "model", "max-durability", "max-stack-size", "unbreakable",
            "enchants", "attributes", "equipment", "slot", "category", "ability",
        ) + ItemStat.entries.map(ItemStat::configName)
        private val ABILITY_OPTIONS = listOf("type", "cooldown", "cooldown-message")
        private val OPTION_HINTS = mapOf(
            "damage" to "attack-damage", "attack_damage" to "attack-damage", "attack-dmg" to "attack-damage",
            "speed" to "attack-speed", "attack_speed" to "attack-speed",
            "armour" to "armor", "toughness" to "armor-toughness", "armor_toughness" to "armor-toughness",
            "knockback_resistance" to "knockback-resistance",
            "durability" to "max-durability", "max_durability" to "max-durability",
            "stack-size" to "max-stack-size", "max_stack_size" to "max-stack-size",
            "enchantments" to "enchants", "attribute" to "attributes", "display-name" to "name",
        )
        private val ATTRIBUTE_OPTIONS = setOf("amount", "operation", "slot")
        /** Vanilla equipment slot group names (`EquipmentSlotGroup.getByName`). */
        val SLOT_GROUPS = listOf("mainhand", "offhand", "hand", "head", "chest", "legs", "feet", "armor", "body", "any")
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
