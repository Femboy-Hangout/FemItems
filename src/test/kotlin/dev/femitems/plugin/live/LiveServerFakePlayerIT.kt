package dev.femitems.plugin.live

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.math.abs
import kotlin.test.assertEquals
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
 * - Jukebox: song reload, permission-gated admin commands, personal playback heard by nearby players
 *   (global region clock -> each listener's EntityScheduler), pause/resume/stop, Custom Jukebox items,
 *   disc insertion into a Custom Jukebox block, automatic disc ejection, and the jukebox GUI
 * - Jukebox driven purely by player actions: placing the block from its item, GUI clicks (volume, song
 *   browser, pause/resume/stop, admin page, disc eject), /femjukebox map, and survival mining of a playing jukebox
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
            assertTrue(
                startupLog.contains("Jukebox ready: loaded 4 song(s)."),
                "[$platform] Expected the jukebox to load the 4 bundled resource-pack tracks on startup",
            )

            // 2. Connect two real fake players: FakeOp (operator) and FakeGuest (non-operator)
            FakePlayerClient(username = "FakeOp", port = server.port).use { opPlayer ->
                FakePlayerClient(username = "FakeGuest", port = server.port).use { guestPlayer ->
                    opPlayer.connectAndAwaitSpawn()
                    guestPlayer.connectAndAwaitSpawn()

                    // Flat worlds spawn slimes; a fake player killed by one can no longer run commands
                    // ("chat.disabled.options") or pick up items, so make both immune for the whole suite.
                    for (name in listOf("FakeOp", "FakeGuest")) {
                        val logBeforeEffect = server.readLog().length
                        server.sendConsoleCommand("effect give $name minecraft:resistance infinite 4 true")
                        server.awaitLog(Regex("""Applied effect Resistance to $name"""), fromOffset = logBeforeEffect)
                    }

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
                            entity-tag: femitems.test_champion
                            name-contains: "Champion"
                            drops:
                              - { material: ECHO_SHARD, min: 1, max: 1, chance: 1.0, name: "&6Champion's Trophy" }
                              - { material: GOLD_INGOT, min: 2, max: 2, chance: 1.0 }
                              - { material: ARROW, min: 70, max: 70, chance: "1.0" }
                              - { material: ENCHANTED_BOOK, min: 1, max: 1, chance: 1.0, random-enchantment: true }
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
                    val resyncsBeforeReload = opPlayer.recipeResyncCount()
                    opPlayer.sendCommand("femitems reload")
                    opPlayer.awaitChatContaining(
                        "[FemItems] Config reloaded. (1 mob rules, 3 recipes)",
                        sinceIndex = opChatBeforeReload,
                    )
                    server.awaitLog(Regex("""Loaded 1 mob rules and 3 recipes\."""), fromOffset = logBeforeReload)

                    // Every recipe-manager change makes the server re-send advancements, tags and the full recipe
                    // book to every player. Swapping 8 default recipes for 3 new ones must cost at most one batch
                    // removal + one batch addition (it used to be one resync per recipe: 11+ here, 16 in prod).
                    Thread.sleep(500)
                    val reloadResyncs = opPlayer.recipeResyncCount() - resyncsBeforeReload
                    assertTrue(
                        reloadResyncs in 1..2,
                        "[$platform] Changed-recipe reload caused $reloadResyncs recipe resyncs; expected 1..2",
                    )
                    val resyncsBeforeUnchanged = opPlayer.recipeResyncCount()

                    // 5b. Async reload: back-to-back reloads must never overlap or double-apply. Each one either
                    //     completes or is rejected with the in-progress notice; at least one must complete.
                    val opChatBeforeBurst = opPlayer.chatCount()
                    opPlayer.sendCommand("femitems reload")
                    opPlayer.sendCommand("femitems reload")
                    opPlayer.awaitChatContaining(
                        "[FemItems] Config reloaded. (1 mob rules, 3 recipes)",
                        sinceIndex = opChatBeforeBurst,
                    )
                    Thread.sleep(1000)
                    val burstReplies = opPlayer.chatMessagesSnapshot().drop(opChatBeforeBurst)
                        .filter { it.startsWith("[FemItems]") }
                    assertTrue(
                        burstReplies.size == 2 && burstReplies.all {
                            it == "[FemItems] Config reloaded. (1 mob rules, 3 recipes)" ||
                                it == "[FemItems] A reload is already in progress; please wait."
                        },
                        "[$platform] Unexpected replies to back-to-back reloads: $burstReplies",
                    )

                    // 5c. Console-initiated reload (global region -> AsyncScheduler -> global region)
                    val logBeforeConsoleReload = server.readLog().length
                    server.sendConsoleCommand("femitems reload")
                    server.awaitLog(Regex("""Loaded 1 mob rules and 3 recipes\."""), fromOffset = logBeforeConsoleReload)

                    // Reloads with unchanged recipes (5b + 5c) must not touch the recipe manager at all.
                    Thread.sleep(500)
                    val unchangedResyncs = opPlayer.recipeResyncCount() - resyncsBeforeUnchanged
                    assertEquals(
                        0,
                        unchangedResyncs,
                        "[$platform] Reloads with unchanged recipes caused $unchangedResyncs recipe resyncs",
                    )

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
                        """summon minecraft:zombie ~ ~ ~ {Tags:["femitems.test_champion"],CustomName:"Arena Champion",NoAI:1b,Invulnerable:0b}""",
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
                    opPlayer.awaitItemMatching(
                        description = "randomly enchanted book drop",
                        sinceIndex = itemCountBeforeKill,
                    ) { item ->
                        item.dataComponentsPatch?.toString().orEmpty().contains("stored_enchantments")
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
                            entity-tag: test.broken
                            drops:
                              - { material: NOT_A_REAL_MATERIAL, min: 1, max: 1, chance: 1.0 }
                        """.trimIndent() + "\n",
                        StandardCharsets.UTF_8,
                    )
                    val opChatBeforeBadReload = opPlayer.chatCount()
                    opPlayer.sendCommand("femitems reload")
                    opPlayer.awaitChatContaining("[FemItems] Config error at line 6.", sinceIndex = opChatBeforeBadReload)

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

                    runJukeboxSuite(server, opPlayer, guestPlayer)
                }
            }

            // 10. Clean server shutdown & assert zero plugin/Folia errors in logs
            server.stopAndAssertClean()
        }
    }

    /** Checks a block through `/execute if block`, whose pass/fail translation keys survive plain-text capture. */
    private fun assertBlock(player: FakePlayerClient, position: String, block: String, expected: Boolean) {
        val mark = player.chatCount()
        player.sendCommand("execute if block $position $block")
        val reply = player.awaitChatContaining("commands.execute.conditional", sinceIndex = mark)
        assertEquals(expected, reply.contains(".pass"), "Expected block at $position to ${if (expected) "" else "not "}be $block (got $reply)")
    }

    /** Fails (naming the cause) if any entity's hitbox intersects the selector volume. */
    private fun assertNoEntityIn(player: FakePlayerClient, volume: String) {
        val mark = player.chatCount()
        player.sendCommand("execute if entity @e[$volume]")
        val reply = player.awaitChatContaining("commands.execute.conditional", sinceIndex = mark)
        assertTrue(reply.contains(".fail"), "Expected no entity in [$volume] (got $reply)")
    }

    private fun runJukeboxSuite(server: LiveServerHarness, op: FakePlayerClient, guest: FakePlayerClient) {
        val platform = server.platform

        // Players spawn at random spots near world spawn; pin both to known positions (within hearing range
        // of each other, away from the blocks this suite places at x=2) so nobody blocks a placement.
        op.sendCommand("tp @s 0.5 -60 0.5")
        op.sendCommand("tp FakeGuest -6.5 -60 0.5")
        Thread.sleep(1000)

        // J1. Drop two GNBS songs into the songs folder and reload (player thread -> AsyncScheduler).
        val songsDir = server.jukeboxSongsDir()
        Files.writeString(
            songsDir.resolve("livetest.gnbs"),
            """
            Song:
              Id: livetest
              Title: Live Test Tune
              Content:
                Instruments:
                  '0': '0'
                Main:
                  - '0!0:100:#12'
                  - '250!0:100:#14'
                  - '250!0:100:#16'
                  - '250!0:100:#17'
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            songsDir.resolve("livelong.gnbs"),
            """
            Song:
              Id: livelong
              Title: Live Long Tune
              Content:
                Instruments:
                  '0': '0'
                Main:
                  - '500!0:100:#12;40'
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )
        val opChatBeforeReload = op.chatCount()
        op.sendCommand("femjukebox reload")
        op.awaitChatContaining("[FemJukebox] Loaded 6 song(s).", sinceIndex = opChatBeforeReload)

        // J2. Non-admins only see note songs and cannot use admin subcommands or disc-only tracks.
        val guestChatBeforeSongs = guest.chatCount()
        guest.sendCommand("femjukebox songs")
        guest.awaitChatContaining("[FemJukebox] Songs (2): livelong, livetest", sinceIndex = guestChatBeforeSongs)
        val guestItemsBeforeGive = guest.receivedItemCount()
        guest.sendCommand("femjukebox give")
        guest.sendCommand("femjukebox play pack-lucys-ward")
        guest.awaitChatContaining("Custom disc tracks can only be played", sinceIndex = guestChatBeforeSongs)
        guest.assertNoChatContaining("Gave", sinceIndex = guestChatBeforeSongs)
        assertTrue(
            guest.receivedItemsSnapshot().drop(guestItemsBeforeGive)
                .none { it.dataComponentsPatch?.toString().orEmpty().contains("Custom Jukebox") },
            "[$platform] Non-admin received a Custom Jukebox item",
        )

        // J3. Personal playback is heard by the player AND a nearby player, then finishes on its own.
        val opChatBeforePlay = op.chatCount()
        val opSoundsBefore = op.soundCount()
        val guestSoundsBefore = guest.soundCount()
        op.sendCommand("femjukebox play livetest")
        op.awaitChatContaining("[FemJukebox] Now playing: Live Test Tune", sinceIndex = opChatBeforePlay)
        op.awaitSoundContaining("note_block.harp", sinceIndex = opSoundsBefore)
        guest.awaitSoundContaining("note_block.harp", sinceIndex = guestSoundsBefore)
        op.awaitChatContaining("[FemJukebox] Finished: Live Test Tune", sinceIndex = opChatBeforePlay)

        // J4. Pause / resume / stop; stopping sends stop-sound packets for the notes that were playing.
        val opChatBeforeLong = op.chatCount()
        op.sendCommand("femjukebox play livelong")
        op.awaitChatContaining("[FemJukebox] Now playing: Live Long Tune", sinceIndex = opChatBeforeLong)
        op.sendCommand("femjukebox pause")
        op.awaitChatContaining("[FemJukebox] Playback paused.", sinceIndex = opChatBeforeLong)
        op.sendCommand("femjukebox resume")
        op.awaitChatContaining("[FemJukebox] Playback resumed.", sinceIndex = opChatBeforeLong)
        val opSoundsBeforeStop = op.soundCount()
        op.awaitSoundContaining("note_block.harp", sinceIndex = opSoundsBeforeStop)
        val opStopsBefore = op.stoppedSoundCount()
        op.sendCommand("femjukebox stop")
        op.awaitChatContaining("[FemJukebox] Playback stopped.", sinceIndex = opChatBeforeLong)
        op.awaitStoppedSoundContaining("note_block.harp", sinceIndex = opStopsBefore)
        op.assertNoChatContaining("Finished: Live Long Tune", sinceIndex = opChatBeforeLong)

        // J5. Admin item commands deliver a Custom Jukebox and a mapped disc (target's EntityScheduler).
        server.sendConsoleCommand("clear FakeOp")
        Thread.sleep(500)
        val opItemsBeforeGive = op.receivedItemCount()
        val opChatBeforeGive = op.chatCount()
        op.sendCommand("femjukebox give FakeOp 2")
        op.awaitChatContaining("[FemJukebox] Gave 2 Custom Jukebox item(s) to FakeOp.", sinceIndex = opChatBeforeGive)
        op.awaitItemMatching("Custom Jukebox item", sinceIndex = opItemsBeforeGive) { item ->
            item.amount == 2 && item.dataComponentsPatch?.toString().orEmpty().contains("Custom Jukebox")
        }
        server.sendConsoleCommand("clear FakeOp")
        Thread.sleep(500)
        val opItemsBeforeDisc = op.receivedItemCount()
        op.sendCommand("femjukebox disc livetest")
        op.awaitChatContaining("[FemJukebox] Gave ♫ Live Test Tune to FakeOp.", sinceIndex = opChatBeforeGive)
        op.awaitItemMatching("mapped Live Test Tune disc", sinceIndex = opItemsBeforeDisc) { item ->
            item.dataComponentsPatch?.toString().orEmpty().contains("Live Test Tune")
        }

        // J6. Insert the disc into a Custom Jukebox block (block's region thread), which starts playback there.
        op.sendCommand("kill @e[type=minecraft:item]")
        op.sendCommand("tp @s 0.5 -60 0.5")
        Thread.sleep(1000)
        op.sendCommand("setblock 2 -60 0 minecraft:air")
        op.sendCommand("""setblock 2 -60 0 minecraft:jukebox{PublicBukkitValues:{"femjukebox:custom_jukebox":1b}}""")
        Thread.sleep(1000)
        val opChatBeforeInsert = op.chatCount()
        op.useItemOnBlock(2, -60, 0)
        op.awaitChatContaining("[FemJukebox] Now playing: Live Test Tune", sinceIndex = opChatBeforeInsert)

        // J7. When the song ends the disc is ejected from the block, intact, and can be picked up again.
        //     (Verified through gameplay: Folia has no /data command to inspect the block directly.)
        val opItemsBeforePickup = op.receivedItemCount()
        op.awaitChatContaining("[FemJukebox] Finished: Live Test Tune", sinceIndex = opChatBeforeInsert)
        // Items were cleared before inserting, so any item now is the ejected disc; bring it to the player
        // rather than depending on where it rolls after popping out of the jukebox.
        Thread.sleep(1000)
        op.sendCommand("tp @e[type=minecraft:item,distance=..8] @s")
        op.awaitItemMatching("ejected Live Test Tune disc picked up", sinceIndex = opItemsBeforePickup) { item ->
            item.dataComponentsPatch?.toString().orEmpty().contains("Live Test Tune")
        }

        // J8. Re-insert, then right-click again: an inserted disc is never replaced; the GUI opens instead.
        Thread.sleep(500)
        val opChatBeforeGui = op.chatCount()
        val screensBefore = op.openedScreenCount()
        op.useItemOnBlock(2, -60, 0)
        op.awaitChatContaining("[FemJukebox] Now playing: Live Test Tune", sinceIndex = opChatBeforeGui)
        op.useItemOnBlock(2, -60, 0)
        val guiAtFirstJukebox = op.awaitScreenTitleContaining("Custom Jukebox • home", sinceIndex = screensBefore)
        op.awaitChatContaining("[FemJukebox] Finished: Live Test Tune", sinceIndex = opChatBeforeGui)
        op.closeContainer(guiAtFirstJukebox) // a real player closes the menu before doing anything else

        // ---- Everything below is driven purely by player actions: placing, right-clicking, GUI clicks, mining.

        // J9. Place a Custom Jukebox from the real item (BlockPlaceEvent tags the block).
        op.sendCommand("setblock 2 -60 2 minecraft:air") // stale block from an earlier run
        op.sendCommand("kill @e[type=minecraft:item]")
        server.sendConsoleCommand("clear FakeOp")
        Thread.sleep(500)
        val opChatBeforePlace = op.chatCount()
        val opItemsBeforePlace = op.receivedItemCount()
        op.sendCommand("femjukebox give FakeOp 1")
        op.awaitChatContaining("[FemJukebox] Gave 1 Custom Jukebox item(s) to FakeOp.", sinceIndex = opChatBeforePlace)
        op.awaitItemMatching("Custom Jukebox item to place", sinceIndex = opItemsBeforePlace) { item ->
            item.dataComponentsPatch?.toString().orEmpty().contains("Custom Jukebox")
        }
        assertBlock(op, "2 -60 2", "minecraft:air", expected = true)
        assertNoEntityIn(op, "x=2,y=-60,z=2,dx=0,dy=0,dz=0") // an entity in the space blocks placement
        assertBlock(op, "2 -61 2", "minecraft:air", expected = false) // solid ground (grass turns to dirt under blocks)
        op.useItemOnBlock(2, -61, 2) // top face of the ground block -> places at (2, -60, 2)
        Thread.sleep(1000)
        assertBlock(op, "2 -60 2", "minecraft:jukebox", expected = true)

        // J10. Empty-handed right-click opens the GUI (a vanilla jukebox would not), then drive it by clicks.
        var screens = op.openedScreenCount()
        op.useItemOnBlock(2, -60, 2)
        var home = op.awaitScreenTitleContaining("Custom Jukebox • home", sinceIndex = screens)

        // Volume + raises this jukebox's shared volume and reopens the menu showing it.
        val opChatBeforeVolume = op.chatCount()
        screens = op.openedScreenCount()
        op.clickContainerSlot(home, 17)
        op.awaitChatContaining("[FemJukebox] Playback volume: 110%", sinceIndex = opChatBeforeVolume)
        home = op.awaitScreenTitleContaining("Custom Jukebox • home", sinceIndex = screens)

        // Browse songs -> pick "livelong" (slot 0; songs are sorted). It plays at the jukebox at 110%.
        screens = op.openedScreenCount()
        op.clickContainerSlot(home, 11)
        val songsPage = op.awaitScreenTitleContaining("Custom Jukebox • Songs", sinceIndex = screens)
        val opChatBeforeGuiPlay = op.chatCount()
        val opSoundsBeforeGuiPlay = op.soundCount()
        op.clickContainerSlot(songsPage, 0)
        op.awaitChatContaining("[FemJukebox] Now playing: Live Long Tune", sinceIndex = opChatBeforeGuiPlay)
        val loud = op.awaitSoundContaining("note_block.harp", sinceIndex = opSoundsBeforeGuiPlay)
        assertTrue(abs(loud.volume - 1.1f) < 0.01f, "[$platform] Jukebox volume 110% not applied to sound: $loud")

        // Pause / resume / stop from the GUI.
        screens = op.openedScreenCount()
        op.useItemOnBlock(2, -60, 2)
        home = op.awaitScreenTitleContaining("Custom Jukebox • home", sinceIndex = screens)
        op.clickContainerSlot(home, 12)
        op.awaitChatContaining("[FemJukebox] Playback paused.", sinceIndex = opChatBeforeGuiPlay)
        op.clickContainerSlot(home, 12)
        op.awaitChatContaining("[FemJukebox] Playback resumed.", sinceIndex = opChatBeforeGuiPlay)
        // Pausing silenced everything; wait for a note to sound again so Stop has something to stop.
        op.awaitSoundContaining("note_block.harp", sinceIndex = op.soundCount())
        val opStopsBeforeGuiStop = op.stoppedSoundCount()
        op.clickContainerSlot(home, 14)
        op.awaitChatContaining("[FemJukebox] Playback stopped.", sinceIndex = opChatBeforeGuiPlay)
        op.awaitStoppedSoundContaining("note_block.harp", sinceIndex = opStopsBeforeGuiStop)

        // Admin-only Custom discs page is reachable for an operator.
        screens = op.openedScreenCount()
        op.clickContainerSlot(home, 15)
        op.closeContainer(op.awaitScreenTitleContaining("Custom Jukebox • Custom discs", sinceIndex = screens))

        // J11. /femjukebox map turns a plain held item into a disc, which plays and is ejected via the GUI.
        server.sendConsoleCommand("clear FakeOp")
        server.sendConsoleCommand("give FakeOp minecraft:paper")
        Thread.sleep(500)
        val opChatBeforeMap = op.chatCount()
        val opItemsBeforeMap = op.receivedItemCount()
        op.sendCommand("femjukebox map livelong")
        op.awaitChatContaining("[FemJukebox] Mapped the held item to Live Long Tune.", sinceIndex = opChatBeforeMap)
        op.awaitItemMatching("paper mapped to livelong", sinceIndex = opItemsBeforeMap) { item ->
            item.dataComponentsPatch?.toString().orEmpty().contains("song_id")
        }
        op.useItemOnBlock(2, -60, 2)
        op.awaitChatContaining("[FemJukebox] Now playing: Live Long Tune", sinceIndex = opChatBeforeMap)

        screens = op.openedScreenCount()
        op.useItemOnBlock(2, -60, 2) // disc already inserted -> GUI, never a replacement
        home = op.awaitScreenTitleContaining("Custom Jukebox • home", sinceIndex = screens)
        screens = op.openedScreenCount()
        op.clickContainerSlot(home, 10)
        val discsPage = op.awaitScreenTitleContaining("Custom Jukebox • Discs", sinceIndex = screens)
        op.awaitSoundContaining("note_block.harp", sinceIndex = op.soundCount())
        val opItemsBeforeEject = op.receivedItemCount()
        val opStopsBeforeEject = op.stoppedSoundCount()
        op.clickContainerSlot(discsPage, 13)
        op.awaitItemMatching("mapped paper returned by the Eject button", sinceIndex = opItemsBeforeEject) { item ->
            item.dataComponentsPatch?.toString().orEmpty().contains("song_id")
        }
        op.awaitStoppedSoundContaining("note_block.harp", sinceIndex = opStopsBeforeEject)
        op.assertNoChatContaining("Finished: Live Long Tune", sinceIndex = opChatBeforeMap)

        // J12. Breaking a playing jukebox in survival stops the music and drops both the jukebox and its disc.
        val opChatBeforeBreak = op.chatCount()
        op.useItemOnBlock(2, -60, 2)
        op.awaitChatContaining("[FemJukebox] Now playing: Live Long Tune", sinceIndex = opChatBeforeBreak)
        server.sendConsoleCommand("effect give FakeOp minecraft:haste 60 255 true")
        op.awaitSoundContaining("note_block.harp", sinceIndex = op.soundCount())
        val opStopsBeforeBreak = op.stoppedSoundCount()
        val opItemsBeforeBreak = op.receivedItemCount()
        op.breakBlock(2, -60, 2)
        op.awaitStoppedSoundContaining("note_block.harp", sinceIndex = opStopsBeforeBreak)
        Thread.sleep(1000)
        op.sendCommand("tp @e[type=minecraft:item,distance=..8] @s")
        op.awaitItemMatching("Custom Jukebox item dropped by breaking", sinceIndex = opItemsBeforeBreak) { item ->
            item.dataComponentsPatch?.toString().orEmpty().contains("Custom Jukebox")
        }
        op.awaitItemMatching("inserted disc dropped by breaking", sinceIndex = opItemsBeforeBreak) { item ->
            item.dataComponentsPatch?.toString().orEmpty().contains("song_id")
        }
        op.assertNoChatContaining("Finished: Live Long Tune", sinceIndex = opChatBeforeBreak)
        server.sendConsoleCommand("effect clear FakeOp minecraft:haste")
    }
}
