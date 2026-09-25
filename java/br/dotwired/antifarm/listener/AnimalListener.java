package br.dotwired.antifarm.listener;

import br.dotwired.antifarm.AntiFarm;
import br.dotwired.antifarm.Rule;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockShearEntityEvent;
import org.bukkit.event.entity.EntityBreedEvent;

/** Caps animals per chunk (no mass pens / crammers) and blocks dispenser shearing. */
public final class AnimalListener implements Listener {

    private final AntiFarm plugin;
    private final Rule breeding;
    private final Rule shearing;
    private final int maxPerChunk;

    public AnimalListener(AntiFarm plugin) {
        this.plugin = plugin;
        this.breeding = plugin.rule("animal-breeding");
        this.shearing = plugin.rule("shearing-automation");
        this.maxPerChunk = breeding.section == null ? 24 : breeding.section.getInt("max-animals-per-chunk", 24);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (!breeding.enabled() || e.getEntity() instanceof Villager) return; // villagers: VillagerListener
        int count = 0;
        for (Entity en : e.getEntity().getLocation().getChunk().getEntities()) if (en instanceof Animals) count++;
        if (count < maxPerChunk || breeding.rollAllow()) return;
        e.setCancelled(true);
        plugin.report(breeding, "chunk has " + count + " animals", e.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenserShear(BlockShearEntityEvent e) {
        if (!shearing.enabled() || shearing.rollAllow()) return;
        e.setCancelled(true);
        plugin.report(shearing, "dispenser shearing " + e.getEntity().getType(), e.getBlock().getLocation());
    }
}
