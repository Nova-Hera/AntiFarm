package br.dotwired.antifarm.listener;

import br.dotwired.antifarm.AntiFarm;
import br.dotwired.antifarm.Mats;
import br.dotwired.antifarm.Rule;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;

import java.util.Set;

/** Dispensers may not use farming items (bone meal, shears, bottles on hives, eggs...). */
public final class DispenserListener implements Listener {

    private final AntiFarm plugin;
    private final Rule rule;
    private final Set<Material> items;

    public DispenserListener(AntiFarm plugin) {
        this.plugin = plugin;
        this.rule = plugin.rule("dispensers");
        this.items = rule.section == null ? Set.of()
                : Mats.parse(rule.section.getStringList("blocked-items"), plugin.getLogger());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (!rule.enabled()) return;
        Block b = e.getBlock();
        if (b.getType() != Material.DISPENSER || !items.contains(e.getItem().getType())) return;
        if (rule.rollAllow()) return;
        e.setCancelled(true);
        plugin.report(rule, "dispensing " + e.getItem().getType(), b.getLocation());
    }
}
