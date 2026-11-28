package dev.femitems.plugin.jukebox

import dev.femitems.plugin.MessageTexts
import dev.femitems.plugin.Messages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

/**
 * Jukebox chat messages, from the `jukebox:` part of `messages.yml`. With the default texts every message's
 * plain-text form starts with `[FemJukebox] ` and stays stable, because live fake-player tests match on it.
 */
object JukeboxMessages {
    private fun render(key: String, vararg resolvers: TagResolver): Component =
        MessageTexts.prefixed("jukebox.prefix", "jukebox.$key", *resolvers)

    private fun value(name: String, value: Any): TagResolver = Placeholder.unparsed(name, value.toString())

    fun nowPlaying(title: String): Component = render("now-playing", value("title", title))
    fun finished(title: String): Component = render("finished", value("title", title))

    fun paused(): Component = render("paused")
    fun resumed(): Component = render("resumed")
    fun resumedFromStart(): Component = render("resumed-from-start")
    fun stopped(): Component = render("stopped")
    fun nothingPlaying(): Component = render("nothing-playing")
    fun nothingPaused(): Component = render("nothing-paused")
    fun shuffle(enabled: Boolean): Component = render(if (enabled) "shuffle-on" else "shuffle-off")
    fun volume(value: Float): Component = render("volume", value("volume", Math.round(value * 100f)))

    fun unknownSong(): Component = render("unknown-song")
    fun playersOnly(): Component = render("players-only")
    fun discOnlyTrack(): Component = render("disc-only")
    fun holdItemToMap(): Component = render("hold-item")

    fun songs(ids: List<String>): Component = render(
        "songs",
        value("count", ids.size),
        if (ids.isEmpty()) Placeholder.component("list", MessageTexts.render("jukebox.songs-none"))
        else value("list", ids.joinToString(", ")),
    )

    fun gaveJukebox(amount: Int, target: String): Component =
        render("gave-jukebox", value("amount", amount), value("target", target))

    fun gaveDisc(title: String, target: String): Component =
        render("gave-disc", value("title", title), value("target", target))

    fun mapped(title: String): Component = render("mapped", value("title", title))

    fun reloaded(count: Int): Component = render("reloaded", value("count", count))

    fun reloadFailed(detail: String): Component =
        render("reload-failed").hoverEvent(HoverEvent.showText(Component.text(detail)))

    fun help(admin: Boolean): Component {
        val builder = Component.text().append(render("help.header"))
        val lines = buildList {
            add("/femjukebox gui" to "gui")
            add("/femjukebox songs" to "songs")
            add("/femjukebox play <song>" to "play")
            add("/femjukebox pause | resume | stop" to "controls")
            add("/femjukebox shuffle" to "shuffle")
            if (admin) {
                add("/femjukebox give [player] [amount]" to "give")
                add("/femjukebox disc <song> [player]" to "disc")
                add("/femjukebox map <song>" to "map")
                add("/femjukebox reload" to "reload")
            }
        }
        for ((command, key) in lines) {
            val suggestion = command.substringBefore(" <").substringBefore(" [").substringBefore(" |")
            builder.append(Component.newline())
                .append(Messages.commandLine(command, MessageTexts.text("jukebox.help.$key"), "$suggestion "))
        }
        return builder.build()
    }
}
