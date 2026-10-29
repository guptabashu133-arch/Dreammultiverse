package org.mydreamduels.dreammultiverse.netherportal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.world.MVWorld;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which world a nether/end portal in a given world leads to.
 *
 * Order: explicit /mvnp link -> reverse of someone else's explicit link ->
 * naming convention (world <-> world_nether, world <-> world_the_end).
 * A link to "none" disables that portal type in the world.
 */
public final class NetherPortalManager {

    public static final String NONE = "none";

    private final Dreammultiverse plugin;
    private final File file;
    private final Map<String, String> netherLinks = new ConcurrentHashMap<>();
    private final Map<String, String> endLinks = new ConcurrentHashMap<>();

    public NetherPortalManager(Dreammultiverse plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "netherportals.yml");
    }

    public void load() {
        netherLinks.clear();
        endLinks.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        read(yaml.getConfigurationSection("links.nether"), netherLinks);
        read(yaml.getConfigurationSection("links.end"), endLinks);
    }

    private static void read(ConfigurationSection s, Map<String, String> into) {
        if (s == null) return;
        for (String k : s.getKeys(false)) into.put(k.toLowerCase(Locale.ROOT), s.getString(k, NONE));
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, String> e : netherLinks.entrySet()) yaml.set("links.nether." + e.getKey(), e.getValue());
        for (Map.Entry<String, String> e : endLinks.entrySet()) yaml.set("links.end." + e.getKey(), e.getValue());
        plugin.io().write(file, yaml.saveToString());
    }

    private Map<String, String> links(PortalType type) {
        return type == PortalType.ENDER ? endLinks : netherLinks;
    }

    public void link(PortalType type, String from, String to) {
        links(type).put(from.toLowerCase(Locale.ROOT), to);
        save();
    }

    public boolean unlink(PortalType type, String from) {
        boolean removed = links(type).remove(from.toLowerCase(Locale.ROOT)) != null;
        if (removed) save();
        return removed;
    }

    public Map<String, String> view(PortalType type) {
        return new LinkedHashMap<>(links(type));
    }

    public boolean hasExplicitRule(World from, PortalType type) {
        String name = from.getName().toLowerCase(Locale.ROOT);
        if (links(type).containsKey(name)) return true;
        for (String target : links(type).values()) if (target.equalsIgnoreCase(from.getName())) return true;
        return false;
    }

    /** Destination world, or null when this portal should lead nowhere (or the target isn't loaded). */
    public World target(World from, PortalType type) {
        Map<String, String> links = links(type);
        String explicit = links.get(from.getName().toLowerCase(Locale.ROOT));
        if (explicit != null) return explicit.equalsIgnoreCase(NONE) ? null : Bukkit.getWorld(explicit);
        for (Map.Entry<String, String> e : links.entrySet()) {
            if (e.getValue().equalsIgnoreCase(from.getName())) {
                World back = Bukkit.getWorld(e.getKey());
                if (back != null) return back;
                for (World w : Bukkit.getWorlds()) if (w.getName().equalsIgnoreCase(e.getKey())) return w;
            }
        }
        String netherSuffix = plugin.getConfig().getString("nether-portals.nether-suffix", "_nether");
        String endSuffix = plugin.getConfig().getString("nether-portals.end-suffix", "_the_end");
        String name = from.getName();
        if (type == PortalType.ENDER) {
            if (from.getEnvironment() == World.Environment.THE_END) {
                return name.endsWith(endSuffix) ? Bukkit.getWorld(name.substring(0, name.length() - endSuffix.length())) : plugin.worlds().defaultWorld();
            }
            String base = name.endsWith(netherSuffix) ? name.substring(0, name.length() - netherSuffix.length()) : name;
            return Bukkit.getWorld(base + endSuffix);
        }
        if (from.getEnvironment() == World.Environment.NETHER) {
            return name.endsWith(netherSuffix) ? Bukkit.getWorld(name.substring(0, name.length() - netherSuffix.length())) : null;
        }
        String base = name.endsWith(endSuffix) ? name.substring(0, name.length() - endSuffix.length()) : name;
        return Bukkit.getWorld(base + netherSuffix);
    }

    /** Nether-style coordinate scaling between two worlds, using each world's 'scale' property. */
    public Location scaled(Location from, World to) {
        MVWorld a = plugin.worlds().get(from.getWorld());
        MVWorld b = plugin.worlds().get(to);
        double sa = a != null ? a.getScale() : MVWorld.defaultScale(from.getWorld().getEnvironment());
        double sb = b != null ? b.getScale() : MVWorld.defaultScale(to.getEnvironment());
        double factor = sa / sb;
        WorldBorderClamp clamp = new WorldBorderClamp(to);
        double x = clamp.x(from.getX() * factor);
        double z = clamp.z(from.getZ() * factor);
        double y = Math.max(to.getMinHeight() + 1, Math.min(from.getY(), to.getMaxHeight() - 2));
        if (to.getEnvironment() == World.Environment.NETHER) y = Math.min(y, 120);
        return new Location(to, x, y, z, from.getYaw(), from.getPitch());
    }

    /** Vanilla's obsidian landing platform location in an end world. */
    public static Location endPlatform(World end) {
        return new Location(end, 100.5, 49, 0.5, 90f, 0f);
    }

    /** Keeps scaled coordinates inside the target's world border. */
    private record WorldBorderClamp(double minX, double maxX, double minZ, double maxZ) {
        WorldBorderClamp(World w) {
            this(w.getWorldBorder().getCenter().getX() - w.getWorldBorder().getSize() / 2 + 16,
                    w.getWorldBorder().getCenter().getX() + w.getWorldBorder().getSize() / 2 - 16,
                    w.getWorldBorder().getCenter().getZ() - w.getWorldBorder().getSize() / 2 + 16,
                    w.getWorldBorder().getCenter().getZ() + w.getWorldBorder().getSize() / 2 - 16);
        }

        double x(double v) {
            return Math.max(minX, Math.min(maxX, v));
        }

        double z(double v) {
            return Math.max(minZ, Math.min(maxZ, v));
        }
    }
}
