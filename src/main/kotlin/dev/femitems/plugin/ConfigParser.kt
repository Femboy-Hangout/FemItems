package dev.femitems.plugin

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.regex.Pattern
import kotlin.random.Random

object ConfigParser {
    private val LEGACY_COLOR_PATTERN: Pattern = Pattern.compile("(?i)[§&][0-9A-FK-ORX]")
    private val LINE_NUMBER_PATTERN: Pattern = Pattern.compile("(?i)\\bline\\s*:?\\s*(\\d+)")
    private val MATERIAL_ERROR_PATTERN: Pattern =
        Pattern.compile("Unknown (?:drop material|ingredient).*?:\\s*([A-Za-z0-9_:-]+)")
    private val ENCHANT_ERROR_PATTERN: Pattern =
        Pattern.compile("recipes\\.([A-Za-z0-9_-]+)\\.result\\.enchants\\.([^\\s]+)")
    private val PATH_ERROR_PATTERN: Pattern =
        Pattern.compile("(?:mobs|recipes)\\.([A-Za-z0-9_-]+)(?:\\.([A-Za-z0-9_-]+))?")

    fun parse(
        config: YamlConfiguration,
        maxStackSizeProvider: (Material) -> Int = { it.maxStackSize },
        enchantmentValidator: (NamespacedKey) -> Boolean = ::defaultEnchantmentExists,
    ): ParsedConfig {
        val mobRules = readMobRules(config)
        val recipes = readRecipes(config, maxStackSizeProvider, enchantmentValidator)
        return ParsedConfig(mobRules = mobRules, recipes = recipes)
    }

    fun readMobRules(config: YamlConfiguration): List<MobRule> {
        val mobs = config.getConfigurationSection("mobs") ?: return emptyList()
        val rules = ArrayList<MobRule>()

        for (id in mobs.getKeys(false)) {
            val section = mobs.getConfigurationSection(id) ?: continue
            val entityName = section.getString("entity", "").orEmpty().uppercase(Locale.ROOT)
            val type: EntityType? = if (entityName == "ANY") {
                null
            } else {
                try {
                    EntityType.valueOf(entityName)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("mobs.$id.entity is not a valid entity type (or ANY)")
                }
            }

            val nameContains = section.getString("name-contains", "").orEmpty()
            if (type == null && nameContains.isBlank()) {
                throw IllegalArgumentException("mobs.$id.entity: ANY requires a non-empty name-contains filter")
            }

            val drops = ArrayList<DropRule>()
            for (raw in section.getMapList("drops")) {
                val materialName = raw["material"].toString()
                val material = Material.matchMaterial(materialName)
                if (material == null || isAirMaterial(material)) {
                    throw IllegalArgumentException("Unknown drop material in mobs.$id: $materialName")
                }
                val min = intValue(raw["min"], 1, "mobs.$id.drops.min")
                val max = intValue(raw["max"], min, "mobs.$id.drops.max")
                val chance = doubleValue(raw["chance"], 1.0, "mobs.$id.drops.chance")
                if (min < 1 || max < min || chance < 0.0 || chance > 1.0) {
                    throw IllegalArgumentException("Invalid count/chance in mobs.$id")
                }
                val customName = if (raw.containsKey("name") && raw["name"] != null) {
                    raw["name"].toString()
                } else {
                    null
                }
                val randomEnchantment = when (val value = raw["random-enchantment"]) {
                    null -> false
                    is Boolean -> value
                    else -> value.toString().toBooleanStrictOrNull()
                        ?: throw IllegalArgumentException("mobs.$id.drops.random-enchantment must be true or false")
                }
                if (randomEnchantment && material != Material.ENCHANTED_BOOK) {
                    throw IllegalArgumentException("mobs.$id.drops.random-enchantment requires material ENCHANTED_BOOK")
                }
                drops.add(DropRule(material, min, max, chance, customName, randomEnchantment))
            }

            rules.add(MobRule(id, type, nameContains, drops.toList()))
        }

        return rules.toList()
    }

