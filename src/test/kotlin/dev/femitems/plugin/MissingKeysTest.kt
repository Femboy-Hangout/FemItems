package dev.femitems.plugin

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Settings the live file lacks are named; the server's own lists (mobs, recipes) are not. */
class MissingKeysTest {

    private fun yaml(text: String) = YamlConfiguration().apply { loadFromString(text) }

    /** The real bundled files, so a new setting in them is covered too. */
    private fun bundled(name: String) = yaml(Files.readString(Path.of("src/main/resources", name)))

    @Test
    fun `the bundled files have nothing missing against themselves`() {
        for (name in listOf("config.yml", "jukebox/config.yml")) {
            val warnings = mutableListOf<String>()
            assertEquals(0, MissingKeys.check(name, "w", bundled(name), bundled(name), false, { warnings += it }, "mobs", "recipes"))
            assertTrue(warnings.isEmpty(), "$name: $warnings")
        }
    }

    @Test
    fun `missing settings are named, own lists are not, and the file is left alone`() {
        val live = bundled("config.yml")
        live.set("resource-pack.required", null)
        live.set("resource-pack.self-host", null)
        live.set("mobs.archer", null)          // the server's own mob list
        live.set("recipes", null)              // even all recipes gone is not "missing"
        val before = live.saveToString()

        val warnings = mutableListOf<String>()
        val n = MissingKeys.check("config.yml", "plugins/FemItems/config.yml", live, bundled("config.yml"), false,
            { warnings += it }, "mobs", "recipes")

        assertEquals(2, n)
        assertEquals(
            listOf(
                "[config.yml] 'resource-pack.required' is missing - the default is: false",
                "[config.yml] 'resource-pack.self-host' (the whole section) is missing - the default is.",
                "[config.yml] 2 setting(s) missing (above). Add them to plugins/FemItems/config.yml to change them - " +
                    "the jar's copy of config.yml shows where they go.",
            ),
            warnings,
        )
        assertEquals(before, live.saveToString(), "report only: nothing filled in")
        assertFalse(live.contains("resource-pack.required", true))
    }

    @Test
    fun `fill puts the jar's value in memory`() {
        val live = bundled("jukebox/config.yml")
        live.set("jukebox.radius", null)
        assertEquals(1, MissingKeys.check("jukebox/config.yml", "w", live, bundled("jukebox/config.yml"), true, { }))
        assertEquals(32, live.getInt("jukebox.radius"))
    }

    @Test
    fun `an empty or unreadable file is one line`() {
        val warnings = mutableListOf<String>()
        MissingKeys.check("jukebox/config.yml", "w", YamlConfiguration(), bundled("jukebox/config.yml"), false, { warnings += it })
        assertEquals(listOf("[jukebox/config.yml] is empty or couldn't be read - every setting uses its default until it's fixed."), warnings)
    }
}
