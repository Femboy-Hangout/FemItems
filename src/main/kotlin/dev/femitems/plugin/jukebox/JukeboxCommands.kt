package dev.femitems.plugin.jukebox

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Brigadier tree for `/femjukebox`. Admin subcommands are hidden from players without `femjukebox.admin`. */
class JukeboxCommands(private val plugin: JavaPlugin, private val feature: JukeboxFeature) {
    private val music get() = feature.music

    fun build(label: String = "femjukebox"): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(label)
            .executes { ctx -> ctx.reply(JukeboxMessages.help(ctx.isAdmin())) }
            .then(Commands.literal("help").executes { ctx -> ctx.reply(JukeboxMessages.help(ctx.isAdmin())) })
            .then(Commands.literal("gui").executes { ctx -> ctx.asPlayer { feature.menu.open(it, null) } })
            .then(Commands.literal("songs").executes { ctx ->
                val catalog = music.catalog
                ctx.reply(JukeboxMessages.songs(if (ctx.isAdmin()) catalog.ids else catalog.noteSongs.map(Song::id)))
            })
            .then(
                Commands.literal("play").then(
                    Commands.argument("song", StringArgumentType.string()).suggests(songSuggestions).executes { ctx ->
                        ctx.asPlayer { player ->
                            val song = music.catalog.song(StringArgumentType.getString(ctx, "song"))
                            when {
                                song == null -> player.sendMessage(JukeboxMessages.unknownSong())
                                song.isResourcePackTrack && !ctx.isAdmin() -> player.sendMessage(JukeboxMessages.discOnlyTrack())
                                else -> music.play(player, song)
                            }
                        }
                    },
                ),
            )
            .then(Commands.literal("pause").executes { ctx -> ctx.asPlayer(music::pause) })
            .then(Commands.literal("resume").executes { ctx -> ctx.asPlayer(music::resume) })
            .then(Commands.literal("stop").executes { ctx -> ctx.asPlayer { music.stop(it, null) } })
            .then(Commands.literal("shuffle").executes { ctx -> ctx.asPlayer(music::toggleShuffle) })
            .then(admin("give").executes { ctx -> giveJukebox(ctx, ctx.senderPlayerOrNull(), 1) }
                .then(Commands.argument("player", ArgumentTypes.player())
                    .executes { ctx -> giveJukebox(ctx, ctx.targetPlayer(), 1) }
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                        .executes { ctx -> giveJukebox(ctx, ctx.targetPlayer(), IntegerArgumentType.getInteger(ctx, "amount")) })))
            .then(admin("disc").then(Commands.argument("song", StringArgumentType.string()).suggests(songSuggestions)
                .executes { ctx -> giveDisc(ctx, ctx.senderPlayerOrNull()) }
                .then(Commands.argument("player", ArgumentTypes.player())
                    .executes { ctx -> giveDisc(ctx, ctx.targetPlayer()) })))
            .then(admin("map").then(Commands.argument("song", StringArgumentType.string()).suggests(songSuggestions)
                .executes { ctx -> ctx.asPlayer { player -> mapHeld(player, StringArgumentType.getString(ctx, "song")) } }))
            .then(admin("reload").executes { ctx ->
                val sender = ctx.source.sender
                feature.reloadAsync { result ->
                    result.onSuccess { sender.sendMessage(JukeboxMessages.reloaded(it)) }
                        .onFailure {
                            plugin.logger.warning("Jukebox reload failed: $it")
                            sender.sendMessage(JukeboxMessages.reloadFailed(it.toString()))
                        }
                }
                Command.SINGLE_SUCCESS
            })
            .build()

    private fun admin(name: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).requires { it.sender.hasPermission(JukeboxFeature.ADMIN_PERMISSION) }

    private val songSuggestions = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val admin = ctx.source.sender.hasPermission(JukeboxFeature.ADMIN_PERMISSION)
        val songs = if (admin) music.catalog.all else music.catalog.noteSongs
        val typed = builder.remainingLowerCase.removePrefix("\"")
        for (song in songs) {
            if (song.id.lowercase().startsWith(typed)) builder.suggest(quoteIfNeeded(song.id))
        }
        builder.buildFuture()
    }

    private fun giveJukebox(ctx: CommandContext<CommandSourceStack>, target: Player?, amount: Int): Int {
        if (target == null) return ctx.reply(JukeboxMessages.playersOnly())
        val sender = ctx.source.sender
        target.scheduler.run(plugin, {
            giveTo(target, JukeboxItems.createJukebox(amount))
            sender.sendMessage(JukeboxMessages.gaveJukebox(amount, target.name))
        }, null)
        return Command.SINGLE_SUCCESS
    }

    private fun giveDisc(ctx: CommandContext<CommandSourceStack>, target: Player?): Int {
        if (target == null) return ctx.reply(JukeboxMessages.playersOnly())
        val song = music.catalog.song(StringArgumentType.getString(ctx, "song"))
            ?: return ctx.reply(JukeboxMessages.unknownSong())
        val sender = ctx.source.sender
        target.scheduler.run(plugin, {
            giveTo(target, JukeboxItems.createDisc(song))
            sender.sendMessage(JukeboxMessages.gaveDisc(song.title, target.name))
        }, null)
        return Command.SINGLE_SUCCESS
    }

    /** Runs on the player's thread (Brigadier executes player commands there). */
    private fun mapHeld(player: Player, songId: String) {
        val song = music.catalog.song(songId) ?: return player.sendMessage(JukeboxMessages.unknownSong())
        val held = player.inventory.itemInMainHand
        if (held.isEmpty) return player.sendMessage(JukeboxMessages.holdItemToMap())
        held.editMeta { it.persistentDataContainer.set(JukeboxKeys.SONG_ID, PersistentDataType.STRING, song.id) }
        player.sendMessage(JukeboxMessages.mapped(song.title))
    }

    private fun giveTo(player: Player, item: ItemStack) {
        player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun CommandContext<CommandSourceStack>.isAdmin(): Boolean =
        source.sender.hasPermission(JukeboxFeature.ADMIN_PERMISSION)

    private fun CommandContext<CommandSourceStack>.reply(message: Component): Int {
        source.sender.sendMessage(message)
        return Command.SINGLE_SUCCESS
    }

    private fun CommandContext<CommandSourceStack>.asPlayer(action: (Player) -> Unit): Int {
        val player = source.sender as? Player ?: return reply(JukeboxMessages.playersOnly())
        action(player)
        return Command.SINGLE_SUCCESS
    }

    private fun CommandContext<CommandSourceStack>.senderPlayerOrNull(): Player? = source.sender as? Player

    private fun CommandContext<CommandSourceStack>.targetPlayer(): Player? =
        getArgument("player", PlayerSelectorArgumentResolver::class.java).resolve(source).firstOrNull()

    companion object {
        /** Brigadier's unquoted-string alphabet; anything else needs quotes. */
        fun quoteIfNeeded(value: String): String =
            if (value.isNotEmpty() && value.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it in "_-.+" }) {
                value
            } else {
                "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }
    }
}