    fun readRecipes(
        config: YamlConfiguration,
        maxStackSizeProvider: (Material) -> Int = { it.maxStackSize },
        enchantmentValidator: (NamespacedKey) -> Boolean = ::defaultEnchantmentExists,
    ): List<RecipeDefinition> {
        val section = config.getConfigurationSection("recipes") ?: return emptyList()
        val recipes = ArrayList<RecipeDefinition>()

        for (id in section.getKeys(false)) {
            val recipeSection = section.getConfigurationSection(id) ?: continue
            val result = readItem(
                section = recipeSection.getConfigurationSection("result"),
                path = "recipes.$id.result",
                maxStackSizeProvider = maxStackSizeProvider,
                enchantmentValidator = enchantmentValidator,
            )
            val type = recipeSection.getString("type", "shaped").orEmpty().lowercase(Locale.ROOT)

            when (type) {
                "shaped" -> {
                    val shape = recipeSection.getStringList("shape")
                    if (shape.isEmpty() || shape.size > 3) {
                        throw IllegalArgumentException("recipes.$id.shape must have 1 to 3 rows")
                    }
                    val rowLength = shape.first().length
                    if (rowLength !in 1..3 || shape.any { it.length != rowLength }) {
                        throw IllegalArgumentException("recipes.$id.shape rows must all be 1 to 3 characters of equal length")
                    }
                    val ingredientsSection = recipeSection.getConfigurationSection("ingredients")
                        ?: throw IllegalArgumentException("recipes.$id.ingredients is required")
                    val ingredients = LinkedHashMap<Char, Material>()
                    for (symbol in ingredientsSection.getKeys(false)) {
                        val materialName = ingredientsSection.getString(symbol, "").orEmpty()
                        val material = Material.matchMaterial(materialName)
                        if (material == null || isAirMaterial(material)) {
                            throw IllegalArgumentException("Unknown ingredient in recipes.$id: $materialName")
                        }
                        if (symbol.length != 1) {
                            throw IllegalArgumentException("Ingredient keys must be one character in recipes.$id")
                        }
                        ingredients[symbol[0]] = material
                    }
                    for (row in shape) {
                        for (ch in row) {
                            if (ch != ' ' && !ingredients.containsKey(ch)) {
                                throw IllegalArgumentException("recipes.$id.ingredients is missing symbol '$ch'")
                            }
                        }
                    }
                    for (symbol in ingredients.keys) {
                        if (shape.none { row -> row.contains(symbol) }) {
                            throw IllegalArgumentException("recipes.$id.ingredients symbol '$symbol' is not used in shape")
                        }
                    }
                    recipes.add(
                        ShapedRecipeDefinition(
                            id = id,
                            result = result,
                            shape = shape.toList(),
                            ingredients = ingredients.toMap(),
                        ),
                    )
                }

                "shapeless" -> {
                    val rawIngredients = recipeSection.getStringList("ingredients")
                    if (rawIngredients.isEmpty() || rawIngredients.size > 9) {
                        throw IllegalArgumentException("recipes.$id.ingredients must contain between 1 and 9 materials")
                    }
                    val ingredients = rawIngredients.map { materialName ->
                        val material = Material.matchMaterial(materialName)
                        if (material == null || isAirMaterial(material)) {
                            throw IllegalArgumentException("Unknown ingredient in recipes.$id: $materialName")
                        }
                        material
                    }
                    recipes.add(
                        ShapelessRecipeDefinition(
                            id = id,
                            result = result,
                            ingredients = ingredients,
                        ),
                    )
                }

                else -> throw IllegalArgumentException("recipes.$id.type must be shaped or shapeless")
            }
        }

        return recipes.toList()
    }

