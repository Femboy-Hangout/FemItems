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
)

data class MobRule(
    val id: String,
    val type: EntityType?,
    val nameContains: String,
    val drops: List<DropRule>,
)

data class ItemDefinition(
    val material: Material,
    val amount: Int,
    val name: String? = null,
    val lore: List<String> = emptyList(),
    val unbreakable: Boolean = false,
    val enchants: Map<NamespacedKey, Int> = emptyMap(),
)

sealed interface RecipeDefinition {
    val id: String
    val result: ItemDefinition
}

data class ShapedRecipeDefinition(
    override val id: String,
    override val result: ItemDefinition,
    val shape: List<String>,
    val ingredients: Map<Char, Material>,
) : RecipeDefinition

data class ShapelessRecipeDefinition(
    override val id: String,
    override val result: ItemDefinition,
    val ingredients: List<Material>,
) : RecipeDefinition

data class ParsedConfig(
    val mobRules: List<MobRule>,
    val recipes: List<RecipeDefinition>,
)

data class RolledDrop(
    val material: Material,
    val amount: Int,
    val name: String? = null,
)
