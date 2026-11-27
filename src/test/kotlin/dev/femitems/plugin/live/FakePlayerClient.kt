package dev.femitems.plugin.live

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.cloudburstmc.math.vector.Vector3i
import org.geysermc.mcprotocollib.network.Session
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEquipmentPacket
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.ModifierOperation
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityMotionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateMobEffectPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateAttributesPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket
import org.geysermc.mcprotocollib.protocol.data.game.entity.`object`.Direction
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStopSoundPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundUpdateRecipesPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A resource pack the server offered. Like a real client, the fake player downloads it, checks the SHA-1,
 * and reports ACCEPTED -> DOWNLOADED -> SUCCESSFULLY_LOADED (or FAILED_DOWNLOAD) back to the server.
 */
data class OfferedPack(val url: String, val hash: String, val required: Boolean, val bytes: ByteArray?, val sha1Matches: Boolean)

/** An item seen in a container slot ([containerId] 0 is the player's own inventory; armour head is slot 5). */
data class SlotUpdate(val containerId: Int, val slot: Int, val item: ItemStack?)

/** A sound the client was told to play. */
data class CapturedSound(val name: String, val volume: Float, val pitch: Float)

/** A container screen the server opened on the client. [id] is needed to click its slots. */
data class OpenedScreen(val id: Int, val title: String)

/** An entity that appeared for this client; [type] is MCProtocolLib's EntityType name (e.g. `PIG`). */
data class SpawnedEntity(val id: Int, val type: String)

/** A health value the server synced for a living entity. */
data class EntityHealth(val entityId: Int, val health: Float)

/** The attribute's effective value, computed from its base and modifiers exactly as the vanilla client does. */
internal fun attributeValue(attribute: Attribute): Double {
    val modifiers = attribute.modifiers
    var base = attribute.value
    for (modifier in modifiers) if (modifier.operation == ModifierOperation.ADD) base += modifier.amount
    var total = base
    for (modifier in modifiers) if (modifier.operation == ModifierOperation.ADD_MULTIPLIED_BASE) total += base * modifier.amount
    for (modifier in modifiers) if (modifier.operation == ModifierOperation.ADD_MULTIPLIED_TOTAL) total *= 1.0 + modifier.amount
    return total
}

