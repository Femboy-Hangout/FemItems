package dev.femitems.plugin.items

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * What custom items do besides their right-click ability:
 * - **Refresh:** copies players already have pick up items.yml changes (stats, name, lore, ability) on join,
 *   after a reload, when held, picked up, or when an inventory is closed.
 * - **Soulbound** (`soulbound: true`): kept on death instead of dropped.
 * - **On-hit** (`on-hit:`): a light particle/sound touch, with an occasional small extra, on fully charged hits.
 *
 * Threading: every handler runs on the thread that owns the player (or the hit entity, which a melee attacker is
 * next to); [refreshOnline] hops onto each player's own scheduler.
 */
class ItemPerkService(private val plugin: JavaPlugin, private val items: CustomItemService) : Listener {
    /** Player -> whether their swing in progress is fully charged. */
    private val fullSwings = ConcurrentHashMap<UUID, Boolean>()

    /** After `/femitems reload`: brings every online player's items up to date on their own thread. */
    fun refreshOnline() {
        for (player in plugin.server.onlinePlayers) {
            player.scheduler.run(plugin, { refreshPlayer(player) }, null)
        }
    }

    private fun refreshPlayer(player: Player) {
        if (!player.isOnline) return
        items.refreshAll(player.inventory)
        items.refreshAll(player.enderChest)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = refreshPlayer(event.player)

    @EventHandler(ignoreCancelled = true)
    fun onHeld(event: PlayerItemHeldEvent) {
        val inventory = event.player.inventory
        items.refreshed(inventory.getItem(event.newSlot))?.let { inventory.setItem(event.newSlot, it) }
    }

    /** Items taken out of chests or shulkers update as soon as the chest is closed. */
    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        items.refreshAll(player.inventory)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val fresh = items.refreshed(event.item.itemStack) ?: return
        event.item.itemStack = fresh
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(event: PlayerDeathEvent) {
        if (event.keepInventory) return
        val definitions = items.current.definitions
        val iterator = event.drops.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val id = items.idOf(item) ?: continue
            if (definitions[id]?.soulbound != true) continue
            iterator.remove()
            event.itemsToKeep.add(item)
        }
    }

    /** Vanilla resets the attack charge before the damage event, so it is read here, just before the swing. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onSwing(event: PrePlayerAttackEntityEvent) {
        if (event.willAttack()) fullSwings[event.player.uniqueId] = event.player.attackCooldown >= FULL_SWING
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        fullSwings.remove(event.player.uniqueId)
    }

    /** Players currently tracked for full swings (for `/femitems stats`; must drop back as players leave). */
    fun trackedSwings(): Int = fullSwings.size

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHit(event: EntityDamageByEntityEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return // not sweeps, thorns, projectiles
        val player = event.damager as? Player ?: return
        val target = event.entity as? LivingEntity ?: return
        val id = items.idOf(player.inventory.itemInMainHand) ?: return
        val effect = items.current.definitions[id]?.onHit ?: return
        // Only full swings, so spam-clicking adds nothing.
        if (fullSwings.remove(player.uniqueId) != true) return
        val at = target.location.add(0.0, target.height * 0.6, 0.0)
        effect.particle?.let { name ->
            runCatching { Particle.valueOf(name) }.getOrNull()?.let {
                target.world.spawnParticle(it, at, effect.particleCount, 0.3, 0.35, 0.3, 0.02)
            }
        }
        effect.sound?.let { target.world.playSound(at, it, SoundCategory.PLAYERS, 0.6f, 1.2f) }
        if (effect.chancePercent < 100.0 && ThreadLocalRandom.current().nextDouble(100.0) >= effect.chancePercent) return
        effect.effect?.let { key ->
            val type = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(key) ?: return@let
            target.addPotionEffect(PotionEffect(type, (effect.durationSeconds * 20).toInt().coerceAtLeast(1), effect.level - 1))
        }
        if (effect.fireSeconds > 0) target.fireTicks = maxOf(target.fireTicks, (effect.fireSeconds * 20).toInt())
        if (effect.heal > 0 && !player.isDead) {
            val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            player.health = (player.health + effect.heal).coerceAtMost(max)
        }
    }

    private companion object {
        const val FULL_SWING = 0.9f
    }
}
