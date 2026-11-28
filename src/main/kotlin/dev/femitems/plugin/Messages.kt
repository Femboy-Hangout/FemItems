package dev.femitems.plugin

import dev.femitems.plugin.items.ExternalPackStatus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

/**
 * FemItems chat messages. The texts come from `messages.yml` ([MessageTexts]); with the default texts every
 * message's plain-text form starts with `[FemItems] ` and stays stable, because live fake-player tests match on it.
 * Values put into a message (names, ids, errors) are never parsed as MiniMessage.
 */
object Messages {
    private fun render(key: String, vararg resolvers: TagResolver): Component =
        MessageTexts.prefixed("prefix", "items.$key", *resolvers)

    private fun value(name: String, value: Any): TagResolver = Placeholder.unparsed(name, value.toString())

    /** Custom items are only mentioned when there are some, so existing output stays unchanged. */
    fun reloaded(mobRules: Int, recipes: Int, customItems: Int = 0): Component = render(
        "reloaded",
        value("rules", mobRules),
        value("recipes", recipes),
        Placeholder.component(
            "items",
            if (customItems > 0) MessageTexts.render("items.reloaded-custom-items", value("count", customItems)) else Component.empty(),
        ),
    )

    fun customItems(ids: List<String>): Component = render(
        "custom-items",
        value("count", ids.size),
        if (ids.isEmpty()) Placeholder.component("list", MessageTexts.render("items.custom-items-none"))
        else value("list", ids.joinToString(", ")),
    )

    fun unknownCustomItem(id: String): Component = render("unknown-item", value("id", id))

    fun gaveCustomItem(amount: Int, id: String, target: String): Component =
        render("gave-item", value("amount", amount), value("id", id), value("target", target))

    /** An `announce: true` item dropped. [item] is its hoverable name; [player] is null with no player killer. */
    fun rareDrop(player: String?, item: Component, mob: Component): Component =
        if (player != null) render("rare-drop", value("player", player), Placeholder.component("item", item), Placeholder.component("mob", mob))
        else render("rare-drop-no-player", Placeholder.component("item", item), Placeholder.component("mob", mob))

    fun playersOnly(): Component = render("players-only")

    fun reloadInProgress(): Component = render("reload-in-progress")

    fun configError(line: Int?, detail: String, source: String = "config.yml"): Component {
        val key = (if (source == "config.yml") "config-error" else "pack-error") + (if (line != null) "-at-line" else "")
        return render(key, value("line", line ?: 0), value("file", source))
            .hoverEvent(HoverEvent.showText(Component.text(detail)))
    }

    /** Where the custom item resource pack stands (mode: external). */
    fun packStatus(status: ExternalPackStatus, fileName: String): Component = when (status) {
        is ExternalPackStatus.Verified -> render("pack.verified", value("url", status.url))
        is ExternalPackStatus.Outdated -> render("pack.outdated", value("file", fileName)).hoverEvent(
            HoverEvent.showText(MessageTexts.render("items.pack.outdated-hover", value("url", status.url), value("sha1", status.remoteSha1))),
        )
        is ExternalPackStatus.Unreachable -> render("pack.unreachable", value("reason", status.reason)).hoverEvent(
            HoverEvent.showText(MessageTexts.render("items.pack.unreachable-hover", value("url", status.url))),
        )
        ExternalPackStatus.NotConfigured -> render("pack.not-configured", value("file", fileName))
        ExternalPackStatus.Checking -> render("pack.checking")
    }

    fun packMode(mode: String): Component = render("pack.nothing-to-check", value("mode", mode))

    /** Internal state sizes, e.g. "playbacks=0 bossbars=1 ..." (used by leak/soak tests). */
    fun stats(sizes: Map<String, Int>): Component =
        render("stats", value("sizes", sizes.entries.joinToString(" ") { "${it.key}=${it.value}" }))

    fun debugToggled(enabled: Boolean): Component = render(if (enabled) "debug-on" else "debug-off")

    fun info(version: String, platform: String, mobRules: Int, recipes: Int, debug: Boolean): Component {
        val header = render("info.header", value("version", version), value("platform", platform))
        val stats = MessageTexts.render(
            "items.info.stats",
            value("rules", mobRules),
            value("recipes", recipes),
            Placeholder.component("debug", MessageTexts.render(if (debug) "items.info.debug-on" else "items.info.debug-off")),
        )
        return Component.text()
            .append(header)
            .append(Component.newline()).append(stats)
            .append(Component.newline()).append(commandLine("/femitems reload", MessageTexts.text("items.info.reload")))
            .append(Component.newline()).append(commandLine("/femitems gui", MessageTexts.text("items.info.gui")))
            .append(Component.newline()).append(commandLine("/femitems debug", MessageTexts.text("items.info.debug")))
            .build()
    }

    /**
     * A clickable `  ▸ /command — description` help line (`command-line` in messages.yml) that fills [fill] into chat.
     * [description] is MiniMessage from messages.yml.
     */
    fun commandLine(command: String, description: String, fill: String = command): Component =
        MessageTexts.render(
            "command-line",
            value("command", command),
            Placeholder.parsed("description", description),
        )
            .clickEvent(ClickEvent.suggestCommand(fill))
            .hoverEvent(HoverEvent.showText(MessageTexts.render("command-line-hover", value("command", fill.trim()))))
}
