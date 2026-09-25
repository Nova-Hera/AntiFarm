package br.dotwired.antifarm.listener;

import br.dotwired.antifarm.AntiFarm;
import br.dotwired.antifarm.Rule;
import com.destroystokyo.paper.entity.villager.Reputation;
import com.destroystokyo.paper.entity.villager.ReputationType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Kills iron golem farms and villager trading halls/breeders/cure-discount loops; normal village trading stays. */
public final class VillagerListener implements Listener {

    private final AntiFarm plugin;
    private final Rule golems;
    private final Rule halls;
    private final int maxPerChunk;
    private final boolean stripCureDiscount;

    public VillagerListener(AntiFarm plugin) {
        this.plugin = plugin;
        this.golems = plugin.rule("iron-golem-farm");
        this.halls = plugin.rule("villager-trading-halls");
        var s = halls.section;
        this.maxPerChunk = s == null ? 8 : s.getInt("max-villagers-per-chunk", 8);
        this.stripCureDiscount = s == null || s.getBoolean("strip-cure-discount", true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGolemSpawn(CreatureSpawnEvent e) {
        if (!golems.enabled() || e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE) return;
        if (golems.rollAllow()) return;
        e.setCancelled(true);
        plugin.report(golems, "village-defense golem", e.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVillagerBreed(EntityBreedEvent e) {
        if (!halls.enabled() || !(e.getEntity() instanceof Villager) || halls.rollAllow()) return;
        e.setCancelled(true);
        plugin.report(halls, "villager breeding", e.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTradeOpen(PlayerInteractEntityEvent e) {
        if (!halls.enabled() || !(e.getRightClicked() instanceof Villager v)) return;
        int count = 0;
        for (Entity en : v.getLocation().getChunk().getEntities()) if (en instanceof Villager) count++;
        if (count <= maxPerChunk || halls.rollAllow()) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage("§cThis villager refuses to trade: too many villagers crammed here (" + count + "/" + maxPerChunk + " per chunk).");
        plugin.report(halls, "trade refused, " + count + " villagers in chunk", v.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCure(EntityTransformEvent e) {
        if (!halls.enabled() || !stripCureDiscount || e.getTransformReason() != EntityTransformEvent.TransformReason.CURED) return;
        if (!(e.getTransformedEntity() instanceof Villager v)) return;
        // cure gossip is added right after the conversion, so strip it on the next tick
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!v.isValid()) return;
            Map<UUID, Reputation> reps = new HashMap<>(v.getReputations());
            reps.values().forEach(r -> {
                r.setReputation(ReputationType.MAJOR_POSITIVE, 0);
                r.setReputation(ReputationType.MINOR_POSITIVE, 0);
            });
            v.setReputations(reps);
            plugin.report(halls, "cure discount stripped", v.getLocation());
        }, 1L);
    }
}
