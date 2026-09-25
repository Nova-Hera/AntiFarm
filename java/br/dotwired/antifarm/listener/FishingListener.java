package br.dotwired.antifarm.listener;

import br.dotwired.antifarm.AntiFarm;
import br.dotwired.antifarm.Rule;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AFK/auto-clicker fishing: a catch made from exactly the same position and camera angle as the previous
 * catches (N in a row), or above a catches-per-window cap, yields the afk-fishing factor.
 */
public final class FishingListener implements Listener {

    private final AntiFarm plugin;
    private final Rule rule;
    private final int samePoseCatches;
    private final int maxCatches;
    private final long windowMs;

    private final Map<UUID, Location> lastPose = new HashMap<>();
    private final Map<UUID, Integer> streak = new HashMap<>();
    private final Map<UUID, Deque<Long>> catches = new HashMap<>();

    public FishingListener(AntiFarm plugin) {
        this.plugin = plugin;
        this.rule = plugin.rule("afk-fishing");
        var s = rule.section;
        samePoseCatches = s == null ? 3 : s.getInt("same-pose-catches", 3);
        maxCatches = s == null ? 40 : s.getInt("max-catches-per-window", 40);
        windowMs = (s == null ? 600 : s.getLong("window-seconds", 600)) * 1000L;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (!rule.enabled() || e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Location pose = p.getLocation();
        long now = System.currentTimeMillis();

        Location prev = lastPose.put(id, pose);
        int s = (prev != null && samePose(prev, pose)) ? streak.getOrDefault(id, 0) + 1 : 0;
        streak.put(id, s);

        Deque<Long> ts = catches.computeIfAbsent(id, k -> new ArrayDeque<>());
        ts.addLast(now);
        while (!ts.isEmpty() && now - ts.peekFirst() > windowMs) ts.pollFirst();

        String why = s >= samePoseCatches ? "same pose x" + (s + 1) : ts.size() > maxCatches ? "rate " + ts.size() : null;
        if (why == null) return;

        e.setExpToDrop(Rule.scale(e.getExpToDrop(), rule.yield()));
        if (e.getCaught() instanceof Item item) {
            var stack = item.getItemStack();
            int n = Rule.scale(stack.getAmount(), rule.yield());
            if (n <= 0) item.remove();
            else {
                stack.setAmount(n);
                item.setItemStack(stack);
            }
        }
        plugin.report(rule, p.getName() + " " + why, pose);
    }

    private static boolean samePose(Location a, Location b) {
        if (!a.getWorld().equals(b.getWorld())) return false;
        return a.distanceSquared(b) < 0.09
                && Math.abs(a.getYaw() - b.getYaw()) < 1.0f
                && Math.abs(a.getPitch() - b.getPitch()) < 1.0f;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastPose.remove(id);
        streak.remove(id);
        catches.remove(id);
    }
}