internal class BoundedEventBuffer<T>(private val capacity: Int) {
    private val entries = ArrayDeque<T>()
    private var totalAdded = 0

    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    @Synchronized
    fun add(value: T) {
        entries.addLast(value)
        totalAdded++
        if (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun count(): Int = totalAdded

    @Synchronized
    fun snapshot(): List<T> = entries.toList()

    @Synchronized
    fun snapshotSince(index: Int): List<T> {
        val oldestRetainedIndex = totalAdded - entries.size
        val skip = (index - oldestRetainedIndex).coerceAtLeast(0)
        return entries.drop(skip)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalAdded = 0
    }
}

/**
 * Headless Minecraft 26.3 fake player backed by MCProtocolLib.
 * Connects over real TCP protocol to an offline-mode Paper or Folia server,
 * completes spawn handshake so chunks tick and items can be picked up,
 * sends player commands, and captures received system chat and inventory packets.
 */
class FakePlayerClient(
    val username: String,
    private val host: String = "127.0.0.1",
    private val port: Int,
) : AutoCloseable {

    private val loginLatch = CountDownLatch(1)
    private val spawnedLatch = CountDownLatch(1)
    private val loadedSent = AtomicBoolean(false)
    private val disconnectReason = AtomicReference<String?>()
    private val chatMessages = BoundedEventBuffer<String>(MAX_CAPTURED_EVENTS)
    private val receivedItems = BoundedEventBuffer<ItemStack>(MAX_CAPTURED_EVENTS)
    private val recipeResyncs = AtomicInteger(0)
    private val sounds = BoundedEventBuffer<CapturedSound>(MAX_CAPTURED_EVENTS)
    private val stoppedSounds = BoundedEventBuffer<String>(MAX_CAPTURED_EVENTS)
    private val openedScreens = BoundedEventBuffer<OpenedScreen>(MAX_CAPTURED_EVENTS)
    private val containerStateIds = ConcurrentHashMap<Int, Int>()
    private val offeredPacks = BoundedEventBuffer<OfferedPack>(MAX_CAPTURED_EVENTS)
    private val slotUpdates = BoundedEventBuffer<SlotUpdate>(MAX_CAPTURED_EVENTS)
    private val seenEquipment = BoundedEventBuffer<String>(MAX_CAPTURED_EVENTS)
    private val blockSequence = AtomicInteger(0)
    private val ownEntityId = AtomicInteger(-1)
    /** This player's own attribute values (e.g. `minecraft:armor`), computed from base + modifiers like the client does. */
    private val ownAttributes = ConcurrentHashMap<String, Double>()
    private val spawnedEntities = BoundedEventBuffer<SpawnedEntity>(MAX_CAPTURED_EVENTS)
    private val entityHealth = BoundedEventBuffer<EntityHealth>(MAX_CAPTURED_EVENTS)
    /** Absolute positions the server moved this player to (teleports), as x/y/z. */
    private val teleports = BoundedEventBuffer<List<Double>>(MAX_CAPTURED_EVENTS)
    /** Velocities the server gave this player (x, y, z), e.g. from a leap. */
    private val ownMotion = BoundedEventBuffer<List<Double>>(MAX_CAPTURED_EVENTS)
    private val ownHealth = BoundedEventBuffer<Float>(MAX_CAPTURED_EVENTS)
    /** Potion effects added to this player, as `EFFECT:amplifier`. */
    private val ownEffects = BoundedEventBuffer<String>(MAX_CAPTURED_EVENTS)

    private var session: ClientNetworkSession? = null

    fun connectAndAwaitSpawn(timeout: Duration = Duration.ofSeconds(30)) {
        val protocol = MinecraftProtocol(username)
        val client = ClientNetworkSessionFactory.factory()
            .setAddress(host, port)
            .setProtocol(protocol)
            .create()

        client.addListener(object : SessionAdapter() {
            override fun packetReceived(session: Session, packet: Packet) {
                when (packet) {
                    is ClientboundLoginPacket -> {
                        ownEntityId.set(packet.entityId)
                        session.send(
                            ServerboundClientInformationPacket(
                                "en_US",
                                2,
                                ChatVisibility.FULL,
                                true,
                                SkinPart.entries,
                                HandPreference.RIGHT_HAND,
                                false,
                                true,
                                ParticleStatus.ALL,
                            ),
                        )
                        loginLatch.countDown()
                    }

                    is ClientboundPlayerPositionPacket -> {
                        val pos = packet.position
                        teleports.add(listOf(pos.x, pos.y, pos.z))
                        session.send(
                            ServerboundAcceptTeleportationPacket(
                                packet.id,
                                pos.x,
                                pos.y,
                                pos.z,
                                packet.yRot,
                                packet.xRot,
                            ),
                        )
                        if (loadedSent.compareAndSet(false, true)) {
                            session.send(ServerboundPlayerLoadedPacket.INSTANCE)
                        }
                        session.send(ServerboundClientTickEndPacket.INSTANCE)
                        spawnedLatch.countDown()
                    }

                    // Sent once per server-wide recipe/resource resync (PlayerList.reloadResources),
                    // which also re-reads and re-sends every player's advancements. Expensive.
                    is ClientboundUpdateRecipesPacket -> recipeResyncs.incrementAndGet()

                    is ClientboundUpdateAttributesPacket -> if (packet.entityId == ownEntityId.get()) {
                        for (attribute in packet.attributes) ownAttributes[attribute.type.identifier.asString()] = attributeValue(attribute)
                    }

                    is ClientboundAddEntityPacket -> spawnedEntities.add(SpawnedEntity(packet.entityId, packet.type.name))

                    is ClientboundSetEntityMotionPacket -> if (packet.entityId == ownEntityId.get()) {
                        ownMotion.add(listOf(packet.movement.x, packet.movement.y, packet.movement.z))
                    }

                    is ClientboundSetHealthPacket -> ownHealth.add(packet.health)

                    is ClientboundUpdateMobEffectPacket -> if (packet.entityId == ownEntityId.get()) {
                        ownEffects.add("${packet.effect}:${packet.amplifier}")
                    }

                    // Living entities sync their health to every viewer (metadata index 9).
                    is ClientboundSetEntityDataPacket -> for (data in packet.metadata) {
                        val health = data.value
                        if (data.id == 9 && health is Float) entityHealth.add(EntityHealth(packet.entityId, health))
                    }

                    is ClientboundSystemChatPacket -> {
                        val plain = PlainTextComponentSerializer.plainText().serialize(packet.content)
                        chatMessages.add(plain)
                    }

                    is ClientboundSoundPacket -> sounds.add(CapturedSound(packet.sound.name, packet.volume, packet.pitch))

                    // A stop without a sound key stops every sound (in the category).
                    is ClientboundStopSoundPacket -> stoppedSounds.add(packet.sound?.asString() ?: "*")

                    is ClientboundOpenScreenPacket -> openedScreens.add(
                        OpenedScreen(packet.containerId, PlainTextComponentSerializer.plainText().serialize(packet.title)),
                    )

                    is ClientboundResourcePackPushPacket -> handlePackOffer(session, packet)

                    // What this client sees other entities wearing/holding (rendered from these items).
                    is ClientboundSetEquipmentPacket -> for (equipment in packet.equipment) {
                        equipment.item?.let { seenEquipment.add("${equipment.slot}=${it.dataComponentsPatch}") }
                    }

                    is ClientboundContainerSetSlotPacket -> {
                        containerStateIds[packet.containerId] = packet.stateId
                        slotUpdates.add(SlotUpdate(packet.containerId, packet.slot, packet.item))
                        val item = packet.item
                        if (item != null && item.amount > 0) {
                            receivedItems.add(item)
                        }
                    }

                    is ClientboundContainerSetContentPacket -> {
                        containerStateIds[packet.containerId] = packet.stateId
                        // A full resync (e.g. after a click the server corrected) carries every slot at once.
                        packet.items.forEachIndexed { index, item ->
                            if (item != null && item.amount > 0) slotUpdates.add(SlotUpdate(packet.containerId, index, item))
                        }
                        for (item in packet.items) {
                            if (item != null && item.amount > 0) {
                                receivedItems.add(item)
                            }
                        }
                    }
                }
            }

            override fun disconnected(event: DisconnectedEvent) {
                val reasonText = PlainTextComponentSerializer.plainText().serialize(event.reason)
                disconnectReason.set(reasonText.ifBlank { event.cause?.message ?: "unknown" })
                loginLatch.countDown()
                spawnedLatch.countDown()
            }
        })

        session = client
        client.connect(true)

        val loggedIn = loginLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!loggedIn || disconnectReason.get() != null) {
            throw AssertionError(
                "Fake player '$username' failed to log into $host:$port " +
                    "(disconnected=${disconnectReason.get()}, connected=${client.isConnected})",
            )
        }

        val spawned = spawnedLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!spawned || !client.isConnected) {
            throw AssertionError(
                "Fake player '$username' logged in but did not finish spawning " +
                    "(disconnected=${disconnectReason.get()}, connected=${client.isConnected})",
            )
        }
    }

