package org.mydreamduels.dreammultiverse.anchor;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.util.LocationUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Named saved locations (/mv anchor) usable as "a:name" destinations. Stored in anchors.yml. */
public final class AnchorManager {

    private final Dreammultiverse plugin;
    private final File file;
    private final Map<String, Location> anchors = new ConcurrentHashMap<>();
    private final Map<String, String> displayNames = new ConcurrentHashMap<>();
    /** Anchors whose world isn't loaded right now - kept so saving doesn't lose them. */
    private final Map<String, ConfigurationSection> unresolved = new ConcurrentHashMap<>();

    public AnchorManager(Dreammultiverse plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "anchors.yml");
    }

    public void load() {
        anchors.clear();
        displayNames.clear();
        unresolved.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("anchors");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            Location loc = LocationUtil.read(s);
            String k = name.toLowerCase(Locale.ROOT);
            displayNames.put(k, name);
            if (loc != null) anchors.put(k, loc);
            else if (s != null) unresolved.put(k, s);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Location> e : anchors.entrySet()) {
            LocationUtil.write(yaml.createSection("anchors." + displayNames.get(e.getKey())), e.getValue());
        }
        for (Map.Entry<String, ConfigurationSection> e : unresolved.entrySet()) {
            yaml.set("anchors." + displayNames.get(e.getKey()), e.getValue());
        }
        plugin.io().write(file, yaml.saveToString());
    }

    public Location get(String name) {
        Location l = anchors.get(name.toLowerCase(Locale.ROOT));
        if (l == null) {
            ConfigurationSection s = unresolved.get(name.toLowerCase(Locale.ROOT));
            l = LocationUtil.read(s);
        }
        return l == null ? null : l.clone();
    }

    public void set(String name, Location loc) {
        String k = name.toLowerCase(Locale.ROOT);
        unresolved.remove(k);
        anchors.put(k, loc.clone());
        displayNames.put(k, name);
        save();
    }

    public boolean remove(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        boolean existed = anchors.remove(k) != null | unresolved.remove(k) != null;
        displayNames.remove(k);
        if (existed) save();
        return existed;
    }

    public List<String> names() {
        List<String> out = new ArrayList<>(displayNames.values());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