    fun readItem(
        section: ConfigurationSection?,
        path: String,
        maxStackSizeProvider: (Material) -> Int = { it.maxStackSize },
        enchantmentValidator: (NamespacedKey) -> Boolean = ::defaultEnchantmentExists,
    ): ItemDefinition {
        if (section == null) {
            throw IllegalArgumentException("$path is required")
        }
        val materialName = section.getString("material", "").orEmpty()
        val material = Material.matchMaterial(materialName)
        val amount = section.getInt("amount", 1)
        if (material == null || isAirMaterial(material) || amount < 1 || amount > maxStackSizeProvider(material)) {
            throw IllegalArgumentException("$path has an invalid material or amount")
        }
        val name = section.getString("name")
        val lore = section.getStringList("lore")
        val unbreakable = section.getBoolean("unbreakable", false)
        val enchants = LinkedHashMap<NamespacedKey, Int>()
        val enchantments = section.getConfigurationSection("enchants")
        if (enchantments != null) {
            for (enchantName in enchantments.getKeys(false)) {
                val level = enchantments.getInt(enchantName, 0)
                if (level < 1) {
                    throw IllegalArgumentException("Enchant level must be at least 1 at $path.enchants.$enchantName")
                }
                val normalizedKey = enchantName.lowercase(Locale.ROOT)
                val enchantKey = if (normalizedKey.contains(":")) {
                    NamespacedKey.fromString(normalizedKey)
                } else {
                    try {
                        NamespacedKey.minecraft(normalizedKey)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                } ?: throw IllegalArgumentException("Invalid enchantment key at $path.enchants.$enchantName")
                if (!enchantmentValidator(enchantKey)) {
                    throw IllegalArgumentException("Unknown enchantment at $path.enchants.$enchantName")
                }
                enchants[enchantKey] = level
            }
        }
        return ItemDefinition(
            material = material,
            amount = amount,
            name = name,
            lore = lore.toList(),
            unbreakable = unbreakable,
            enchants = enchants.toMap(),
        )
    }

    private fun defaultEnchantmentExists(key: NamespacedKey): Boolean =
        RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key) != null

    private fun isAirMaterial(material: Material): Boolean =
        material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR

    fun matchesRule(rule: MobRule, entityType: EntityType, customNamePlain: String?): Boolean {
        if (rule.type != null && entityType != rule.type) {
            return false
        }
        return matchesName(customNamePlain, rule.nameContains)
    }

    fun matchesName(actualCustomName: String?, expectedSubstring: String?): Boolean {
        if (expectedSubstring.isNullOrBlank()) {
            return true
        }
        if (actualCustomName == null) {
            return false
        }
        val cleanActual = stripLegacyColorCodes(actualCustomName).lowercase(Locale.ROOT)
        val cleanExpected = stripLegacyColorCodes(expectedSubstring).lowercase(Locale.ROOT)
        return cleanActual.contains(cleanExpected)
    }

    fun stripLegacyColorCodes(input: String): String =
        LEGACY_COLOR_PATTERN.matcher(input).replaceAll("")

    fun rollDrops(
        rules: List<MobRule>,
        entityType: EntityType,
        customNamePlain: String?,
        random: Random = Random.Default,
    ): List<RolledDrop> {
        val rolled = ArrayList<RolledDrop>()
        for (rule in rules) {
            if (!matchesRule(rule, entityType, customNamePlain)) continue
            for (drop in rule.drops) {
                if (random.nextDouble() >= drop.chance) continue
                val amount = random.nextInt(drop.min, drop.max + 1)
                rolled.add(
                    RolledDrop(
                        material = drop.material,
                        amount = amount,
                        name = drop.name,
                        randomEnchantment = drop.randomEnchantment,
                    ),
                )
            }
        }
        return rolled
    }

    /**
     * Splits [amount] into stack sizes no larger than [maxStackSize], so oversized drop counts
     * (for example 100 arrows) become several valid stacks instead of one illegal stack.
     */
    fun splitIntoStacks(amount: Int, maxStackSize: Int): List<Int> {
        if (amount <= 0) return emptyList()
        val stackSize = maxStackSize.coerceAtLeast(1)
        val stacks = ArrayList<Int>((amount + stackSize - 1) / stackSize)
        var remaining = amount
        while (remaining > 0) {
            val next = minOf(remaining, stackSize)
            stacks.add(next)
            remaining -= next
        }
        return stacks
    }

