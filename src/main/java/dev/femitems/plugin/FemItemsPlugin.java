package dev.femitems.plugin;

import com.mojang.brigadier.Command;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class FemItemsPlugin extends JavaPlugin implements Listener {
    private final Set<NamespacedKey> recipeKeys = new HashSet<>();
    private List<MobRule> mobRules = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommands();
        reloadContent();
    }

    @Override
    public void onDisable() {
        removeRegisteredRecipes();
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(reloadCommand("femitems"));
            event.registrar().register(reloadCommand("femi"));
        });
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> reloadCommand(String label) {
        return Commands.literal(label)
                .requires(source -> source.getSender().hasPermission("femitems.reload"))
                .then(Commands.literal("reload").executes(context -> {
                    reloadConfig();
                    reloadContent();
                    context.getSource().getSender().sendMessage("FemItems configuration reloaded.");
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    private void reloadContent() {
        try {
            List<MobRule> newRules = readMobRules();
            List<Recipe> newRecipes = readRecipes();
            removeRegisteredRecipes();
            mobRules = newRules;
            for (Recipe recipe : newRecipes) {
                if (Bukkit.addRecipe(recipe)) {
                    recipeKeys.add(keyOf(recipe));
                }
            }
            getLogger().info("Loaded " + mobRules.size() + " mob rules and " + recipeKeys.size() + " recipes.");
        } catch (RuntimeException exception) {
            getLogger().severe("Could not load configuration: " + exception.getMessage());
            getLogger().log(java.util.logging.Level.SEVERE, "Configuration load failure", exception);
        }
    }

    private NamespacedKey keyOf(Recipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) return shaped.getKey();
        if (recipe instanceof ShapelessRecipe shapeless) return shapeless.getKey();
        throw new IllegalArgumentException("Unsupported recipe implementation: " + recipe.getClass().getName());
    }

    private List<MobRule> readMobRules() {
        List<MobRule> rules = new ArrayList<>();
        ConfigurationSection mobs = getConfig().getConfigurationSection("mobs");
        if (mobs == null) return rules;
        for (String id : mobs.getKeys(false)) {
            ConfigurationSection section = mobs.getConfigurationSection(id);
            if (section == null) continue;
            EntityType type;
            try {
                type = EntityType.valueOf(section.getString("entity", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("mobs." + id + ".entity is not a valid entity type");
            }
            List<DropRule> drops = new ArrayList<>();
            for (Map<?, ?> raw : section.getMapList("drops")) {
                String materialName = String.valueOf(raw.get("material"));
                Material material = Material.matchMaterial(materialName);
                if (material == null || material.isAir()) throw new IllegalArgumentException("Unknown drop material in mobs." + id + ": " + materialName);
                int min = number(raw.get("min"), 1);
                int max = number(raw.get("max"), min);
                double chance = decimal(raw.get("chance"), 1.0);
                if (min < 1 || max < min || chance < 0 || chance > 1) throw new IllegalArgumentException("Invalid count/chance in mobs." + id);
                String customName = raw.containsKey("name") ? String.valueOf(raw.get("name")) : null;
                drops.add(new DropRule(material, min, max, chance, customName));
            }
            rules.add(new MobRule(type, section.getString("name-contains", ""), drops));
        }
        return rules;
    }

    private List<Recipe> readRecipes() {
        List<Recipe> recipes = new ArrayList<>();
        ConfigurationSection section = getConfig().getConfigurationSection("recipes");
        if (section == null) return recipes;
        for (String id : section.getKeys(false)) {
            ConfigurationSection recipeSection = section.getConfigurationSection(id);
            if (recipeSection == null) continue;
            NamespacedKey key = new NamespacedKey(this, id.toLowerCase(Locale.ROOT));
            ItemStack result = readItem(recipeSection.getConfigurationSection("result"), "recipes." + id + ".result");
            String type = recipeSection.getString("type", "shaped").toLowerCase(Locale.ROOT);
            if (type.equals("shaped")) {
                ShapedRecipe recipe = new ShapedRecipe(key, result);
                List<String> shape = recipeSection.getStringList("shape");
                if (shape.isEmpty() || shape.size() > 3) throw new IllegalArgumentException("recipes." + id + ".shape must have 1 to 3 rows");
                recipe.shape(shape.toArray(String[]::new));
                ConfigurationSection ingredients = recipeSection.getConfigurationSection("ingredients");
                if (ingredients == null) throw new IllegalArgumentException("recipes." + id + ".ingredients is required");
                for (String symbol : ingredients.getKeys(false)) {
                    String materialName = ingredients.getString(symbol, "");
                    Material material = Material.matchMaterial(materialName);
                    if (material == null || material.isAir()) throw new IllegalArgumentException("Unknown ingredient in recipes." + id + ": " + materialName);
                    if (symbol.length() != 1) throw new IllegalArgumentException("Ingredient keys must be one character in recipes." + id);
                    recipe.setIngredient(symbol.charAt(0), material);
                }
                recipes.add(recipe);
            } else if (type.equals("shapeless")) {
                ShapelessRecipe recipe = new ShapelessRecipe(key, result);
                for (String materialName : recipeSection.getStringList("ingredients")) {
                    Material material = Material.matchMaterial(materialName);
                    if (material == null || material.isAir()) throw new IllegalArgumentException("Unknown ingredient in recipes." + id + ": " + materialName);
                    recipe.addIngredient(material);
                }
                recipes.add(recipe);
            } else {
                throw new IllegalArgumentException("recipes." + id + ".type must be shaped or shapeless");
            }
        }
        return recipes;
    }

    private ItemStack readItem(ConfigurationSection section, String path) {
        if (section == null) throw new IllegalArgumentException(path + " is required");
        String materialName = section.getString("material", "");
        Material material = Material.matchMaterial(materialName);
        int amount = section.getInt("amount", 1);
        if (material == null || material.isAir() || amount < 1 || amount > material.getMaxStackSize()) {
            throw new IllegalArgumentException(path + " has an invalid material or amount");
        }
        ItemStack item = new ItemStack(material, amount);
        String name = section.getString("name");
        if (name != null) {
            var meta = item.getItemMeta();
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void removeRegisteredRecipes() {
        for (NamespacedKey key : recipeKeys) Bukkit.removeRecipe(key);
        recipeKeys.clear();
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        for (MobRule rule : mobRules) {
            if (entity.getType() != rule.type || !matchesName(entity, rule.nameContains)) continue;
            for (DropRule drop : rule.drops) {
                if (ThreadLocalRandom.current().nextDouble() >= drop.chance) continue;
                int amount = ThreadLocalRandom.current().nextInt(drop.min, drop.max + 1);
                ItemStack item = new ItemStack(drop.material, amount);
                if (drop.name != null) {
                    var meta = item.getItemMeta();
                    meta.setDisplayName(drop.name);
                    item.setItemMeta(meta);
                }
                entity.getWorld().dropItemNaturally(entity.getLocation(), item);
            }
        }
    }

    private boolean matchesName(Entity entity, String expected) {
        if (expected == null || expected.isBlank()) return true;
        String actual = entity.getCustomName();
        return actual != null && actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static double decimal(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private record MobRule(EntityType type, String nameContains, List<DropRule> drops) { }
    private record DropRule(Material material, int min, int max, double chance, String name) { }
}
