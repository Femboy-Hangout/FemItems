package dev.femitems.plugin.jukebox

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JukeboxParsingTest {
    private val identity: (String) -> String? = { it }

    // ------------------------------------------------------------------ GNBS

    @Test
    fun `gnbs rows apply delays, tick delays, repeats, and parts`() {
        val song = GnbsReader.parse(
            """
            Song:
              Id: demo
              Title: Demo Tune
              Content:
                Instruments:
                  '0': '0'
                  '1': '5'
                Parts:
                  riff:
                    - '100!1:50:#12'
                Main:
                  - '0!0:100:#12'
                  - '250!0:100:#24'
                  - '5t!?riff'
                  - '100!0:80;2'
            """.trimIndent(),
            "fallback",
            identity,
        )
        assertEquals("demo", song.id)
        assertEquals("Demo Tune", song.title)
        assertEquals(1000f, song.tempo)
        assertEquals(listOf(0, 250, 600, 700, 800, 900), song.notes.map(Note::tick))
        assertEquals("minecraft:block.note_block.harp", song.notes[0].soundEvent)
        assertEquals("minecraft:block.note_block.guitar", song.notes[2].soundEvent)
        // '#12' with the default sample key 12 is the root pitch; '#24' is one octave up.
        assertClose(1f, song.notes[0].pitch)
        assertClose(2f, song.notes[1].pitch)
        assertClose(0.5f, song.notes[2].volume)
        // Last note at 900 ms, plus the 1 s tail.
        assertEquals(1900, song.lengthTicks)
    }

    @Test
    fun `gnbs stop notes, custom instruments, and fallback id`() {
        val resolved = mutableListOf<String>()
        val song = GnbsReader.parse(
            """
            Content:
              Instruments:
                '20': 'my_pack/violin'
              Main:
                - '0!20:100:1.5'
                - '500!-20:0'
            """.trimIndent(),
            "from-file",
        ) { sample -> resolved += sample; "my_pack:music.violin" }
        assertEquals("from-file", song.id)
        assertEquals("from-file", song.title)
        assertEquals(listOf("my_pack/violin"), resolved)
        assertEquals("my_pack:music.violin", song.notes[0].soundEvent)
        assertClose(1.5f, song.notes[0].pitch)
        assertFalse(song.notes[0].stop)
        assertTrue(song.notes[1].stop)
    }

    @Test
    fun `gnbs without rows or notes is rejected`() {
        assertFailsWith<IOException> { GnbsReader.parse("Song:\n  Id: x\n", "x", identity) }
        assertFailsWith<IOException> { GnbsReader.parse("Main:\n  - '100!'\n", "x") { null } }
    }

    @Test
    fun `gnbs songs that expand past the note limit are rejected instead of exhausting memory`() {
        val started = System.nanoTime()
        val error = assertFailsWith<IOException> {
            GnbsReader.parse(
                """
                Content:
                  Parts:
                    a: ['0!0:100;999']
                    b: ['?a;999']
                    c: ['?b;999']
                  Main: ['?c']
                """.trimIndent(),
                "bomb",
                identity,
            )
        }
        assertTrue(error.message!!.contains("more than ${GnbsReader.MAX_NOTES} notes"), error.message)
        assertTrue(System.nanoTime() - started < 10_000_000_000L, "rejecting the song must be quick")
    }

    @Test
    fun `gnbs repeat bombs without notes are rejected instead of running forever`() {
        val started = System.nanoTime()
        val error = assertFailsWith<IOException> {
            GnbsReader.parse(
                """
                Content:
                  Parts:
                    a: ['1!;9999']
                    b: ['?a;9999']
                    c: ['?b;9999']
                    d: ['?c;9999']
                  Main: ['?d', '0!0:100']
                """.trimIndent(),
                "rows",
                identity,
            )
        }
        assertTrue(error.message!!.contains("more than ${GnbsReader.MAX_EXPANDED_ROWS} rows"), error.message)
        assertTrue(System.nanoTime() - started < 10_000_000_000L, "rejecting the song must be quick")
    }

    @Test
    fun `gnbs delay parser handles milliseconds, ticks, and junk`() {
        assertEquals(250, GnbsReader.parseDelay("250"))
        assertEquals(250, GnbsReader.parseDelay("5t"))
        assertEquals(0, GnbsReader.parseDelay("abc"))
        assertEquals(0, GnbsReader.parseDelay("-10"))
    }

    // ------------------------------------------------------------------ NBS

    @Test
    fun `nbs v5 files are read with layer volume and custom instruments`(@TempDir dir: Path) {
        val file = dir.resolve("My Song.nbs")
        Files.write(file, nbsV5())
        val song = NbsReader.read(file) { sample -> if (sample == "violin.ogg") "my_pack:violin" else null }
        assertEquals("My Song", song.id)
        assertEquals("Test Title", song.title)
        assertEquals(10f, song.tempo)
        assertEquals(20, song.lengthTicks)
        assertEquals(3, song.notes.size)
        val (first, second, custom) = song.notes
        assertEquals(Triple(0, 0, 45), Triple(first.tick, first.instrument, first.key))
        assertEquals(100, first.velocity)
        // Second note sits on layer 1, whose volume is 50%.
        assertEquals(Triple(4, 1, 57), Triple(second.tick, second.instrument, second.key))
        assertEquals(40, second.velocity)
        assertEquals("my_pack:violin", custom.soundEvent)
        assertEquals(8, custom.tick)
        // key 45 + sample key 45 - 90 = 0 semitones.
        assertClose(1f, custom.pitch)
    }

    @Test
    fun `truncated nbs files report a clear error`(@TempDir dir: Path) {
        val file = dir.resolve("broken.nbs")
        Files.write(file, nbsV5().copyOf(30))
        val error = assertFailsWith<IOException> { NbsReader.read(file) }
        assertTrue(error.message!!.contains("Truncated"), error.message)
    }

    @Test
    fun `nbs converts to gnbs that plays the same notes at the same times`(@TempDir dir: Path) {
        val source = dir.resolve("tune.nbs")
        Files.write(source, nbsV5())
        val output = dir.resolve("gnbs").resolve("tune.gnbs")
        val resolver: (String) -> String? = { if (it == "violin.ogg") "my_pack:violin" else it }

        assertTrue(NbsToGnbsConverter.convert(source, output, resolver))
        assertFalse(NbsToGnbsConverter.convert(source, output, resolver), "unchanged output must not be rewritten")

        val song = GnbsReader.read(output, resolver)
        assertEquals("tune", song.id)
        assertEquals("Test Title", song.title)
        // 10 ticks per second: ticks 0, 4, 8 -> 0, 400, 800 ms.
        assertEquals(listOf(0, 400, 800), song.notes.map(Note::tick))
        assertEquals("minecraft:block.note_block.harp", song.notes[0].soundEvent)
        assertClose(1f, song.notes[0].pitch)
        assertEquals("minecraft:block.note_block.bass", song.notes[1].soundEvent)
        assertClose(2f, song.notes[1].pitch)
        assertClose(0.4f, song.notes[1].volume)
        assertEquals("my_pack:violin", song.notes[2].soundEvent)
        assertClose(1f, song.notes[2].pitch)
    }

    // ------------------------------------------------------------------ clock, sounds, settings

    @Test
    fun `song clock releases notes when due and reports progress`() {
        val song = Song("s", "S", 1000f, 200, listOf(Note(0, 0, 45, 100), Note(50, 0, 45, 100), Note(120, 0, 45, 100)))
        val clock = SongClock(song)
        assertEquals(listOf(0, 50), clock.advance(1).map(Note::tick)) // 1 tick = 50 ms
        assertEquals(emptyList(), clock.advance(1).map(Note::tick))
        assertEquals(listOf(120), clock.advance(1).map(Note::tick))
        assertClose(0.25f, clock.remaining)
        assertFalse(clock.finished)
        clock.advance(1)
        assertTrue(clock.finished)
        clock.reset()
        assertEquals(0, clock.tick)
        assertEquals(listOf(0), clock.advance(0).map(Note::tick))
    }

    @Test
    fun `song clock honours tick interval`() {
        val clock = SongClock(Song("s", "S", 20f, 100, emptyList()))
        clock.advance(5)
        assertEquals(5, clock.tick)
    }

    @Test
    fun `sound resolver maps samples, passes namespaced ids, and rejects junk`() {
        val resolver = SoundResolver(mapOf("My_Pack/Violin" to "my_pack:music.violin"))
        assertEquals("my_pack:music.violin", resolver.resolve("my_pack/violin.ogg"))
        assertEquals("my_pack:music.violin", resolver.resolve("MY_PACK\\VIOLIN"))
        assertEquals("custom:thing", resolver.resolve("custom:thing"))
        assertEquals("minecraft:block.note_block.harp", resolver.resolve("note/harp.ogg"))
        assertEquals("minecraft:block.note_block.bass", resolver.resolve("note.bass"))
        assertEquals("minecraft:note.bass", resolver.resolve("minecraft:note.bass"), "namespaced ids pass through")
        assertEquals("minecraft:entity.cat.ambient", resolver.resolve("entity/cat/ambient.ogg"))
        assertNull(resolver.resolve("weird sample!.ogg"))
        assertNull(resolver.resolve(" "))
    }

    @Test
    fun `volume steps clamp between 0 and 200 percent`() {
        assertClose(1.1f, SoundResolver.stepVolume(1.0f, 0.1f))
        assertClose(2.0f, SoundResolver.stepVolume(1.95f, 0.1f))
        assertClose(0.0f, SoundResolver.stepVolume(0.05f, -0.1f))
    }

    @Test
    fun `settings parse bundled defaults and clamp bad values`() {
        val bundled = YamlConfiguration.loadConfiguration(resource("jukebox/config.yml").reader())
        assertEquals(JukeboxSettings(), JukeboxSettings.from(bundled))

        val custom = YamlConfiguration().apply {
            set("jukebox.bossbar", false)
            set("jukebox.bossbar-color", "red")
            set("jukebox.radius", -4)
            set("playback.default-volume", 9.0)
            set("playback.tick-interval", 0)
        }
        val settings = JukeboxSettings.from(custom)
        assertFalse(settings.bossBar)
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.RED, settings.bossBarColor)
        assertEquals(0.0, settings.radius)
        assertEquals(2f, settings.defaultVolume)
        assertEquals(1, settings.tickInterval)

        val unknownColor = JukeboxSettings.from(YamlConfiguration().apply { set("jukebox.bossbar-color", "plaid") })
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.PURPLE, unknownColor.bossBarColor)
    }

    // ------------------------------------------------------------------ catalog

    @Test
    fun `catalog loads converted nbs, gnbs, and resource pack tracks`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("convert"))
        Files.write(dir.resolve("convert").resolve("imported.nbs"), nbsV5())
        Files.createDirectories(dir.resolve("gnbs"))
        Files.writeString(dir.resolve("gnbs").resolve("Hand Made.gnbs"), "Main:\n  - '0!0:100'\n")
        Files.writeString(dir.resolve("gnbs").resolve("broken.gnbs"), "Main: [\n")
        Files.copy(resource("jukebox/resource-pack-discs.yml"), dir.resolve("resource-pack-discs.yml"))
        Files.writeString(
            dir.resolve("resource-pack-discs.yml"),
            Files.readString(dir.resolve("resource-pack-discs.yml")) +
                "  broken:\n    title: 'No sound'\n    material: MUSIC_DISC_13\n    duration-seconds: 3\n",
        )

        val catalog = SongCatalogLoader(dir, Logger.getAnonymousLogger()) { it.uppercase() }.load()

        assertTrue(Files.isRegularFile(dir.resolve("gnbs").resolve("imported.gnbs")), "NBS import must be converted")
        assertTrue(Files.isRegularFile(dir.resolve("gnbs-sounds.yml")), "default sound mappings must be created")
        assertEquals(
            listOf("Hand Made", "imported", "pack-lucys-pigstep", "pack-lucys-relic", "pack-lucys-stal", "pack-lucys-ward"),
            catalog.ids,
        )
        assertEquals(listOf("Hand Made", "imported"), catalog.noteSongs.map(Song::id))
        assertEquals(4, catalog.customDiscSongs.size)
        assertNotNull(catalog.song("HAND MADE"), "lookups are case-insensitive")

        val pigstep = assertNotNull(catalog.resourcePackTrackForMaterial("MUSIC_DISC_PIGSTEP"))
        assertEquals("pack-lucys-pigstep", pigstep.id)
        assertTrue(pigstep.isResourcePackTrack)
        assertEquals("minecraft:music_disc.pigstep", pigstep.soundEvent)
        assertEquals(2854, pigstep.lengthTicks) // ceil(142.666304 s * 20)
        assertNull(catalog.resourcePackTrackForMaterial("MUSIC_DISC_CAT"))
    }

    @Test
    fun `song argument suggestions quote ids brigadier cannot read bare`() {
        assertEquals("pack-lucys-ward", JukeboxCommands.quoteIfNeeded("pack-lucys-ward"))
        assertEquals("\"Hand Made\"", JukeboxCommands.quoteIfNeeded("Hand Made"))
        assertEquals("\"say \\\"hi\\\"\"", JukeboxCommands.quoteIfNeeded("say \"hi\""))
    }

    @Test
    fun `jukebox messages keep a stable plain-text form`() {
        val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        assertEquals("[FemJukebox] Now playing: Live Tune", plain.serialize(JukeboxMessages.nowPlaying("Live Tune")))
        assertEquals("[FemJukebox] Finished: <b>x</b>", plain.serialize(JukeboxMessages.finished("<b>x</b>")))
        assertEquals("[FemJukebox] Playback volume: 120%", plain.serialize(JukeboxMessages.volume(1.2f)))
        assertEquals("[FemJukebox] Songs (2): a, b", plain.serialize(JukeboxMessages.songs(listOf("a", "b"))))
        assertFalse(plain.serialize(JukeboxMessages.help(admin = false)).contains("/femjukebox give"))
        assertTrue(plain.serialize(JukeboxMessages.help(admin = true)).contains("/femjukebox give"))
    }

    // ------------------------------------------------------------------ helpers

    private fun resource(path: String): java.io.InputStream =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "Missing bundled resource $path" }

    private fun assertClose(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < 0.0001f, "expected $expected but was $actual")

    /**
     * A minimal NBS v5 file: tempo 10 t/s, two layers (100% and 50% volume), one custom instrument
     * (`violin.ogg`, sample key 45), and notes at ticks 0 (harp), 4 (bass, layer 1), 8 (custom).
     */
    private fun nbsV5(): ByteArray {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Int) { u16(v); u16(v shr 16) }
        fun str(s: String) { val b = s.toByteArray(StandardCharsets.UTF_8); u32(b.size); out.write(b) }

        u16(0); u8(5); u8(16); u16(20) // new format, version 5, 16 vanilla instruments, length 20
        u16(2) // layers
        str("Test Title"); str("author"); str(""); str("desc")
        u16(1000) // tempo 10.00 t/s
        u8(0); u8(10); u8(4) // auto-save, duration, time signature
        repeat(5) { u32(0) }
        str("") // imported file name
        u8(0); u8(0); u16(0) // loop on/off, max loop count, loop start tick

        // tick 0, layer 0: harp key 45 velocity 100
        u16(1); u16(1); u8(0); u8(45); u8(100); u8(100); u16(0); u16(0)
        // tick 4, layer 1: bass key 57 velocity 80 (layer volume 50% -> 40)
        u16(4); u16(2); u8(1); u8(57); u8(80); u8(100); u16(0); u16(0)
        // tick 8, layer 0: custom instrument 16 key 45
        u16(4); u16(1); u8(16); u8(45); u8(100); u8(100); u16(0); u16(0)
        u16(0) // end of notes

        str("Lead"); u8(0); u8(100); u8(100) // layer 0: name, locked, volume, stereo
        str("Bass"); u8(0); u8(50); u8(100) // layer 1
        u8(1); str("Violin"); str("violin.ogg"); u8(45); u8(0) // custom instruments
        return out.toByteArray()
    }
}
