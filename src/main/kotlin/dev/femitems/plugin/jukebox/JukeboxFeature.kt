package dev.femitems.plugin.jukebox

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The jukebox / NBS music feature (formerly the standalone FemJukebox plugin), hosted by FemItems.
 * All of its files live in `plugins/FemItems/jukebox/`.
 */
class JukeboxFeature(private val plugin: JavaPlugin) {
    val folder: Path = plugin.dataFolder.toPath().resolve("jukebox")
    private val loader = SongCatalogLoader(folder, plugin.logger)
    private val reloadLock = Any()

    @Volatile
    var settings: JukeboxSettings = JukeboxSettings()
        private set

    val music = MusicService(plugin) { settings }
    private val legacyDiscs = DepositedDiscStore(folder.resolve("deposited-discs.yml").toFile(), plugin)
    val menu = JukeboxMenu(plugin, music, legacyDiscs)

    private var watcher: WatchService? = null
    private val pendingReload = AtomicReference<ScheduledTask?>()

    /** Synchronous startup (onEnable): songs exist before anyone can join. */
    fun enable() {
        importStandaloneData()
        for (resource in DEFAULT_RESOURCES) {
            if (!Files.exists(folder.resolve(resource))) plugin.saveResource("jukebox/$resource", false)
        }
        legacyDiscs.load()
        val count = reload()
        plugin.server.pluginManager.registerEvents(menu, plugin)
        music.start()
        startWatcher()
        plugin.logger.info("Jukebox ready: loaded $count song(s).")
    }

    fun disable() {
        pendingReload.getAndSet(null)?.cancel()
        runCatching { watcher?.close() }
        watcher = null
        music.shutdown()
    }

    /** Reloads `config.yml` and the song catalog; thread-agnostic (file I/O only). Returns the song count. */
    fun reload(): Int = synchronized(reloadLock) {
        settings = JukeboxSettings.from(YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile()))
        val catalog = loader.load()
        music.catalog = catalog
        catalog.size
    }

    /** `/femjukebox reload`: converts NBS imports and reloads songs on the AsyncScheduler. */
    fun reloadAsync(onDone: (Result<Int>) -> Unit) {
        plugin.server.asyncScheduler.runNow(plugin) { onDone(runCatching(::reload)) }
    }

    /** One-time copy of `plugins/FemJukebox/` (songs, mappings, config) into `plugins/FemItems/jukebox/`. */
    private fun importStandaloneData() {
        val legacy = plugin.dataFolder.parentFile?.toPath()?.resolve("FemJukebox") ?: return
        if (Files.exists(folder) || !Files.isDirectory(legacy)) return
        try {
            Files.walk(legacy).use { paths ->
                for (source in paths) {
                    val target = folder.resolve(legacy.relativize(source).toString())
                    if (Files.isDirectory(source)) Files.createDirectories(target)
                    else Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
            plugin.logger.info("Imported FemJukebox data from $legacy into $folder.")
        } catch (e: IOException) {
            plugin.logger.warning("Could not import FemJukebox data from $legacy: ${e.message}")
        }
    }

    /** Watches `convert/` and `gnbs/` and reloads (debounced) when song files change. */
    private fun startWatcher() {
        val service = try {
            FileSystems.getDefault().newWatchService().also { service ->
                for (dir in listOf(loader.gnbsFolder, loader.convertFolder)) {
                    dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE)
                }
            }
        } catch (e: IOException) {
            plugin.logger.warning("Song folder watching is unavailable: ${e.message}")
            return
        }
        watcher = service
        plugin.server.asyncScheduler.runNow(plugin) { watchLoop(service) }
    }

    private fun watchLoop(service: WatchService) {
        while (true) {
            val key = try {
                service.take()
            } catch (_: ClosedWatchServiceException) {
                return
            } catch (_: InterruptedException) {
                return
            }
            val relevant = key.pollEvents().any { event ->
                event.kind() == StandardWatchEventKinds.OVERFLOW ||
                    (event.context() as? Path)?.fileName?.toString()?.lowercase(Locale.ROOT)
                        ?.let { it.endsWith(".nbs") || it.endsWith(".gnbs") } == true
            }
            if (!key.reset()) return
            if (relevant && plugin.isEnabled) scheduleDebouncedReload()
        }
    }

    private fun scheduleDebouncedReload() {
        val task = plugin.server.asyncScheduler.runDelayed(plugin, {
            val count = reload()
            plugin.logger.info("Reloaded song catalog: $count song(s).")
        }, 500, TimeUnit.MILLISECONDS)
        pendingReload.getAndSet(task)?.cancel()
    }

    companion object {
        const val ADMIN_PERMISSION = "femjukebox.admin"
        private val DEFAULT_RESOURCES = listOf("config.yml", "resource-pack-discs.yml")
    }
}
