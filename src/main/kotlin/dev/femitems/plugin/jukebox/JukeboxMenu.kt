package dev.femitems.plugin.jukebox

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Jukebox
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom Jukebox blocks and the jukebox GUI.
 *
 * Threading: block events run on the block's region thread, so they read and write the jukebox's
 * persistent data directly. GUI clicks run on the clicking player's thread; anything touching the jukebox
 * block from there hops to the block's `RegionScheduler`, and results come back via the player's
 * `EntityScheduler`. Playback changes go through [MusicService], which hops to the global region.
 */
class JukeboxMenu(
    private val plugin: JavaPlugin,
    private val music: MusicService,
    private val legacyDiscs: DepositedDiscStore,
) : Listener {

    enum class Page(val title: String) {
        HOME("home"),
        SONGS("Songs"),
        CUSTOM_DISCS("Custom discs"),
        DISCS("Discs"),
    }

    sealed interface MenuAction {
        data class Open(val page: Page) : MenuAction
        data class PlaySong(val songId: String, val adminOnly: Boolean) : MenuAction
        data object TogglePause : MenuAction
        data object ToggleShuffle : MenuAction
        data object Stop : MenuAction
        data class Volume(val delta: Float) : MenuAction
        data object Eject : MenuAction
        data class TakeLegacyDisc(val songId: String) : MenuAction
    }

    /** Identifies our inventories (instead of matching titles) and remembers the jukebox and slot actions. */
    class Holder(val jukebox: BlockPos?, val page: Page) : InventoryHolder {
        val actions = HashMap<Int, MenuAction>()
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    /** What the GUI needs to know about a jukebox block; read on the block's region thread. */
    data class JukeboxSnapshot(val volume: Float, val inserted: ItemStack?, val trackId: String?)

    init {
        music.discEjector = { pos, token -> onRegion(pos) { block -> ejectIfToken(block, token) } }
    }

    // ---------------------------------------------------------------- GUI

    /** Opens the GUI for [player]; call on the player's thread. */
    fun open(player: Player, jukebox: BlockPos?, page: Page = Page.HOME) {
        if (jukebox == null) {
            show(player, null, page, null)
            return
        }
        onRegion(jukebox) { block ->
            val snapshot = snapshot(block) ?: return@onRegion
            player.scheduler.run(plugin, { show(player, jukebox, page, snapshot) }, null)
        }
    }

    private fun show(player: Player, jukebox: BlockPos?, page: Page, snapshot: JukeboxSnapshot?) {
        val holder = Holder(jukebox, page)
        val inventory = Bukkit.createInventory(holder, 27, JukeboxItems.plain("Custom Jukebox • ${page.title}", NamedTextColor.LIGHT_PURPLE))
        holder.backing = inventory
        fun put(slot: Int, item: ItemStack, action: MenuAction?) {
            inventory.setItem(slot, item)
            if (action != null) holder.actions[slot] = action
        }
        val admin = player.hasPermission(JukeboxFeature.ADMIN_PERMISSION)
        when (page) {
            Page.HOME -> {
                val current = if (jukebox != null) music.currentAt(jukebox) else music.current(player.uniqueId)
                put(10, button(Material.MUSIC_DISC_13, current?.let { "Playing: ${it.title}" } ?: "Nothing playing",
                    "Click to view deposited discs"), MenuAction.Open(Page.DISCS))
                put(11, button(Material.BOOK, "Browse songs"), MenuAction.Open(Page.SONGS))
                put(12, button(Material.REDSTONE, "Pause / resume"), MenuAction.TogglePause)
                put(13, button(Material.COMPASS, "Toggle shuffle",
                    "Currently ${if (music.isShuffling(player.uniqueId)) "on" else "off"}"), MenuAction.ToggleShuffle)
                put(14, button(Material.BARRIER, "Stop"), MenuAction.Stop)
                if (admin) put(15, button(Material.MUSIC_DISC_PIGSTEP, "Custom discs (admin)"), MenuAction.Open(Page.CUSTOM_DISCS))
                val volume = snapshot?.volume ?: music.personalVolume(player.uniqueId)
                val label = "${Math.round(volume * 100f)}%"
                put(16, button(Material.MAGMA_CREAM, "Volume - ($label)"), MenuAction.Volume(-0.1f))
                put(17, button(Material.GLOWSTONE_DUST, "Volume + ($label)"), MenuAction.Volume(0.1f))
            }

            Page.SONGS, Page.CUSTOM_DISCS -> {
                val adminOnly = page == Page.CUSTOM_DISCS
                val songs = if (adminOnly) music.catalog.customDiscSongs else music.catalog.noteSongs
                for ((slot, song) in songs.take(26).withIndex()) {
                    put(slot, ItemStack(JukeboxItems.discMaterial(song)).apply {
                        editMeta { it.displayName(JukeboxItems.plain(song.title)) }
                    }, MenuAction.PlaySong(song.id, adminOnly))
                }
                put(26, button(Material.ARROW, "Back"), MenuAction.Open(Page.HOME))
            }

            Page.DISCS -> {
                val inserted = snapshot?.inserted
                val legacy = legacyDiscs.get(player.uniqueId)
                when {
                    inserted != null -> put(13, inserted.clone().apply {
                        editMeta { it.lore(listOf(JukeboxItems.plain("Click to eject this disc", NamedTextColor.GRAY))) }
                    }, MenuAction.Eject)

                    legacy.isNotEmpty() -> legacy.mapNotNull(music.catalog::song).take(26).forEachIndexed { slot, song ->
                        put(slot, JukeboxItems.createDisc(song), MenuAction.TakeLegacyDisc(song.id))
                    }

                    else -> put(13, button(Material.PAPER, "No disc inserted"), null)
                }
                put(26, button(Material.ARROW, "Back"), MenuAction.Open(Page.HOME))
            }
        }
        player.openInventory(inventory)
    }

    private fun button(material: Material, name: String, lore: String? = null): ItemStack = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(JukeboxItems.plain(name))
            if (lore != null) meta.lore(listOf(JukeboxItems.plain(lore, NamedTextColor.GRAY)))
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.getHolder(false) as? Holder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != event.view.topInventory) return
        val action = holder.actions[event.slot] ?: return
        val jukebox = holder.jukebox
        when (action) {
            is MenuAction.Open -> open(player, jukebox, action.page)
            MenuAction.TogglePause -> music.togglePause(player, jukebox)
            MenuAction.ToggleShuffle -> {
                music.toggleShuffle(player)
                open(player, jukebox, Page.HOME)
            }
            MenuAction.Stop -> music.stop(player, jukebox)
            is MenuAction.Volume -> changeVolume(player, jukebox, action.delta)
            is MenuAction.PlaySong -> {
                player.closeInventory()
                if (action.adminOnly && !player.hasPermission(JukeboxFeature.ADMIN_PERMISSION)) return
                val song = music.catalog.song(action.songId) ?: return player.sendMessage(JukeboxMessages.unknownSong())
                if (jukebox == null) {
                    music.play(player, song)
                } else {
                    // Choosing a song at a jukebox replaces (ejects) whatever disc it holds.
                    onRegion(jukebox) { block ->
                        val state = block.state as? Jukebox ?: return@onRegion
                        if (!JukeboxItems.isCustomJukebox(state)) return@onRegion
                        takeDisc(block)?.let { ejectOnTop(block, it) }
                        music.play(player, song, JukeboxStart(jukebox, volumeOf(state), null))
                    }
                }
            }
            MenuAction.Eject -> {
                player.closeInventory()
                if (jukebox == null) return
                onRegion(jukebox) { block ->
                    val disc = takeDisc(block)
                    music.stop(player, jukebox, quiet = true)
                    if (disc != null) player.scheduler.run(plugin, { give(player, disc) }, null)
                }
            }
            is MenuAction.TakeLegacyDisc -> {
                player.closeInventory()
                val song = music.catalog.song(action.songId) ?: return
                if (legacyDiscs.remove(player.uniqueId, action.songId)) give(player, JukeboxItems.createDisc(song))
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.getHolder(false) is Holder) event.isCancelled = true
    }

    private fun changeVolume(player: Player, jukebox: BlockPos?, delta: Float) {
        if (jukebox == null) {
            music.adjustPersonalVolume(player, delta)
            open(player, null, Page.HOME)
            return
        }
        onRegion(jukebox) { block ->
            val state = block.state as? Jukebox ?: return@onRegion
            if (!JukeboxItems.isCustomJukebox(state)) return@onRegion
            val value = SoundResolver.stepVolume(volumeOf(state), delta)
            state.persistentDataContainer.set(JukeboxKeys.VOLUME, PersistentDataType.FLOAT, value)
            state.update(true, false)
            music.applyJukeboxVolume(jukebox, value)
            player.sendMessage(JukeboxMessages.volume(value))
            val snapshot = snapshot(block) ?: return@onRegion
            player.scheduler.run(plugin, { show(player, jukebox, Page.HOME, snapshot) }, null)
        }
    }

    // ---------------------------------------------------------------- block events (block's region thread)

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (!JukeboxItems.isCustomJukeboxItem(event.itemInHand)) return
        val state = event.blockPlaced.state as? Jukebox ?: return
        state.persistentDataContainer.set(JukeboxKeys.CUSTOM_JUKEBOX, PersistentDataType.BYTE, 1)
        state.update(true, false)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.JUKEBOX) return
        val state = block.state as? Jukebox ?: return
        if (!JukeboxItems.isCustomJukebox(state)) return
        if (event.useInteractedBlock() == Event.Result.DENY) return // e.g. claimed by a protection plugin
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        // Handle the main-hand event only (it fires first), using a disc from either hand.
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val pos = BlockPos.of(block)
        val snapshot = snapshot(state)
        val inventory = player.inventory
        val (hand, track) = listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)
            .firstNotNullOfOrNull { slot -> JukeboxItems.trackFor(inventory.getItem(slot), music.catalog)?.let { slot to it } }
            ?: (null to null)
        // An already inserted disc is never replaced by clicking; the GUI's Discs page ejects it.
        if (snapshot.inserted != null || hand == null || track == null) {
            show(player, pos, Page.HOME, snapshot)
            return
        }

        val held = inventory.getItem(hand)
        val token = UUID.randomUUID().toString()
        val pdc = state.persistentDataContainer
        pdc.set(JukeboxKeys.INSERTED_DISC, PersistentDataType.BYTE_ARRAY, held.asOne().serializeAsBytes())
        pdc.set(JukeboxKeys.INSERTED_TRACK, PersistentDataType.STRING, track.id)
        pdc.set(JukeboxKeys.INSERTED_TOKEN, PersistentDataType.STRING, token)
        state.update(true, false)
        if (player.gameMode != GameMode.CREATIVE) inventory.setItem(hand, held.subtract())
        music.play(player, track, JukeboxStart(pos, snapshot.volume, token))
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        if (block.type != Material.JUKEBOX) return
        val state = block.state as? Jukebox ?: return
        if (!JukeboxItems.isCustomJukebox(state)) return
        val pos = BlockPos.of(block)
        music.stopAt(pos)
        closeViewers(pos)
        event.isDropItems = false
        if (event.player.gameMode != GameMode.CREATIVE) dropAt(block, JukeboxItems.createJukebox())
        takeDisc(block)?.let { dropAt(block, it) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        music.playerQuit(event.player.uniqueId)
    }

    private fun closeViewers(pos: BlockPos) {
        for (viewer in Bukkit.getOnlinePlayers()) {
            viewer.scheduler.run(plugin, {
                val holder = viewer.openInventory.topInventory.getHolder(false) as? Holder
                if (holder?.jukebox == pos) viewer.closeInventory()
            }, null)
        }
    }

    // ---------------------------------------------------------------- jukebox block data (region thread)

    private fun onRegion(pos: BlockPos, action: (Block) -> Unit) {
        val location = pos.location() ?: return
        if (!plugin.isEnabled) return
        plugin.server.regionScheduler.execute(plugin, location) { action(location.block) }
    }

    private fun snapshot(block: Block): JukeboxSnapshot? =
        (block.state as? Jukebox)?.takeIf(JukeboxItems::isCustomJukebox)?.let(::snapshot)

    private fun snapshot(state: Jukebox): JukeboxSnapshot {
        val pdc = state.persistentDataContainer
        return JukeboxSnapshot(volumeOf(state), readDisc(state), pdc.get(JukeboxKeys.INSERTED_TRACK, PersistentDataType.STRING))
    }

    private fun volumeOf(state: Jukebox): Float =
        state.persistentDataContainer.get(JukeboxKeys.VOLUME, PersistentDataType.FLOAT) ?: music.defaultVolume

    private fun readDisc(state: Jukebox): ItemStack? {
        val bytes = state.persistentDataContainer.get(JukeboxKeys.INSERTED_DISC, PersistentDataType.BYTE_ARRAY) ?: return null
        return try {
            ItemStack.deserializeBytes(bytes)
        } catch (e: IllegalArgumentException) {
            plugin.logger.warning("Could not read inserted custom disc: ${e.message}")
            null
        }
    }

    /** Removes and returns the disc stored in a custom jukebox, if any. */
    private fun takeDisc(block: Block): ItemStack? {
        val state = block.state as? Jukebox ?: return null
        val disc = readDisc(state)
        val pdc = state.persistentDataContainer
        if (disc == null && !pdc.has(JukeboxKeys.INSERTED_DISC)) return null
        pdc.remove(JukeboxKeys.INSERTED_DISC)
        pdc.remove(JukeboxKeys.INSERTED_TRACK)
        pdc.remove(JukeboxKeys.INSERTED_TOKEN)
        state.update(true, false)
        return disc
    }

    /** Ejects the stored disc only if it is still the one inserted for the playback that ended. */
    private fun ejectIfToken(block: Block, token: String) {
        val state = block.state as? Jukebox ?: return
        if (state.persistentDataContainer.get(JukeboxKeys.INSERTED_TOKEN, PersistentDataType.STRING) != token) return
        takeDisc(block)?.let { ejectOnTop(block, it) }
    }

    private fun dropAt(block: Block, item: ItemStack) {
        block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), item)
    }

    /** Pops an ejected disc out on top of the jukebox, like a vanilla jukebox does. */
    private fun ejectOnTop(block: Block, item: ItemStack) {
        block.world.dropItemNaturally(block.location.add(0.5, 1.01, 0.5), item)
    }

    /** Runs on [player]'s thread. */
    private fun give(player: Player, item: ItemStack) {
        player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
    }
}

