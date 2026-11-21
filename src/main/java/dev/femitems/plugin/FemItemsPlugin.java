package dev.femitems.plugin;

import com.mojang.brigadier.Command;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
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
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FemItemsPlugin extends JavaPlugin implements Listener {
    private final Set<NamespacedKey> recipeKeys = new HashSet<>();
    private List<MobRule> mobRules = List.of();
    private boolean debugMobDeaths;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommands();
        String error = reloadContent();
        if (error != null) getLogger().warning(error);
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
                    String error = reloadContent();
                    if (error == null) {
                        context.getSource().getSender().sendMessage("[FemItems] Config reloaded.");
                    } else if (error.equals("?")) {
                        context.getSource().getSender().sendMessage("[FemItems] Config error; see console.");
                    } else {
                        context.getSource().getSender().sendMessage("[FemItems] Config error at line " + error + ".");
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("debug").executes(context -> {
                    debugMobDeaths = !debugMobDeaths;
                    context.getSource().getSender().sendMessage("[FemItems] Mob death debug " + (debugMobDeaths ? "enabled; check console after killing a mob." : "disabled."));
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    private String reloadContent() {
        try {
            File configFile = new File(getDataFolder(), "config.yml");
            YamlConfiguration newConfig = new YamlConfiguration();
            newConfig.load(configFile);
            List<MobRule> newRules = readMobRules(newConfig);
            List<Recipe> newRecipes = readRecipes(newConfig);
            removeRegisteredRecipes();
            mobRules = newRules;
            for (Recipe recipe : newRecipes) {
                if (Bukkit.addRecipe(recipe)) {
                    recipeKeys.add(keyOf(recipe));
                }
            }
            getLogger().info("Loaded " + mobRules.size() + " mob rules and " + recipeKeys.size() + " recipes.");
            return null;
        } catch (Exception exception) {
            int line = findErrorLine(exception);
            String error = line > 0 ? "config.yml line " + line : "config.yml (line unavailable): " + conciseError(exception);
            getLogger().warning("[FemItems] " + error);
            return line > 0 ? Integer.toString(line) : "?";
        }
    }

    private NamespacedKey keyOf(Recipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) return shaped.getKey();
        if (recipe instanceof ShapelessRecipe shapeless) return shapeless.getKey();
        throw new IllegalArgumentException("Unsupported recipe implementation: " + recipe.getClass().getName());
    }

    private List<MobRule> readMobRules(YamlConfiguration config) {
        List<MobRule> rules = new ArrayList<>();
        ConfigurationSection mobs = config.getConfigurationSection("mobs");
        if (mobs == null) return rules;
        for (String id : mobs.getKeys(false)) {
            ConfigurationSection section = mobs.getConfigurationSection(id);
            if (section == null) continue;
            String entityName = section.getString("entity", "").toUpperCase(Locale.ROOT);
            EntityType type = null;
            if (!entityName.equals("ANY")) {
                try {
                    type = EntityType.valueOf(entityName);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("mobs." + id + ".entity is not a valid entity type (or ANY)");
                }
            }
            String nameContains = section.getString("name-contains", "");
            if (type == null && nameContains.isBlank()) {
                throw new IllegalArgumentException("mobs." + id + ".entity: ANY requires a non-empty name-contains filter");
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
            rules.add(new MobRule(id, type, nameContains, drops));
        }
        return rules;
    }

    private List<Recipe> readRecipes(YamlConfiguration config) {
        List<Recipe> recipes = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("recipes");
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
        if (debugMobDeaths && !(entity instanceof Player)) {
            List<String> matches = mobRules.stream()
                    .filter(rule -> (rule.type == null || entity.getType() == rule.type) && matchesName(entity, rule.nameContains))
                    .map(MobRule::id)
                    .toList();
            getLogger().info("Mob death debug: entity=" + entity.getType() + ", customName=" + entity.getCustomName() + ", matchingRules=" + matches);
        }
        for (MobRule rule : mobRules) {
            if ((rule.type != null && entity.getType() != rule.type) || !matchesName(entity, rule.nameContains)) continue;
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
        return actual != null && ChatColor.stripColor(actual).toLowerCase(Locale.ROOT).contains(ChatColor.stripColor(expected).toLowerCase(Locale.ROOT));
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static double decimal(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private int findErrorLine(Exception exception) {
        Pattern linePattern = Pattern.compile("(?i)\\bline\\s*:?\\s*(\\d+)");
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            Matcher matcher = linePattern.matcher(String.valueOf(cause.getMessage()));
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
        }
        try {
            String contents = Files.readString(new File(getDataFolder(), "config.yml").toPath());
            int line = findSemanticErrorLine(exception.getMessage(), contents);
            if (line > 0) return line;
        } catch (IOException ignored) {
            // Keep the parser's error even if the source file cannot be read again.
        }
        return -1;
    }

    private int findSemanticErrorLine(String message, String contents) {
        if (message == null) return -1;
        String[] lines = contents.split("\\R");
        Matcher materialMatcher = Pattern.compile("Unknown (?:drop material|ingredient).*?:\\s*([A-Za-z0-9_:-]+)").matcher(message);
        if (materialMatcher.find()) {
            String needle = "material: " + materialMatcher.group(1);
            for (int i = 0; i < lines.length; i++) if (lines[i].contains(needle)) return i + 1;
        }

        Matcher pathMatcher = Pattern.compile("(?:mobs|recipes)\\.([A-Za-z0-9_-]+)(?:\\.([A-Za-z0-9_-]+))?").matcher(message);
        if (!pathMatcher.find()) return -1;
        String section = pathMatcher.group(1);
        String field = pathMatcher.group(2);
        Pattern sectionLine = Pattern.compile("^\\s*" + Pattern.quote(section) + ":\\s*(?:#.*)?$");
        int sectionIndent = -1;
        for (int i = 0; i < lines.length; i++) {
            if (sectionLine.matcher(lines[i]).matches()) {
                sectionIndent = leadingSpaces(lines[i]);
                if (field == null) return i + 1;
                for (int j = i + 1; j < lines.length; j++) {
                    if (!lines[j].isBlank() && leadingSpaces(lines[j]) <= sectionIndent) break;
                    if (lines[j].trim().startsWith(field + ":")) return j + 1;
                }
                return i + 1;
            }
        }
        return -1;
    }

    private int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') count++;
        return count;
    }

    private String conciseError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.replaceAll("\\s+", " ").trim();
    }

    private record MobRule(String id, EntityType type, String nameContains, List<DropRule> drops) { }
    private record DropRule(Material material, int min, int max, double chance, String name) { }
}
