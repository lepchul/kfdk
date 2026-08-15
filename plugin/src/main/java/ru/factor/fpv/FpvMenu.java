package ru.factor.fpv;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FpvMenu implements InventoryHolder {

    public static final int SLOT_FLY    = 2;
    public static final int SLOT_INFO   = 4;
    public static final int SLOT_BOOM   = 6;
    public static final int SLOT_PICKUP = 8;

    private final FpvDrone drone;
    private final Inventory inv;

    public FpvMenu(FpvPlugin plugin, FpvDrone drone) {
        this.drone = drone;
        this.inv = org.bukkit.Bukkit.createInventory(this, 9,
                color(plugin.getConfig().getString("menu.title", "&8Пульт FPV")));

        inv.setItem(SLOT_FLY, icon(Material.SPYGLASS,
                color(plugin.getConfig().getString("menu.fly-name", "&b&lЛететь от лица дрона")),
                Arrays.asList("§7Вы увидите мир глазами дрона.",
                        "§7Куда смотрите — туда и летит.",
                        "", "§8Тело останется на месте.")));

        List<String> info = new ArrayList<>();
        info.add("§7Скорость: §f" + plugin.getConfig().getDouble("flight.speed-blocks-per-second", 10.0) + " бл/с");
        info.add("§7Дальность: §f" + (int) plugin.getConfig().getDouble("flight.max-range", 500) + " блоков");
        info.add("§7Батарея: §f" + plugin.getConfig().getInt("flight.max-seconds", 90) + " сек");
        info.add("");
        info.add("§cПаутина глушит дрон.");
        inv.setItem(SLOT_INFO, icon(Material.PAPER,
                color(plugin.getConfig().getString("menu.info-name", "&e&lИнформация")), info));

        inv.setItem(SLOT_BOOM, icon(Material.TNT,
                color(plugin.getConfig().getString("menu.boom-name", "&c&lПодорвать на месте")),
                Arrays.asList("§7Детонация прямо здесь.", "§cОсторожно, вы рядом.")));

        inv.setItem(SLOT_PICKUP, icon(Material.HOPPER,
                color(plugin.getConfig().getString("menu.pickup-name", "&6&lЗабрать дрон")),
                Arrays.asList("§7Вернуть предмет в инвентарь.")));

        Material fill = Material.matchMaterial(
                plugin.getConfig().getString("menu.filler", "GRAY_STAINED_GLASS_PANE").toUpperCase());
        if (fill != null && fill != Material.AIR) {
            ItemStack filler = icon(fill, " ", null);
            for (int i = 0; i < 9; i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    private static String color(String s) {
        return s == null ? "" : s.replace('&', '\u00A7');
    }

    private ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    public FpvDrone drone() { return drone; }
    public void open(Player p) { p.openInventory(inv); }

    @Override
    public Inventory getInventory() { return inv; }
}
