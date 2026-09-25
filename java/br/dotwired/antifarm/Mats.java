package br.dotwired.antifarm;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public final class Mats {
    private Mats() {}

    /** Parses material names; entries starting with '#' are vanilla block/item tags (e.g. "#logs"). */
    public static Set<Material> parse(List<String> names, Logger log) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String raw : names) {
            String n = raw.trim().toLowerCase();
            if (n.startsWith("#")) {
                NamespacedKey key = NamespacedKey.minecraft(n.substring(1));
                Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag == null) tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
                if (tag == null) log.warning("Unknown tag in config: " + raw);
                else out.addAll(tag.getValues());
            } else {
                Material m = Material.matchMaterial(n);
                if (m == null) log.warning("Unknown material in config: " + raw);
                else out.add(m);
            }
        }
        return out;
    }
}
