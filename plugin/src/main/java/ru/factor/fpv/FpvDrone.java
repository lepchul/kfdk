package ru.factor.fpv;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * ФПВ-дрон.
 * Пилот в режиме наблюдателя летит сам на WASD, а дрон следует за его камерой.
 * Так управление получается родное, без телепортов игрока и без дёрганья картинки.
 */
public class FpvDrone {

    public enum State { IDLE, FLYING, REPLAY, DEAD }

    public final UUID id;
    public final Phantom entity;
    public final UUID owner;

    public State state = State.IDLE;
    public Location home;
    public GameMode homeMode = GameMode.SURVIVAL;
    public float homeFlySpeed = 0.1f;
    public int ticksFlown = 0;
    public boolean webStuck = false;

    /** Пройденный маршрут — для нити и для повтора. */
    public final Deque<Location> path = new ArrayDeque<>();

    private final FpvPlugin plugin;
    private int soundTimer = 0;
    private int fiberTimer = 0;
    private int stuckTicks = 0;
    private Location prevPos;

    // повтор после подрыва
    private int replayTick = 0;
    private int replayLength = 0;
    private Location replayFrom;
    private Location replayTo;
    private Location impact;

    public FpvDrone(FpvPlugin plugin, Phantom entity, UUID owner) {
        this.plugin = plugin;
        this.entity = entity;
        this.owner = owner;
        this.id = entity.getUniqueId();
    }

    public boolean alive() {
        return state != State.DEAD && entity.isValid() && !entity.isDead();
    }

    public Player pilot() {
        return org.bukkit.Bukkit.getPlayer(owner);
    }

    // ────────────────────────────────────────────────────── взлёт

    public void takeOff(Player p) {
        home = p.getLocation().clone();
        homeMode = p.getGameMode();
        homeFlySpeed = p.getFlySpeed();
        state = State.FLYING;
        ticksFlown = 0;
        path.clear();
        path.add(entity.getLocation().clone());

        plugin.sessions().save(p, home, homeMode, homeFlySpeed);

        p.setGameMode(GameMode.SPECTATOR);
        Location start = entity.getLocation().clone();
        start.setYaw(p.getLocation().getYaw());
        start.setPitch(p.getLocation().getPitch());
        p.teleport(start);
        p.setFlySpeed((float) plugin.getConfig().getDouble("flight.fly-speed", 0.08));
        prevPos = start.clone();

        entity.getWorld().playSound(entity.getLocation(),
                Cfg.sound(plugin, "effects.launch-sound", Sound.ENTITY_BEE_LOOP_AGGRESSIVE), 1.2f, 1.4f);
        p.sendMessage(plugin.msg("takeoff"));
    }

    // ──────────────────────────────────────────────────────── тик

    public boolean tick() {
        if (state == State.DEAD) return false;
        if (state == State.REPLAY) return replayTick();
        if (!alive()) return false;
        if (entity.getFireTicks() > 0) entity.setFireTicks(0);
        if (state != State.FLYING) return true;

        Player p = pilot();
        if (p == null || !p.isOnline()) {
            fizzle();
            return state == State.REPLAY;
        }

        int maxTicks = plugin.getConfig().getInt("flight.max-seconds", 90) * 20;
        double maxRange = plugin.getConfig().getDouble("flight.max-range", 500);
        ticksFlown++;

        if (ticksFlown > maxTicks) {
            detonate(plugin.msg("lost-battery"));
            return state == State.REPLAY;
        }

        Location pos = p.getLocation();
        if (home != null && home.getWorld() != null && home.getWorld().equals(pos.getWorld())
                && pos.distance(home) > maxRange) {
            detonate(plugin.msg("lost-range"));
            return state == State.REPLAY;
        }

        // ── ограничение скорости: в наблюдателе игрок может разогнаться колёсиком
        double maxStep = plugin.getConfig().getDouble("flight.speed-blocks-per-second", 10.0) / 20.0;
        Material here = pos.getBlock().getType();

        double webMul = plugin.getConfig().getDouble("web.speed-multiplier", 0.15);
        float baseFly = (float) plugin.getConfig().getDouble("flight.fly-speed", 0.08);

        if (here == Material.COBWEB) {
            webStuck = true;
            maxStep *= webMul;
            float want = (float) Math.max(0.005, baseFly * webMul);
            if (Math.abs(p.getFlySpeed() - want) > 0.001f) p.setFlySpeed(want);
            stuckTicks++;
            int limit = (int) (plugin.getConfig().getDouble("web.stuck-seconds", 3.0) * 20);
            if (stuckTicks % 8 == 0) {
                pos.getWorld().spawnParticle(Particle.BLOCK_CRACK, pos, 6, 0.2, 0.2, 0.2,
                        Material.COBWEB.createBlockData());
            }
            if (plugin.getConfig().getBoolean("web.can-trap", true) && stuckTicks > limit) {
                p.sendMessage(plugin.msg("web-trapped"));
                fizzle();
                return state == State.REPLAY;
            }
        } else {
            if (webStuck && Math.abs(p.getFlySpeed() - baseFly) > 0.001f) p.setFlySpeed(baseFly);
            webStuck = false;
            stuckTicks = 0;
        }

        // Ограничитель скорости.
        // Осаживаем только при явном превышении: если поправлять каждый тик,
        // камера начинает дёргаться. Обычную скорость держит fly-speed.
        String mode = plugin.getConfig().getString("flight.limit-mode", "SOFT").toUpperCase();
        if (!mode.equals("OFF") && prevPos != null && prevPos.getWorld() != null
                && prevPos.getWorld().equals(pos.getWorld())) {
            double tolerance = Math.max(1.0,
                    plugin.getConfig().getDouble("flight.speed-tolerance", 1.8));
            double allowed = maxStep * tolerance;
            double moved = pos.distance(prevPos);
            if (moved > allowed) {
                Vector dir = pos.toVector().subtract(prevPos.toVector());
                if (dir.lengthSquared() > 0) {
                    Location capped = prevPos.clone().add(dir.normalize().multiply(allowed));
                    capped.setYaw(pos.getYaw());
                    capped.setPitch(pos.getPitch());
                    p.teleport(capped);
                    pos = capped;
                }
            }
        }
        prevPos = pos.clone();

        // ── столкновение
        if (pos.getBlock().getType().isSolid()) {
            detonate(null);
            return state == State.REPLAY;
        }
        if (pos.getBlock().isLiquid() && plugin.getConfig().getBoolean("flight.water-kills", true)) {
            fizzle();
            return state == State.REPLAY;
        }

        // ── дрон следует за камерой
        Location droneAt = pos.clone();
        droneAt.setDirection(pos.getDirection());
        entity.teleport(droneAt);

        trackPath(droneAt);
        effects(droneAt);
        drawFiber(p);
        hud(p);
        return true;
    }

