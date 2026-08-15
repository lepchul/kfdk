package ru.factor.fpv;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Пока игрок «сидит в очках», он в режиме наблюдателя.
 * Если сервер упадёт в этот момент, игрок останется наблюдателем навсегда,
 * поэтому состояние пишем на диск и восстанавливаем при следующем входе.
 */
public class Sessions {

    private final FpvPlugin plugin;
    private final File file;
    private final YamlConfiguration cfg;

    public Sessions(FpvPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pilots.yml");
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    public void save(Player p, Location home, GameMode mode, float flySpeed) {
        String k = p.getUniqueId().toString();
        cfg.set(k + ".mode", mode.name());
        cfg.set(k + ".fly-speed", flySpeed);
        cfg.set(k + ".world", home.getWorld() == null ? "" : home.getWorld().getName());
        cfg.set(k + ".x", home.getX());
        cfg.set(k + ".y", home.getY());
        cfg.set(k + ".z", home.getZ());
        cfg.set(k + ".yaw", home.getYaw());
        cfg.set(k + ".pitch", home.getPitch());
        flush();
    }

    public void clear(UUID uuid) {
        cfg.set(uuid.toString(), null);
        flush();
    }

    public boolean has(UUID uuid) {
        return cfg.contains(uuid.toString());
    }

    /** Вернуть игрока в нормальный режим и на точку запуска. */
    public boolean restore(Player p) {
        String k = p.getUniqueId().toString();
        if (!cfg.contains(k)) return false;

        GameMode mode;
        try {
            mode = GameMode.valueOf(cfg.getString(k + ".mode", "SURVIVAL"));
        } catch (IllegalArgumentException e) {
            mode = GameMode.SURVIVAL;
        }
        if (mode == GameMode.SPECTATOR) mode = GameMode.SURVIVAL;
        double fly = cfg.getDouble(k + ".fly-speed", 0.1);
        p.setFlySpeed(fly <= 0 ? 0.1f : (float) fly);

        try {
            p.setSpectatorTarget(null);
        } catch (Throwable ignored) {
        }
        p.setGameMode(mode);

        var world = Bukkit.getWorld(cfg.getString(k + ".world", ""));
        if (world != null) {
            p.teleport(new Location(world,
                    cfg.getDouble(k + ".x"), cfg.getDouble(k + ".y"), cfg.getDouble(k + ".z"),
                    (float) cfg.getDouble(k + ".yaw"), (float) cfg.getDouble(k + ".pitch")));
        }
        clear(p.getUniqueId());
        return true;
    }

    public void restoreAllOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (has(p.getUniqueId())) {
                restore(p);
                p.sendMessage(plugin.msg("recovered"));
            }
        }
    }

    private void flush() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Не удалось сохранить pilots.yml: " + e.getMessage());
        }
    }
}
