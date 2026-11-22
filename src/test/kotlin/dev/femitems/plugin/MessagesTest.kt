package dev.femitems.plugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
                "  ▸ /femitems reload — reload config.yml",
                "  ▸ /femitems debug — toggle mob-death diagnostics",
            ),
            lines,
        )
    }
}
