package dev.femitems.plugin.jukebox

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

/**
 * Styled jukebox chat messages. Every message's plain-text form starts with `[FemJukebox] ` and stays
 * stable, because live fake-player tests match on it.
 */
object JukeboxMessages {
    private val mm = MiniMessage.miniMessage()

    private const val PINK = "#ff7eb6"
    private const val LAVENDER = "#c084fc"
    private const val SUCCESS = "#7ee8a2"
    private const val ERROR = "#ff6b81"
    private const val MUTED = "#a1a1aa"
    private const val PREFIX =
        "<dark_gray>[</dark_gray><gradient:$PINK:$LAVENDER>FemJukebox</gradient><dark_gray>]</dark_gray> "

    private fun render(template: String, vararg resolvers: TagResolver): Component =
        mm.deserialize(PREFIX + template, *resolvers)

    private fun muted(text: String): Component =
        render("<color:$MUTED><text></color>", Placeholder.unparsed("text", text))

    private fun error(text: String): Component =
        render("<color:$ERROR><text></color>", Placeholder.unparsed("text", text))

    fun nowPlaying(title: String): Component =
        render("<color:$LAVENDER>Now playing:</color> <white><title></white>", Placeholder.unparsed("title", title))

    fun finished(title: String): Component =
        render("<color:$MUTED>Finished:</color> <white><title></white>", Placeholder.unparsed("title", title))

    fun paused(): Component = muted("Playback paused.")
    fun resumed(): Component = render("<color:$SUCCESS>Playback resumed.</color>")
    fun resumedFromStart(): Component =
        render("<color:$SUCCESS>Playback resumed.</color> <color:$MUTED>Resource-pack audio restarts from the beginning.</color>")
    fun stopped(): Component = muted("Playback stopped.")
    fun nothingPlaying(): Component = muted("Nothing is playing.")
    fun nothingPaused(): Component = muted("Nothing is paused.")
    fun shuffle(enabled: Boolean): Component = muted("Shuffle ${if (enabled) "enabled" else "disabled"}.")
    fun volume(value: Float): Component = muted("Playback volume: ${Math.round(value * 100f)}%")

    fun unknownSong(): Component = error("Unknown song. Use /femjukebox songs.")
    fun playersOnly(): Component = error("Only players can do that.")
    fun discOnlyTrack(): Component =
        error("Custom disc tracks can only be played by using the disc in a Custom Jukebox.")
    fun holdItemToMap(): Component = error("Hold the disc you want to map in your main hand.")

    fun songs(ids: List<String>): Component =
        render(
            "<color:$LAVENDER>Songs (<count>):</color> <white><list></white>",
            Placeholder.unparsed("count", ids.size.toString()),
            Placeholder.unparsed("list", if (ids.isEmpty()) "none" else ids.joinToString(", ")),
        )

    fun gaveJukebox(amount: Int, target: String): Component =
        render(
            "<color:$SUCCESS>Gave <amount> Custom Jukebox item(s) to <target>.</color>",
            Placeholder.unparsed("amount", amount.toString()),
            Placeholder.unparsed("target", target),
        )

    fun gaveDisc(title: String, target: String): Component =
        render(
            "<color:$SUCCESS>Gave ♫ <title> to <target>.</color>",
            Placeholder.unparsed("title", title),
            Placeholder.unparsed("target", target),
        )

    fun mapped(title: String): Component =
        render("<color:$SUCCESS>Mapped the held item to <title>.</color>", Placeholder.unparsed("title", title))

    fun reloaded(count: Int): Component =
        render(
            "<color:$SUCCESS>Loaded <count> song(s).</color> <color:$MUTED>File changes also reload automatically.</color>",
            Placeholder.unparsed("count", count.toString()),
        )

    fun reloadFailed(detail: String): Component =
        render("<color:$ERROR>Song reload failed; see console.</color>")
            .hoverEvent(HoverEvent.showText(Component.text(detail)))

    fun help(admin: Boolean): Component {
        val builder = Component.text().append(render("<color:$LAVENDER>Jukebox commands</color>"))
        val lines = buildList {
            add("/femjukebox gui" to "open playback controls")
            add("/femjukebox songs" to "list songs")
            add("/femjukebox play <song>" to "play a song to yourself and nearby players")
            add("/femjukebox pause | resume | stop" to "control your playback")
            add("/femjukebox shuffle" to "toggle random next song")
            if (admin) {
                add("/femjukebox give [player] [amount]" to "give Custom Jukebox items")
                add("/femjukebox disc <song> [player]" to "give a mapped disc")
                add("/femjukebox map <song>" to "map the held item to a song")
                add("/femjukebox reload" to "convert NBS imports and reload songs")
            }
        }
        for ((command, description) in lines) {
            val suggestion = command.substringBefore(" <").substringBefore(" [").substringBefore(" |")
            builder.append(Component.newline()).append(
                mm.deserialize(
                    "<color:$PINK>  ▸</color> <color:$LAVENDER><command></color> <color:$MUTED>— <description></color>",
                    Placeholder.unparsed("command", command),
                    Placeholder.unparsed("description", description),
                )
                    .clickEvent(ClickEvent.suggestCommand("$suggestion "))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to fill in $suggestion"))),
            )
        }
        return builder.build()
    }
}
