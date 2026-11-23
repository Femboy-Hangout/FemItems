package dev.femitems.plugin.jukebox

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.TreeMap
import java.util.logging.Logger
import kotlin.math.ceil

/** Immutable song catalog snapshot. Lookups are case-insensitive. */
class SongCatalog(songs: Map<String, Song>) {
    private val byId: Map<String, Song> =
        TreeMap<String, Song>(String.CASE_INSENSITIVE_ORDER).apply { putAll(songs) }

    val size: Int get() = byId.size
    val ids: List<String> get() = byId.keys.toList()
    val all: List<Song> get() = byId.values.toList()
    val noteSongs: List<Song> get() = byId.values.filterNot(Song::isResourcePackTrack)
    val customDiscSongs: List<Song> get() = byId.values.filter(Song::isResourcePackTrack)

    fun song(id: String?): Song? = id?.let(byId::get)

    fun resourcePackTrackForMaterial(material: String): Song? =
        byId.values.firstOrNull { it.isResourcePackTrack && it.discMaterial == material }

    companion object {
        val EMPTY = SongCatalog(emptyMap())
    }
}

/**
 * Loads the catalog from the jukebox data folder:
 * - `.nbs` files in `convert/` are converted to `.gnbs` files in `gnbs/` (sources are kept),
 * - `.gnbs` files in `gnbs/` are loaded as note songs,
 * - `resource-pack-discs.yml` tracks are loaded as resource-pack sound-event songs (`pack-<id>`),
 * - `gnbs-sounds.yml` maps custom sample paths to sound events.
 *
 * Pure file I/O; safe to run on the AsyncScheduler.
 */
class SongCatalogLoader(
    private val folder: Path,
    private val logger: Logger,
    private val materialName: (String) -> String? = { name -> Material.matchMaterial(name)?.name },
) {
    val gnbsFolder: Path get() = folder.resolve("gnbs")
    val convertFolder: Path get() = folder.resolve("convert")
    private val soundsFile: Path get() = folder.resolve("gnbs-sounds.yml")
    private val packDiscsFile: Path get() = folder.resolve("resource-pack-discs.yml")

    fun ensureFolders() {
        Files.createDirectories(gnbsFolder)
        Files.createDirectories(convertFolder)
        if (!Files.exists(soundsFile)) {
            Files.writeString(soundsFile, DEFAULT_SOUND_MAPPINGS, StandardCharsets.UTF_8)
        }
    }

    fun load(): SongCatalog {
        ensureFolders()
        val resolver = SoundResolver(loadSoundMappings())
        convertNbsFolder(resolver)
        val songs = LinkedHashMap<String, Song>()
        for (path in listFiles(gnbsFolder, ".gnbs")) {
            try {
                val song = GnbsReader.read(path, resolver::resolve)
                songs[song.id] = song
            } catch (e: Exception) {
                logger.warning("Skipping ${path.fileName}: ${e.message}")
            }
        }
        songs.putAll(loadResourcePackTracks())
        return SongCatalog(songs)
    }

    private fun loadSoundMappings(): Map<String, String> {
        if (!Files.exists(soundsFile)) return emptyMap()
        val yaml = YamlConfiguration.loadConfiguration(soundsFile.toFile())
        val section = yaml.getConfigurationSection("sounds") ?: return emptyMap()
        return section.getKeys(false)
            .mapNotNull { key -> section.getString(key)?.trim()?.takeIf(String::isNotBlank)?.let { key to it } }
            .toMap()
    }

    private fun convertNbsFolder(resolver: SoundResolver) {
        for (path in listFiles(convertFolder, ".nbs")) {
            val filename = path.fileName.toString()
            val output = gnbsFolder.resolve(filename.replace(Regex("(?i)\\.nbs$"), ".gnbs"))
            try {
                if (NbsToGnbsConverter.convert(path, output, resolver::resolve)) {
                    logger.info("Converted $filename to ${output.fileName}.")
                }
            } catch (e: Exception) {
                logger.warning("Could not convert $filename: ${e.message}")
            }
        }
    }

    private fun loadResourcePackTracks(): Map<String, Song> {
        if (!Files.exists(packDiscsFile)) return emptyMap()
        val yaml = YamlConfiguration.loadConfiguration(packDiscsFile.toFile())
        val tracks = yaml.getConfigurationSection("tracks") ?: return emptyMap()
        val result = LinkedHashMap<String, Song>()
        for (id in tracks.getKeys(false)) {
            val section = tracks.getConfigurationSection(id) ?: continue
            val soundEvent = section.getString("sound-event").orEmpty().trim()
            val duration = section.getDouble("duration-seconds", 0.0)
            val material = materialName(section.getString("material", "MUSIC_DISC_13").orEmpty())
            if (soundEvent.isBlank() || duration <= 0 || material == null) {
                logger.warning("Skipping invalid resource-pack track mapping: $id")
                continue
            }
            val trackId = "pack-$id"
            result[trackId] = Song(
                id = trackId,
                title = section.getString("title")?.takeIf(String::isNotBlank) ?: id,
                tempo = 20f,
                lengthTicks = ceil(duration * 20).toInt(),
                notes = emptyList(),
                soundEvent = soundEvent,
                discMaterial = material,
            )
        }
        return result
    }

    private fun listFiles(dir: Path, extension: String): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { paths ->
                paths.filter { it.fileName.toString().lowercase(Locale.ROOT).endsWith(extension) }
                    .sorted(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName.toString() })
                    .toList()
            }
        } catch (e: IOException) {
            logger.severe("Could not read ${dir.fileName} directory: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private val DEFAULT_SOUND_MAPPINGS = """
            # Map GNBS/NBS custom sample filenames to sound events registered by your resource pack.
            # Use the sample path without .ogg as the key.
            sounds:
              note/harp: minecraft:block.note_block.harp
        """.trimIndent() + "\n"
    }
}
