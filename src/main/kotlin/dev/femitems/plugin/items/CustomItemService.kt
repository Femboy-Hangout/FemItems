package dev.femitems.plugin.items

import dev.femitems.plugin.Messages
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Keyed
import org.bukkit.NamespacedKey
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.FurnaceSmeltEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Everything loaded from `contents/`: definitions, ready-made item stacks, and the built resource pack. */
class LoadedItems(
    val packs: List<ContentPack>,
    val definitions: Map<String, CustomItemDefinition>,
    val templates: Map<String, ItemStack>,
    val resourcePack: BuiltResourcePack?,
    val settings: ResourcePackSettings,
    val included: IncludedFiles = IncludedFiles.EMPTY,
) {
    val count: Int get() = definitions.size

    companion object {
        val EMPTY = LoadedItems(emptyList(), emptyMap(), emptyMap(), null, ResourcePackSettings())
    }
}

/**
 * Custom items (ItemsAdder-style): loads content packs, builds item stacks with their own `item_model`,
 * hosts and sends the generated resource pack, and keeps custom items out of vanilla recipes.
 *
 * Threading: [load] is thread-agnostic (runs on the AsyncScheduler during reloads); [apply] runs on the global
 * region thread; packs are sent from each player's own thread.
 */
class CustomItemService(private val plugin: JavaPlugin) : Listener {
    val itemKey = NamespacedKey(plugin, "item")
    private val packServer = PackServer(plugin.logger)

    @Volatile
    var current: LoadedItems = LoadedItems.EMPTY
        private set

    val contentsFolder: Path get() = plugin.dataFolder.toPath().resolve("contents")

    /** Loads content packs and builds item stacks plus the resource pack. Throws [ContentPackException]. */
    fun load(settings: ResourcePackSettings): LoadedItems {
        val packs = ContentPackLoader(contentsFolder, ::enchantmentExists, ::attributeExists).load()
        val definitions = LinkedHashMap<String, CustomItemDefinition>()
        for (pack in packs) {
            for (item in pack.items) definitions[item.id] = item
        }
        val templates = definitions.mapValues { (_, definition) -> build(definition) }
        val included = IncludedFiles.load(includeFolder)
        val resourcePack = if (packs.isNotEmpty() || included.files.isNotEmpty()) {
            ResourcePackBuilder.build(packs, extra = included)
        } else {
            null
        }
        // Always keep the current pack on disk: this is the file to upload for mode: external.
        Files.createDirectories(packFile.parent)
        if (resourcePack != null) Files.write(packFile, resourcePack.bytes) else Files.deleteIfExists(packFile)
        return LoadedItems(packs, definitions, templates, resourcePack, settings, included)
    }

    /** Raw pack files (music disc sounds, sounds.json, lang...) added to the built pack as-is. */
    val includeFolder: Path get() = plugin.dataFolder.toPath().resolve("resourcepack").resolve("include")

    /** The built pack, written on every load (upload this file for `mode: external`). */
    val packFile: Path get() = plugin.dataFolder.toPath().resolve("resourcepack").resolve("femitems-items.zip")

    @Volatile
    var externalStatus: ExternalPackStatus = ExternalPackStatus.NotConfigured
        private set

    /** Swaps in [loaded]; starts/stops the self-host server and re-sends a changed pack. Global region thread. */
    fun apply(loaded: LoadedItems) {
        val previous = current
        current = loaded
        val pack = loaded.resourcePack
        packServer.pack = pack
        if (pack != null && loaded.settings.mode == PackMode.SELF_HOST) {
            try {
                packServer.start(loaded.settings.selfHostPort)
            } catch (e: Exception) {
                plugin.logger.severe("Could not serve the resource pack on port ${loaded.settings.selfHostPort}: ${e.message}")
            }
        } else {
            packServer.stop()
        }
        if (pack != null) {
            val extra = loaded.included.files.size.takeIf { it > 0 }?.let { ", $it included files" }.orEmpty()
            plugin.logger.info("Built custom item resource pack (${loaded.count} items$extra, ${pack.size / 1024} KiB, sha1 ${pack.sha1}) at $packFile.")
        }
        if (loaded.included.ignored.isNotEmpty()) {
            plugin.logger.warning(
                "Ignored files in resourcepack/include (only an assets/ folder is used): ${loaded.included.ignored.joinToString(", ")}",
            )
        }
        if (loaded.settings.mode == PackMode.EXTERNAL) {
            // Until checkExternalPack verifies the link for this exact pack, nothing is sent.
            val verified = externalStatus as? ExternalPackStatus.Verified
            if (verified == null || verified.sha1 != pack?.sha1 || verified.url != loaded.settings.url) {
                externalStatus = ExternalPackStatus.Checking
            }
        } else {
            externalStatus = ExternalPackStatus.NotConfigured
            val changed = pack?.sha1 != previous.resourcePack?.sha1 || loaded.settings != previous.settings
            if (changed && previous !== LoadedItems.EMPTY) sendToOnlinePlayers()
        }
    }

