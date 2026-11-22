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
                startupLog.contains("Loaded 5 mob rules and 8 recipes."),
                "[$platform] Expected default 5 mob rules and 8 recipes on startup",
            )

            // 2. Connect two real fake players: FakeOp (operator) and FakeGuest (non-operator)
            FakePlayerClient(username = "FakeOp", port = server.port).use { opPlayer ->
                FakePlayerClient(username = "FakeGuest", port = server.port).use { guestPlayer ->
                    opPlayer.connectAndAwaitSpawn()
                    guestPlayer.connectAndAwaitSpawn()

                    // Verify default recipes were automatically unlocked in joining player's recipe book
                    val logBeforeJoinUnlockCheck = server.readLog().length
                    server.sendConsoleCommand("recipe take FakeGuest femitems:soulpiercer_bow")
                    server.awaitLog(
                        Regex("""Took 1 recipe\(s\) from FakeGuest"""),
                        fromOffset = logBeforeJoinUnlockCheck,
                    )

                    val logBeforeOp = server.readLog().length
                    server.sendConsoleCommand("op FakeOp")
                    server.awaitLog(Regex("""Made FakeOp a server operator"""), fromOffset = logBeforeOp)

                    // Verify the bare /femitems info panel shows version, platform, and default counts
                    val opChatBeforeInfo = opPlayer.chatCount()
                    opPlayer.sendCommand("femitems")
                    opPlayer.awaitChatContaining(
                        "[FemItems] v$pluginVersion on $expectedPlatformLabel",
                        sinceIndex = opChatBeforeInfo,
                    )
                    opPlayer.awaitChatContaining("5 mob rules · 8 recipes · debug off", sinceIndex = opChatBeforeInfo)

                    // 3. Verify non-OP fake player cannot run /femitems reload
                    val guestChatMark = guestPlayer.chatCount()
                    guestPlayer.sendCommand("femitems reload")
                    guestPlayer.assertNoChatContaining("[FemItems] Config reloaded.", sinceIndex = guestChatMark)

                    // 4. Write deterministic config with colorized mob drops + shaped/shapeless/book recipes & metadata
                    Files.writeString(
                        server.pluginConfigFile(),
                        """
                        mobs:
                          champion:
                            entity: ANY
                            name-contains: "Champion"
                            drops:
                              - { material: ECHO_SHARD, min: 1, max: 1, chance: 1.0, name: "&6Champion's Trophy" }
                              - { material: GOLD_INGOT, min: 2, max: 2, chance: 1.0 }
                              - { material: ARROW, min: 70, max: 70, chance: "1.0" }
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
                              name: "&6Arena Blade"
                              lore:
                                - "&7Forged in the arena."
                              unbreakable: true
                              enchants:
                                sharpness: 10
                                minecraft:unbreaking: 5
                          gem_mix:
                            type: shapeless
                            ingredients: [DIAMOND, EMERALD]
                            result:
                              material: ECHO_SHARD
                              amount: 1
                          power_book:
                            type: shapeless
                            ingredients: [BOOK, ECHO_SHARD]
                            result:
                              material: ENCHANTED_BOOK
                              amount: 1
                              name: "&5Tome of Power"
                              enchants:
                                power: 5
                        """.trimIndent() + "\n",
                        StandardCharsets.UTF_8,
                    )

                    // 5. Player-initiated /femitems reload (tests player region thread -> GlobalRegionScheduler -> entity.scheduler on Folia!)
                    val opChatBeforeReload = opPlayer.chatCount()
                    val logBeforeReload = server.readLog().length
                    opPlayer.sendCommand("femitems reload")
                    opPlayer.awaitChatContaining(
                        "[FemItems] Config reloaded. (1 mob rules, 3 recipes)",
                        sinceIndex = opChatBeforeReload,
                    )
                    server.awaitLog(Regex("""Loaded 1 mob rules and 3 recipes\."""), fromOffset = logBeforeReload)

                    // Allow 1-tick entity.scheduler recipe discovery task to complete for online players
                    Thread.sleep(500)

                    // 6. Verify reloaded recipes exist in RecipeManager AND were auto-unlocked for online FakeOp
                    val logBeforeShaped = server.readLog().length
                    server.sendConsoleCommand("recipe take FakeOp femitems:arena_blade")
                    server.awaitLog(Regex("""Took 1 recipe\(s\) from FakeOp"""), fromOffset = logBeforeShaped)

                    val logBeforeShapeless = server.readLog().length
                    server.sendConsoleCommand("recipe take FakeOp femitems:gem_mix")
                    server.awaitLog(Regex("""Took 1 recipe\(s\) from FakeOp"""), fromOffset = logBeforeShapeless)

                    val logBeforeBook = server.readLog().length
                    server.sendConsoleCommand("recipe take FakeOp femitems:power_book")
                    server.awaitLog(Regex("""Took 1 recipe\(s\) from FakeOp"""), fromOffset = logBeforeBook)

                    // 7. Player-initiated /femitems debug + live custom-named mob kill + colorized custom drop verification
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

                    // Verify FakeOp actually receives the colorized Champion's Trophy and 2x gold drop via Minecraft protocol
                    opPlayer.awaitItemMatching(
                        description = "Champion's Trophy colorized custom-named item",
                        sinceIndex = itemCountBeforeKill,
                    ) { item ->
                        val components = item.dataComponentsPatch?.toString().orEmpty()
                        components.contains("Champion's Trophy") && !components.contains("&6")
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

                    // 70 arrows (quoted chance) must arrive as valid stacks (64 + 6), never one illegal 70-stack.
                    // Checked via client inventory packets: Folia's `clear <player> <item> 0` count is not reliable.
                    opPlayer.awaitItemMatching(
                        description = "full 64-arrow stack from oversized drop",
                        sinceIndex = itemCountBeforeKill,
                    ) { item -> item.amount == 64 }
                    opPlayer.awaitItemMatching(
                        description = "6-arrow remainder stack from oversized drop",
                        sinceIndex = itemCountBeforeKill,
                    ) { item -> item.amount == 6 }
                    val oversized = opPlayer.receivedItemsSnapshot().drop(itemCountBeforeKill).filter { it.amount > 64 }
                    assertTrue(oversized.isEmpty(), "[$platform] Received stacks above 64: $oversized")

                    // Toggle debug back off
                    val opChatBeforeDebugOff = opPlayer.chatCount()
                    opPlayer.sendCommand("femitems debug")
                    opPlayer.awaitChatContaining("[FemItems] Mob death debug disabled.", sinceIndex = opChatBeforeDebugOff)

                    // 8. Verify invalid material & invalid enchantment config reloads report error lines cleanly without crashing
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

                    Files.writeString(
                        server.pluginConfigFile(),
                        """
                        recipes:
                          bad_enchant:
                            type: shapeless
                            ingredients: [DIAMOND]
                            result:
                              material: DIAMOND_SWORD
                              enchants:
                                definitely_not_an_enchant: 1
                        """.trimIndent() + "\n",
                        StandardCharsets.UTF_8,
                    )
                    val opChatBeforeBadEnchant = opPlayer.chatCount()
                    opPlayer.sendCommand("femitems reload")
                    opPlayer.awaitChatContaining("[FemItems] Config error at line 8.", sinceIndex = opChatBeforeBadEnchant)

                    // 9. A shaped ingredient symbol missing from the shape must fail the reload up front
                    //    (not report success and then wipe recipes on the global region thread)
                    Files.writeString(
                        server.pluginConfigFile(),
                        """
                        recipes:
                          bad_shape:
                            type: shaped
                            shape:
                              - "I"
                            ingredients:
                              I: IRON_INGOT
                              X: DIAMOND
                            result:
                              material: IRON_SWORD
                        """.trimIndent() + "\n",
                        StandardCharsets.UTF_8,
                    )
                    val opChatBeforeBadShape = opPlayer.chatCount()
                    opPlayer.sendCommand("femitems reload")
                    opPlayer.awaitChatContaining("[FemItems] Config error at line 6.", sinceIndex = opChatBeforeBadShape)
                    opPlayer.assertNoChatContaining("[FemItems] Config reloaded.", sinceIndex = opChatBeforeBadShape)

                    // Previously loaded recipes must still be registered after all the failed reloads
                    val logBeforeGive = server.readLog().length
                    server.sendConsoleCommand("recipe give FakeOp femitems:arena_blade")
                    server.awaitLog(Regex("""Unlocked 1 recipe\(s\) for FakeOp"""), fromOffset = logBeforeGive)
                }
            }

            // 10. Clean server shutdown & assert zero plugin/Folia errors in logs
            server.stopAndAssertClean()
        }
    }
}
