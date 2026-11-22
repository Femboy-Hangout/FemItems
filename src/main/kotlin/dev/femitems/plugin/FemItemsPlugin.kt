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
import org.bukkit.Keyed
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.CommandSender
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
import java.util.logging.Level

class FemItemsPlugin : JavaPlugin(), Listener {
    private val recipeLock = Any()
    /** Recipes this plugin currently has registered, with the definition each was built from. Guarded by [recipeLock]. */
    private val registeredDefinitions: MutableMap<NamespacedKey, RecipeDefinition> = LinkedHashMap()

    @Volatile
    private var registeredRecipeKeys: Set<NamespacedKey> = emptySet()

    @Volatile
    private var mobRules: List<MobRule> = emptyList()

    private val debugMobDeaths = AtomicBoolean(false)

    private val reloadInProgress = AtomicBoolean(false)

    override fun onEnable() {
        saveDefaultConfig()
        server.pluginManager.registerEvents(this, this)
        registerCommands()

        val platform = if (isFolia()) "Folia" else "Paper"
        logger.info("Initializing FemItems ${pluginMeta.version} on $platform.")

        loadAndApplyOnStartup()
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
            .executes { context ->
                context.source.sender.sendMessage(
                    Messages.info(
                        version = pluginMeta.version,
                        platform = if (isFolia()) "Folia" else "Paper",
                        mobRules = mobRules.size,
                        recipes = registeredRecipeKeys.size,
                        debug = debugMobDeaths.get(),
                    ),
                )
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.literal("reload").executes { context ->
                    reloadAsync(context.source.sender)
                    Command.SINGLE_SUCCESS
                },
            )
            .then(
                Commands.literal("debug").executes { context ->
                    context.source.sender.sendMessage(Messages.debugToggled(toggleDebugMobDeaths()))
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

    private class LoadedRecipe(val definition: RecipeDefinition, val recipe: Recipe) {
        val key: NamespacedKey = (recipe as Keyed).key
    }

    private class LoadedContent(val mobRules: List<MobRule>, val recipes: List<LoadedRecipe>)

    private class RecipeSwap(val registered: Set<NamespacedKey>, val added: Set<NamespacedKey>)

    private sealed interface LoadResult {
        class Loaded(val content: LoadedContent) : LoadResult
        data class Failure(val line: Int?, val detail: String) : LoadResult
    }

    /** Startup load: synchronous in onEnable so recipes exist before any player can join. */
    private fun loadAndApplyOnStartup() {
        val result = loadContent()
        if (result is LoadResult.Loaded) {
            applyContent(result.content)
        }
    }

    /**
     * `/femitems reload`: file I/O, YAML parsing and item/recipe building run on the AsyncScheduler,
     * so the caller's region (or Paper's main thread) never stalls. Only the final swap, which must
     * touch the server RecipeManager, hops onto the global region thread.
     */
    private fun reloadAsync(sender: CommandSender) {
        if (!reloadInProgress.compareAndSet(false, true)) {
            sender.sendMessage(Messages.reloadInProgress())
            return
        }
        try {
            server.asyncScheduler.runNow(this) { _ ->
                when (val result = loadContent()) {
                    is LoadResult.Failure -> {
                        reloadInProgress.set(false)
                        sender.sendMessage(Messages.configError(result.line, result.detail))
                    }

                    is LoadResult.Loaded -> scheduleApply(sender, result.content)
                }
            }
        } catch (exception: Exception) {
            reloadInProgress.set(false)
            throw exception
        }
    }

    private fun scheduleApply(sender: CommandSender, content: LoadedContent) {
        try {
            server.globalRegionScheduler.execute(this) {
                try {
                    applyContent(content)
                    sender.sendMessage(Messages.reloaded(content.mobRules.size, content.recipes.size))
                } catch (exception: Exception) {
                    logger.log(Level.SEVERE, "Failed to apply reloaded config", exception)
                    sender.sendMessage(Messages.configError(null, ConfigParser.conciseError(exception)))
                } finally {
                    reloadInProgress.set(false)
                }
            }
        } catch (exception: Exception) {
            // Plugin was disabled between the async load and the hop back to the global region.
            reloadInProgress.set(false)
        }
    }

    /** Thread-agnostic: reads and validates config.yml and builds every recipe without touching live state. */
    private fun loadContent(): LoadResult {
        val configFile = File(dataFolder, "config.yml")
        return try {
            val newConfig = YamlConfiguration()
            newConfig.load(configFile)
            val parsed = ConfigParser.parse(newConfig)
            // Build every Bukkit recipe before touching live state, so a bad recipe fails the whole
            // reload up front instead of leaving the server half-reloaded after reporting success.
            val recipes = parsed.recipes.map { definition -> LoadedRecipe(definition, toBukkitRecipe(definition)) }
            LoadResult.Loaded(LoadedContent(parsed.mobRules, recipes))
        } catch (exception: Exception) {
            val line = ConfigParser.findErrorLine(exception, configFile).takeIf { it > 0 }
            val detail = ConfigParser.conciseError(exception)
            val location = if (line != null) "config.yml line $line" else "config.yml (line unavailable)"
            logger.warning("$location: $detail")
            LoadResult.Failure(line, detail)
        }
    }

    /** Must run on the global region thread (the main thread on Paper). */
    private fun applyContent(content: LoadedContent) {
        mobRules = content.mobRules
        val swap = replaceRegisteredRecipes(content.recipes)
        discoverRecipesForOnlinePlayers(swap.added)
        logger.info("Loaded ${content.mobRules.size} mob rules and ${swap.registered.size} recipes.")
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

    /**
     * Swaps in [recipes] while touching the server's recipe manager as little as possible.
     *
     * On Paper/Folia 26.3 every recipe-manager change ends in `finalizeRecipeLoading()`, which calls
     * `PlayerList.reloadResources()`: for EVERY online player it re-reads their advancements from disk,
     * re-sends all advancements and tags, and re-sends the full recipe book. The single-recipe
     * `addRecipe`/`removeRecipe` calls do that once per recipe (their `resend` flag is ignored), which
     * made a reload with 8 recipes trigger 16 full resyncs. So:
     *  - only recipes whose definition actually changed are removed/re-added (unchanged reload = 0 resyncs);
     *  - removals and additions each go through one batch call (worst case = 2 resyncs).
     */
    private fun replaceRegisteredRecipes(recipes: List<LoadedRecipe>): RecipeSwap = synchronized(recipeLock) {
        val wanted = recipes.associateBy(LoadedRecipe::key)
        val toAdd = wanted.values.filter { registeredDefinitions[it.key] != it.definition }

        val toRemove = LinkedHashSet<NamespacedKey>()
        for ((key, definition) in registeredDefinitions) {
            if (wanted[key]?.definition != definition) {
                toRemove += key
            }
        }
        // A stray recipe already under one of our keys (e.g. left behind by an earlier plugin instance)
        // would make the batch add throw part-way through, so clear it in the same removal batch.
        for (entry in toAdd) {
            if (entry.key !in toRemove && Bukkit.getRecipe(entry.key) != null) {
                toRemove += entry.key
            }
        }

        if (toRemove.isNotEmpty()) {
            Bukkit.removeRecipes(toRemove)
            toRemove.forEach(registeredDefinitions::remove)
        }
        val added = LinkedHashSet<NamespacedKey>()
        if (toAdd.isNotEmpty()) {
            Bukkit.addRecipes(toAdd.map(LoadedRecipe::recipe))
            for (entry in toAdd) {
                if (Bukkit.getRecipe(entry.key) != null) {
                    registeredDefinitions[entry.key] = entry.definition
                    added += entry.key
                }
            }
        }

        val snapshot = registeredDefinitions.keys.toSet()
        registeredRecipeKeys = snapshot
        RecipeSwap(snapshot, added)
    }

    private fun removeRegisteredRecipes() = synchronized(recipeLock) {
        if (registeredDefinitions.isNotEmpty()) {
            // One batch call = one resync, instead of one full resync per recipe.
            Bukkit.removeRecipes(registeredDefinitions.keys.toList())
        }
        registeredDefinitions.clear()
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
                .filter { rule -> ConfigParser.matchesRule(rule, entity.type, customNamePlain, entity.scoreboardTags) }
                .map(MobRule::id)
            logger.info("Mob death debug: entity=${entity.type}, customName=$customNamePlain, matchingRules=$matches")
        }

        val drops = ConfigParser.rollDrops(rules, entity.type, customNamePlain, scoreboardTags = entity.scoreboardTags)
        for (drop in drops) {
            val template = if (drop.randomEnchantment) {
                createRandomEnchantedBook(drop.name)
            } else {
                createItemStack(ItemDefinition(material = drop.material, amount = 1, name = drop.name))
            }
            for (stackSize in ConfigParser.splitIntoStacks(drop.amount, template.maxStackSize)) {
                event.drops.add(template.clone().apply { amount = stackSize })
            }
        }
    }

    private fun createRandomEnchantedBook(name: String?): ItemStack {
        val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
        val enchantments = registry.stream().toList()
        require(enchantments.isNotEmpty()) { "No enchantments are available for random enchanted book drops" }
        val enchantment = enchantments.random()
        val level = (1..enchantment.maxLevel).random()
        val item = ItemStack(Material.ENCHANTED_BOOK)
        item.editMeta { meta ->
            if (meta is EnchantmentStorageMeta) {
                meta.addStoredEnchant(enchantment, level, true)
                if (name != null) meta.displayName(parseLegacyComponent(name))
            }
        }
        return item
    }

    companion object {
        fun isFolia(): Boolean =
            ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"))
    }
}
