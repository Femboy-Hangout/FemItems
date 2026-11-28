package dev.femitems.plugin.items

import dev.femitems.plugin.ConfigParser
import dev.femitems.plugin.Messages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale

/** One browser category: its display [name] and the item ids in it, in items.yml order. */
data class ItemCategory(val name: String, val ids: List<String>)

/** Pure grouping and paging for the item browser (unit-tested without a server). */
object ItemCategories {
    const val WEAPONS = "Weapons"
    const val TOOLS = "Tools"
    const val ARMOR = "Armor"
    const val OTHER = "Other"

    /** Items per category page: five rows, with the bottom row for navigation. */
    const val PAGE_SIZE = 45

    private val WEAPON_SUFFIXES = listOf("_SWORD", "_SPEAR")
    private val WEAPON_MATERIALS = setOf(Material.MACE, Material.TRIDENT, Material.BOW, Material.CROSSBOW)
    private val TOOL_SUFFIXES = listOf("_PICKAXE", "_AXE", "_SHOVEL", "_HOE")
    private val TOOL_MATERIALS = setOf(Material.SHEARS, Material.FISHING_ROD, Material.FLINT_AND_STEEL, Material.BRUSH)

    /** The automatic category for items without `category:`, from what the base material is. */
    fun automatic(definition: CustomItemDefinition): String {
        val material = definition.material
        val name = material.name
        return when {
            definition.armorSlot != null || material == Material.SHIELD || material == Material.ELYTRA -> ARMOR
            material in WEAPON_MATERIALS || WEAPON_SUFFIXES.any(name::endsWith) -> WEAPONS
            material in TOOL_MATERIALS || TOOL_SUFFIXES.any(name::endsWith) -> TOOLS
            else -> OTHER
        }
    }

    /**
     * Weapons, Tools and Armor first, then custom categories in the order they first appear, then Other.
     * Custom names match case-insensitively; the first spelling seen is shown.
     */
    fun group(definitions: Collection<CustomItemDefinition>): List<ItemCategory> {
        val names = LinkedHashMap<String, String>()
        val members = LinkedHashMap<String, MutableList<String>>()
        for (definition in definitions) {
            val name = definition.category ?: automatic(definition)
            val key = name.lowercase(Locale.ROOT)
            names.putIfAbsent(key, name)
            members.getOrPut(key) { ArrayList() } += definition.id
        }
        val fixedFirst = listOf(WEAPONS, TOOLS, ARMOR).map { it.lowercase(Locale.ROOT) }
        val order = fixedFirst.filter(members::containsKey) +
            members.keys.filter { it !in fixedFirst && it != OTHER.lowercase(Locale.ROOT) } +
            listOfNotNull(OTHER.lowercase(Locale.ROOT).takeIf(members::containsKey))
        return order.map { ItemCategory(names.getValue(it), members.getValue(it)) }
    }

    fun pageCount(itemCount: Int): Int = maxOf(1, (itemCount + PAGE_SIZE - 1) / PAGE_SIZE)

    /** The ids on [page] (0-based, clamped to the last page). */
    fun page(ids: List<String>, page: Int): List<String> {
        val clamped = page.coerceIn(0, pageCount(ids.size) - 1)
        return ids.drop(clamped * PAGE_SIZE).take(PAGE_SIZE)
    }
}

/**
^ * `/femitems gui`: a chest GUI listing every custom item by category. Players with `femitems.give`
 * take items by clicking (left: one, shift: a stack); everyone else can only look.
 *
 * Threading: opened and clicked on the viewing player's own thread; item data is read from the immutable
 * [CustomItemService.current] snapshot, so a reload while the menu is open is safe.
 */
class ItemBrowser(private val plugin: JavaPlugin, private val items: CustomItemService) : Listener {

    private sealed interface Action {
        data class OpenCategory(val name: String, val page: Int) : Action
        data object Home : Action
        data class Take(val id: String) : Action
    }

