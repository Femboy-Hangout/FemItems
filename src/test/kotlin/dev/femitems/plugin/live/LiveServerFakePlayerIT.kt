package dev.femitems.plugin.live

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertTrue

/**
 * End-to-end integration test suite that boots a real Paper 26.3 server and a real Folia 26.3 server,
 * connects headless fake players via MCProtocolLib 26.3, and verifies live plugin behavior:
 * - Startup & platform detection (Paper / Folia)
 * - Non-OP permission enforcement
 * - Player-initiated /femitems reload (region thread -> GlobalRegionScheduler on Folia)
 * - Player-initiated /femitems debug + live custom-named mob kill + custom drop pickup
 * - Live shaped & shapeless recipe registration in Minecraft's RecipeManager
 * - Invalid config reload error reporting without crashing
 * - Clean disconnect and zero-error server shutdown
 *
 * Run via Maven Failsafe:
 *   ./mvnw -B verify -P live-test
 *   ./mvnw -B verify -P live-test -Dlive.platform=paper
 *   ./mvnw -B verify -P live-test -Dlive.platform=folia
 */
class LiveServerFakePlayerIT {

    private val requestedPlatform: String =
        System.getProperty("live.platform", "all").lowercase()

    private val mcVersion: String =
        System.getProperty("live.mcVersion", "26.3")

    private val pluginVersion: String =
        System.getProperty("femitems.version")
            ?: error("Missing system property 'femitems.version'")

    private val pluginJar: Path =
        Path.of(System.getProperty("femitems.jar") ?: error("Missing system property 'femitems.jar'"))

    private val repoRoot: Path = Path.of("").toAbsolutePath()

    @Test
    fun `live Paper 26_3 server passes full fake-player functional suite`() {
        assumeTrue(requestedPlatform == "all" || requestedPlatform == "paper")
        runFullLiveFunctionalSuite(platform = "paper", expectedPlatformLabel = "Paper")
    }

    @Test
    fun `live Folia 26_3 server passes full fake-player functional suite`() {
        assumeTrue(requestedPlatform == "all" || requestedPlatform == "folia")
        runFullLiveFunctionalSuite(platform = "folia", expectedPlatformLabel = "Folia")
    }

