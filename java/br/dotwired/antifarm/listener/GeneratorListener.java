package br.dotwired.antifarm.listener;

import br.dotwired.antifarm.AntiFarm;
import br.dotwired.antifarm.Mats;
import br.dotwired.antifarm.Rule;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Blocks produced by generators (lava+water cobble/stone/basalt, snow golem trails) are tagged by position in the
 * chunk's persistent data. Mining a tagged block yields the block-generators factor. Natural blocks are unaffected.
 */
public final class GeneratorListener implements Listener {

    private static final int MAX_PER_CHUNK = 4096;

    private final AntiFarm plugin;
    private final Rule rule;
    private final Set<Material> generated;
    private final NamespacedKey kGen;
    private final Set<Block> pendingScale = new HashSet<>();

    public GeneratorListener(AntiFarm plugin) {
        this.plugin = plugin;
        this.rule = plugin.rule("block-generators");
        this.generated = rule.section == null ? Set.of()
                : Mats.parse(rule.section.getStringList("materials"), plugin.getLogger());
        this.kGen = plugin.key("generated");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) { // also covers EntityBlockFormEvent (snow golems)
        if (rule.enabled() && generated.contains(e.getNewState().getType())) setTag(e.getBlock(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!rule.enabled() || !hasTag(e.getBlock())) return;
        setTag(e.getBlock(), false);
        if (pendingScale.size() > 256) pendingScale.clear(); // drops fire the same tick; never grows in practice
        pendingScale.add(e.getBlock());
        e.setExpToDrop(Rule.scale(e.getExpToDrop(), rule.yield()));
        plugin.report(rule, "mined generated " + e.getBlock().getType(), e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (!pendingScale.remove(e.getBlock())) return;
        Iterator<Item> it = e.getItems().iterator();
        while (it.hasNext()) {
            Item item = it.next();
            int n = Rule.scale(item.getItemStack().getAmount(), rule.yield());
            if (n <= 0) it.remove();
            else {
                var s = item.getItemStack();
                s.setAmount(n);
                item.setItemStack(s);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        moveTags(e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        moveTags(e.getBlocks(), e.getDirection());
    }

    private void moveTags(List<Block> blocks, BlockFace dir) {
        if (!rule.enabled()) return;
        List<Block> tagged = new ArrayList<>();
        for (Block b : blocks) if (hasTag(b)) tagged.add(b);
        for (Block b : tagged) setTag(b, false);
        for (Block b : tagged) setTag(b.getRelative(dir), true);
    }

    // ---- chunk PDC storage: packed (x&15, y, z&15) ------------------------------------------------

    private static long pack(Block b) {
        return ((long) b.getY() << 8) | ((b.getX() & 15) << 4) | (b.getZ() & 15);
    }

    private boolean hasTag(Block b) {
        long[] arr = b.getChunk().getPersistentDataContainer().get(kGen, PersistentDataType.LONG_ARRAY);
        if (arr == null) return false;
        long p = pack(b);
        for (long v : arr) if (v == p) return true;
        return false;
    }

    private void setTag(Block b, boolean on) {
        Chunk c = b.getChunk();
        long[] arr = c.getPersistentDataContainer().get(kGen, PersistentDataType.LONG_ARRAY);
        Set<Long> set = new HashSet<>();
        if (arr != null) for (long v : arr) set.add(v);
        boolean changed = on ? set.add(pack(b)) : set.remove(pack(b));
        if (!changed || set.size() > MAX_PER_CHUNK) return;
        long[] out = new long[set.size()];
        int i = 0;
        for (long v : set) out[i++] = v;
        c.getPersistentDataContainer().set(kGen, PersistentDataType.LONG_ARRAY, out);
    }
}