    /**
     * mode: external — downloads the configured link on the AsyncScheduler and compares it with the built pack.
     * Players only get the link once it matches; [report] receives the outcome (e.g. the reloading admin).
     */
    fun checkExternalPack(report: (Component) -> Unit) {
        val loaded = current
        val pack = loaded.resourcePack
        if (loaded.settings.mode != PackMode.EXTERNAL || pack == null) return
        if (loaded.settings.url.isBlank()) {
            externalStatus = ExternalPackStatus.NotConfigured
            plugin.logger.warning("Custom item textures are not sent yet: upload $packFile (e.g. to mc-packs.net) and set resource-pack.url.")
            report(Messages.packStatus(externalStatus, packFile.fileName.toString()))
            return
        }
        externalStatus = ExternalPackStatus.Checking
        plugin.server.asyncScheduler.runNow(plugin) { _ ->
            val status = ExternalPackVerifier.verify(loaded.settings.url, pack.sha1)
            if (current !== loaded) return@runNow // a newer reload superseded this check
            val wasVerified = externalStatus is ExternalPackStatus.Verified
            externalStatus = status
            when (status) {
                is ExternalPackStatus.Verified -> plugin.logger.info("Uploaded resource pack verified at ${status.url}.")
                is ExternalPackStatus.Outdated -> plugin.logger.warning(
                    "The pack at ${status.url} is outdated (sha1 ${status.remoteSha1}, expected ${pack.sha1}); " +
                        "upload the new $packFile and update resource-pack.url. Players are not sent the old link.",
                )
                is ExternalPackStatus.Unreachable -> plugin.logger.warning("Could not download the pack from ${status.url}: ${status.reason}.")
                else -> Unit
            }
            report(Messages.packStatus(status, packFile.fileName.toString()))
            if (status is ExternalPackStatus.Verified && !wasVerified) sendToOnlinePlayers()
        }
    }

    private fun sendToOnlinePlayers() {
        for (player in plugin.server.onlinePlayers) {
            player.scheduler.run(plugin, { sendPack(player) }, null)
        }
    }

    fun shutdown() {
        packServer.stop()
    }

    fun create(id: String, amount: Int = 1): ItemStack? =
        current.templates[id.lowercase()]?.clone()?.also { it.amount = amount }

    fun idOf(item: ItemStack?): String? =
        if (item == null || item.isEmpty) null else item.persistentDataContainer.get(itemKey, PersistentDataType.STRING)