    // ────────────────────────────────────────────── повтор сверху

    /** Запускаем облёт: камера идёт по последнему отрезку маршрута, глядя в точку удара. */
    private void startReplay(Location at) {
        Player p = pilot();
        if (p == null || !p.isOnline() || !plugin.getConfig().getBoolean("replay.enabled", true)) {
            returnPilot();
            state = State.DEAD;
            return;
        }

        impact = at.clone();
        double height = plugin.getConfig().getDouble("replay.height", 12);
        double back = plugin.getConfig().getDouble("replay.back-distance", 18);
        double trail = plugin.getConfig().getDouble("replay.trail-blocks", 35);

        // ищем точку маршрута примерно за trail блоков до удара
        List<Location> pts = new ArrayList<>(path);
        Location start = pts.isEmpty() ? at.clone() : pts.get(0);
        for (int i = pts.size() - 1; i >= 0; i--) {
            if (pts.get(i).getWorld() != null && pts.get(i).getWorld().equals(at.getWorld())
                    && pts.get(i).distance(at) >= trail) {
                start = pts.get(i);
                break;
            }
        }

        Vector approach = at.toVector().subtract(start.toVector());
        if (approach.lengthSquared() < 0.01) approach = new Vector(1, 0, 0);
        Vector unit = approach.normalize();

        replayFrom = start.clone().subtract(unit.clone().multiply(back)).add(0, height, 0);
        replayTo = at.clone().subtract(unit.clone().multiply(back * 0.35)).add(0, height * 0.6, 0);

        replayLength = Math.max(10, (int) (plugin.getConfig().getDouble("replay.seconds", 4.0) * 20));
        replayTick = 0;
        state = State.REPLAY;

        p.sendMessage(plugin.msg("replay-start"));
    }

    private boolean replayTick() {
        Player p = pilot();
        if (p == null || !p.isOnline()) {
            state = State.DEAD;
            return false;
        }
        if (replayFrom == null || replayTo == null || impact == null) {
            returnPilot();
            state = State.DEAD;
            return false;
        }

        double t = (double) replayTick / replayLength;
        if (t >= 1.0) {
            returnPilot();
            state = State.DEAD;
            return false;
        }

        // плавное замедление к концу
        double e = 1 - Math.pow(1 - t, 2);
        Location cam = replayFrom.clone().add(
                replayTo.toVector().subtract(replayFrom.toVector()).multiply(e));
        cam.setDirection(impact.toVector().subtract(cam.toVector()));
        p.teleport(cam);

        if (replayTick % 4 == 0) {
            p.sendActionBar(plugin.msg("replay-hud"));
        }
        replayTick++;
        return true;
    }

    // ─────────────────────────────────────────────── оптоволокно

    private void trackPath(Location at) {
        Location last = path.peekLast();
        double step = plugin.getConfig().getDouble("fiber.point-step", 2.0);
        if (last == null || last.getWorld() == null || !last.getWorld().equals(at.getWorld())
                || last.distanceSquared(at) >= step * step) {
            path.addLast(at.clone());
            int max = plugin.getConfig().getInt("fiber.max-points", 400);
            while (path.size() > max) path.pollFirst();
        }
    }