    private class Holder : InventoryHolder {
        val actions = HashMap<Int, Action>()
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    /** Opens the category overview; call on [player]'s thread. */
    fun openHome(player: Player) {
        val categories = ItemCategories.group(items.current.definitions.values)
        if (categories.isEmpty()) {
            player.sendMessage(Messages.customItems(emptyList()))
            return
        }
        val size = (((categories.size + 8) / 9) * 9).coerceIn(9, 54)
        val holder = Holder()
        val inventory = Bukkit.createInventory(holder, size, title("Custom items"))
        holder.backing = inventory
        categories.take(size).forEachIndexed { slot, category ->
            val icon = items.create(category.ids.first()) ?: ItemStack(Material.CHEST)
            icon.amount = 1
            icon.editMeta { meta ->
                meta.displayName(plain(category.name, NamedTextColor.LIGHT_PURPLE))
                meta.lore(listOf(plain("${category.ids.size} item${if (category.ids.size == 1) "" else "s"}", NamedTextColor.GRAY)))
            }
            inventory.setItem(slot, icon)
            holder.actions[slot] = Action.OpenCategory(category.name, 0)
        }
        player.openInventory(inventory)
    }

    private fun openCategory(player: Player, name: String, requestedPage: Int) {
        val category = ItemCategories.group(items.current.definitions.values)
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return openHome(player) // removed by a reload
        val pages = ItemCategories.pageCount(category.ids.size)
        val page = requestedPage.coerceIn(0, pages - 1)
        val holder = Holder()
        val suffix = if (pages > 1) " (${page + 1}/$pages)" else ""
        val inventory = Bukkit.createInventory(holder, 54, title("Custom items • ${category.name}$suffix"))
        holder.backing = inventory
        val canTake = player.hasPermission(GIVE_PERMISSION)
        ItemCategories.page(category.ids, page).forEachIndexed { slot, id ->
            val icon = items.create(id) ?: return@forEachIndexed
            icon.amount = 1
            icon.editMeta { meta ->
                val lore = ArrayList(meta.lore().orEmpty())
                lore += Component.empty()
                lore += plain(id, NamedTextColor.DARK_GRAY)
                if (canTake) lore += plain("Click: take 1 · Shift-click: take a stack", NamedTextColor.GRAY)
                meta.lore(lore)
            }
            inventory.setItem(slot, icon)
            if (canTake) holder.actions[slot] = Action.Take(id)
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "Back to categories"))
        holder.actions[BACK_SLOT] = Action.Home
        if (page > 0) {
            inventory.setItem(PREVIOUS_SLOT, button(Material.SPECTRAL_ARROW, "Previous page"))
            holder.actions[PREVIOUS_SLOT] = Action.OpenCategory(category.name, page - 1)
        }
        if (page < pages - 1) {
            inventory.setItem(NEXT_SLOT, button(Material.SPECTRAL_ARROW, "Next page"))
            holder.actions[NEXT_SLOT] = Action.OpenCategory(category.name, page + 1)
        }
        player.openInventory(inventory)
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.getHolder(false) as? Holder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != event.view.topInventory) return
        when (val action = holder.actions[event.slot] ?: return) {
            Action.Home -> openHome(player)
            is Action.OpenCategory -> openCategory(player, action.name, action.page)
            is Action.Take -> take(player, action.id, stack = event.click == ClickType.SHIFT_LEFT || event.click == ClickType.SHIFT_RIGHT)
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.getHolder(false) is Holder) event.isCancelled = true
    }

    private fun take(player: Player, id: String, stack: Boolean) {
        if (!player.hasPermission(GIVE_PERMISSION)) return // permission removed while the menu was open
        val template = items.create(id) ?: return player.sendMessage(Messages.unknownCustomItem(id))
        val amount = if (stack) template.maxStackSize else 1
        for (size in ConfigParser.splitIntoStacks(amount, template.maxStackSize)) {
            val item = template.clone().apply { this.amount = size }
            player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
        player.sendMessage(Messages.gaveCustomItem(amount, id, player.name))
    }

    private fun button(material: Material, name: String): ItemStack =
        ItemStack(material).apply { editMeta { it.displayName(plain(name, NamedTextColor.YELLOW)) } }

    private fun title(text: String): Component = Component.text(text, NamedTextColor.DARK_PURPLE)

    private fun plain(text: String, color: NamedTextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    companion object {
        const val GIVE_PERMISSION = "femitems.give"
        private const val BACK_SLOT = 45
        private const val PREVIOUS_SLOT = 48
        private const val NEXT_SLOT = 50
    }
}