    /** Sends the custom item pack to [player] (call on the player's thread). */
    fun sendPack(player: Player) {
        val loaded = current
        val pack = loaded.resourcePack ?: return
        val url = when (loaded.settings.mode) {
            PackMode.OFF -> return
            PackMode.SELF_HOST -> if (packServer.running) loaded.settings.selfHostUrlFor(pack, player.virtualHost?.hostString) else return
            // Only a link verified to serve exactly this pack is ever sent.
            PackMode.EXTERNAL -> (externalStatus as? ExternalPackStatus.Verified)?.takeIf { it.sha1 == pack.sha1 }?.url ?: return
        }
        val info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url), pack.sha1)
        player.sendResourcePacks(
            ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .required(loaded.settings.required)
                .prompt(legacy(loaded.settings.prompt))
                .replace(false),
        )
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        sendPack(event.player)
    }

    /** Custom items keep their base material, so stop them from working in vanilla recipes for that material. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareCraft(event: PrepareItemCraftEvent) {
        val recipe = event.recipe ?: return
        if ((recipe as? Keyed)?.key?.namespace == plugin.name.lowercase()) return
        if (event.inventory.matrix.any { idOf(it) != null }) event.inventory.result = null
    }

    @EventHandler(ignoreCancelled = true)
    fun onSmelt(event: FurnaceSmeltEvent) {
        if (event.recipe?.key?.namespace == plugin.name.lowercase()) return
        if (idOf(event.source) != null) event.isCancelled = true
    }

    private fun build(definition: CustomItemDefinition): ItemStack {
        val item = ItemStack(definition.material)
        item.editMeta { meta ->
            meta.itemModel = NamespacedKey(definition.namespace, definition.key)
            meta.persistentDataContainer.set(itemKey, PersistentDataType.STRING, definition.id)
            definition.name?.let { meta.displayName(legacy(it)) }
            if (definition.lore.isNotEmpty()) meta.lore(definition.lore.map(::legacy))
            if (definition.unbreakable) meta.isUnbreakable = true
            definition.maxStackSize?.let { meta.setMaxStackSize(it) }
            if (definition.maxDurability != null && meta is Damageable) meta.setMaxDamage(definition.maxDurability)
            if (definition.enchants.isNotEmpty()) {
                val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                for ((key, level) in definition.enchants) {
                    val enchantment = registry.get(key) ?: continue
                    if (meta is EnchantmentStorageMeta) meta.addStoredEnchant(enchantment, level, true)
                    else meta.addEnchant(enchantment, level, true)
                }
            }
            val equipment = definition.equipment
            val slot = definition.armorSlot
            if (equipment != null && slot != null) {
                val equippable = meta.equippable
                equippable.slot = equipmentSlot(slot)
                equippable.model = NamespacedKey.fromString(equipment)
                meta.setEquippable(equippable)
            }
        }
        applyAttributes(item, definition)
        return item
    }

    /**
     * Starts from the base item's own modifiers (a diamond sword's 7 damage / 1.6 speed, a helmet's armour),
     * replaces those whose attribute has a headline stat, then adds the extra `attributes` on top.
     * Uses the data component directly: ItemMeta modifiers would silently drop every default.
     */
    @Suppress("UnstableApiUsage")
    private fun applyAttributes(item: ItemStack, definition: CustomItemDefinition) {
        if (definition.stats.isEmpty() && definition.attributes.isEmpty()) return
        val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE)
        val naturalGroup = definition.armorSlot?.let(::slotGroup) ?: EquipmentSlotGroup.MAINHAND
        val defaults = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS)?.modifiers().orEmpty()
        val statAttributes = definition.stats.keys.mapNotNull { registry.get(NamespacedKey.minecraft(it.attribute)) }.toSet()
        val builder = ItemAttributeModifiers.itemAttributes()
        for (entry in defaults) {
            if (entry.attribute() !in statAttributes) builder.addModifier(entry.attribute(), entry.modifier(), entry.group, entry.display())
        }
        for ((stat, value) in definition.stats) {
            val attribute = registry.get(NamespacedKey.minecraft(stat.attribute)) ?: continue
            val original = defaults.firstOrNull { it.attribute() == attribute }
            // Vanilla's own ids make the tooltip show the total ("12 Attack Damage") like a normal weapon.
            val key = when (stat) {
                ItemStat.ATTACK_DAMAGE -> NamespacedKey.minecraft("base_attack_damage")
                ItemStat.ATTACK_SPEED -> NamespacedKey.minecraft("base_attack_speed")
                else -> original?.modifier()?.key ?: NamespacedKey(plugin, "${definition.namespace}.${definition.key}.${stat.attribute}")
            }
            val group = original?.group ?: naturalGroup
            builder.addModifier(attribute, AttributeModifier(key, stat.modifierAmount(value), AttributeModifier.Operation.ADD_NUMBER, group), group)
        }
        for (spec in definition.attributes) {
            val attribute = registry.get(spec.attribute) ?: continue
            val group = spec.slot?.let(EquipmentSlotGroup::getByName) ?: naturalGroup
            val modifierKey = NamespacedKey(plugin, "${definition.namespace}.${definition.key}.${spec.attribute.key}")
            builder.addModifier(attribute, AttributeModifier(modifierKey, spec.amount, operation(spec.operation), group), group)
        }
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build())
    }

    private fun enchantmentExists(key: NamespacedKey): Boolean =
        RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key) != null

    private fun attributeExists(key: NamespacedKey): Boolean =
        RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE).get(key) != null

    companion object {
        /** Stable id: a re-sent pack replaces the previous FemItems pack on the client instead of stacking. */
        val PACK_ID: UUID = UUID.nameUUIDFromBytes("femitems:custom-items".toByteArray())

        fun legacy(text: String): Component =
            LegacyComponentSerializer.legacyAmpersand().deserialize(text)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

        private fun equipmentSlot(slot: ArmorSlot): EquipmentSlot = when (slot) {
            ArmorSlot.HEAD -> EquipmentSlot.HEAD
            ArmorSlot.CHEST -> EquipmentSlot.CHEST
            ArmorSlot.LEGS -> EquipmentSlot.LEGS
            ArmorSlot.FEET -> EquipmentSlot.FEET
        }

        private fun operation(operation: ModifierOperation): AttributeModifier.Operation = when (operation) {
            ModifierOperation.ADD -> AttributeModifier.Operation.ADD_NUMBER
            ModifierOperation.MULTIPLY_BASE -> AttributeModifier.Operation.ADD_SCALAR
            ModifierOperation.MULTIPLY_TOTAL -> AttributeModifier.Operation.MULTIPLY_SCALAR_1
        }

        private fun slotGroup(slot: ArmorSlot): EquipmentSlotGroup = when (slot) {
            ArmorSlot.HEAD -> EquipmentSlotGroup.HEAD
            ArmorSlot.CHEST -> EquipmentSlotGroup.CHEST
            ArmorSlot.LEGS -> EquipmentSlotGroup.LEGS
            ArmorSlot.FEET -> EquipmentSlotGroup.FEET
        }
    }
}
