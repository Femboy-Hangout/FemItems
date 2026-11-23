package dev.femitems.plugin.jukebox

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** World-independent block coordinate, safe to pass between region threads. */
data class BlockPos(val world: UUID, val x: Int, val y: Int, val z: Int) {
    fun location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x.toDouble(), y.toDouble(), z.toDouble()) }
    fun center(): Location? = location()?.add(0.5, 0.5, 0.5)

    companion object {
        fun of(block: Block) = BlockPos(block.world.uid, block.x, block.y, block.z)
    }
}

/** Where and how a jukebox-sourced playback starts. [discToken] identifies the disc inserted for it, if any. */
data class JukeboxStart(val pos: BlockPos, val volume: Float, val discToken: String?)

/**
 * Folia-safe music playback.
 *
 * Threading model:
 * - Every [Playback] is created, advanced, and removed **only on the global region thread** (one
 *   `GlobalRegionScheduler` clock task plus `execute` hops from commands, GUI clicks, and events).
 * - Sounds, stop-sounds, and bossbars are applied on each listener's own `EntityScheduler`; every clock tick
 *   schedules at most one task per online player, which plays all of that tick's due notes within range.
 * - Jukebox blocks are never touched here; disc ejection is delegated to [discEjector], which hops to the
 *   block's `RegionScheduler`.
 */
