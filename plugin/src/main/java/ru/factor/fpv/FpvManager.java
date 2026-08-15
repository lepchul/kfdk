package ru.factor.fpv;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class FpvManager {

    private final FpvPlugin plugin;
    private final Sessions sessions;
    private final Map<UUID, FpvDrone> drones = new HashMap<>();
    private BukkitTask task;

    public FpvManager(FpvPlugin plugin, Sessions sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (FpvDrone d : new ArrayList<>(drones.values())) {
            if (d.state == FpvDrone.State.FLYING || d.state == FpvDrone.State.REPLAY) {
                d.returnPilot();               // пилота обязательно вернуть в тело
                if (d.entity.isValid()) d.entity.remove();
            }
        }
        drones.clear();
    }

    private void tickAll() {
        Iterator<Map.Entry<UUID, FpvDrone>> it = drones.entrySet().iterator();
        while (it.hasNext()) {
            FpvDrone d = it.next().getValue();
            try {
                if (!d.tick()) it.remove();
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка тика FPV-дрона: " + e.getMessage());
                d.returnPilot();
                it.remove();
            }
        }
    }

    public FpvDrone spawn(Player owner, Location at) {
        String name = plugin.getConfig()
                .getString("item.entity-name", "&b\u2708 ФПВ Дрон").replace('&', '\u00A7');

        Phantom ph = at.getWorld().spawn(at, Phantom.class, p -> {
            p.setAI(false);
            p.setGravity(false);
            p.setSilent(true);
            p.setCollidable(false);
            p.setPersistent(true);
            p.setSize(0);
            p.setCustomName(name);
            p.setCustomNameVisible(plugin.getConfig().getBoolean("item.show-name-always", true));
            p.setRemoveWhenFarAway(false);
            double hp = plugin.getConfig().getDouble("drone.health", 4.0);
            var attr = p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (attr != null) attr.setBaseValue(hp);
            p.setHealth(Math.min(hp, attr == null ? hp : attr.getValue()));
            p.getPersistentDataContainer().set(FpvPlugin.KEY_ENTITY, PersistentDataType.BYTE, (byte) 1);
            p.getPersistentDataContainer().set(FpvPlugin.KEY_OWNER, PersistentDataType.STRING,
                    owner.getUniqueId().toString());
        });

        FpvDrone d = new FpvDrone(plugin, ph, owner.getUniqueId());
        drones.put(d.id, d);
        return d;
    }

    public FpvDrone byEntity(Entity e) {
        if (!(e instanceof Phantom ph)) return null;
        FpvDrone d = drones.get(e.getUniqueId());
        if (d != null) return d;
        if (!isDrone(e)) return null;

        String raw = ph.getPersistentDataContainer()
                .get(FpvPlugin.KEY_OWNER, PersistentDataType.STRING);
        UUID owner;
        try {
            owner = raw == null ? new UUID(0, 0) : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            owner = new UUID(0, 0);
        }
        FpvDrone restored = new FpvDrone(plugin, ph, owner);
        drones.put(restored.id, restored);
        return restored;
    }

    public boolean isDrone(Entity e) {
        return e instanceof Phantom p && p.getPersistentDataContainer()
                .has(FpvPlugin.KEY_ENTITY, PersistentDataType.BYTE);
    }

    /** Дрон, которым сейчас управляет игрок. */
    public FpvDrone flyingOf(UUID owner) {
        for (FpvDrone d : drones.values()) {
            if (owner.equals(d.owner)
                    && (d.state == FpvDrone.State.FLYING || d.state == FpvDrone.State.REPLAY)) return d;
        }
        return null;
    }

    public int countOf(UUID owner) {
        int n = 0;
        for (FpvDrone d : drones.values()) if (owner.equals(d.owner) && d.alive()) n++;
        return n;
    }

    public void remove(UUID id) { drones.remove(id); }
}