    fun chatCount(): Int = chatMessages.count()

    fun chatMessagesSnapshot(): List<String> = chatMessages.snapshot()

    /** Number of full recipe resyncs (ClientboundUpdateRecipesPacket) received since connecting. */
    fun recipeResyncCount(): Int = recipeResyncs.get()


    fun sendCommand(command: String) {
        val active = checkNotNull(session) { "Fake player '$username' is not connected" }
        check(active.isConnected) {
            "Fake player '$username' disconnected before command '$command': ${disconnectReason.get()}"
        }
        active.send(ServerboundChatCommandPacket(command.removePrefix("/")))
    }

    /** Right-clicks the top face of the block at [x], [y], [z] with the main hand. */
    fun useItemOnBlock(x: Int, y: Int, z: Int) {
        send(
            ServerboundUseItemOnPacket(
                Vector3i.from(x, y, z),
                Direction.UP,
                Hand.MAIN_HAND,
                0.5f,
                1f,
                0.5f,
                false,
                false,
                blockSequence.incrementAndGet(),
            ),
        )
    }

    /** Left-clicks [slot] (0-based, top inventory first) of a container screen the server opened. */
    fun clickContainerSlot(screen: OpenedScreen, slot: Int) {
        send(
            ServerboundContainerClickPacket(
                screen.id,
                containerStateIds[screen.id] ?: 0,
                slot,
                ContainerActionType.CLICK_ITEM,
                ClickItemAction.LEFT_CLICK,
                null,
                HashMap<Int, HashedStack>() as Map<Int, HashedStack>,
            ),
        )
    }

