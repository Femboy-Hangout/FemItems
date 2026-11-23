package dev.femitems.plugin.jukebox

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Jukebox
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * Persistent-data keys. They intentionally keep the original `femjukebox:` namespace so Custom Jukebox
 * blocks, items, and mapped discs created by the standalone FemJukebox plugin keep working.
 */
object JukeboxKeys {
    private fun key(path: String): NamespacedKey = checkNotNull(NamespacedKey.fromString("femjukebox:$path"))

    val CUSTOM_JUKEBOX = key("custom_jukebox")
    val SONG_ID = key("song_id")
    val INSERTED_DISC = key("inserted_disc")
    val INSERTED_TRACK = key("inserted_track")
    val INSERTED_TOKEN = key("inserted_token")
    val VOLUME = key("jukebox_volume")
}

object JukeboxItems {
    fun plain(text: String, color: NamedTextColor = NamedTextColor.WHITE): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    fun createJukebox(amount: Int = 1): ItemStack = ItemStack(Material.JUKEBOX, amount).apply {
        editMeta { meta ->
            meta.displayName(plain("Custom Jukebox", NamedTextColor.LIGHT_PURPLE))
            meta.lore(listOf(plain("Right-click for more options", NamedTextColor.GRAY)))
            meta.persistentDataContainer.set(JukeboxKeys.CUSTOM_JUKEBOX, PersistentDataType.BYTE, 1)
        }
    }

    fun discMaterial(song: Song): Material =
        song.discMaterial?.let(Material::matchMaterial) ?: Material.MUSIC_DISC_13

    fun createDisc(song: Song): ItemStack = ItemStack(discMaterial(song)).apply {
        editMeta { meta ->
            meta.displayName(plain("♫ ${song.title}"))
            meta.persistentDataContainer.set(JukeboxKeys.SONG_ID, PersistentDataType.STRING, song.id)
        }
    }

    fun isCustomJukeboxItem(item: ItemStack?): Boolean =
        item != null && !item.isEmpty && item.persistentDataContainer.has(JukeboxKeys.CUSTOM_JUKEBOX, PersistentDataType.BYTE)

    fun songId(item: ItemStack?): String? =
        if (item == null || item.isEmpty) null else item.persistentDataContainer.get(JukeboxKeys.SONG_ID, PersistentDataType.STRING)

    /** The song an item plays when inserted: an explicit mapping, else a configured resource-pack disc material. */
    fun trackFor(item: ItemStack?, catalog: SongCatalog): Song? {
        if (item == null || item.isEmpty) return null
        catalog.song(songId(item))?.let { return it }
        return catalog.resourcePackTrackForMaterial(item.type.name)
    }

    fun isCustomJukebox(state: Any?): Boolean =
        state is Jukebox && state.persistentDataContainer.has(JukeboxKeys.CUSTOM_JUKEBOX, PersistentDataType.BYTE)
}
