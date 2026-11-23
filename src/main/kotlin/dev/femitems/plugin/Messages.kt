package dev.femitems.plugin

import dev.femitems.plugin.items.ExternalPackStatus
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

    /** Custom items are only mentioned when there are some, so existing output stays unchanged. */
    fun reloaded(mobRules: Int, recipes: Int, customItems: Int = 0): Component = render(
        "<color:$SUCCESS>Config reloaded.</color> <color:$MUTED>(<rules> mob rules, <recipes> recipes<items>)</color>",
        Placeholder.unparsed("rules", mobRules.toString()),
        Placeholder.unparsed("recipes", recipes.toString()),
        Placeholder.unparsed("items", if (customItems > 0) ", $customItems custom items" else ""),
    )

    fun customItems(ids: List<String>): Component = render(
        "<color:$LAVENDER>Custom items (<count>):</color> <white><list></white>",
        Placeholder.unparsed("count", ids.size.toString()),
        Placeholder.unparsed("list", if (ids.isEmpty()) "none - add content packs to plugins/FemItems/contents/" else ids.joinToString(", ")),
    )

    fun unknownCustomItem(id: String): Component = render(
        "<color:$ERROR>Unknown custom item: <id></color> <color:$MUTED>(see /femitems items)</color>",
        Placeholder.unparsed("id", id),
    )

    fun gaveCustomItem(amount: Int, id: String, target: String): Component = render(
        "<color:$SUCCESS>Gave <amount>x <id> to <target>.</color>",
        Placeholder.unparsed("amount", amount.toString()),
        Placeholder.unparsed("id", id),
        Placeholder.unparsed("target", target),
    )

    fun reloadInProgress(): Component =
        render("<color:$MUTED>A reload is already in progress; please wait.</color>")

    fun configError(line: Int?, detail: String, source: String = "config.yml"): Component {
        val where = if (source == "config.yml") "Config error" else "Content pack error in $source"
        val text = if (line != null) "$where at line $line." else "$where; see console."
        return render("<color:$ERROR><text></color> <color:$MUTED><italic>(hover for details)</italic></color>",
            Placeholder.unparsed("text", text),
        ).hoverEvent(HoverEvent.showText(Component.text(detail)))
    }

    /** Where the custom item resource pack stands (mode: external). */
    fun packStatus(status: ExternalPackStatus, fileName: String): Component = when (status) {
        is ExternalPackStatus.Verified -> render(
            "<color:$SUCCESS>Resource pack verified; players get it from</color> <white><url></white>",
            Placeholder.unparsed("url", status.url),
        )
        is ExternalPackStatus.Outdated -> render(
            "<color:$ERROR>Uploaded resource pack is outdated.</color> <color:$MUTED>Upload resourcepack/<file> again and update resource-pack.url, then /femitems pack.</color>",
            Placeholder.unparsed("file", fileName),
        ).hoverEvent(HoverEvent.showText(Component.text("Link: ${status.url}\nIts SHA-1: ${status.remoteSha1}")))
        is ExternalPackStatus.Unreachable -> render(
            "<color:$ERROR>Could not download the resource pack:</color> <color:$MUTED><reason></color>",
            Placeholder.unparsed("reason", status.reason),
        ).hoverEvent(HoverEvent.showText(Component.text("Link: ${status.url}")))
        ExternalPackStatus.NotConfigured -> render(
            "<color:$LAVENDER>Resource pack not sent yet.</color> <color:$MUTED>Upload plugins/FemItems/resourcepack/<file> to mc-packs.net and put its download link in resource-pack.url, then /femitems pack.</color>",
            Placeholder.unparsed("file", fileName),
        )
        ExternalPackStatus.Checking -> render("<color:$MUTED>Checking the uploaded resource pack...</color>")
    }

    fun packMode(mode: String): Component = render(
        "<color:$MUTED>Resource pack mode is</color> <white><mode></white><color:$MUTED>; nothing to verify.</color>",
        Placeholder.unparsed("mode", mode),
    )

    /** Internal state sizes, e.g. "playbacks=0 bossbars=1 ..." (used by leak/soak tests). */
    fun stats(sizes: Map<String, Int>): Component = render(
        "<color:$MUTED>State:</color> <white><sizes></white>",
        Placeholder.unparsed("sizes", sizes.entries.joinToString(" ") { "${it.key}=${it.value}" }),
    )

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
