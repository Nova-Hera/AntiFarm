package br.dotwired.antifarm;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One anti-farm rule. BLOCK = yield 0 (action cancelled where possible), NERF = yield {@link #factor},
 * OFF = rule ignored. Switching between full block and "lobotomized" automation is a config change only.
 */
public final class Rule {

    public enum Mode { BLOCK, NERF, OFF }

    public final String name;
    public final Mode mode;
    public final double factor;
    public final ConfigurationSection section;

    private Rule(String name, Mode mode, double factor, ConfigurationSection section) {
        this.name = name;
        this.mode = mode;
        this.factor = factor;
        this.section = section;
    }

    static Rule load(ConfigurationSection rules, String name) {
        ConfigurationSection s = rules == null ? null : rules.getConfigurationSection(name);
        if (s == null) return new Rule(name, Mode.OFF, 1.0, null);
        Mode mode;
        try {
            mode = Mode.valueOf(s.getString("mode", "BLOCK").toUpperCase());
        } catch (IllegalArgumentException e) {
            mode = Mode.BLOCK;
        }
        double factor = Math.max(0.0, Math.min(1.0, s.getDouble("factor", 0.0)));
        return new Rule(name, mode, factor, s);
    }

    public boolean enabled() {
        return mode != Mode.OFF;
    }

    /** Yield multiplier when this rule triggers (1.0 when OFF). */
    public double yield() {
        return switch (mode) {
            case OFF -> 1.0;
            case BLOCK -> 0.0;
            case NERF -> factor;
        };
    }

    /** For binary actions (e.g. a barter, a breed): true = let it happen this time. */
    public boolean rollAllow() {
        double y = this.yield();
        return y >= 1.0 || (y > 0.0 && ThreadLocalRandom.current().nextDouble() < y);
    }

    // ---- yield helpers -------------------------------------------------------------------------

    public static int scale(int amount, double factor) {
        if (factor >= 1.0) return amount;
        if (factor <= 0.0 || amount <= 0) return 0;
        double exact = amount * factor;
        int whole = (int) Math.floor(exact);
        return whole + (ThreadLocalRandom.current().nextDouble() < exact - whole ? 1 : 0);
    }

    /** Scales a mutable drop list in place; stacks scaled to zero are removed. */
    public static void scaleDrops(List<ItemStack> drops, double factor) {
        if (factor >= 1.0) return;
        Iterator<ItemStack> it = drops.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (stack == null) continue;
            int n = scale(stack.getAmount(), factor);
            if (n <= 0) it.remove();
            else stack.setAmount(n);
        }
    }
}
