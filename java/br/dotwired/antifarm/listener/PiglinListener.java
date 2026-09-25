package br.dotwired.antifarm.listener;

import br.dotwired.antifarm.AntiFarm;
import br.dotwired.antifarm.Rule;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Piglin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/** Bartering only with gold a player threw by hand — not dispensed/farmed gold lying on the ground. */
public final class PiglinListener implements Listener {

    private final AntiFarm plugin;
    private final Rule rule;
    private final NamespacedKey kByPlayer;

    public PiglinListener(AntiFarm plugin) {
        this.plugin = plugin;
        this.rule = plugin.rule("piglin-barter");
        this.kByPlayer = plugin.key("barter_by_player");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!rule.enabled() || !(e.getEntity() instanceof Piglin p)) return;
        UUID thrower = e.getItem().getThrower();
        byte byPlayer = (byte) (thrower != null && Bukkit.getPlayer(thrower) != null ? 1 : 0);
        p.getPersistentDataContainer().set(kByPlayer, PersistentDataType.BYTE, byPlayer);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBarter(PiglinBarterEvent e) {
        if (!rule.enabled()) return;
        var pdc = e.getEntity().getPersistentDataContainer();
        Byte byPlayer = pdc.get(kByPlayer, PersistentDataType.BYTE);
        pdc.remove(kByPlayer);
        if (byPlayer != null && byPlayer == 1) return;
        if (rule.rollAllow()) return;
        e.setCancelled(true);
        plugin.report(rule, "automated barter", e.getEntity().getLocation());
    }
}