    private fun handlePackOffer(session: Session, packet: ClientboundResourcePackPushPacket) {
        Thread({
            session.send(ServerboundResourcePackPacket(packet.id, ResourcePackStatus.ACCEPTED))
            val bytes = runCatching {
                HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(packet.url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                ).takeIf { it.statusCode() == 200 }?.body()
            }.getOrNull()
            val sha1 = bytes?.let { MessageDigest.getInstance("SHA-1").digest(it).joinToString("") { b -> "%02x".format(b) } }
            val matches = sha1 != null && sha1.equals(packet.hash, ignoreCase = true)
            if (matches) {
                session.send(ServerboundResourcePackPacket(packet.id, ResourcePackStatus.DOWNLOADED))
                session.send(ServerboundResourcePackPacket(packet.id, ResourcePackStatus.SUCCESSFULLY_LOADED))
            } else {
                session.send(ServerboundResourcePackPacket(packet.id, ResourcePackStatus.FAILED_DOWNLOAD))
            }
            offeredPacks.add(OfferedPack(packet.url, packet.hash, packet.isRequired, bytes, matches))
        }, "$username-pack-download").apply { isDaemon = true }.start()
    }

    /**
     * Right-clicks with the held item in the air (e.g. equips armour). Like a real client, the packet carries where
     * the player looks ([yaw], [pitch]) and the server applies it.
     */
    fun useHeldItem(yaw: Float = 0f, pitch: Float = 0f) {
        send(ServerboundUseItemPacket(Hand.MAIN_HAND, blockSequence.incrementAndGet(), yaw, pitch))
    }

    fun offeredPackCount(): Int = offeredPacks.count()

    /** Waits until the client has handled a resource pack offer (downloaded and hash-checked it). */
    fun awaitPackOffer(sinceIndex: Int = 0, timeout: Duration = Duration.ofSeconds(20)): OfferedPack =
        awaitEvent(offeredPacks, "resource pack offer", sinceIndex, timeout) { true }

    fun slotUpdateCount(): Int = slotUpdates.count()

    fun awaitSlotUpdate(description: String, sinceIndex: Int = 0, timeout: Duration = Duration.ofSeconds(15), predicate: (SlotUpdate) -> Boolean): SlotUpdate =
        awaitEvent(slotUpdates, description, sinceIndex, timeout, predicate)

    fun seenEquipmentCount(): Int = seenEquipment.count()

    /** Waits until this client sees an entity wearing/holding an item whose components contain [substring]. */
    fun awaitSeenEquipmentContaining(substring: String, sinceIndex: Int = 0, timeout: Duration = Duration.ofSeconds(15)): String =
        awaitEvent(seenEquipment, "equipment containing '$substring'", sinceIndex, timeout) { it.contains(substring) }

    /** Closes a container screen, as pressing Esc does. */
    fun closeContainer(screen: OpenedScreen) {
        send(ServerboundContainerClosePacket(screen.id))
    }

