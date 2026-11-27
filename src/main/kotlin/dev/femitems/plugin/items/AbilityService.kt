package dev.femitems.plugin.items

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.SmallFireball
import org.bukkit.potion.PotionEffect
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/** Pure helpers for abilities (unit-tested without a server). */
object Abilities {
    /** Teleports are checked every quarter block, so a wall can never be skipped over. */
    const val SWEEP_STEP = 0.25

    /** Shortest dash worth doing; anything less reports "no room" and keeps the cooldown free. */
    const val MIN_DISTANCE = 0.5

    /**
     * The farthest distance up to [distance] reachable in a straight line where every step along the way is
     * clear ([isClear] = the player fits there). Stops at the first blocked step, so it never passes through.
     */
    fun farthestClear(distance: Double, isClear: (Double) -> Boolean): Double {
        var reached = 0.0
        var next = SWEEP_STEP
        while (next < distance + 1e-9) {
            if (!isClear(next)) return reached
            reached = next
            next += SWEEP_STEP
        }
        if (reached < distance && isClear(distance)) reached = distance
        return reached
    }

    /** Leap: the look direction times [power], lifted so it is never below 0.4 upwards (works on flat ground). */
    fun leapVelocity(look: Vector, power: Double): Vector {
        val velocity = look.clone().normalize().multiply(power)
        velocity.y = (velocity.y.coerceAtLeast(0.0) + 0.4).coerceAtMost(power + 0.4)
        return velocity
    }

    /** Whole seconds left, rounded up (0.2 s left still says 1). */
    fun secondsLeft(remainingNanos: Long): Long = ceil(remainingNanos / 1_000_000_000.0).toLong().coerceAtLeast(1)

    /** Fills `{seconds}` in; "1 seconds" becomes "1 second". */
    fun cooldownText(template: String, seconds: Long): String {
        val text = if (seconds == 1L) template.replace("{seconds} seconds", "{seconds} second") else template
        return text.replace("{seconds}", seconds.toString())
    }
}

/**
 * Right-click abilities on custom items (`ability:` in items.yml), with per-player cooldowns.
 *
 * Threading: interact events run on the player's own region thread, which also owns the few blocks a dash
 * crosses (checked with `isOwnedByCurrentRegion`, so Folia never reads another region's blocks). Movement
 * goes through `teleportAsync`. Cooldown state is a concurrent map, cleared when a player leaves.
 */
class AbilityService(private val plugin: org.bukkit.plugin.java.JavaPlugin, private val items: CustomItemService) : Listener {

    private class PlayerState {
        /** Item id -> System.nanoTime() when it can be used again. */
        val readyAt = ConcurrentHashMap<String, Long>()
        @Volatile var lastTrigger = 0L
    }

    private val states = ConcurrentHashMap<UUID, PlayerState>()

