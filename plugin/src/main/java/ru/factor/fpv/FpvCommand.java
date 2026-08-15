package ru.factor.fpv;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FpvCommand implements CommandExecutor, TabCompleter {

    private final FpvPlugin plugin;
    private final FpvManager manager;

    public FpvCommand(FpvPlugin plugin, FpvManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("\u00A7e/fpv give [ник] [кол-во] \u00A77— выдать дрон");
            s.sendMessage("\u00A7e/fpv land \u00A77— вернуться в тело, дрон падает");
            s.sendMessage("\u00A7e/fpv boom \u00A77— подорвать свой дрон");
            s.sendMessage("\u00A7e/fpv rescue <ник> \u00A77— вытащить застрявшего пилота");
            s.sendMessage("\u00A7e/fpv reload \u00A77— перечитать конфиг");
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "give" -> {
                if (!s.hasPermission("fpv.admin")) { s.sendMessage("\u00A7cНет прав."); return true; }
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player p ? p : null);
                if (t == null) { s.sendMessage("\u00A7cИгрок не найден."); return true; }
                int n = 1;
                if (a.length > 2) {
                    try { n = Math.max(1, Math.min(64, Integer.parseInt(a[2]))); }
                    catch (NumberFormatException ignored) { }
                }
                t.getInventory().addItem(FpvItem.create(plugin, n));
                s.sendMessage("\u00A7aВыдано " + n + " шт.");
            }
            case "land" -> {
                if (!(s instanceof Player p)) { s.sendMessage("\u00A7cТолько в игре."); return true; }
                FpvDrone d = manager.flyingOf(p.getUniqueId());
                if (d == null) { p.sendMessage("\u00A77Вы не управляете дроном."); return true; }
                d.fizzle();
            }
            case "boom" -> {
                if (!(s instanceof Player p)) { s.sendMessage("\u00A7cТолько в игре."); return true; }
                FpvDrone d = manager.flyingOf(p.getUniqueId());
                if (d == null) { p.sendMessage("\u00A77Вы не управляете дроном."); return true; }
                d.detonate(null);
            }
            case "rescue" -> {
                if (!s.hasPermission("fpv.admin")) { s.sendMessage("\u00A7cНет прав."); return true; }
                if (a.length < 2) { s.sendMessage("\u00A7cУкажите ник."); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage("\u00A7cИгрок не в сети."); return true; }
                FpvDrone d = manager.flyingOf(t.getUniqueId());
                if (d != null) { d.fizzle(); }
                else if (!plugin.sessions().restore(t)) {
                    t.setGameMode(org.bukkit.GameMode.SURVIVAL);
                }
                s.sendMessage("\u00A7aПилот возвращён: " + t.getName());
                t.sendMessage(plugin.msg("recovered"));
            }
            case "reload" -> {
                if (!s.hasPermission("fpv.admin")) { s.sendMessage("\u00A7cНет прав."); return true; }
                plugin.reloadConfig();
                plugin.registerRecipe();
                s.sendMessage("\u00A7aКонфиг и рецепт перечитаны.");
            }
            default -> s.sendMessage("\u00A7cНеизвестная команда.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 1) return Arrays.asList("give", "land", "boom", "rescue", "reload");
        if (a.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        return List.of();
    }
}