/**
 * Read-only compatibility for `deposited-discs.yml` from early FemJukebox versions (per-player lists of
 * song IDs). Players can still withdraw those discs from the GUI's Discs page; nothing new is added.
 */
class DepositedDiscStore(private val file: File, private val plugin: JavaPlugin) {
    private val discs = ConcurrentHashMap<UUID, List<String>>()

    fun load() {
        discs.clear()
        if (!file.isFile) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        for (key in yaml.getKeys(false)) {
            val id = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
            yaml.getStringList(key).takeIf(List<String>::isNotEmpty)?.let { discs[id] = it }
        }
    }

    fun get(player: UUID): List<String> = discs[player].orEmpty()

    fun remove(player: UUID, songId: String): Boolean {
        var removed = false
        discs.computeIfPresent(player) { _, list ->
            val index = list.indexOf(songId)
            if (index < 0) return@computeIfPresent list
            removed = true
            (list.take(index) + list.drop(index + 1)).ifEmpty { null }
        }
        if (removed) save()
        return removed
    }

    private fun save() {
        val snapshot = discs.toMap()
        plugin.server.asyncScheduler.runNow(plugin) {
            synchronized(this) {
                val yaml = YamlConfiguration()
                for ((id, list) in snapshot) yaml.set(id.toString(), list)
                runCatching { yaml.save(file) }.onFailure { plugin.logger.severe("Could not save deposited discs: ${it.message}") }
            }
        }
    }
}