class MusicService(
    private val plugin: JavaPlugin,
    private val settings: () -> JukeboxSettings,
) {
    @Volatile
    var catalog: SongCatalog = SongCatalog.EMPTY

    /** Called on the global thread when a jukebox playback with an inserted disc ends; must hop regions itself. */
    @Volatile
    var discEjector: (BlockPos, String) -> Unit = { _, _ -> }

    private val playbacks = ConcurrentHashMap<UUID, Playback>()
    private val bossBars = ConcurrentHashMap<UUID, BossBar>()
    private val personalVolumes = ConcurrentHashMap<UUID, Float>()
    private val shuffle: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private var clockTask: ScheduledTask? = null

    class Playback(
        song: Song,
        val source: BlockPos?,
        /** Where sound is emitted: the jukebox centre, or the controller's last known location. */
        @Volatile var at: Location,
        @Volatile var jukeboxVolume: Float,
        @Volatile var discToken: String?,
    ) {
        @Volatile var song: Song = song
            private set
        /** Global-thread only. */
        var clock = SongClock(song)
            private set
        @Volatile var paused = false
        @Volatile var active = true
        val noteAudience: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val resourceAudience: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val activeSounds: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun switchTo(next: Song) {
            song = next
            clock = SongClock(next)
        }
    }

    private class Emission(
        val playback: Playback,
        val at: Location,
        val sound: String,
        val volume: Float,
        val pitch: Float,
        val resource: Boolean,
    )

    private class ControllerUpdate(val playback: Playback, val title: String?, val progress: Float)

    fun start() {
        val interval = settings().tickInterval
        clockTask = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { tick() }, 1L, interval)
    }

    // ---------------------------------------------------------------- queries (any thread)

    fun current(player: UUID): Song? = playbacks[player]?.song
    fun currentAt(pos: BlockPos): Song? = playbackAt(pos)?.second?.song
    fun isShuffling(player: UUID): Boolean = player in shuffle
    val defaultVolume: Float get() = settings().defaultVolume
    fun personalVolume(player: UUID): Float = personalVolumes[player] ?: defaultVolume
    fun activePlaybackCount(): Int = playbacks.size

    /** Sizes of every per-player store, for leak checks (`/femitems stats`); all must return to baseline. */
    fun stateSizes(): Map<String, Int> = linkedMapOf(
        "playbacks" to playbacks.size,
        "bossbars" to bossBars.size,
        "volumes" to personalVolumes.size,
        "shuffle" to shuffle.size,
        "listeners" to playbacks.values.sumOf { it.noteAudience.size + it.resourceAudience.size },
    )

    // ---------------------------------------------------------------- commands (any thread → global)

    /**
     * Starts [song] for [controller]. For a personal playback ([jukebox] == null) this reads the controller's
     * location, so call it on the controller's own thread (command/event/GUI handlers already are).
     */
    fun play(controller: Player, song: Song, jukebox: JukeboxStart? = null) {
        val id = controller.uniqueId
        val at = jukebox?.pos?.center() ?: controller.location.clone()
        global {
            if (jukebox != null) {
                playbackAt(jukebox.pos)?.let { (owner, state) -> if (owner != id) clear(owner, state, eject = true) }
            }
            playbacks[id]?.let { clear(id, it, eject = true) }
            val state = Playback(
                song = song,
                source = jukebox?.pos,
                at = at,
                jukeboxVolume = jukebox?.volume ?: settings().defaultVolume,
                discToken = jukebox?.discToken,
            )
            playbacks[id] = state
            if (song.isResourcePackTrack) dispatch(startResourceTrack(state), emptyMap())
            controller.sendMessage(JukeboxMessages.nowPlaying(song.title))
        }
    }

    fun togglePause(player: Player, jukebox: BlockPos?) = global {
        val state = (if (jukebox != null) playbackAt(jukebox)?.second else playbacks[player.uniqueId])
        when {
            state == null -> player.sendMessage(JukeboxMessages.nothingPlaying())
            state.paused -> resume(player, state)
            else -> pause(player, state)
        }
    }

    fun pause(player: Player) = global {
        val state = playbacks[player.uniqueId]
        if (state == null || state.paused) player.sendMessage(JukeboxMessages.nothingPlaying()) else pause(player, state)
    }

    fun resume(player: Player) = global {
        val state = playbacks[player.uniqueId]
        if (state == null || !state.paused) player.sendMessage(JukeboxMessages.nothingPaused()) else resume(player, state)
    }

    /** Stops [player]'s own playback, or whatever plays at [jukebox]. Ejects an inserted disc. */
    fun stop(player: Player, jukebox: BlockPos?, quiet: Boolean = false) = global {
        val entry = if (jukebox != null) playbackAt(jukebox) else playbacks[player.uniqueId]?.let { player.uniqueId to it }
        if (entry == null) {
            if (!quiet) player.sendMessage(JukeboxMessages.nothingPlaying())
            return@global
        }
        clear(entry.first, entry.second, eject = true)
        if (!quiet) player.sendMessage(JukeboxMessages.stopped())
    }

    /** Stops playback at a jukebox that is being removed; its disc is handled by the caller. */
    fun stopAt(pos: BlockPos) = global {
        playbackAt(pos)?.let { (owner, state) -> clear(owner, state, eject = false) }
    }

    fun toggleShuffle(player: Player) {
        val id = player.uniqueId
        val enabled = shuffle.add(id) || !shuffle.remove(id)
        player.sendMessage(JukeboxMessages.shuffle(enabled))
    }

    fun adjustPersonalVolume(player: Player, delta: Float): Float {
        val value = SoundResolver.stepVolume(personalVolume(player.uniqueId), delta)
        personalVolumes[player.uniqueId] = value
        global { playbacks[player.uniqueId]?.takeIf { it.source == null }?.let(::stopNotes) }
        player.sendMessage(JukeboxMessages.volume(value))
        return value
    }

    /** Applies a jukebox's new shared volume (already persisted on the block) to its active playback. */
    fun applyJukeboxVolume(pos: BlockPos, value: Float) = global {
        val state = playbackAt(pos)?.second ?: return@global
        state.jukeboxVolume = value
        if (state.song.isResourcePackTrack) {
            stopResourceTrack(state)
            state.clock.reset()
            if (!state.paused) dispatch(startResourceTrack(state), emptyMap())
        } else {
            stopNotes(state)
        }
    }

    fun playerQuit(id: UUID) {
        shuffle.remove(id)
        personalVolumes.remove(id)
        global {
            playbacks[id]?.let { clear(id, it, eject = true) }
            for (state in playbacks.values) {
                state.noteAudience.remove(id)
                state.resourceAudience.remove(id)
            }
            bossBars.remove(id)
        }
    }

    fun shutdown() {
        clockTask?.cancel()
        clockTask = null
        // The plugin is already disabled, so nothing can be scheduled: only touch players this thread owns
        // (all of them on Paper's main thread; on Folia the server is shutting down and they are leaving).
        for (state in playbacks.values) {
            state.active = false
            val sounds = state.activeSounds + listOfNotNull(state.song.soundEvent)
            for (id in state.noteAudience + state.resourceAudience) {
                val player = Bukkit.getPlayer(id) ?: continue
                if (Bukkit.isOwnedByCurrentRegion(player)) sounds.forEach { player.stopSound(it, SoundCategory.RECORDS) }
            }
        }
        for ((id, bar) in bossBars) {
            val player = Bukkit.getPlayer(id) ?: continue
            if (Bukkit.isOwnedByCurrentRegion(player)) player.hideBossBar(bar)
        }
        playbacks.clear()
        bossBars.clear()
        personalVolumes.clear()
        shuffle.clear()
    }

    // ---------------------------------------------------------------- global-thread internals

    private fun global(action: () -> Unit) {
        if (!plugin.isEnabled) return
        plugin.server.globalRegionScheduler.execute(plugin, action)
    }

    private fun playbackAt(pos: BlockPos): Pair<UUID, Playback>? =
        playbacks.entries.firstOrNull { it.value.source == pos }?.let { it.key to it.value }

    private fun tick() {
        val config = settings()
        val emissions = ArrayList<Emission>()
        val controllers = HashMap<UUID, ControllerUpdate>()
        for ((owner, state) in playbacks) {
            if (Bukkit.getPlayer(owner) == null) {
                clear(owner, state, eject = true)
                continue
            }
            val title = if (config.bossBar) state.song.title + if (state.paused) " (Paused)" else "" else null
            controllers[owner] = ControllerUpdate(state, title, state.clock.remaining)
            if (state.paused) continue

            for (note in state.clock.advance(config.tickInterval)) {
                val sound = note.soundEvent?.takeIf(String::isNotBlank)
                    ?: noteBlockSound(note.instrument) ?: continue
                if (note.stop) {
                    stopSounds(state.noteAudience.toList(), listOf(sound))
                    state.activeSounds.remove(sound)
                    continue
                }
                val (volume, pitch) = if (note.soundEvent.isNullOrBlank()) {
                    note.velocity / 100f to Math.pow(2.0, (note.key - 45) / 12.0).toFloat()
                } else {
                    note.volume to note.pitch
                }
                emissions += Emission(state, state.at, sound, volume, pitch, resource = false)
                state.activeSounds += sound
            }
            if (state.clock.finished) finish(owner, state, emissions)
        }
        dispatch(emissions, controllers)
    }

    private fun finish(owner: UUID, state: Playback, emissions: MutableList<Emission>) {
        stopResourceTrack(state)
        stopNotes(state)
        ejectDisc(state)
        val controller = Bukkit.getPlayer(owner)
        if (owner in shuffle) {
            val next = catalog.noteSongs.filter { it.id != state.song.id }.randomOrNull()
            if (next != null) {
                state.switchTo(next)
                if (next.isResourcePackTrack) emissions += startResourceTrack(state)
                controller?.sendMessage(JukeboxMessages.nowPlaying(next.title))
                return
            }
        }
        controller?.sendMessage(JukeboxMessages.finished(state.song.title))
        clear(owner, state, eject = false)
    }

    private fun pause(player: Player, state: Playback) {
        state.paused = true
        stopResourceTrack(state)
        stopNotes(state)
        player.sendMessage(JukeboxMessages.paused())
    }

    private fun resume(player: Player, state: Playback) {
        state.paused = false
        if (state.song.isResourcePackTrack) {
            state.clock.reset()
            dispatch(startResourceTrack(state), emptyMap())
            player.sendMessage(JukeboxMessages.resumedFromStart())
        } else {
            player.sendMessage(JukeboxMessages.resumed())
        }
    }

    private fun clear(owner: UUID, state: Playback, eject: Boolean) {
        playbacks.remove(owner, state)
        if (!state.active) return
        state.active = false
        stopResourceTrack(state)
        stopNotes(state)
        val player = Bukkit.getPlayer(owner)
        val bar = bossBars[owner]
        if (player != null && bar != null) player.scheduler.run(plugin, { player.hideBossBar(bar) }, null)
        if (eject) ejectDisc(state)
    }

    private fun ejectDisc(state: Playback) {
        val pos = state.source ?: return
        val token = state.discToken ?: return
        state.discToken = null
        discEjector(pos, token)
    }

    private fun startResourceTrack(state: Playback): List<Emission> {
        val sound = state.song.soundEvent ?: return emptyList()
        return listOf(Emission(state, state.at, sound, 1f, 1f, resource = true))
    }

    private fun stopResourceTrack(state: Playback) {
        val sound = state.song.soundEvent ?: return
        val audience = state.resourceAudience.toList()
        state.resourceAudience.clear()
        stopSounds(audience, listOf(sound))
    }

    private fun stopNotes(state: Playback) {
        val audience = state.noteAudience.toList()
        val sounds = state.activeSounds.toList()
        state.noteAudience.clear()
        state.activeSounds.clear()
        if (sounds.isNotEmpty()) stopSounds(audience, sounds)
    }

    private fun stopSounds(targets: Collection<UUID>, sounds: Collection<String>) {
        for (id in targets) {
            val player = Bukkit.getPlayer(id) ?: continue
            player.scheduler.run(plugin, { sounds.forEach { player.stopSound(it, SoundCategory.RECORDS) } }, null)
        }
    }

    /** Hands each online player one task that plays every in-range emission and updates their bossbar. */
    private fun dispatch(emissions: List<Emission>, controllers: Map<UUID, ControllerUpdate>) {
        if (emissions.isEmpty() && controllers.isEmpty()) return
        val config = settings()
        val radiusSquared = config.radius * config.radius
        for (player in plugin.server.onlinePlayers) {
            val update = controllers[player.uniqueId]
            if (emissions.isEmpty() && update == null) continue
            player.scheduler.run(plugin, { deliver(player, emissions, update, radiusSquared, config) }, null)
        }
    }

    /** Runs on [player]'s own thread. */
    private fun deliver(
        player: Player,
        emissions: List<Emission>,
        update: ControllerUpdate?,
        radiusSquared: Double,
        config: JukeboxSettings,
    ) {
        val here = player.location
        for (emission in emissions) {
            val state = emission.playback
            if (!state.active || emission.at.world != here.world || emission.at.distanceSquared(here) > radiusSquared) continue
            val base = if (state.source != null) state.jukeboxVolume else personalVolume(player.uniqueId)
            player.playSound(emission.at, emission.sound, SoundCategory.RECORDS, base * emission.volume, emission.pitch)
            (if (emission.resource) state.resourceAudience else state.noteAudience) += player.uniqueId
        }
        if (update == null || !update.playback.active) return
        if (update.playback.source == null) update.playback.at = here.clone()
        if (update.title != null) {
            val bar = bossBars.computeIfAbsent(player.uniqueId) {
                BossBar.bossBar(Component.empty(), 1f, config.bossBarColor, BossBar.Overlay.PROGRESS)
            }
            bar.name(Component.text(update.title)).progress(update.progress).color(config.bossBarColor)
            player.showBossBar(bar)
        }
    }
}
