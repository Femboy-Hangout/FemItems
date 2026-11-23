package dev.femitems.plugin.jukebox

/**
 * A playable track. Note songs carry [notes] timed in song ticks (`tempo` ticks per second); resource-pack
 * tracks carry a single [soundEvent] played once, with [lengthTicks] in server ticks (tempo 20).
 */
data class Song(
    val id: String,
    val title: String,
    val tempo: Float,
    val lengthTicks: Int,
    val notes: List<Note>,
    val soundEvent: String? = null,
    val discMaterial: String? = null,
) {
    val isResourcePackTrack: Boolean get() = !soundEvent.isNullOrBlank()
}

/**
 * One note event. Raw NBS notes use [instrument]/[key]/[velocity]; resolved notes (everything produced by
 * [GnbsReader]) carry an explicit [soundEvent], [volume] and [pitch]. A [stop] note silences [soundEvent].
 */
data class Note(
    val tick: Int,
    val instrument: Int,
    val key: Int,
    val velocity: Int,
    val soundEvent: String? = null,
    val stop: Boolean = false,
    val volume: Float = velocity / 100f,
    val pitch: Float = 0f,
)

/** Vanilla note-block instruments in NBS/GNBS index order. */
internal val NOTE_BLOCK_INSTRUMENTS = listOf(
    "harp", "bass", "basedrum", "snare", "hat", "guitar", "flute", "bell", "chime", "xylophone",
    "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling", "trumpet", "trumpet_exposed",
    "trumpet_weathered", "trumpet_oxidized",
)

internal fun noteBlockSound(index: Int): String? =
    NOTE_BLOCK_INSTRUMENTS.getOrNull(index)?.let { "minecraft:block.note_block.$it" }
