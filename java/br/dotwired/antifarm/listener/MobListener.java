package br.dotwired.antifarm.listener;

import br.dotwired.antifarm.AntiFarm;
import br.dotwired.antifarm.Rule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mob drops/XP only for "real fights". A player landing the last hit is NOT enough: any of these signals
 * marks the kill as farmed and applies the mob-farm yield:
 *  - no player killer (crushers, lava blades, cramming, drowning, wither roses...)
 *  - taint: the mob took environmental damage (fall from drop towers, cramming, suffocation, magma...)
 *  - spawn origin: spawner / spawn egg / golem building / village defense / nether portal / conversions
 *  - displacement: a hostile mob died far from its spawn point without ever targeting a player (water streams)
 *  - kill density: too many kills in the same ~48x48 area inside the window (one-hit grinders)
 *  - stationary killer: the player has not moved for the last N kills (AFK/auto-clicker grinders)
 */
public final class MobListener implements Listener {

    private final AntiFarm plugin;
    private final Rule rule;
    private final NamespacedKey kReason, kSpawn, kTaint, kTargeted;
    private final Set<EntityDamageEvent.DamageCause> taintCauses = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
    private final Set<CreatureSpawnEvent.SpawnReason> taggedReasons = EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
    private final double displacement;
    private final long windowMs;
    private final int freeHostile, freeAnimal;
    private final int stationaryKills;
    private final double stationaryRadius;
    private final long stationaryWindowMs;

    private final Map<Long, Deque<Long>> hostileKills = new HashMap<>();
    private final Map<Long, Deque<Long>> animalKills = new HashMap<>();
    private final Map<UUID, Deque<Location>> playerKillSpots = new HashMap<>();
    private final Map<UUID, Deque<Long>> playerKillTimes = new HashMap<>();

