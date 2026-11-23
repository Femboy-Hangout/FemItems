package dev.femitems.plugin

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginDescriptorTest {

    @Test
    fun `paper-plugin descriptor declares Paper 26_3 and Folia support with filtered version`() {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("paper-plugin.yml")) {
            "Filtered paper-plugin.yml should be present on classpath"
        }
        val descriptor = InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
            YamlConfiguration.loadConfiguration(reader)
        }

        assertEquals("FemItems", descriptor.getString("name"))
        assertEquals("26.3", descriptor.getString("api-version"))
        assertTrue(descriptor.getBoolean("folia-supported"), "folia-supported must be true")

        val version = descriptor.getString("version")
        assertNotNull(version)
        assertFalse(version.contains("\${"), "Version placeholder must be filtered by Maven")
        assertTrue(Regex("""^\d+\.\d+\.\d+.*$""").matches(version), "Version '$version' must follow SemVer")

        assertEquals("op", descriptor.getString("permissions.femitems.reload.default"))
        assertEquals("op", descriptor.getString("permissions.femjukebox.admin.default"))

        val mainClassName = checkNotNull(descriptor.getString("main"))
        val mainClass = Class.forName(mainClassName)
        assertTrue(JavaPlugin::class.java.isAssignableFrom(mainClass))
    }
}
