package dev.femitems.plugin.jukebox

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.pow

/** Reads Open Note Block Studio `.nbs` files (all format versions, including v4+ custom instruments). */
object NbsReader {

    fun read(file: Path, soundResolver: (String) -> String? = { it }): Song =
        try {
            DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { input ->
                read(input, file.fileName.toString(), soundResolver)
            }
        } catch (e: EOFException) {
            throw IOException("Truncated NBS file: ${file.fileName}", e)
        }

    private fun read(input: DataInputStream, fileName: String, soundResolver: (String) -> String?): Song {
        val first = input.u16()
        val version: Int
        val declaredLength: Int
        val vanillaInstrumentCount: Int
        if (first == 0) {
            version = input.u8()
            vanillaInstrumentCount = input.u8()
            declaredLength = if (version >= 3) input.u16() else -1
        } else {
            // Legacy (version 0) files store the song length in the first short.
            version = 0
            vanillaInstrumentCount = 10
            declaredLength = first
        }
        val layerCount = input.u16()
        val title = input.nbsString()
        input.nbsString() // author
        input.nbsString() // original author
        input.nbsString() // description
        val tempoRaw = input.u16()
        input.skipNBytes(3) // auto-save, auto-save duration, time signature
        input.skipNBytes(4 * 5) // minutes spent, clicks, blocks added/removed
        input.nbsString() // imported path
        if (version >= 4) input.skipNBytes(1 + 1 + 2) // loop on/off (byte), max loop count (byte), loop start tick (short)

        val notes = ArrayList<Note>()
        val noteLayers = ArrayList<Int>()
        var tick = -1
        var maxTick = 0
        while (true) {
            val jump = input.u16()
            if (jump == 0) break
            tick += jump
            var layer = -1
            while (true) {
                val layerJump = input.u16()
                if (layerJump == 0) break
                layer += layerJump
                val instrument = input.u8()
                val key = input.u8()
                var velocity = 100
                if (version >= 4) {
                    velocity = input.u8()
                    input.u8() // panning
                    input.u16() // fine pitch
                }
                notes += Note(tick, instrument, key, velocity)
                noteLayers += layer
                maxTick = maxOf(maxTick, tick)
            }
        }

        // Layer volume exists in older versions too; v4 adds a lock flag.
        val layerVolumes = ArrayList<Int>(layerCount)
        repeat(layerCount) {
            input.nbsString()
            if (version >= 4) input.u8() // locked
            layerVolumes += input.u8()
            if (version >= 2) input.u8() // stereo
        }
        val customInstruments = ArrayList<CustomInstrument>()
        if (version >= 4) {
            repeat(input.u8()) {
                val name = input.nbsString()
                val soundFile = input.nbsString()
                val sampleKey = input.u8()
                input.u8() // press key
                customInstruments += CustomInstrument(name, soundFile, sampleKey)
            }
        }
        if (tempoRaw == 0) throw IOException("Invalid NBS tempo in $fileName")

        val resolved = ArrayList<Note>(notes.size)
        for ((index, note) in notes.withIndex()) {
            val layerVolume = layerVolumes.getOrElse(noteLayers[index]) { 100 }
            val velocity = (note.velocity * layerVolume / 100).coerceIn(0, 100)
            val customIndex = note.instrument - vanillaInstrumentCount
            if (customIndex < 0) {
                resolved += note.copy(velocity = velocity, volume = velocity / 100f)
                continue
            }
            val custom = customInstruments.getOrNull(customIndex) ?: continue
            val sound = soundResolver(custom.soundFile)?.takeIf(String::isNotBlank) ?: continue
            val pitch = 2.0.pow((note.key + custom.sampleKey - 90) / 12.0).toFloat()
            resolved += Note(note.tick, -1, note.key, velocity, sound, false, velocity / 100f, pitch)
        }

        val id = fileName.replace(Regex("(?i)\\.nbs$"), "")
        return Song(
            id = id,
            title = title.ifBlank { fileName },
            tempo = tempoRaw / 100f,
            lengthTicks = if (declaredLength >= 0) maxOf(declaredLength, maxTick + 1) else maxTick + 1,
            notes = resolved.toList(),
        )
    }

    private fun DataInputStream.u8(): Int = readUnsignedByte()
    private fun DataInputStream.u16(): Int = u8() or (u8() shl 8)
    private fun DataInputStream.u32(): Int = u16() or (u16() shl 16)
    private fun DataInputStream.nbsString(): String {
        val length = u32()
        if (length < 0 || length > 1_000_000) throw IOException("Invalid NBS string length")
        return String(readNBytes(length), StandardCharsets.UTF_8)
    }

    private data class CustomInstrument(val name: String, val soundFile: String, val sampleKey: Int)
}
