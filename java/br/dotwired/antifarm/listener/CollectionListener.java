package br.dotwired.antifarm.listener;

import br.dotwired.antifarm.AntiFarm;
import br.dotwired.antifarm.Rule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Item;
import org.bukkit.entity.Villager;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * No machine collection of ground items (hoppers, hopper minecarts, allays, villagers), no crafter
 * auto-crafting, no hopper-driven composters. NERF mode for collection = a one-time tax on each item entity
 * (instead of a per-tick chance, which would eventually let everything through).
 */
public final class CollectionListener implements Listener {

    private final AntiFarm plugin;
    private final Rule collection;
    private final Rule crafter;
    private final Rule composter;
    private final Rule villagerFarming;
    private final boolean hoppers, hopperMinecarts, allays;
    private final NamespacedKey kTaxed;

    public CollectionListener(AntiFarm plugin) {
        this.plugin = plugin;
        this.collection = plugin.rule("item-collection");
        this.crafter = plugin.rule("crafter");
        this.composter = plugin.rule("composter-automation");
        this.villagerFarming = plugin.rule("villager-farming");
        var s = collection.section;
        hoppers = s == null || s.getBoolean("hoppers", true);
        hopperMinecarts = s == null || s.getBoolean("hopper-minecarts", true);
        allays = s == null || s.getBoolean("allays", true);
        kTaxed = plugin.key("taxed");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onContainerPickup(InventoryPickupItemEvent e) {
        if (!collection.enabled()) return;
        InventoryHolder h = e.getInventory().getHolder(false);
        boolean applies = (h instanceof HopperMinecart && hopperMinecarts) || (h instanceof Hopper && hoppers);
        if (applies) handle(e.getItem(), () -> e.setCancelled(true), h instanceof Hopper ? "hopper" : "hopper minecart");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Allay && allays && collection.enabled()) {
            handle(e.getItem(), () -> e.setCancelled(true), "allay");
        } else if (e.getEntity() instanceof Villager && villagerFarming.enabled() && !villagerFarming.rollAllow()) {
            e.setCancelled(true); // farmer villagers collecting crops/seeds to share and replant
        }
    }

    private void handle(Item item, Runnable cancel, String who) {
        if (collection.mode == Rule.Mode.BLOCK) {
            cancel.run();
            return;
        }
        // NERF: tax the item entity once, then let it be collected
        var pdc = item.getPersistentDataContainer();
        if (pdc.has(kTaxed, PersistentDataType.BYTE)) return;
        pdc.set(kTaxed, PersistentDataType.BYTE, (byte) 1);
        ItemStack stack = item.getItemStack();
        int n = Rule.scale(stack.getAmount(), collection.yield());
        plugin.report(collection, who + " taxed " + stack.getType(), item.getLocation());
        if (n <= 0) {
            cancel.run();
            item.remove();
        } else {
            stack.setAmount(n);
            item.setItemStack(stack);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent e) {
        if (!crafter.enabled() || crafter.rollAllow()) return;
        e.setCancelled(true);
        plugin.report(crafter, "auto-craft " + e.getResult().getType(), e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (!composter.enabled()) return;
        if (!isComposter(e.getSource()) && !isComposter(e.getDestination())) return;
        if (composter.rollAllow()) return;
        e.setCancelled(true);
        plugin.report(composter, "hopper<->composter", e.getSource().getLocation());
    }

    private static boolean isComposter(Inventory inv) {
        Location l = inv.getLocation();
        return l != null && l.getWorld() != null && l.getBlock().getType() == Material.COMPOSTER;
    }
}