    private void drawFiber(Player p) {
        if (!plugin.getConfig().getBoolean("fiber.enabled", true)) return;
        int every = Math.max(1, plugin.getConfig().getInt("fiber.draw-every-ticks", 4));
        if (++fiberTimer < every) return;
        fiberTimer = 0;

        Particle particle = Cfg.particle(plugin, "fiber.particle", Particle.CRIT);
        double render = plugin.getConfig().getDouble("fiber.render-distance", 48);
        double dense = plugin.getConfig().getDouble("fiber.density", 0.6);
        World w = entity.getWorld();

        Location prev = null;
        for (Location point : path) {
            if (prev != null && point.getWorld() != null && point.getWorld().equals(w)
                    && point.distanceSquared(p.getLocation()) < render * render) {
                line(w, particle, prev, point, dense);
            }
            prev = point;
        }
        if (prev != null && prev.getWorld() != null && prev.getWorld().equals(w)) {
            line(w, particle, prev, entity.getLocation(), dense);
        }
    }

    private void line(World w, Particle particle, Location a, Location b, double dense) {
        Vector seg = b.toVector().subtract(a.toVector());
        double len = seg.length();
        if (len < 0.01) return;
        Vector unit = seg.normalize();
        for (double d = 0; d < len; d += dense) {
            w.spawnParticle(particle, a.clone().add(unit.clone().multiply(d)), 1, 0, 0, 0, 0);
        }
    }

    private void effects(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        int count = plugin.getConfig().getInt("effects.trail-count", 1);
        if (count > 0) {
            w.spawnParticle(Cfg.particle(plugin, "effects.trail-particle", Particle.SMOKE_NORMAL),
                    at, count, 0.05, 0.05, 0.05, 0.01);
        }
        int interval = Math.max(1, plugin.getConfig().getInt("effects.engine-interval-ticks", 5));
        if (++soundTimer >= interval) {
            soundTimer = 0;
            w.playSound(at, Cfg.sound(plugin, "effects.engine-sound", Sound.ENTITY_BEE_LOOP_AGGRESSIVE),
                    (float) plugin.getConfig().getDouble("effects.engine-volume", 0.9),
                    (float) plugin.getConfig().getDouble("effects.engine-pitch", 1.9));
        }
    }

    private void hud(Player p) {
        if (!plugin.getConfig().getBoolean("hud.enabled", true)) return;
        int left = plugin.getConfig().getInt("flight.max-seconds", 90) - ticksFlown / 20;
        double dist = (home != null && home.getWorld() != null
                && home.getWorld().equals(entity.getWorld()))
                ? entity.getLocation().distance(home) : 0;
        p.sendActionBar(plugin.msg("hud",
                "dist", String.valueOf((int) dist),
                "sec", String.valueOf(Math.max(0, left)),
                "web", webStuck ? plugin.msg("hud-web") : ""));
    }

    // ─────────────────────────────────────────────── завершение

    public void returnPilot() {
        Player p = pilot();
        if (p != null) {
            try {
                p.setSpectatorTarget(null);
            } catch (Throwable ignored) {
            }
            p.setFlySpeed(homeFlySpeed <= 0 ? 0.1f : homeFlySpeed);
            p.setGameMode(homeMode == GameMode.SPECTATOR ? GameMode.SURVIVAL : homeMode);
            if (home != null && home.getWorld() != null) p.teleport(home);
            p.sendActionBar("");
        }
        plugin.sessions().clear(owner);
    }

    public void detonate(String reason) {
        if (state == State.DEAD || state == State.REPLAY) return;
        Location at = entity.getLocation().clone();

        float power = (float) plugin.getConfig().getDouble("explosion.power", 4.0);
        boolean fire = plugin.getConfig().getBoolean("explosion.set-fire", false);
        boolean blocks = plugin.getConfig().getBoolean("explosion.break-blocks", true);
        World w = at.getWorld();
        if (w != null && plugin.getConfig().getStringList("protection.no-grief-worlds")
                .contains(w.getName())) blocks = false;

        entity.remove();
        if (w != null) w.createExplosion(at, power, fire, blocks);

        Player p = pilot();
        if (p != null) {
            p.sendMessage(reason != null ? reason : plugin.msg("hit",
                    "x", String.valueOf(at.getBlockX()),
                    "y", String.valueOf(at.getBlockY()),
                    "z", String.valueOf(at.getBlockZ())));
        }
        startReplay(at);
    }

    public void fizzle() {
        if (state == State.DEAD || state == State.REPLAY) return;
        Location at = entity.getLocation().clone();
        state = State.DEAD;
        entity.remove();
        returnPilot();

        World w = at.getWorld();
        if (w != null) {
            w.spawnParticle(Cfg.particle(plugin, "effects.fizzle-particle", Particle.SMOKE_LARGE),
                    at, 25, 0.4, 0.4, 0.4, 0.05);
            w.playSound(at, Cfg.sound(plugin, "effects.fizzle-sound",
                    Sound.ENTITY_GENERIC_EXTINGUISH_FIRE), 1f, 1f);
        }
        Player p = pilot();
        if (p != null) p.sendMessage(plugin.msg("signal-lost"));
    }
}
