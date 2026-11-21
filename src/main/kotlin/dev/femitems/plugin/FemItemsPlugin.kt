package dev.femitems.plugin

import com.mojang.brigadier.Command
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.ServerBuildInfo
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class FemItemsPlugin : JavaPlugin(), Listener {
    private val recipeLock = Any()
    private val recipeKeys: MutableSet<NamespacedKey> = HashSet()

    @Volatile
    private var mobRules: List<MobRule> = emptyList()

    private val debugMobDeaths = AtomicBoolean(false)

    override fun onEnable() {
        saveDefaultConfig()
        server.pluginManager.registerEvents(this, this)
        registerCommands()

        val platform = if (isFolia()) "Folia" else "Paper"
        logger.info("Initializing FemItems ${pluginMeta.version} on $platform.")

        val error = reloadContent(scheduleOnGlobalRegion = false)
        if (error != null) {
            logger.warning(error)
        }
    }

    override fun onDisable() {
        removeRegisteredRecipes()
    }

    private fun registerCommands() {
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(buildRootCommand("femitems"))
            event.registrar().register(buildRootCommand("femi"))
        }
    }

    private fun buildRootCommand(label: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(label)
            .requires { source -> source.sender.hasPermission("femitems.reload") }
            .then(
                Commands.literal("reload").executes { context ->
                    val sender = context.source.sender
                    val error = reloadContent(scheduleOnGlobalRegion = true)
                    when (error) {
                        null -> sender.sendMessage("[FemItems] Config reloaded.")
                        "?" -> sender.sendMessage("[FemItems] Config error; see console.")
                        else -> sender.sendMessage("[FemItems] Config error at line $error.")
                    }
                    Command.SINGLE_SUCCESS
                },
            )
            .then(
                Commands.literal("debug").executes { context ->
                    val enabled = toggleDebugMobDeaths()
                    val message = if (enabled) {
                        "[FemItems] Mob death debug enabled; check console after killing a mob."
                    } else {
                        "[FemItems] Mob death debug disabled."
                    }
                    context.source.sender.sendMessage(message)
                    Command.SINGLE_SUCCESS
                },
            )
            .build()

    private fun toggleDebugMobDeaths(): Boolean {
        while (true) {
            val current = debugMobDeaths.get()
            val next = !current
            if (debugMobDeaths.compareAndSet(current, next)) {
                return next
            }
        }
    }

    private fun reloadContent(scheduleOnGlobalRegion: Boolean): String? {
        val configFile = File(dataFolder, "config.yml")
        return try {
            val newConfig = YamlConfiguration()
            newConfig.load(configFile)
            val parsed = ConfigParser.parse(newConfig)

            mobRules = parsed.mobRules
            val ruleCount = parsed.mobRules.size

            if (scheduleOnGlobalRegion) {
                server.globalRegionScheduler.execute(this) {
                    val registeredCount = replaceRegisteredRecipes(parsed.recipes)
                    logger.info("Loaded $ruleCount mob rules and $registeredCount recipes.")
                }
            } else {
                val registeredCount = replaceRegisteredRecipes(parsed.recipes)
                logger.info("Loaded $ruleCount mob rules and $registeredCount recipes.")
            }
            null
        } catch (exception: Exception) {
            val line = ConfigParser.findErrorLine(exception, configFile)
            val error = if (line > 0) {
                "config.yml line $line"
            } else {
                "config.yml (line unavailable): ${ConfigParser.conciseError(exception)}"
            }
            logger.warning("[FemItems] $error")
            if (line > 0) line.toString() else "?"
        }
    }

    private fun toBukkitRecipe(definition: RecipeDefinition): Recipe {
        val key = NamespacedKey(this, definition.id.lowercase(Locale.ROOT))
        val result = createItemStack(definition.result)
        return when (definition) {
            is ShapedRecipeDefinition -> ShapedRecipe(key, result).apply {
                shape(*definition.shape.toTypedArray())
                for ((symbol, material) in definition.ingredients) {
                    setIngredient(symbol, material)
                }
            }

            is ShapelessRecipeDefinition -> ShapelessRecipe(key, result).apply {
                for (material in definition.ingredients) {
                    addIngredient(material)
                }
            }
        }
    }

    private fun createItemStack(definition: ItemDefinition): ItemStack =
        createItemStack(definition.material, definition.amount, definition.name)

    private fun createItemStack(material: org.bukkit.Material, amount: Int, name: String?): ItemStack {
        val item = ItemStack(material, amount)
        if (name != null) {
            item.editMeta { meta ->
                meta.displayName(Component.text(name))
            }
        }
        return item
    }

    private fun replaceRegisteredRecipes(definitions: List<RecipeDefinition>): Int = synchronized(recipeLock) {
        for (key in recipeKeys) {
            Bukkit.removeRecipe(key)
        }
        recipeKeys.clear()
        for (definition in definitions) {
            val recipe = toBukkitRecipe(definition)
            if (Bukkit.addRecipe(recipe)) {
                recipeKeys.add(keyOf(recipe))
            }
        }
        recipeKeys.size
    }

    private fun removeRegisteredRecipes() = synchronized(recipeLock) {
        for (key in recipeKeys) {
            Bukkit.removeRecipe(key)
        }
        recipeKeys.clear()
    }

    private fun keyOf(recipe: Recipe): NamespacedKey = when (recipe) {
        is ShapedRecipe -> recipe.key
        is ShapelessRecipe -> recipe.key
        else -> throw IllegalArgumentException("Unsupported recipe implementation: ${recipe.javaClass.name}")
    }

    @EventHandler
    fun onMobDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val rules = mobRules
        val customNamePlain = entity.customName()?.let { PlainTextComponentSerializer.plainText().serialize(it) }

        if (debugMobDeaths.get() && entity !is Player) {
            val matches = rules
                .filter { rule -> ConfigParser.matchesRule(rule, entity.type, customNamePlain) }
                .map(MobRule::id)
            logger.info("Mob death debug: entity=${entity.type}, customName=$customNamePlain, matchingRules=$matches")
        }

        val drops = ConfigParser.rollDrops(rules, entity.type, customNamePlain)
        for (drop in drops) {
            event.drops.add(createItemStack(drop.material, drop.amount, drop.name))
        }
    }

    companion object {
        fun isFolia(): Boolean =
            ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"))
    }
}
