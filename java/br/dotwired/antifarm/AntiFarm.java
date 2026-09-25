package br.dotwired.antifarm;

import br.dotwired.antifarm.listener.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AntiFarm extends JavaPlugin {

    public static final List<String> RULE_NAMES = List.of(
            "crops-non-player-break", "villager-farming", "dispensers", "mob-farm", "iron-golem-farm",
            "piglin-barter", "animal-breeding", "shearing-automation", "item-collection", "crafter",
            "composter-automation", "block-generators", "villager-trading-halls", "afk-fishing");

    private final Map<String, Rule> rules = new HashMap<>();
    private final Set<UUID> debuggers = new HashSet<>();

    public NamespacedKey key(String k) {
        return new NamespacedKey(this, k);
    }

    public Rule rule(String name) {
        return rules.getOrDefault(name, Rule.load(null, name));
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reload();
    }

    public void reload() {
        reloadConfig();
        rules.clear();
        for (String n : RULE_NAMES) rules.put(n, Rule.load(getConfig().getConfigurationSection("rules"), n));

        // Recipes removed on enable/reload; re-adding one requires a restart.
        for (String r : getConfig().getStringList("remove-recipes")) {
            if (Bukkit.removeRecipe(NamespacedKey.minecraft(r.toLowerCase()))) getLogger().info("Removed recipe " + r);
        }

        HandlerList.unregisterAll(this);
        List<Listener> listeners = List.of(
                new CropListener(this), new MobListener(this), new AnimalListener(this),
                new DispenserListener(this), new CollectionListener(this), new GeneratorListener(this),
                new VillagerListener(this), new PiglinListener(this), new FishingListener(this));
        for (Listener l : listeners) Bukkit.getPluginManager().registerEvents(l, this);

        StringBuilder sb = new StringBuilder("Rules:");
        for (String n : RULE_NAMES) {
            Rule r = rules.get(n);
            sb.append(' ').append(n).append('=').append(r.mode).append(r.mode == Rule.Mode.NERF ? "(" + r.factor + ")" : "");
        }
        getLogger().info(sb.toString());
    }

    /** Reports a reduced yield to admins who toggled /antifarm debug, and to the log if log-triggers is on. */
    public void report(Rule rule, String what, Location at) {
        String msg = "[AntiFarm] " + rule.name + " -> " + what + " x" + rule.yield()
                + (at == null ? "" : " @ " + at.getWorld().getName() + " " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ());
        if (getConfig().getBoolean("log-triggers", false)) getLogger().info(msg);
        if (debuggers.isEmpty()) return;
        for (UUID id : debuggers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && (at == null || (p.getWorld().equals(at.getWorld()) && p.getLocation().distanceSquared(at) < 128 * 128))) {
                p.sendMessage("§8" + msg);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e/antifarm reload §7| §e/antifarm debug §7| §e/antifarm rules");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reload();
                sender.sendMessage("§aAntiFarm reloaded.");
            }
            case "debug" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Players only. Use log-triggers: true in config.yml for console output.");
                    return true;
                }
                if (debuggers.remove(p.getUniqueId())) p.sendMessage("§eAntiFarm debug off.");
                else {
                    debuggers.add(p.getUniqueId());
                    p.sendMessage("§eAntiFarm debug on (triggers within 128 blocks).");
                }
            }
            case "rules" -> {
                for (String n : RULE_NAMES) {
                    Rule r = rules.get(n);
                    sender.sendMessage("§7" + n + ": §f" + r.mode + (r.mode == Rule.Mode.NERF ? " x" + r.factor : ""));
                }
            }
            default -> sender.sendMessage("§cUnknown subcommand.");
        }
        return true;
    }
}
