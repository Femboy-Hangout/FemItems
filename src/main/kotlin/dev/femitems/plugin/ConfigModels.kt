package dev.femitems.plugin

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.EntityType

data class DropRule(
    val material: Material,
    val min: Int,
    val max: Int,
    val chance: Double,
    val name: String? = null,
    val randomEnchantment: Boolean = false,
    /** Custom item id (`namespace:key`) to drop instead of a plain [material] stack. */
    val customItem: String? = null,
)

data class MobRule(
    val id: String,
    val type: EntityType?,
    val nameContains: String,
    val entityTag: String,
    val drops: List<DropRule>,
)

data class ItemDefinition(
    val material: Material,
    val amount: Int,
    val name: String? = null,
    val lore: List<String> = emptyList(),
    val unbreakable: Boolean = false,
    val enchants: Map<NamespacedKey, Int> = emptyMap(),
    /** Custom item id (`namespace:key`); when set, the result is that custom item and [material] is its base. */
    val customItem: String? = null,
)

/** A recipe ingredient: a vanilla material, or an exact custom item. */
sealed interface Ingredient {
    data class Vanilla(val material: Material) : Ingredient
    data class Custom(val id: String) : Ingredient
}

/** Cooking stations a smelting recipe registers for. */
enum class CookingMachine(val configName: String, val keySuffix: String) {
    FURNACE("furnace", ""),
    BLAST_FURNACE("blast_furnace", "_blast_furnace"),
    SMOKER("smoker", "_smoker"),
    CAMPFIRE("campfire", "_campfire"),
}

sealed interface RecipeDefinition {
    val id: String
    val result: ItemDefinition
}

data class ShapedRecipeDefinition(
    override val id: String,
    override val result: ItemDefinition,
    val shape: List<String>,
    val ingredients: Map<Char, Ingredient>,
) : RecipeDefinition

data class ShapelessRecipeDefinition(
    override val id: String,
    override val result: ItemDefinition,
    val ingredients: List<Ingredient>,
) : RecipeDefinition

/** Furnace / blast furnace / smoker / campfire recipe; one server recipe is registered per machine. */
data class CookingRecipeDefinition(
    override val id: String,
    override val result: ItemDefinition,
    val ingredient: Ingredient,
    val machines: List<CookingMachine>,
    val experience: Float,
    val cookTime: Int,
) : RecipeDefinition

data class ParsedConfig(
    val mobRules: List<MobRule>,
    val recipes: List<RecipeDefinition>,
)

data class RolledDrop(
    val material: Material,
    val amount: Int,
    val name: String? = null,
    val randomEnchantment: Boolean = false,
    val customItem: String? = null,
)
