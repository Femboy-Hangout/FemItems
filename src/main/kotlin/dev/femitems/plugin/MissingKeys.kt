package dev.femitems.plugin

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Tells the console which settings a live file (config.yml, jukebox/config.yml) doesn't have that the jar's copy
 * does, so an update that adds a setting, or a line deleted by accident, gets noticed:
 * ```
 *   [config.yml] 'resource-pack.required' is missing - the default is: false
 *   [config.yml] 1 setting(s) missing (above). Add them to plugins/FemItems/config.yml ...
 * ```
 * A whole missing section is reported once.
 *
 * `fill = true` copies the jar's values into [live] *in memory* (the file on disk is never rewritten). FemItems
 * uses `fill = false`: its settings already fall back to the same defaults in code, and filling `resource-pack.mode`
 * would break the 2.2.0-style self-host detection (no `mode` key + a top-level `port`).
 *
 * `ownLists`: sections whose entries are the server's own (`mobs`, `recipes`) - nothing under them is checked, so a
 * default mob or recipe deleted on purpose stays deleted.
 *
 * Same behaviour as the `MissingKeys` class in the other Fem plugins (FemCore, FemSkills, FemSkyblock, ...).
 */
object MissingKeys {

    /** Checks [live] against the jar's copy of [file]. @return how many missing things were reported */
    fun check(plugin: Plugin, file: String, live: ConfigurationSection, fill: Boolean, vararg ownLists: String): Int {
        val bundled = plugin.getResource(file)?.use {
            YamlConfiguration.loadConfiguration(InputStreamReader(it, StandardCharsets.UTF_8))
        } ?: return 0
        val where = "plugins/${plugin.name}/$file"
        return check(file, where, live, bundled, fill, { plugin.logger.warning(it) }, *ownLists)
    }

    /** The work, without a server (tests). [where] is the live file's path, for the summary line. */
    fun check(
        file: String, where: String, live: ConfigurationSection, bundled: ConfigurationSection,
        fill: Boolean, warn: (String) -> Unit, vararg ownLists: String,
    ): Int {
        val missing = bundled.getKeys(true).filter { key ->
            !bundled.isConfigurationSection(key) &&
                ownLists.none { key == it || key.startsWith("$it.") } &&
                !live.contains(key, true)
        }
        if (missing.isEmpty()) return 0
        // A whole section that isn't there is one line, not one per setting in it.
        val reported = LinkedHashSet<String>()
        missing.forEach { reported.add(topMissing(it, live)) }
        if (live.getKeys(false).isEmpty()) {
            warn("[$file] is empty or couldn't be read - every setting uses its default until it's fixed.")
        } else {
            for (key in reported) {
                val section = bundled.isConfigurationSection(key)
                val what = when {
                    section && fill -> "using its defaults."
                    section -> "its defaults are in the jar's copy."
                    fill -> "using the default: " + show(bundled.get(key))
                    else -> "the default is: " + show(bundled.get(key))
                }
                warn(
                    "[$file] '$key'" + (if (section) " (the whole section)" else "") + " is missing - " +
                        what,
                )
            }
            warn(
                "[$file] ${reported.size} setting(s) missing (above). Add them to $where to change them - " +
                    "the jar's copy of $file shows where they go.",
            )
        }
        if (fill) missing.forEach { live.set(it, bundled.get(it)) }
        return reported.size
    }

    /** The outermost part of [key] the live file doesn't have: "a.b" when even a.b is absent for a.b.c. */
    private fun topMissing(key: String, live: ConfigurationSection): String {
        val path = StringBuilder()
        for (part in key.split('.')) {
            if (path.isNotEmpty()) path.append('.')
            path.append(part)
            if (!live.contains(path.toString(), true)) return path.toString()
        }
        return key
    }

    private fun show(value: Any?): String = when (value) {
        is String -> "\"" + (if (value.length > 80) value.take(80) + "..." else value) + "\""
        is List<*> -> if (value.isEmpty()) "[]" else "a list of ${value.size}"
        else -> value.toString()
    }
}
