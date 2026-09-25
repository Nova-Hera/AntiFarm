package br.dotwired.antifarm.listener;

import br.dotwired.antifarm.AntiFarm;
import br.dotwired.antifarm.Mats;
import br.dotwired.antifarm.Rule;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Item;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Crops/plants only yield when a player breaks them by hand. Covers water/piston/flying-machine harvesters
 * (BlockBreakBlockEvent), explosion harvesters, villager/fox harvesters, and self-breaking plants such as
 * cactus via an "unattributed drop" catch-all.
 */
public final class CropListener implements Listener {

    private final AntiFarm plugin;
    private final Rule crops;
    private final Rule villagers;
    private final Set<Material> farmBlocks;
    private final Set<Material> unattributed;
    /** block key -> server tick of the last player break/harvest there. */
    private final Map<Long, Integer> recentPlayerBreaks = new HashMap<>();

    public CropListener(AntiFarm plugin) {
        this.plugin = plugin;
        this.crops = plugin.rule("crops-non-player-break");
        this.villagers = plugin.rule("villager-farming");
        var cfg = plugin.getConfig();
        this.farmBlocks = Mats.parse(cfg.getStringList("farm-blocks"), plugin.getLogger());
        this.unattributed = Mats.parse(cfg.getStringList("rules.crops-non-player-break.unattributed-drops"), plugin.getLogger());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreakBlock(BlockBreakBlockEvent e) {
        if (!crops.enabled() || !farmBlocks.contains(e.getBlock().getType())) return;
        Rule.scaleDrops(e.getDrops(), crops.yield());
        e.setExpToDrop(Rule.scale(e.getExpToDrop(), crops.yield()));
        plugin.report(crops, "broken by " + e.getSource().getType(), e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        stripExplosion(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        stripExplosion(e.blockList());
    }

    private void stripExplosion(List<Block> blocks) {
        if (!crops.enabled()) return;
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (!farmBlocks.contains(b.getType()) || crops.rollAllow()) continue;
            Material type = b.getType();
            it.remove();
            b.setType(Material.AIR, false); // destroyed, but without drops
            plugin.report(crops, "exploded " + type, b.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (!villagers.enabled()) return;
        if (!(e.getEntity() instanceof Villager) && !(e.getEntity() instanceof Fox)) return;
        if (!farmBlocks.contains(e.getBlock().getType())) return;
        if (villagers.rollAllow()) return;
        e.setCancelled(true);
        plugin.report(villagers, e.getEntity().getType() + " harvesting", e.getBlock().getLocation());
    }

    // ---- unattributed drops (cactus breaking itself, etc.) --------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBreak(BlockBreakEvent e) {
        markPlayer(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHarvest(PlayerHarvestBlockEvent e) {
        markPlayer(e.getHarvestedBlock());
    }

    /** A dying player's inventory spills unowned items: never treat those as farm output. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        markPlayer(e.getEntity().getLocation().getBlock());
    }

    private void markPlayer(Block b) {
        if (unattributed.isEmpty()) return;
        if (recentPlayerBreaks.size() > 4096) recentPlayerBreaks.clear();
        // A player breaking the base of a cactus/cane column drops the whole column: mark it vertically.
        for (int dy = -1; dy <= 24; dy++) recentPlayerBreaks.put(key(b.getX(), b.getY() + dy, b.getZ()), Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!crops.enabled() || unattributed.isEmpty()) return;
        Item item = e.getEntity();
        if (!unattributed.contains(item.getItemStack().getType()) || item.getThrower() != null) return;
        Location l = item.getLocation();
        int now = Bukkit.getCurrentTick();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    Integer t = recentPlayerBreaks.get(key(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz));
                    if (t != null && now - t <= 5) return;
                }
        int n = Rule.scale(item.getItemStack().getAmount(), crops.yield());
        if (n <= 0) e.setCancelled(true);
        else item.getItemStack().setAmount(n);
        plugin.report(crops, "unattributed " + item.getItemStack().getType(), l);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
