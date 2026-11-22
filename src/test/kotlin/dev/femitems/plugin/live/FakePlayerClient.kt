package dev.femitems.plugin.live

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.geysermc.mcprotocollib.network.Session
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory
import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

                    is ClientboundSystemChatPacket -> {
                        val plain = PlainTextComponentSerializer.plainText().serialize(packet.content)
                        chatMessages.add(plain)
                    }

                    is ClientboundContainerSetSlotPacket -> {
                        val item = packet.item
                        if (item != null && item.amount > 0) {
                            receivedItems.add(item)
                        }
                    }

                    is ClientboundContainerSetContentPacket -> {
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

    fun sendCommand(command: String) {
        val active = checkNotNull(session) { "Fake player '$username' is not connected" }
        check(active.isConnected) {
            "Fake player '$username' disconnected before command '$command': ${disconnectReason.get()}"
        }
        active.send(ServerboundChatCommandPacket(command.removePrefix("/")))
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
    }

    private companion object {
        const val MAX_CAPTURED_EVENTS = 512
    }
}
