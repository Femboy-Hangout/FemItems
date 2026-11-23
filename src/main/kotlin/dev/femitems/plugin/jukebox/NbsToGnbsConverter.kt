package dev.femitems.plugin.jukebox

import org.bukkit.configuration.file.YamlConfiguration
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import kotlin.math.roundToLong

/** Converts imported `.nbs` files into readable `.gnbs` files, leaving the source untouched. */
object NbsToGnbsConverter {

    /** Returns true when [output] was written, false when it already held identical content. */
    fun convert(source: Path, output: Path, soundResolver: (String) -> String?): Boolean {
        val data = toGnbs(NbsReader.read(source, soundResolver))
        output.parent?.let(Files::createDirectories)
        if (Files.exists(output) && Files.readString(output, StandardCharsets.UTF_8) == data) return false
        Files.writeString(output, data, StandardCharsets.UTF_8)
        return true
    }

    fun toGnbs(song: Song): String {
        val yaml = YamlConfiguration()
        yaml.set("Song.Id", song.id)
        yaml.set("Song.Title", song.title)
        yaml.set("Song.Category", "RECORDS")

        val customIds = LinkedHashMap<String, String>()
        val atTimes = TreeMap<Long, MutableList<Note>>()
        for (note in song.notes) {
            val millis = maxOf(0L, (note.tick * 1000.0 / song.tempo).roundToLong())
            atTimes.getOrPut(millis, ::ArrayList) += note
        }
        val main = ArrayList<String>()
        var previousMillis = 0L
        for ((millis, notes) in atTimes) {
            val rowNotes = ArrayList<String>()
            for (note in notes) {
                val sound = note.soundEvent
                if (!sound.isNullOrBlank()) {
                    val instrumentId = customIds.getOrPut(sound) {
                        val newId = (20 + customIds.size).toString()
                        yaml.set("Song.Content.Instruments.$newId", sound)
                        // Custom notes store their absolute playback pitch in the row itself.
                        yaml.set("Song.Content.InstrumentKeys.$newId", 12)
                        newId
                    }
                    rowNotes += "$instrumentId:${note.velocity}:${note.pitch}"
                } else if (note.instrument >= 0) {
                    yaml.set("Song.Content.Instruments.${note.instrument}", note.instrument)
                    rowNotes += "${note.instrument}:${note.velocity}:#${note.key - 33}"
                }
            }
            if (rowNotes.isNotEmpty()) {
                main += "${maxOf(0L, millis - previousMillis)}!${rowNotes.joinToString("_")}"
                previousMillis = millis
            }
        }
        if (main.isEmpty()) throw IOException("NBS has no playable notes after resolving custom instruments")
        yaml.set("Song.Content.Main", main)
        return yaml.saveToString()
    }
}
