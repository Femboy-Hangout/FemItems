package dev.femitems.plugin

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import dev.femitems.plugin.items.ContentPackException
import dev.femitems.plugin.items.CustomItemService
import dev.femitems.plugin.items.LoadedItems
import dev.femitems.plugin.items.ResourcePackSettings
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import com.mojang.brigadier.tree.LiteralCommandNode
import dev.femitems.plugin.jukebox.JukeboxCommands
import dev.femitems.plugin.jukebox.JukeboxFeature
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
import org.bukkit.inventory.BlastingRecipe
import org.bukkit.inventory.CampfireRecipe
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.SmokingRecipe
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
    /** Recipes this plugin has registered, with the fingerprint (definition + referenced custom items) each was built from. */
    private val registeredDefinitions: MutableMap<NamespacedKey, Any> = LinkedHashMap()

    @Volatile
    private var registeredRecipeKeys: Set<NamespacedKey> = emptySet()

    @Volatile
    private var mobRules: List<MobRule> = emptyList()

    private val debugMobDeaths = AtomicBoolean(false)

    private val reloadInProgress = AtomicBoolean(false)

    /** ItemsAdder-style custom items with their own textures, from `contents/<namespace>/`. */
    private val customItems = CustomItemService(this)

    /** NBS music & Custom Jukebox feature (formerly the standalone FemJukebox plugin). */
    private var jukebox: JukeboxFeature? = null

    override fun onEnable() {
        saveDefaultConfig()
        server.pluginManager.registerEvents(this, this)
        server.pluginManager.registerEvents(customItems, this)

        val platform = if (isFolia()) "Folia" else "Paper"
        logger.info("Initializing FemItems ${pluginMeta.version} on $platform.")

        loadAndApplyOnStartup()
        enableJukebox()
        registerCommands()
    }

    override fun onDisable() {
        jukebox?.disable()
        jukebox = null
        customItems.shutdown()
        removeRegisteredRecipes()
    }

    private fun enableJukebox() {
        if (server.pluginManager.getPlugin("FemJukebox") != null) {
            logger.severe(
                "The standalone FemJukebox plugin is installed, but its features are now part of FemItems. " +
                    "Remove FemJukebox's JAR and restart; FemItems' jukebox stays disabled until then.",
            )
            return
        }
        jukebox = try {
            JukeboxFeature(this).also(JukeboxFeature::enable)
        } catch (exception: Exception) {
            logger.log(Level.SEVERE, "Could not enable the jukebox feature", exception)
            null
        }
    }

    private fun registerCommands() {
        val jukeboxCommands = jukebox?.let { JukeboxCommands(this, it) }
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(buildRootCommand("femitems"))
            event.registrar().register(buildRootCommand("femi"))
            jukeboxCommands?.let { event.registrar().register(it.build(), "Play NBS music and manage Custom Jukeboxes") }
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
            .then(
                Commands.literal("pack").executes { context ->
                    // Re-check the uploaded pack (e.g. right after uploading a new version).
                    val sender = context.source.sender
                    if (customItems.current.resourcePack == null) {
                        sender.sendMessage(Messages.customItems(emptyList()))
                    } else if (customItems.current.settings.mode == dev.femitems.plugin.items.PackMode.EXTERNAL) {
                        sender.sendMessage(Messages.packStatus(dev.femitems.plugin.items.ExternalPackStatus.Checking, ""))
                        customItems.checkExternalPack(sender::sendMessage)
                    } else {
                        sender.sendMessage(Messages.packMode(customItems.current.settings.mode.configName))
                    }
                    Command.SINGLE_SUCCESS
                },
            )
            .then(
                Commands.literal("stats").executes { context ->
                    val sizes = (jukebox?.music?.stateSizes() ?: emptyMap()) +
                        mapOf("custom_items" to customItems.current.count, "recipes" to registeredRecipeKeys.size)
                    context.source.sender.sendMessage(Messages.stats(sizes))
                    Command.SINGLE_SUCCESS
                },
            )
            .then(
                Commands.literal("items").executes { context ->
                    context.source.sender.sendMessage(Messages.customItems(customItems.current.definitions.keys.toList()))
                    Command.SINGLE_SUCCESS
                },
            )
            .then(
                Commands.literal("give")
                    .requires { source -> source.sender.hasPermission("femitems.give") }
                    .then(
                        Commands.argument("player", ArgumentTypes.player()).then(
                            Commands.argument("item", StringArgumentType.greedyString())
                                .suggests { _, builder ->
                                    val typed = builder.remainingLowerCase
                                    customItems.current.definitions.keys.filter { it.startsWith(typed) }.forEach(builder::suggest)
                                    builder.buildFuture()
                                }
                                .executes { context -> giveCustomItem(context.source.sender, context.getArgument("player", PlayerSelectorArgumentResolver::class.java).resolve(context.source), StringArgumentType.getString(context, "item")) },
                        ),
                    ),
            )
            .build()

    /** `/femitems give <player> <namespace:item> [amount]` — the amount is an optional trailing number. */
    private fun giveCustomItem(sender: CommandSender, targets: List<Player>, input: String): Int {
        val parts = input.trim().split(Regex("\\s+"))
        val id = parts[0].lowercase(Locale.ROOT)
        val amount = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 64 * 36) ?: 1
        val template = customItems.create(id)
        if (template == null) {
            sender.sendMessage(Messages.unknownCustomItem(id))
            return 0
        }
        for (target in targets) {
            target.scheduler.run(this, { _ ->
                for (stackSize in ConfigParser.splitIntoStacks(amount, template.maxStackSize)) {
                    val stack = template.clone().apply { this.amount = stackSize }
                    target.inventory.addItem(stack).values.forEach { target.world.dropItemNaturally(target.location, it) }
                }
                sender.sendMessage(Messages.gaveCustomItem(amount, id, target.name))
            }, null)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun toggleDebugMobDeaths(): Boolean {
        while (true) {
            val current = debugMobDeaths.get()
            val next = !current
            if (debugMobDeaths.compareAndSet(current, next)) {
                return next
            }
        }
    }

    /** One server recipe; a smelting definition yields one per machine. [fingerprint] detects real changes. */
    private class LoadedRecipe(val definition: RecipeDefinition, val recipe: Recipe, val fingerprint: Any) {
        val key: NamespacedKey = (recipe as Keyed).key
    }

    private class LoadedContent(
        val mobRules: List<MobRule>,
        val recipes: List<LoadedRecipe>,
        val definitionCount: Int,
        val items: LoadedItems,
    )

    private class RecipeSwap(val registered: Set<NamespacedKey>, val added: Set<NamespacedKey>)

    private sealed interface LoadResult {
        class Loaded(val content: LoadedContent) : LoadResult
        data class Failure(val line: Int?, val detail: String, val source: String = "config.yml") : LoadResult
    }

    /** Startup load: synchronous in onEnable so recipes exist before any player can join. */
    private fun loadAndApplyOnStartup() {
        val result = loadContent()
        if (result is LoadResult.Loaded) {
            applyContent(result.content)
            customItems.checkExternalPack { } // outcome is logged; runs on the AsyncScheduler
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
                        sender.sendMessage(Messages.configError(result.line, result.detail, result.source))
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
                    sender.sendMessage(Messages.reloaded(content.mobRules.size, content.definitionCount, content.items.count))
                    customItems.checkExternalPack(sender::sendMessage)
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
            // A bad resource-pack setting must never stop items, recipes and drops from loading.
            val settings = try {
                ResourcePackSettings.from(newConfig.getConfigurationSection("resource-pack"))
            } catch (exception: IllegalArgumentException) {
                logger.severe("config.yml resource-pack: ${exception.message}. The pack will not be sent until this is fixed; items, recipes and drops still load.")
                ResourcePackSettings(mode = dev.femitems.plugin.items.PackMode.OFF)
            }
            val items = customItems.load(settings)
            val parsed = ConfigParser.parse(
                newConfig,
                customItems = items.definitions.mapValues { it.value.material },
                customStackSize = { id -> items.templates[id]?.maxStackSize ?: 64 },
            )
            // Build every Bukkit recipe before touching live state, so a bad recipe fails the whole
            // reload up front instead of leaving the server half-reloaded after reporting success.
            val recipes = parsed.recipes.flatMap { definition -> toBukkitRecipes(definition, items) }
            LoadResult.Loaded(LoadedContent(parsed.mobRules, recipes, parsed.recipes.size, items))
        } catch (exception: ContentPackException) {
            val line = ConfigParser.findErrorLine(exception, exception.file).takeIf { it > 0 }
            val detail = ConfigParser.conciseError(exception)
            val source = exception.file.toPath().let { dataFolder.toPath().relativize(it).toString().replace('\\', '/') }
            logger.warning("$source${if (line != null) " line $line" else ""}: $detail")
            LoadResult.Failure(line, detail, source)
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
        customItems.apply(content.items)
        mobRules = content.mobRules
        val swap = replaceRegisteredRecipes(content.recipes)
        discoverRecipesForOnlinePlayers(swap.added)
        logger.info("Loaded ${content.mobRules.size} mob rules and ${content.definitionCount} recipes.")
        if (content.items.count > 0) {
            logger.info("Loaded ${content.items.count} custom items from ${content.items.packs.size} content pack(s).")
        }
    }

    private fun toBukkitRecipes(definition: RecipeDefinition, items: LoadedItems): List<LoadedRecipe> {
        val baseKey = definition.id.lowercase(Locale.ROOT)
        val result = createItemStack(definition.result, items)
        // Rebuild a recipe when its definition or any custom item it uses (ingredient or result) changes.
        val referenced = when (definition) {
            is ShapedRecipeDefinition -> definition.ingredients.values
            is ShapelessRecipeDefinition -> definition.ingredients
            is CookingRecipeDefinition -> listOf(definition.ingredient)
        }.filterIsInstance<Ingredient.Custom>().map(Ingredient.Custom::id) + listOfNotNull(definition.result.customItem)
        val fingerprint = listOf(definition) + referenced.sorted().map { items.definitions[it] }
        fun choice(ingredient: Ingredient): RecipeChoice = when (ingredient) {
            is Ingredient.Vanilla -> RecipeChoice.MaterialChoice(ingredient.material)
            is Ingredient.Custom -> RecipeChoice.ExactChoice(items.templates.getValue(ingredient.id))
        }
        return when (definition) {
            is ShapedRecipeDefinition -> listOf(
                ShapedRecipe(NamespacedKey(this, baseKey), result).apply {
                    shape(*definition.shape.toTypedArray())
                    for ((symbol, ingredient) in definition.ingredients) setIngredient(symbol, choice(ingredient))
                },
            )

            is ShapelessRecipeDefinition -> listOf(
                ShapelessRecipe(NamespacedKey(this, baseKey), result).apply {
                    for (ingredient in definition.ingredients) addIngredient(choice(ingredient))
                },
            )

            is CookingRecipeDefinition -> definition.machines.map { machine ->
                val key = NamespacedKey(this, baseKey + machine.keySuffix)
                val input = choice(definition.ingredient)
                when (machine) {
                    CookingMachine.FURNACE -> FurnaceRecipe(key, result, input, definition.experience, definition.cookTime)
                    CookingMachine.BLAST_FURNACE -> BlastingRecipe(key, result, input, definition.experience, definition.cookTime)
                    CookingMachine.SMOKER -> SmokingRecipe(key, result, input, definition.experience, definition.cookTime)
                    CookingMachine.CAMPFIRE -> CampfireRecipe(key, result, input, definition.experience, definition.cookTime)
                }
            }
        }.map { LoadedRecipe(definition, it, fingerprint) }
    }

    private fun createItemStack(definition: ItemDefinition, items: LoadedItems = customItems.current): ItemStack {
        definition.customItem?.let { id ->
            return items.templates.getValue(id).clone().apply { amount = definition.amount }
        }
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
        val toAdd = wanted.values.filter { registeredDefinitions[it.key] != it.fingerprint }

        val toRemove = LinkedHashSet<NamespacedKey>()
        for ((key, definition) in registeredDefinitions) {
            if (wanted[key]?.fingerprint != definition) {
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
                    registeredDefinitions[entry.key] = entry.fingerprint
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
            val template = if (drop.customItem != null) {
                customItems.create(drop.customItem) ?: continue
            } else if (drop.randomEnchantment) {
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
