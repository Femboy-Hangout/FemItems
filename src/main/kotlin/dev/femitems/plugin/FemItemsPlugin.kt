package dev.femitems.plugin

import com.mojang.brigadier.Command
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.ServerBuildInfo
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class FemItemsPlugin : JavaPlugin(), Listener {
    private val recipeLock = Any()
    private val recipeKeys: MutableSet<NamespacedKey> = HashSet()

    @Volatile
    private var registeredRecipeKeys: Set<NamespacedKey> = emptySet()

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
                    val registeredKeys = replaceRegisteredRecipes(parsed.recipes)
                    discoverRecipesForOnlinePlayers(registeredKeys)
                    logger.info("Loaded $ruleCount mob rules and ${registeredKeys.size} recipes.")
                }
            } else {
                val registeredKeys = replaceRegisteredRecipes(parsed.recipes)
                discoverRecipesForOnlinePlayers(registeredKeys)
                logger.info("Loaded $ruleCount mob rules and ${registeredKeys.size} recipes.")
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

    private fun createItemStack(definition: ItemDefinition): ItemStack {
        val item = ItemStack(definition.material, definition.amount)
        val hasMetaChanges = definition.name != null ||
            definition.lore.isNotEmpty() ||
            definition.unbreakable ||
            definition.enchants.isNotEmpty()
        if (hasMetaChanges) {
            val enchantmentRegistry = if (definition.enchants.isNotEmpty()) {
                RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
            } else {
                null
            }
            item.editMeta { meta ->
                if (definition.name != null) {
                    meta.displayName(parseLegacyComponent(definition.name))
                }
                if (definition.lore.isNotEmpty()) {
                    meta.lore(definition.lore.map(::parseLegacyComponent))
                }
                if (definition.unbreakable) {
                    meta.isUnbreakable = true
                }
                if (enchantmentRegistry != null) {
                    for ((enchantKey, level) in definition.enchants) {
                        val enchantment = enchantmentRegistry.get(enchantKey)
                            ?: throw IllegalArgumentException("Unknown enchantment: $enchantKey")
                        if (meta is EnchantmentStorageMeta) {
                            meta.addStoredEnchant(enchantment, level, true)
                        } else {
                            meta.addEnchant(enchantment, level, true)
                        }
                    }
                }
            }
        }
        return item
    }

    private fun parseLegacyComponent(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand()
            .deserialize(text)
            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

    private fun replaceRegisteredRecipes(definitions: List<RecipeDefinition>): Set<NamespacedKey> =
        synchronized(recipeLock) {
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
            val snapshot = recipeKeys.toSet()
            registeredRecipeKeys = snapshot
            snapshot
        }

    private fun removeRegisteredRecipes() = synchronized(recipeLock) {
        for (key in recipeKeys) {
            Bukkit.removeRecipe(key)
        }
        recipeKeys.clear()
        registeredRecipeKeys = emptySet()
    }

    private fun discoverRecipesForOnlinePlayers(keys: Set<NamespacedKey>) {
        if (keys.isEmpty()) return
        for (player in server.onlinePlayers) {
            player.scheduler.run(this, { _ ->
                player.discoverRecipes(keys)
            }, null)
        }
    }

    private fun keyOf(recipe: Recipe): NamespacedKey = when (recipe) {
        is ShapedRecipe -> recipe.key
        is ShapelessRecipe -> recipe.key
        else -> throw IllegalArgumentException("Unsupported recipe implementation: ${recipe.javaClass.name}")
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val keys = registeredRecipeKeys
        if (keys.isNotEmpty()) {
            event.player.discoverRecipes(keys)
        }
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
            event.drops.add(
                createItemStack(ItemDefinition(material = drop.material, amount = drop.amount, name = drop.name)),
            )
        }
    }

    companion object {
        fun isFolia(): Boolean =
            ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"))
    }
}