    public MobListener(AntiFarm plugin) {
        this.plugin = plugin;
        this.rule = plugin.rule("mob-farm");
        kReason = plugin.key("spawn_reason");
        kSpawn = plugin.key("spawn_pos");
        kTaint = plugin.key("taint");
        kTargeted = plugin.key("targeted");
        ConfigurationSection s = rule.section;
        List<String> causes = s == null ? List.of() : s.getStringList("taint-causes");
        for (String c : causes) {
            try { taintCauses.add(EntityDamageEvent.DamageCause.valueOf(c.toUpperCase())); }
            catch (IllegalArgumentException ex) { plugin.getLogger().warning("Unknown damage cause: " + c); }
        }
        List<String> reasons = s == null ? List.of() : s.getStringList("tagged-spawn-reasons");
        for (String r : reasons) {
            try { taggedReasons.add(CreatureSpawnEvent.SpawnReason.valueOf(r.toUpperCase())); }
            catch (IllegalArgumentException ex) { plugin.getLogger().warning("Unknown spawn reason: " + r); }
        }
        displacement = s == null ? 24 : s.getDouble("displacement-blocks", 24);
        windowMs = (s == null ? 600 : s.getLong("density.window-seconds", 600)) * 1000L;
        freeHostile = s == null ? 20 : s.getInt("density.free-hostile-kills", 20);
        freeAnimal = s == null ? 16 : s.getInt("density.free-animal-kills", 16);
        stationaryKills = s == null ? 10 : s.getInt("stationary.kills", 10);
        stationaryRadius = s == null ? 2.0 : s.getDouble("stationary.radius", 2.0);
        stationaryWindowMs = (s == null ? 300 : s.getLong("stationary.window-seconds", 300)) * 1000L;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!rule.enabled()) return;
        LivingEntity le = e.getEntity();
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        pdc.set(kReason, PersistentDataType.STRING, e.getSpawnReason().name());
        Location l = e.getLocation();
        pdc.set(kSpawn, PersistentDataType.INTEGER_ARRAY, new int[]{l.getBlockX(), l.getBlockY(), l.getBlockZ()});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!rule.enabled() || e.getEntity() instanceof Player || !(e.getEntity() instanceof LivingEntity)) return;
        if (taintCauses.contains(e.getCause()))
            e.getEntity().getPersistentDataContainer().set(kTaint, PersistentDataType.STRING, e.getCause().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (rule.enabled() && e.getTarget() instanceof Player)
            e.getEntity().getPersistentDataContainer().set(kTargeted, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        if (!rule.enabled() || dead instanceof Player || dead instanceof ArmorStand) return;
        String signal = farmSignal(dead, dead.getKiller());
        if (signal == null) return;
        Rule.scaleDrops(e.getDrops(), rule.yield());
        e.setDroppedExp(Rule.scale(e.getDroppedExp(), rule.yield()));
        plugin.report(rule, dead.getType() + " " + signal, dead.getLocation());
    }

    /** @return why this kill counts as farmed, or null for a legitimate kill. */
    private String farmSignal(LivingEntity dead, Player killer) {
        PersistentDataContainer pdc = dead.getPersistentDataContainer();
        long now = System.currentTimeMillis();
        Location at = dead.getLocation();
        boolean animal = dead instanceof Animals;

        // density is counted for every death, so the area heats up even from non-player kills
        long region = regionKey(at);
        Deque<Long> times = (animal ? animalKills : hostileKills).computeIfAbsent(region, k -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > windowMs) times.pollFirst();
        prune(now);

        if (killer == null) return "no-player-kill";
        boolean stationary = stationary(killer, now); // always record the kill spot
        String taint = pdc.get(kTaint, PersistentDataType.STRING);
        if (taint != null) return "taint:" + taint;
        String reason = pdc.get(kReason, PersistentDataType.STRING);
        if (reason != null) {
            try {
                if (taggedReasons.contains(CreatureSpawnEvent.SpawnReason.valueOf(reason))) return "spawn:" + reason;
            } catch (IllegalArgumentException ignored) { }
        }
        if (dead instanceof Enemy && !pdc.has(kTargeted, PersistentDataType.BYTE)) {
            int[] sp = pdc.get(kSpawn, PersistentDataType.INTEGER_ARRAY);
            if (sp != null && sp.length == 3) {
                double dx = at.getX() - sp[0], dy = at.getY() - sp[1], dz = at.getZ() - sp[2];
                if (dx * dx + dy * dy + dz * dz > displacement * displacement) return "displaced";
            }
        }
        if (times.size() > (animal ? freeAnimal : freeHostile)) return "density:" + times.size();
        if (stationary) return "stationary-killer";
        return null;
    }

    private boolean stationary(Player killer, long now) {
        if (stationaryKills <= 0) return false;
        Deque<Location> spots = playerKillSpots.computeIfAbsent(killer.getUniqueId(), k -> new ArrayDeque<>());
        Deque<Long> ts = playerKillTimes.computeIfAbsent(killer.getUniqueId(), k -> new ArrayDeque<>());
        spots.addLast(killer.getLocation());
        ts.addLast(now);
        while (spots.size() > stationaryKills) { spots.pollFirst(); ts.pollFirst(); }
        if (spots.size() < stationaryKills || now - ts.peekFirst() > stationaryWindowMs) return false;
        Location last = spots.peekLast();
        for (Location l : spots) {
            if (!l.getWorld().equals(last.getWorld()) || l.distanceSquared(last) > stationaryRadius * stationaryRadius) return false;
        }
        return true;
    }

    private void prune(long now) {
        if (hostileKills.size() + animalKills.size() < 2048) return;
        for (Map<Long, Deque<Long>> m : List.of(hostileKills, animalKills)) {
            List<Long> dead = new ArrayList<>();
            m.forEach((k, v) -> { if (v.isEmpty() || now - v.peekLast() > windowMs) dead.add(k); });
            dead.forEach(m::remove);
        }
    }

    /** ~48x48 block cells (3x3 chunks), keyed per world. */
    private static long regionKey(Location l) {
        long cx = Math.floorDiv(l.getBlockX(), 48), cz = Math.floorDiv(l.getBlockZ(), 48);
        return (l.getWorld().getUID().getLeastSignificantBits() * 31) ^ (cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