    /** Players with ability state (for `/femitems stats`; must drop back as players leave). */
    fun trackedPlayers(): Int = states.size

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.useItemInHand() == Event.Result.DENY) return // e.g. a protection plugin blocks item use here
        val player = event.player
        val id = items.idOf(event.item) ?: return
        val ability = items.current.definitions[id]?.ability ?: return
        val block = event.clickedBlock
        // Right-clicking a door, chest, button... keeps doing that (sneak to use the ability instead).
        if (event.action == Action.RIGHT_CLICK_BLOCK && block != null && block.type.isInteractable && !player.isSneaking) return
        event.setUseItemInHand(Event.Result.DENY)
        event.setUseInteractedBlock(Event.Result.DENY)

        val now = System.nanoTime()
        val state = states.computeIfAbsent(player.uniqueId) { PlayerState() }
        // One click can arrive as both a block click and an air click; only the first one counts.
        if (now - state.lastTrigger < DOUBLE_CLICK_NANOS) return
        state.lastTrigger = now
        val ready = state.readyAt[id]
        if (ready != null && ready - now > 0) {
            player.sendMessage(CustomItemService.legacy(Abilities.cooldownText(ability.cooldownMessage, Abilities.secondsLeft(ready - now))))
            return
        }
        val used = when (ability.type) {
            AbilityType.TELEPORT -> teleport(player, ability.distance)
            AbilityType.LEAP -> leap(player, ability.power)
            AbilityType.HEAL -> heal(player, ability.amount)
            AbilityType.EFFECT -> effect(player, ability)
            AbilityType.FIREBALL -> fireball(player, ability.incendiary)
            AbilityType.LIGHTNING -> lightning(player, ability.distance, ability.damage)
        }
        if (used && ability.cooldownSeconds > 0) {
            state.readyAt[id] = now + (ability.cooldownSeconds * 1_000_000_000).toLong()
        }
    }

    /** Dashes up to [distance] blocks the way [player] faces (horizontally); false if there was no room. */
    private fun teleport(player: Player, distance: Double): Boolean {
        val origin = player.location
        val direction = origin.direction.setY(0)
        if (direction.lengthSquared() < 1e-6) return noRoom(player) // looking straight up or down
        direction.normalize()
        val world = player.world
        val box = player.boundingBox
        val border = world.worldBorder
        val reached = Abilities.farthestClear(distance) { step ->
            val offset: Vector = direction.clone().multiply(step)
            val target = origin.clone().add(offset)
            Bukkit.isOwnedByCurrentRegion(target) && border.isInside(target) && !world.hasCollisionsIn(box.clone().shift(offset))
        }
        if (reached < Abilities.MIN_DISTANCE) return noRoom(player)
        val destination: Location = origin.clone().add(direction.multiply(reached))
        world.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1f, 1.2f)
        player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept { moved ->
            if (moved) player.scheduler.run(plugin, { player.world.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1f, 1.2f) }, null)
        }
        return true
    }

    private fun noRoom(player: Player): Boolean = fail(player, NO_ROOM_MESSAGE)

    /** Tells [player] why nothing happened; the cooldown is not started. */
    private fun fail(player: Player, message: String): Boolean {
        player.sendMessage(CustomItemService.legacy(message))
        return false
    }

    /** Launches [player] the way they face, always with some lift so it works on flat ground. */
    private fun leap(player: Player, power: Double): Boolean {
        val velocity = Abilities.leapVelocity(player.location.direction, power)
        player.velocity = velocity
        player.world.playSound(player.location, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 1f, 1f)
        return true
    }

    private fun heal(player: Player, amount: Double): Boolean {
        val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        if (player.health >= max) return fail(player, FULL_HEALTH_MESSAGE)
        player.health = (player.health + amount).coerceAtMost(max)
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.6f, 1.6f)
        return true
    }

    private fun effect(player: Player, ability: ItemAbility): Boolean {
        val key = ability.effect ?: return false
        val type = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(key) ?: return false
        val ticks = (ability.durationSeconds * 20).toInt().coerceAtLeast(1)
        player.addPotionEffect(PotionEffect(type, ticks, ability.level - 1))
        player.world.playSound(player.location, Sound.BLOCK_BREWING_STAND_BREW, SoundCategory.PLAYERS, 0.8f, 1.4f)
        return true
    }

    private fun fireball(player: Player, incendiary: Boolean): Boolean {
        player.launchProjectile(SmallFireball::class.java).apply { setIsIncendiary(incendiary) }
        player.world.playSound(player.location, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 1f, 1f)
        return true
    }

    /** Strikes the block [player] looks at. Folia: only when every chunk in range belongs to this region. */
    private fun lightning(player: Player, distance: Double, damage: Boolean): Boolean {
        val radiusChunks = (distance / 16).toInt() + 1
        if (!Bukkit.isOwnedByCurrentRegion(player.location, radiusChunks)) return fail(player, NO_TARGET_MESSAGE)
        val target = player.getTargetBlockExact(distance.toInt(), FluidCollisionMode.NEVER) ?: return fail(player, NO_TARGET_MESSAGE)
        val strikeAt = target.location.add(0.5, 1.0, 0.5)
        if (damage) player.world.strikeLightning(strikeAt) else player.world.strikeLightningEffect(strikeAt)
        return true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        states.remove(event.player.uniqueId)
    }

    fun shutdown() {
        states.clear()
    }

    companion object {
        private const val DOUBLE_CLICK_NANOS = 150_000_000L
        const val NO_ROOM_MESSAGE = "&cNo room to teleport there."
        const val FULL_HEALTH_MESSAGE = "&cYou're already at full health."
        const val NO_TARGET_MESSAGE = "&cLook at a block within range."
    }
}
