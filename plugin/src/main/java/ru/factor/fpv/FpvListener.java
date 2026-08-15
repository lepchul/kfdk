package ru.factor.fpv;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class FpvListener implements Listener {

    private final FpvPlugin plugin;
    private final FpvManager manager;

    public FpvListener(FpvPlugin plugin, FpvManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ─────────────────────── проверка: в центре именно дрон-камикадзе

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.getRecipe() == null) return;
        ItemStack result = e.getInventory().getResult();
        if (!FpvItem.isDrone(result)) return;
        if (!plugin.getConfig().getBoolean("recipe.require-kamikaze-tag", true)) return;

        String raw = plugin.getConfig()
                .getString("recipe.kamikaze-tag", "kamikazedrone:kamikaze_drone");
        String[] parts = raw.split(":", 2);
        if (parts.length != 2) return;
        NamespacedKey key = new NamespacedKey(parts[0], parts[1]);

        // Центр сетки крафта — индекс 5 (1..9 в матрице 3x3)
        ItemStack center = e.getInventory().getMatrix()[4];
        boolean ok = center != null && center.getItemMeta() != null
                && center.getItemMeta().getPersistentDataContainer()
                    .has(key, PersistentDataType.BYTE);
        if (!ok) e.getInventory().setResult(null);
    }

    // ─────────────────────────────────────────────── установка

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;

        ItemStack item = e.getItem();
        if (!FpvItem.isDrone(item)) return;
        e.setCancelled(true);

        Player p = e.getPlayer();
        if (!p.hasPermission("fpv.use")) {
            p.sendMessage(plugin.msg("no-permission"));
            return;
        }
        int limit = plugin.getConfig().getInt("drone.max-per-player", 2);
        if (manager.countOf(p.getUniqueId()) >= limit) {
            p.sendMessage(plugin.msg("too-many", "n", String.valueOf(limit)));
            return;
        }

        Block block = e.getClickedBlock();
        if (block == null) return;
        Location at = block.getRelative(e.getBlockFace()).getLocation().add(0.5, 0.2, 0.5);
        if (at.getWorld() == null) return;
        if (plugin.getConfig().getStringList("protection.worlds-blacklist")
                .contains(at.getWorld().getName())) {
            p.sendMessage(plugin.msg("world-blocked"));
            return;
        }

        manager.spawn(p, at);
        if (p.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
        at.getWorld().playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 2.0f);
        p.sendMessage(plugin.msg("placed"));
    }

    // ─────────────────────────────────────────────────── меню

    @EventHandler(ignoreCancelled = true)
    public void onClickDrone(PlayerInteractAtEntityEvent e) {
        if (!manager.isDrone(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        FpvDrone d = manager.byEntity(e.getRightClicked());
        if (d == null) return;

        if (d.state == FpvDrone.State.FLYING) {
            p.sendMessage(plugin.msg("already-flying"));
            return;
        }
        if (!p.hasPermission("fpv.admin") && !d.owner.equals(p.getUniqueId())) {
            p.sendMessage(plugin.msg("not-yours"));
            return;
        }
        new FpvMenu(plugin, d).open(p);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof FpvMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        FpvDrone d = menu.drone();
        if (d == null || !d.alive()) {
            p.closeInventory();
            p.sendMessage(plugin.msg("drone-gone"));
            return;
        }

        switch (e.getRawSlot()) {
            case FpvMenu.SLOT_FLY -> {
                p.closeInventory();
                if (manager.flyingOf(p.getUniqueId()) != null) {
                    p.sendMessage(plugin.msg("already-piloting"));
                    return;
                }
                d.takeOff(p);
            }
            case FpvMenu.SLOT_BOOM -> {
                p.closeInventory();
                d.detonate(null);
            }
            case FpvMenu.SLOT_PICKUP -> {
                p.closeInventory();
                d.entity.remove();
                d.state = FpvDrone.State.DEAD;
                manager.remove(d.id);
                p.getInventory().addItem(FpvItem.create(plugin, 1));
                p.sendMessage(plugin.msg("picked-up"));
            }
            default -> { }
        }
    }

    // ─────────────────────────────── подрыв в полёте: приседание

    private final java.util.Map<java.util.UUID, Long> lastSneak = new java.util.HashMap<>();

    /** Двойное приседание — подрыв. Одиночное в наблюдателе просто снижает высоту. */
    @EventHandler
    public void onSneakDetonate(org.bukkit.event.player.PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        FpvDrone d = manager.flyingOf(e.getPlayer().getUniqueId());
        if (d == null) return;

        long now = System.currentTimeMillis();
        Long prev = lastSneak.put(e.getPlayer().getUniqueId(), now);
        long window = plugin.getConfig().getLong("controls.double-sneak-ms", 450);
        if (prev != null && now - prev <= window) {
            lastSneak.remove(e.getPlayer().getUniqueId());
            d.detonate(null);
        }
    }

    // ──────────────────────────────────────────── урон по дрону

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!manager.isDrone(e.getEntity())) return;
        FpvDrone d = manager.byEntity(e.getEntity());
        if (d == null) return;

        Entity damager = e.getDamager();
        boolean byShot = damager instanceof AbstractArrow
                || (damager instanceof Projectile pr && pr.getShooter() instanceof Player);

        if (byShot && plugin.getConfig().getBoolean("drone.arrow-instant-kill", true)) {
            e.setCancelled(true);
            if (plugin.getConfig().getBoolean("drone.explode-when-shot", false)) d.detonate(null);
            else d.fizzle();
            if (damager instanceof Projectile pr && pr.getShooter() instanceof Player shooter) {
                shooter.sendMessage(plugin.msg("you-shot-down"));
            }
            return;
        }

        if (d.entity.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            d.fizzle();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFire(EntityDamageEvent e) {
        if (!manager.isDrone(e.getEntity())) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || e.getCause() == EntityDamageEvent.DamageCause.FIRE) e.setCancelled(true);
    }

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        if (manager.isDrone(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        if (manager.isDrone(e.getEntity())) e.setCancelled(true);
    }

    // ──────────────────────────── страховка от зависания пилота

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        FpvDrone d = manager.flyingOf(e.getPlayer().getUniqueId());
        if (d != null) {
            d.returnPilot();
            manager.remove(d.id);
            if (d.entity.isValid()) d.entity.remove();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (plugin.sessions().has(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (plugin.sessions().restore(p)) p.sendMessage(plugin.msg("recovered"));
            }, 20L);
        }
    }
}
