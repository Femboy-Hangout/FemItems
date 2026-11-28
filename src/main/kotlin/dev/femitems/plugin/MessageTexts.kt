package dev.femitems.plugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * The texts from `plugins/FemItems/messages.yml` (MiniMessage). Anything that file doesn't have, or a text
 * MiniMessage can't read, uses the jar's copy, so a message never goes missing.
 *
 * Thread-safe: [load] swaps one immutable map, so a reload on the AsyncScheduler is fine while region threads send.
 */
object MessageTexts {
    const val FILE = "messages.yml"
    private val mm = MiniMessage.miniMessage()

    /** The jar's messages.yml; also what unit tests render with. */
    private val bundled: Map<String, String> = run {
        val stream = MessageTexts::class.java.classLoader.getResourceAsStream(FILE)
            ?: error("$FILE is missing from the jar")
        texts(InputStreamReader(stream, StandardCharsets.UTF_8).use(YamlConfiguration::loadConfiguration))
    }

    @Volatile
    private var current: Map<String, String> = bundled

    /** Saves the default file on first run, then reads it. Thread-agnostic (file I/O only). */
    fun load(plugin: Plugin) {
        val file = File(plugin.dataFolder, FILE)
        if (!file.exists()) plugin.saveResource(FILE, false)
        val yaml = YamlConfiguration.loadConfiguration(file)
        MissingKeys.check(plugin, FILE, yaml, false)
        val (_, broken) = use(texts(yaml))
        broken.forEach { plugin.logger.warning("[$FILE] '$it' isn't valid MiniMessage - using the default text.") }
    }

    /** Overlays [overrides] on the defaults; returns the result and the keys that were invalid. For tests too. */
    fun use(overrides: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val broken = mutableListOf<String>()
        val merged = bundled.toMutableMap()
        for ((key, text) in overrides) {
            if (key !in bundled) continue
            if (runCatching { mm.deserialize(text) }.isSuccess) merged[key] = text else broken += key
        }
        current = merged
        return merged to broken
    }

    /** Back to the jar's texts (tests). */
    fun reset() {
        current = bundled
    }

    fun text(key: String): String = current[key] ?: bundled[key] ?: error("$FILE has no '$key'")

    /** Renders [key]; if the server's text throws with these placeholders, the default is used instead. */
    fun render(key: String, vararg resolvers: TagResolver): Component = try {
        mm.deserialize(text(key), *resolvers)
    } catch (exception: RuntimeException) {
        mm.deserialize(bundled.getValue(key), *resolvers)
    }

    /** [key] with the [prefixKey] text (`prefix` / `jukebox.prefix`) in front. */
    fun prefixed(prefixKey: String, key: String, vararg resolvers: TagResolver): Component = try {
        mm.deserialize(text(prefixKey) + text(key), *resolvers)
    } catch (exception: RuntimeException) {
        mm.deserialize(bundled.getValue(prefixKey) + bundled.getValue(key), *resolvers)
    }

    private fun texts(yaml: YamlConfiguration): Map<String, String> =
        yaml.getKeys(true).filter { yaml.isString(it) }.associateWith { yaml.getString(it)!! }
}
