package dev.femitems.plugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

/**
 * Styled, Adventure-based chat messages for FemItems.
 *
 * Every message renders as plain text starting with `[FemItems] ` so console output, logs, and
 * plain-text consumers (including the live fake-player tests) stay readable and stable.
 */
object Messages {
    private val mm = MiniMessage.miniMessage()

    private const val PINK = "#ff7eb6"
    private const val LAVENDER = "#c084fc"
    private const val SUCCESS = "#7ee8a2"
    private const val ERROR = "#ff6b81"
    private const val MUTED = "#a1a1aa"

    private const val PREFIX =
        "<dark_gray>[</dark_gray><gradient:$PINK:$LAVENDER>FemItems</gradient><dark_gray>]</dark_gray> "

    private fun render(template: String, vararg resolvers: TagResolver): Component =
        mm.deserialize(PREFIX + template, *resolvers)

    fun reloaded(mobRules: Int, recipes: Int): Component = render(
        "<color:$SUCCESS>Config reloaded.</color> <color:$MUTED>(<rules> mob rules, <recipes> recipes)</color>",
        Placeholder.unparsed("rules", mobRules.toString()),
        Placeholder.unparsed("recipes", recipes.toString()),
    )

    fun configError(line: Int?, detail: String): Component {
        val text = if (line != null) "Config error at line $line." else "Config error; see console."
        return render("<color:$ERROR><text></color> <color:$MUTED><italic>(hover for details)</italic></color>",
            Placeholder.unparsed("text", text),
        ).hoverEvent(HoverEvent.showText(Component.text(detail)))
    }

    fun debugToggled(enabled: Boolean): Component = if (enabled) {
        render("<color:$LAVENDER>Mob death debug enabled; check console after killing a mob.</color>")
    } else {
        render("<color:$MUTED>Mob death debug disabled.</color>")
    }

    fun info(version: String, platform: String, mobRules: Int, recipes: Int, debug: Boolean): Component {
        val header = render(
            "<white>v<version></white> <color:$MUTED>on</color> <color:$LAVENDER><platform></color>",
            Placeholder.unparsed("version", version),
            Placeholder.unparsed("platform", platform),
        )
        val stats = mm.deserialize(
            "<color:$PINK>  ✦</color> <white><rules></white> <color:$MUTED>mob rules ·</color> " +
                "<white><recipes></white> <color:$MUTED>recipes · debug</color> <debug>",
            Placeholder.unparsed("rules", mobRules.toString()),
            Placeholder.unparsed("recipes", recipes.toString()),
            Placeholder.parsed("debug", if (debug) "<color:$SUCCESS>on</color>" else "<color:$MUTED>off</color>"),
        )
        return Component.text()
            .append(header)
            .append(Component.newline()).append(stats)
            .append(Component.newline()).append(commandLine("/femitems reload", "reload config.yml"))
            .append(Component.newline()).append(commandLine("/femitems debug", "toggle mob-death diagnostics"))
            .build()
    }

    private fun commandLine(command: String, description: String): Component =
        mm.deserialize(
            "<color:$PINK>  ▸</color> <color:$LAVENDER><command></color> <color:$MUTED>— <description></color>",
            Placeholder.unparsed("command", command),
            Placeholder.unparsed("description", description),
        )
            .clickEvent(ClickEvent.suggestCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text("Click to fill in $command")))
}
