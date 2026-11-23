package dev.femitems.plugin.jukebox

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.configuration.ConfigurationSection
import java.util.Locale

/** Immutable snapshot of `jukebox/config.yml`. */
data class JukeboxSettings(
    val bossBar: Boolean = true,
    val bossBarColor: BossBar.Color = BossBar.Color.PURPLE,
    val radius: Double = 32.0,
    val defaultVolume: Float = 1f,
    val tickInterval: Long = 1,
) {
    companion object {
        fun from(config: ConfigurationSection): JukeboxSettings {
            val colorName = config.getString("jukebox.bossbar-color", "PURPLE").orEmpty().trim()
            val color = BossBar.Color.NAMES.value(colorName.lowercase(Locale.ROOT)) ?: BossBar.Color.PURPLE
            return JukeboxSettings(
                bossBar = config.getBoolean("jukebox.bossbar", true),
                bossBarColor = color,
                radius = config.getDouble("jukebox.radius", 32.0).coerceAtLeast(0.0),
                defaultVolume = config.getDouble("playback.default-volume", 1.0).toFloat().coerceIn(0f, 2f),
                tickInterval = config.getLong("playback.tick-interval", 1).coerceIn(1, 20),
            )
        }
    }
}

/**
 * Pure song clock: advances one playback position by [serverTicks] server ticks and returns the notes that
 * became due. Kept free of server calls so it can be unit-tested.
 */
class SongClock(val song: Song) {
    var position = 0.0
        private set
    var noteIndex = 0
        private set

    val tick: Int get() = position.toInt()
    val finished: Boolean get() = tick >= song.lengthTicks

    /** Remaining-progress fraction for the bossbar (1 = just started, 0 = finished). */
    val remaining: Float
        get() = if (song.lengthTicks <= 0) 0f else (1f - tick / song.lengthTicks.toFloat()).coerceIn(0f, 1f)

    fun advance(serverTicks: Long): List<Note> {
        position += song.tempo / 20.0 * serverTicks
        val now = tick
        if (noteIndex >= song.notes.size || song.notes[noteIndex].tick > now) return emptyList()
        val due = ArrayList<Note>()
        while (noteIndex < song.notes.size && song.notes[noteIndex].tick <= now) due += song.notes[noteIndex++]
        return due
    }

    fun reset() {
        position = 0.0
        noteIndex = 0
    }
}

/** Resolves NBS/GNBS custom-sample paths to sound-event IDs using `gnbs-sounds.yml` mappings. */
class SoundResolver(mappings: Map<String, String>) {
    private val mappings: Map<String, String> = mappings.mapKeys { normalize(it.key) }

    fun resolve(sample: String?): String? {
        if (sample.isNullOrBlank()) return null
        val normalized = normalize(sample)
        val mapped = mappings[normalized] ?: mappings[normalized.removeSuffix(".ogg")]
        if (mapped != null) return mapped
        if (':' in sample && '\\' !in sample && '/' !in sample) return sample
        var path = normalized.removeSuffix(".ogg").removePrefix("custom/").removePrefix("minecraft/")
        if (path.startsWith("note/")) path = "block.note_block." + path.substring(5)
        if (path.startsWith("note.")) path = "block.note_block." + path.substring(5)
        if (path.startsWith("block/") || path.startsWith("entity/") || path.startsWith("item/")) path = path.replace('/', '.')
        return if (VALID_PATH.matches(path)) "minecraft:$path" else null
    }

    companion object {
        private val VALID_PATH = Regex("[a-z0-9_./-]+")
        fun normalize(value: String): String =
            value.trim().replace('\\', '/').replace(Regex("(?i)^minecraft:"), "").lowercase(Locale.ROOT)

        /** Linear volume step used by the GUI volume buttons; rounds to one decimal. */
        fun stepVolume(current: Float, delta: Float): Float =
            (((current + delta).coerceIn(0f, 2f)) * 10f).let { kotlin.math.round(it) / 10f }
    }
}