    fun findErrorLine(exception: Throwable, configFile: File): Int {
        var cause: Throwable? = exception
        while (cause != null) {
            val matcher = LINE_NUMBER_PATTERN.matcher(cause.message.toString())
            if (matcher.find()) {
                return matcher.group(1).toInt()
            }
            cause = cause.cause
        }

        try {
            if (configFile.isFile) {
                val contents = Files.readString(configFile.toPath())
                val line = findSemanticErrorLine(exception.message, contents)
                if (line > 0) return line
            }
        } catch (_: IOException) {
            // Keep the parser's error even if the source file cannot be read again.
        }
        return -1
    }

    fun findSemanticErrorLine(message: String?, contents: String): Int {
        if (message == null) return -1
        val lines = contents.split(Regex("\\R"))

        val materialMatcher = MATERIAL_ERROR_PATTERN.matcher(message)
        if (materialMatcher.find()) {
            val needle = "material: ${materialMatcher.group(1)}"
            for (i in lines.indices) {
                if (lines[i].contains(needle)) return i + 1
            }
        }

        val enchantMatcher = ENCHANT_ERROR_PATTERN.matcher(message)
        if (enchantMatcher.find()) {
            val recipeId = enchantMatcher.group(1)
            val enchantKey = enchantMatcher.group(2)
            val recipeLine = Pattern.compile("^\\s*${Pattern.quote(recipeId)}:\\s*(?:#.*)?$")
            for (i in lines.indices) {
                if (recipeLine.matcher(lines[i]).matches()) {
                    val recipeIndent = leadingSpaces(lines[i])
                    for (j in (i + 1) until lines.size) {
                        if (lines[j].isNotBlank() && leadingSpaces(lines[j]) <= recipeIndent) break
                        if (lines[j].trim().startsWith("enchants:")) {
                            val enchantsIndent = leadingSpaces(lines[j])
                            for (k in (j + 1) until lines.size) {
                                if (lines[k].isNotBlank() && leadingSpaces(lines[k]) <= enchantsIndent) break
                                val trimmed = lines[k].trim()
                                if (trimmed.startsWith("$enchantKey:") ||
                                    trimmed.startsWith("\"$enchantKey\":") ||
                                    trimmed.startsWith("'$enchantKey':")
                                ) {
                                    return k + 1
                                }
                            }
                            return j + 1
                        }
                    }
                }
            }
        }

        val pathMatcher = PATH_ERROR_PATTERN.matcher(message)
        if (!pathMatcher.find()) return -1
        val section = pathMatcher.group(1)
        val field = pathMatcher.group(2)
        val sectionLine = Pattern.compile("^\\s*${Pattern.quote(section)}:\\s*(?:#.*)?$")

        for (i in lines.indices) {
            if (sectionLine.matcher(lines[i]).matches()) {
                val sectionIndent = leadingSpaces(lines[i])
                if (field == null) return i + 1
                for (j in (i + 1) until lines.size) {
                    if (lines[j].isNotBlank() && leadingSpaces(lines[j]) <= sectionIndent) break
                    if (lines[j].trim().startsWith("$field:")) return j + 1
                }
                return i + 1
            }
        }
        return -1
    }

    fun conciseError(exception: Throwable): String {
        val message = exception.message
        if (message.isNullOrBlank()) {
            return exception.javaClass.simpleName
        }
        return message.replace(Regex("\\s+"), " ").trim()
    }

    // Quoted numbers ("0.05") are accepted; anything non-numeric is an error instead of a silent fallback.
    private fun intValue(value: Any?, fallback: Int, path: String): Int = when (value) {
        null -> fallback
        is Int, is Long, is Short, is Byte -> (value as Number).toInt()
        is String -> value.trim().toIntOrNull()
            ?: throw IllegalArgumentException("$path must be a whole number, got '$value'")
        else -> throw IllegalArgumentException("$path must be a whole number, got '$value'")
    }

    private fun doubleValue(value: Any?, fallback: Double, path: String): Double = when (value) {
        null -> fallback
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("$path must be a number, got '$value'")
        else -> throw IllegalArgumentException("$path must be a number, got '$value'")
    }

    private fun leadingSpaces(line: String): Int {
        var count = 0
        while (count < line.length && line[count] == ' ') count++
        return count
    }
}