    /**
     * Mines the block at [x], [y], [z] as a player would: start digging, hold for [hold], finish.
     * Survival needs enough time (or Haste) for the block's hardness; creative breaks instantly.
     */
    fun breakBlock(x: Int, y: Int, z: Int, hold: Duration = Duration.ofSeconds(1)) {
        val position = Vector3i.from(x, y, z)
        send(ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, position, Direction.UP, blockSequence.incrementAndGet()))
        Thread.sleep(hold.toMillis())
        send(ServerboundPlayerActionPacket(PlayerAction.FINISH_DIGGING, position, Direction.UP, blockSequence.incrementAndGet()))
    }

    private fun send(packet: Packet) {
        val active = checkNotNull(session) { "Fake player '$username' is not connected" }
        check(active.isConnected) { "Fake player '$username' disconnected: ${disconnectReason.get()}" }
        active.send(packet)
    }

    /** Selects hotbar slot [slot] (0-8), like scrolling or pressing a number key. */
    fun selectHotbarSlot(slot: Int) {
        send(ServerboundSetCarriedItemPacket(slot))
    }

    /** Left-clicks (attacks) the entity with network id [entityId] using the held item. */
    fun attack(entityId: Int) {
        send(ServerboundAttackPacket(entityId))
    }

    /** Waits until this player's own [attribute] (e.g. `minecraft:armor`) is [expected], as the client computes it. */
    fun awaitOwnAttribute(attribute: String, expected: Double, timeout: Duration = Duration.ofSeconds(15)) {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            val value = ownAttributes[attribute]
            if (value != null && Math.abs(value - expected) < 1e-6) return
            if (disconnectReason.get() != null) throw AssertionError("Fake player '$username' disconnected: ${disconnectReason.get()}")
            Thread.sleep(50)
        }
        throw AssertionError("Fake player '$username': $attribute is ${ownAttributes[attribute]}, expected $expected. All: $ownAttributes")
    }

    fun teleportCount(): Int = teleports.count()

    fun motionCount(): Int = ownMotion.count()

    fun awaitMotion(description: String, sinceIndex: Int = 0, timeout: Duration = Duration.ofSeconds(15), predicate: (List<Double>) -> Boolean): List<Double> =
        awaitEvent(ownMotion, description, sinceIndex, timeout, predicate)

    fun healthCount(): Int = ownHealth.count()

    fun awaitHealth(description: String, sinceIndex: Int = 0, timeout: Duration = Duration.ofSeconds(15), predicate: (Float) -> Boolean): Float =
        awaitEvent(ownHealth, description, sinceIndex, timeout, predicate)

    fun effectCount(): Int = ownEffects.count()

    fun awaitEffect(description: String, sinceIndex: Int = 0, timeout: Duration = Duration.ofSeconds(15), predicate: (String) -> Boolean): String =
        awaitEvent(ownEffects, description, sinceIndex, timeout, predicate)

    /** Waits for the server to move this player to a position matching [predicate] (x, y, z). */
    fun awaitTeleport(description: String, sinceIndex: Int = 0, timeout: Duration = Duration.ofSeconds(15), predicate: (List<Double>) -> Boolean): List<Double> =
        awaitEvent(teleports, description, sinceIndex, timeout, predicate)

    fun spawnedEntityCount(): Int = spawnedEntities.count()

    /** Waits for an entity of [type] (MCProtocolLib name, e.g. `PIG`) to appear for this client. */
    fun awaitSpawnedEntity(type: String, sinceIndex: Int = 0, timeout: Duration = Duration.ofSeconds(15)): SpawnedEntity =
        awaitEvent(spawnedEntities, "spawned $type", sinceIndex, timeout) { it.type == type }

    fun entityHealthCount(): Int = entityHealth.count()

    /** Waits for a health update of [entityId] matching [predicate]. */
    fun awaitEntityHealth(entityId: Int, sinceIndex: Int = 0, timeout: Duration = Duration.ofSeconds(15), predicate: (Float) -> Boolean): EntityHealth =
        awaitEvent(entityHealth, "health update of entity $entityId", sinceIndex, timeout) { it.entityId == entityId && predicate(it.health) }

    fun soundCount(): Int = sounds.count()

    fun stoppedSoundCount(): Int = stoppedSounds.count()

    fun openedScreenCount(): Int = openedScreens.count()

    /** Waits for a played sound whose name contains [substring] (e.g. `note_block.harp`). */
    fun awaitSoundContaining(
        substring: String,
        sinceIndex: Int = 0,
        timeout: Duration = Duration.ofSeconds(15),
        predicate: (CapturedSound) -> Boolean = { true },
    ): CapturedSound =
        awaitEvent(sounds, "sound containing '$substring'", sinceIndex, timeout) { it.name.contains(substring) && predicate(it) }

    /** Waits for a stop-sound packet whose sound key contains [substring] (or a stop-all). */
    fun awaitStoppedSoundContaining(substring: String, sinceIndex: Int = 0, timeout: Duration = Duration.ofSeconds(15)): String =
        awaitEvent(stoppedSounds, "stopped sound containing '$substring'", sinceIndex, timeout) {
            it == "*" || it.contains(substring)
        }

    /** Waits for a container screen whose plain-text title contains [substring]. */
    fun awaitScreenTitleContaining(substring: String, sinceIndex: Int = 0, timeout: Duration = Duration.ofSeconds(15)): OpenedScreen =
        awaitEvent(openedScreens, "screen titled '$substring'", sinceIndex, timeout) { it.title.contains(substring) }

    private fun <T> awaitEvent(
        buffer: BoundedEventBuffer<T>,
        description: String,
        sinceIndex: Int,
        timeout: Duration,
        predicate: (T) -> Boolean,
    ): T {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            buffer.snapshotSince(sinceIndex).firstOrNull(predicate)?.let { return it }
            if (disconnectReason.get() != null) {
                throw AssertionError("Fake player '$username' disconnected while waiting for $description: ${disconnectReason.get()}")
            }
            Thread.sleep(50)
        }
        throw AssertionError(
            "Timed out waiting for fake player '$username' to receive $description. " +
                "Events since $sinceIndex: ${buffer.snapshotSince(sinceIndex)}",
        )
    }

    fun awaitChatContaining(
        substring: String,
        sinceIndex: Int = 0,
        timeout: Duration = Duration.ofSeconds(15),
    ): String {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            val snapshot = chatMessages.snapshotSince(sinceIndex)
            for (message in snapshot) {
                if (message.contains(substring)) {
                    return message
                }
            }
            if (disconnectReason.get() != null) {
                throw AssertionError(
                    "Fake player '$username' disconnected while waiting for chat '$substring': " +
                        "${disconnectReason.get()}. Received chat: $snapshot",
                )
            }
            Thread.sleep(100)
        }
        throw AssertionError(
            "Timed out waiting for fake player '$username' to receive chat containing '$substring'. " +
                "Messages since $sinceIndex: ${chatMessages.snapshotSince(sinceIndex)}",
        )
    }

    fun assertNoChatContaining(
        substring: String,
        sinceIndex: Int,
        waitDuration: Duration = Duration.ofSeconds(2),
    ) {
        Thread.sleep(waitDuration.toMillis())
        val recent = chatMessages.snapshotSince(sinceIndex)
        if (recent.any { it.contains(substring) }) {
            throw AssertionError(
                "Fake player '$username' unexpectedly received chat containing '$substring': $recent",
            )
        }
    }

    fun receivedItemCount(): Int = receivedItems.count()

    fun receivedItemsSnapshot(): List<ItemStack> = receivedItems.snapshot()

    fun awaitItemMatching(
        description: String,
        sinceIndex: Int = 0,
        timeout: Duration = Duration.ofSeconds(15),
        predicate: (ItemStack) -> Boolean,
    ): ItemStack {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            val snapshot = receivedItems.snapshotSince(sinceIndex)
            for (item in snapshot) {
                if (predicate(item)) {
                    return item
                }
            }
            if (disconnectReason.get() != null) {
                throw AssertionError(
                    "Fake player '$username' disconnected while waiting for item ($description): ${disconnectReason.get()}",
                )
            }
            Thread.sleep(100)
        }
        throw AssertionError(
            "Timed out waiting for fake player '$username' to receive item ($description). " +
                "Items since $sinceIndex: ${receivedItems.snapshotSince(sinceIndex)}",
        )
    }

    override fun close() {
        val active = session ?: return
        session = null
        if (active.isConnected) {
            active.disconnect("Integration test complete")
        }
        chatMessages.clear()
        receivedItems.clear()
        sounds.clear()
        stoppedSounds.clear()
        openedScreens.clear()
        containerStateIds.clear()
        offeredPacks.clear()
        slotUpdates.clear()
        seenEquipment.clear()
    }

    private companion object {
        const val MAX_CAPTURED_EVENTS = 512
    }
}