    private fun runFullLiveFunctionalSuite(platform: String, expectedPlatformLabel: String) {
        LiveServerHarness(
            platform = platform,
            mcVersion = mcVersion,
            repoRoot = repoRoot,
            pluginJar = pluginJar,
        ).use { server ->
            server.start()

            // 1. Verify FemItems enabled cleanly with expected version & platform
            val startupLog = server.readLog()
            assertTrue(
                startupLog.contains("Initializing FemItems $pluginVersion on $expectedPlatformLabel."),
                "[$platform] Expected startup banner for $pluginVersion on $expectedPlatformLabel",
            )
            assertTrue(
                startupLog.contains("Loaded 5 mob rules and 0 recipes."),
                "[$platform] Expected default 5 mob rules and 0 recipes on startup",
            )

            // 2. Connect two real fake players: FakeOp (operator) and FakeGuest (non-operator)
            FakePlayerClient(username = "FakeOp", port = server.port).use { opPlayer ->
                FakePlayerClient(username = "FakeGuest", port = server.port).use { guestPlayer ->
                    opPlayer.connectAndAwaitSpawn()
                    guestPlayer.connectAndAwaitSpawn()

                    val logBeforeOp = server.readLog().length
                    server.sendConsoleCommand("op FakeOp")
                    server.awaitLog(Regex("""Made FakeOp a server operator"""), fromOffset = logBeforeOp)

                    // 3. Verify non-OP fake player cannot run /femitems reload
                    val guestChatMark = guestPlayer.chatCount()
                    guestPlayer.sendCommand("femitems reload")
                    guestPlayer.assertNoChatContaining("[FemItems] Config reloaded.", sinceIndex = guestChatMark)

                    // 4. Write deterministic config with guaranteed mob drops + shaped & shapeless recipes
                    Files.writeString(
                        server.pluginConfigFile(),
                        """
                        mobs:
                          champion:
                            entity: ANY
                            name-contains: "Champion"
                            drops:
                              - { material: ECHO_SHARD, min: 1, max: 1, chance: 1.0, name: "Champion's Trophy" }
                              - { material: GOLD_INGOT, min: 2, max: 2, chance: 1.0 }
                        recipes:
                          arena_blade:
                            type: shaped
                            shape:
                              - " I "
                              - " I "
                              - " S "
                            ingredients:
                              I: IRON_INGOT
                              S: STICK
                            result:
                              material: IRON_SWORD
                              amount: 1
                              name: "Arena Blade"
                          gem_mix:
                            type: shapeless
                            ingredients: [DIAMOND, EMERALD]
                            result:
                              material: ECHO_SHARD
                              amount: 1
                        """.trimIndent() + "\n",
                        StandardCharsets.UTF_8,
                    )

                    // 5. Player-initiated /femitems reload (tests player region thread -> GlobalRegionScheduler on Folia!)
                    val opChatBeforeReload = opPlayer.chatCount()
                    val logBeforeReload = server.readLog().length
                    opPlayer.sendCommand("femitems reload")
                    opPlayer.awaitChatContaining("[FemItems] Config reloaded.", sinceIndex = opChatBeforeReload)
                    server.awaitLog(Regex("""Loaded 1 mob rules and 2 recipes\."""), fromOffset = logBeforeReload)

                    // 6. Verify custom shaped & shapeless recipes exist in live Minecraft RecipeManager
                    val logBeforeRecipes = server.readLog().length
                    server.sendConsoleCommand("recipe give FakeOp femitems:arena_blade")
                    server.awaitLog(Regex("""Unlocked 1 recipe\(s\) for FakeOp"""), fromOffset = logBeforeRecipes)

                    val logBeforeShapeless = server.readLog().length
                    server.sendConsoleCommand("recipe give FakeOp femitems:gem_mix")
                    server.awaitLog(Regex("""Unlocked 1 recipe\(s\) for FakeOp"""), fromOffset = logBeforeShapeless)

                    // 7. Player-initiated /femitems debug + live custom-named mob kill + custom drop verification
                    val opChatBeforeDebug = opPlayer.chatCount()
                    opPlayer.sendCommand("femitems debug")
                    opPlayer.awaitChatContaining("[FemItems] Mob death debug enabled", sinceIndex = opChatBeforeDebug)

                    // Ensure FakeOp is in survival, protected from flat-world slimes, with a clean inventory
                    server.sendConsoleCommand("gamemode survival FakeOp")
                    server.sendConsoleCommand("effect give FakeOp minecraft:resistance 300 4 true")
                    server.sendConsoleCommand("clear FakeOp")
                    Thread.sleep(500)

                    // Summon custom-named Champion zombie at FakeOp's location and kill it
                    val itemCountBeforeKill = opPlayer.receivedItemCount()
                    val logBeforeMobKill = server.readLog().length
                    opPlayer.sendCommand(
                        """summon minecraft:zombie ~ ~ ~ {CustomName:"Arena Champion",NoAI:1b,Invulnerable:0b}""",
                    )
                    Thread.sleep(500)
                    opPlayer.sendCommand("kill @e[type=minecraft:zombie,distance=..10]")

                    server.awaitLog(
                        Regex("""Mob death debug: entity=ZOMBIE, customName=Arena Champion, matchingRules=\[champion\]"""),
                        timeout = Duration.ofSeconds(15),
                        fromOffset = logBeforeMobKill,
                    )

                    // Verify FakeOp actually receives the custom-named Champion's Trophy and 2x gold drop via Minecraft protocol
                    opPlayer.awaitItemMatching(
                        description = "Champion's Trophy custom-named item",
                        sinceIndex = itemCountBeforeKill,
                    ) { item ->
                        item.dataComponentsPatch?.toString()?.contains("Champion's Trophy") == true
                    }
                    opPlayer.awaitItemMatching(
                        description = "2x gold ingot drop stack",
                        sinceIndex = itemCountBeforeKill,
                    ) { item ->
                        item.amount == 2 && item.dataComponentsPatch == null
                    }

                    // Double-check on the server side that both materials exist in FakeOp's inventory
                    val logBeforeEchoCheck = server.readLog().length
                    server.sendConsoleCommand("clear FakeOp minecraft:echo_shard 0")
                    server.awaitLog(
                        Regex("""Found [1-9]\d* matching item\(s\) on player FakeOp"""),
                        timeout = Duration.ofSeconds(15),
                        fromOffset = logBeforeEchoCheck,
                    )

                    val logBeforeGoldCheck = server.readLog().length
                    server.sendConsoleCommand("clear FakeOp minecraft:gold_ingot 0")
                    server.awaitLog(
                        Regex("""Found [1-9]\d* matching item\(s\) on player FakeOp"""),
                        timeout = Duration.ofSeconds(15),
                        fromOffset = logBeforeGoldCheck,
                    )

                    // Toggle debug back off
                    val opChatBeforeDebugOff = opPlayer.chatCount()
                    opPlayer.sendCommand("femitems debug")
                    opPlayer.awaitChatContaining("[FemItems] Mob death debug disabled.", sinceIndex = opChatBeforeDebugOff)

                    // 8. Verify invalid config reload reports error line cleanly without crashing
                    Files.writeString(
                        server.pluginConfigFile(),
                        """
                        mobs:
                          broken:
                            entity: ZOMBIE
                            drops:
                              - { material: NOT_A_REAL_MATERIAL, min: 1, max: 1, chance: 1.0 }
                        """.trimIndent() + "\n",
                        StandardCharsets.UTF_8,
                    )
                    val opChatBeforeBadReload = opPlayer.chatCount()
                    opPlayer.sendCommand("femitems reload")
                    opPlayer.awaitChatContaining("[FemItems] Config error at line 5.", sinceIndex = opChatBeforeBadReload)
                }
            }

            // 9. Clean server shutdown & assert zero plugin/Folia errors in logs
            server.stopAndAssertClean()
        }
    }
}
