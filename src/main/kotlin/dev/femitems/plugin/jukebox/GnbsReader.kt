package dev.femitems.plugin.jukebox

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Reader for GMusic's human-readable GNBS YAML song format: timed rows (`delayMs!notes;repeats`),
 * reusable parts (`?partName`), note stoppers (`-instrument:...`), built-in and custom instruments.
 * Note ticks are milliseconds, so resulting songs use tempo 1000.
 */
object GnbsReader {
    private const val MAX_PART_DEPTH = 16
    private const val MAX_REPEATS = 10_000

    /** Most notes one song may expand to; larger songs are rejected instead of exhausting memory. */
    const val MAX_NOTES = 1_000_000

    /** Most row repetitions one song may expand (bounds CPU for nested repeats/parts even without notes). */
    const val MAX_EXPANDED_ROWS = 5_000_000
    private val DIGITS = Regex("[0-9]+")

    fun read(file: Path, soundResolver: (String) -> String?): Song {
        val fallbackId = file.fileName.toString().replace(Regex("(?i)\\.gnbs$"), "")
        return parse(Files.readString(file, StandardCharsets.UTF_8), fallbackId, soundResolver)
    }

    fun parse(text: String, fallbackId: String, soundResolver: (String) -> String?): Song {
        val yaml = YamlConfiguration()
        try {
            yaml.load(StringReader(text))
        } catch (e: Exception) {
            throw IOException("Invalid GNBS YAML: ${e.message?.lineSequence()?.firstOrNull()}", e)
        }
        val song: ConfigurationSection = yaml.getConfigurationSection("Song") ?: yaml
        val id = song.getString("Id")?.takeIf(String::isNotBlank) ?: fallbackId
        val title = song.getString("Title")?.takeIf(String::isNotBlank) ?: id
        val content: ConfigurationSection = song.getConfigurationSection("Content") ?: song
        val main = content.getStringList("Main")
        if (main.isEmpty()) throw IOException("GNBS song has no Content.Main rows")

        val instrumentKeys = content.getConfigurationSection("InstrumentKeys")
        val instruments = HashMap<String, Instrument>()
        content.getConfigurationSection("Instruments")?.let { section ->
            for (instrumentId in section.getKeys(false)) {
                val value = section.getString(instrumentId).orEmpty().trim()
                if (value.isBlank()) continue
                val sound = if (DIGITS.matches(value)) value.toIntOrNull()?.let(::noteBlockSound) else soundResolver(value)
                val sampleKey = instrumentKeys?.getInt(instrumentId, 12) ?: 12
                instruments[instrumentId] = Instrument(sound, sampleKey)
            }
        }
        val parts = HashMap<String, List<String>>()
        content.getConfigurationSection("Parts")?.let { section ->
            for (name in section.getKeys(false)) parts[name] = section.getStringList(name)
        }

        val expander = Expander(instruments, parts, soundResolver)
        for (row in main) expander.expand(row, 0)
        val notes = expander.notes.sortedBy(Note::tick)
        if (notes.isEmpty()) throw IOException("GNBS contains no playable notes")
        val duration = maxOf(expander.cursor, notes.last().tick + 1000L).coerceAtMost(Int.MAX_VALUE.toLong())
        return Song(id, title, 1000f, maxOf(1, duration.toInt()), notes)
    }

    private class Expander(
        private val instruments: Map<String, Instrument>,
        private val parts: Map<String, List<String>>,
        private val soundResolver: (String) -> String?,
    ) {
        val notes = ArrayList<Note>()
        var cursor = 0L
        private var expandedRows = 0L

        fun expand(source: String?, depth: Int) {
            if (depth > MAX_PART_DEPTH || source.isNullOrBlank()) return
            var row = source.trim()
            var repeat = 1
            val semicolon = row.lastIndexOf(';')
            if (semicolon >= 0) {
                repeat += maxOf(0, row.substring(semicolon + 1).trim().toIntOrNull() ?: 0)
                row = row.substring(0, semicolon).trim()
            }
            val bang = row.indexOf('!')
            val delay = parseDelay(if (bang >= 0) row.substring(0, bang) else "0")
            val body = if (bang >= 0) row.substring(bang + 1).trim() else row
            repeat(minOf(repeat, MAX_REPEATS)) {
                if (++expandedRows > MAX_EXPANDED_ROWS) {
                    throw IOException("song expands to more than $MAX_EXPANDED_ROWS rows (check repeats and nested parts)")
                }
                cursor += delay
                if (body.isBlank()) return@repeat
                for (token in body.split('_')) {
                    val part = token.trim()
                    if (part.isEmpty()) continue
                    if (part[0] == '?') {
                        parts[part.substring(1)]?.forEach { expand(it, depth + 1) }
                        continue
                    }
                    parseNote(part)
                }
            }
        }

        private fun parseNote(text: String) {
            val fields = text.split(':')
            if (fields.size < 2) return
            val stop = fields[0].startsWith("-")
            val instrumentId = if (stop) fields[0].substring(1) else fields[0]
            val instrument = instruments[instrumentId]
            val sound = (if (instrument != null) instrument.sound else fallbackSound(instrumentId))
                ?.takeIf(String::isNotBlank) ?: return
            if (notes.size >= MAX_NOTES) throw IOException("song has more than $MAX_NOTES notes")
            val at = cursor.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (stop) {
                notes += Note(at, -1, 0, 0, sound, stop = true, volume = 0f, pitch = 1f)
                return
            }
            val volume = (fields[1].trim().toFloatOrNull() ?: 100f) / 100f
            val encodedPitch = fields.getOrNull(2)?.trim() ?: "#12"
            val encoded = encodedPitch.removePrefix("#").trim().toIntOrNull() ?: 12
            val sampleKey = instrument?.sampleKey ?: 12
            val pitch = if (encodedPitch.startsWith("#")) {
                2.0.pow((encoded + sampleKey - 24) / 12.0).toFloat()
            } else {
                encodedPitch.toFloatOrNull() ?: 1f
            }
            notes += Note(at, -1, encoded, (volume * 100).roundToInt().coerceIn(0, 100), sound, false, volume, pitch)
        }

        private fun fallbackSound(id: String): String? =
            id.toIntOrNull()?.let(::noteBlockSound) ?: soundResolver(id)
    }

    /** `250` = 250 ms; `5t` = 5 server ticks (250 ms). */
    internal fun parseDelay(value: String): Long {
        val v = value.trim().lowercase(Locale.ROOT)
        val inTicks = v.endsWith("t")
        val number = (if (inTicks) v.dropLast(1) else v).toDoubleOrNull() ?: return 0
        return maxOf(0L, (number * if (inTicks) 50.0 else 1.0).roundToLong())
    }

    private data class Instrument(val sound: String?, val sampleKey: Int)
}
