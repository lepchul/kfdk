package ru.factor.fpv;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

public class FpvPlugin extends JavaPlugin {

    public static NamespacedKey KEY_ITEM;
    public static NamespacedKey KEY_ENTITY;
    public static NamespacedKey KEY_OWNER;

    private FpvManager manager;
    private Sessions sessions;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        KEY_ITEM   = new NamespacedKey(this, "fpv_drone");
        KEY_ENTITY = new NamespacedKey(this, "fpv_entity");
        KEY_OWNER  = new NamespacedKey(this, "fpv_owner");

        sessions = new Sessions(this);
        manager  = new FpvManager(this, sessions);

        getServer().getPluginManager().registerEvents(new FpvListener(this, manager), this);
        FpvCommand cmd = new FpvCommand(this, manager);
        if (getCommand("fpv") != null) {
            getCommand("fpv").setExecutor(cmd);
            getCommand("fpv").setTabCompleter(cmd);
        }

        registerRecipe();
        manager.start();

        // Если сервер падал, пока кто-то летел — вернём его в нормальное состояние
        sessions.restoreAllOnline();

        getLogger().info("FPV-дроны запущены. Скорость: "
                + getConfig().getDouble("flight.speed-blocks-per-second", 10.0) + " бл/с");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
        Bukkit.removeRecipe(KEY_ITEM);
    }

    public FpvManager manager()  { return manager; }
    public Sessions sessions()   { return sessions; }

    // ────────────────────────────────────────────────────── рецепт

    public void registerRecipe() {
        Bukkit.removeRecipe(KEY_ITEM);
        if (!getConfig().getBoolean("recipe.enabled", true)) return;

        String[] rows = {
                getConfig().getString("recipe.row1", ""),
                getConfig().getString("recipe.row2", ""),
                getConfig().getString("recipe.row3", "")
        };

        Map<Material, Character> letters = new HashMap<>();
        Map<Character, Material> back = new HashMap<>();
        char next = 'a';
        String[] shape = new String[3];

        for (int r = 0; r < 3; r++) {
            String[] cells = rows[r].split(",");
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < 3; c++) {
                String raw = c < cells.length ? cells[c].trim().toUpperCase() : "AIR";
                if (raw.isEmpty()) raw = "AIR";
                Material m = Material.matchMaterial(raw);
                if (m == null) {
                    getLogger().warning("Неизвестный предмет в рецепте: " + raw);
                    m = Material.AIR;
                }
                if (m == Material.AIR) {
                    line.append(' ');
                } else {
                    Character ch = letters.get(m);
                    if (ch == null) {
                        ch = next++;
                        letters.put(m, ch);
                        back.put(ch, m);
                    }
                    line.append(ch);
                }
            }
            shape[r] = line.toString();
        }

        if (back.isEmpty()) {
            getLogger().warning("Пустой рецепт — крафт отключён.");
            return;
        }

        ShapedRecipe recipe = new ShapedRecipe(KEY_ITEM, FpvItem.create(this, 1));
        recipe.shape(shape);
        back.forEach(recipe::setIngredient);
        Bukkit.addRecipe(recipe);
        getLogger().info("Рецепт зарегистрирован.");
    }

    public String msg(String path, String... kv) {
        String s = getConfig().getString("messages." + path, path);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s.replace('&', '\u00A7');
    }
}
