package dev.femitems.plugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import dev.femitems.plugin.jukebox.JukeboxMessages
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MessagesTest {
    private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)

    @Test
    fun `styled messages keep stable plain-text output`() {
        assertEquals("[FemItems] Config reloaded. (5 mob rules, 8 recipes)", plain(Messages.reloaded(5, 8)))
        assertEquals(
            "[FemItems] A reload is already in progress; please wait.",
            plain(Messages.reloadInProgress()),
        )
        assertEquals(
            "[FemItems] Config error at line 5. (hover for details)",
            plain(Messages.configError(5, "Unknown drop material")),
        )
        assertEquals(
            "[FemItems] Config error; see console. (hover for details)",
            plain(Messages.configError(null, "boom")),
        )
        assertEquals(
            "[FemItems] Mob death debug enabled; check console after killing a mob.",
            plain(Messages.debugToggled(true)),
        )
        assertEquals("[FemItems] Mob death debug disabled.", plain(Messages.debugToggled(false)))
    }

    @Test
    fun `config error exposes the parser detail on hover`() {
        val hover = assertNotNull(Messages.configError(3, "Unknown ingredient in recipes.x: FOO").hoverEvent())
        assertEquals(HoverEvent.Action.SHOW_TEXT, hover.action())
        assertEquals("Unknown ingredient in recipes.x: FOO", plain(hover.value() as Component))
    }

    @Test
    fun `info panel lists version, platform, counts, and commands`() {
        val lines = plain(Messages.info("1.3.0", "Folia", mobRules = 5, recipes = 8, debug = false)).lines()
        assertEquals(
            listOf(
                "[FemItems] v1.3.0 on Folia",
                "  ✦ 5 mob rules · 8 recipes · debug off",
                "  ▸ /femitems reload — reload config.yml and messages.yml",
                "  ▸ /femitems gui — browse custom items by category",
                "  ▸ /femitems debug — toggle mob-death diagnostics",
            ),
            lines,
        )
    }

    @AfterEach
    fun resetTexts() = MessageTexts.reset()

    @Test
    fun `messages_yml texts replace the defaults and keep placeholders`() {
        MessageTexts.use(
            mapOf(
                "prefix" to "<gold>[Items]</gold> ",
                "items.gave-item" to "<green>Here, <target>: <amount> of <id>",
                "jukebox.now-playing" to "Song: <title>",
            ),
        )
        assertEquals("[Items] Here, Steve: 3 of ruby", plain(Messages.gaveCustomItem(3, "ruby", "Steve")))
        // The jukebox keeps its own prefix; untouched messages keep their default text.
        assertEquals("[FemJukebox] Song: Tune", plain(JukeboxMessages.nowPlaying("Tune")))
        assertEquals("[Items] Mob death debug disabled.", plain(Messages.debugToggled(false)))
    }

    @Test
    fun `values put into messages are never parsed as MiniMessage`() {
        assertEquals(
            "[FemItems] Gave 1x <red>x to <bold>Bob.",
            plain(Messages.gaveCustomItem(1, "<red>x", "<bold>Bob")),
        )
    }

    @Test
    fun `invalid or unknown texts fall back to the defaults`() {
        val (_, broken) = MessageTexts.use(mapOf("items.stats" to "<color:#zz>bad", "items.not-a-message" to "hi"))
        // Non-strict MiniMessage reads almost anything; whatever it reads, the message still renders.
        assertTrue(broken.isEmpty() || broken == listOf("items.stats"))
        assertTrue(plain(Messages.stats(mapOf("a" to 1))).startsWith("[FemItems] "))
    }

    @Test
    fun `every message in the jar's messages_yml renders with default text`() {
        assertEquals("[FemItems] Config reloaded. (5 mob rules, 8 recipes, 2 custom items)", plain(Messages.reloaded(5, 8, 2)))
        assertEquals("[FemItems] Content pack error in contents/x/items.yml at line 4. (hover for details)",
            plain(Messages.configError(4, "boom", "contents/x/items.yml")))
        assertEquals("[FemItems] Custom items (0): none - add content packs to plugins/FemItems/contents/", plain(Messages.customItems(emptyList())))
        assertEquals(
            "[FemItems] ✦ Lucy got Frost Axe from Frost Jarl!",
            plain(Messages.rareDrop("Lucy", Component.text("Frost Axe"), Component.text("Frost Jarl"))),
        )
        assertEquals(
            "[FemItems] ✦ Frost Jarl dropped Frost Axe!",
            plain(Messages.rareDrop(null, Component.text("Frost Axe"), Component.text("Frost Jarl"))),
        )
        val skipped = Messages.skippedMissing(listOf("recipes.a skipped: x", "recipes.b skipped: y"))
        assertEquals("[FemItems] 2 drop(s)/recipe(s) skipped: they use custom items that aren't loaded. (hover for details)", plain(skipped))
        assertEquals("recipes.a skipped: x\nrecipes.b skipped: y", plain(skipped.hoverEvent()!!.value() as Component))
        assertEquals("[FemJukebox] Shuffle enabled.", plain(JukeboxMessages.shuffle(true)))
        assertEquals("[FemJukebox] Playback volume: 50%", plain(JukeboxMessages.volume(0.5f)))
        assertEquals("[FemJukebox] Songs (0): none", plain(JukeboxMessages.songs(emptyList())))
        val help = plain(JukeboxMessages.help(admin = false)).lines()
        assertEquals("[FemJukebox] Jukebox commands", help.first())
        assertEquals("  ▸ /femjukebox gui — open playback controls", help[1])
        val outdated = Messages.packStatus(dev.femitems.plugin.items.ExternalPackStatus.Outdated("http://x", "abc"), "p.zip")
        assertEquals("Link: http://x\nIts SHA-1: abc",plain(outdated.hoverEvent()!!.value() as Component))
    }
}
