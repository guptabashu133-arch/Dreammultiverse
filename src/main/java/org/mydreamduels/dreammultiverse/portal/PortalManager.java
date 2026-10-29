package org.mydreamduels.dreammultiverse.portal;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mydreamduels.dreammultiverse.Dreammultiverse;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** portals.yml plus fast "which portal is this block in" lookups for the move listener. */
public final class PortalManager {

    private final Dreammultiverse plugin;
    private final File file;
    private final Map<String, Portal> portals = new ConcurrentHashMap<>();
    /** world name -> portals in it; rebuilt on every change so lookups never lock. */
    private volatile Map<String, List<Portal>> byWorld = Map.of();

    public PortalManager(Dreammultiverse plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "portals.yml");
    }

    public void load() {
        portals.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("portals");
        if (root != null) {
            for (String name : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(name);
                if (s != null) portals.put(name.toLowerCase(Locale.ROOT), Portal.load(name, s));
            }
        }
        reindex();
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Portal p : portals.values()) p.save(yaml.createSection("portals." + p.getName()));
        plugin.io().write(file, yaml.saveToString());
        reindex();
    }

    private void reindex() {
        Map<String, List<Portal>> index = new HashMap<>();
        for (Portal p : portals.values()) index.computeIfAbsent(p.getWorld(), k -> new ArrayList<>()).add(p);
        byWorld = index;
    }

    public Portal get(String name) {
        return name == null ? null : portals.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Portal> all() {
        return portals.values();
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Portal p : portals.values()) out.add(p.getName());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public void add(Portal portal) {
        portals.put(portal.getName().toLowerCase(Locale.ROOT), portal);
        save();
    }

    public boolean remove(String name) {
        boolean removed = portals.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) save();
        return removed;
    }

    public Portal at(Location loc) {
        if (loc.getWorld() == null) return null;
        List<Portal> list = byWorld.get(loc.getWorld().getName());
        if (list == null) return null;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        for (Portal p : list) if (p.contains(x, y, z)) return p;
        return null;
    }

    public Portal at(World world, int x, int y, int z) {
        List<Portal> list = byWorld.get(world.getName());
        if (list == null) return null;
        for (Portal p : list) if (p.contains(x, y, z)) return p;
        return null;
    }

    /**
     * Fills the portal's empty space (or its previous fill) with a material.
     * Done chunk by chunk on each chunk's own region thread so it works on Folia.
     * done gets the number of blocks changed.
     */
    public void fill(Portal portal, Material material, Consumer<Integer> done) {
        World world = plugin.getServer().getWorld(portal.getWorld());
        if (world == null) {
            done.accept(-1);
            return;
        }
        Material previous = portal.getFill().isBlank() ? null : Material.matchMaterial(portal.getFill());
        BlockData data = material.createBlockData();
        if (data instanceof Orientable orientable && material == Material.NETHER_PORTAL) {
            orientable.setAxis(portal.getMaxX() == portal.getMinX() ? org.bukkit.Axis.Z : org.bukkit.Axis.X);
        }

        List<int[]> chunks = new ArrayList<>();
        for (int cx = portal.getMinX() >> 4; cx <= portal.getMaxX() >> 4; cx++) {
            for (int cz = portal.getMinZ() >> 4; cz <= portal.getMaxZ() >> 4; cz++) chunks.add(new int[]{cx, cz});
        }
        AtomicInteger remaining = new AtomicInteger(chunks.size());
        AtomicInteger changed = new AtomicInteger();
        for (int[] c : chunks) {
            Location anchor = new Location(world, (c[0] << 4) + 8, portal.getMinY(), (c[1] << 4) + 8);
            plugin.scheduler().runAtLocation(anchor, () -> {
                int x0 = Math.max(portal.getMinX(), c[0] << 4), x1 = Math.min(portal.getMaxX(), (c[0] << 4) + 15);
                int z0 = Math.max(portal.getMinZ(), c[1] << 4), z1 = Math.min(portal.getMaxZ(), (c[1] << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int y = portal.getMinY(); y <= portal.getMaxY(); y++) {
                        for (int z = z0; z <= z1; z++) {
                            Block b = world.getBlockAt(x, y, z);
                            Material t = b.getType();
                            if (t.isAir() || t == previous || isFillMaterial(t)) {
                                b.setBlockData(data, false);
                                changed.incrementAndGet();
                            }
                        }
                    }
                }
                if (remaining.decrementAndGet() == 0) {
                    portal.setFill(material.isAir() ? "" : material.name());
                    save();
                    done.accept(changed.get());
                }
            });
        }
    }

    public static boolean isFillMaterial(Material m) {
        return m == Material.WATER || m == Material.LAVA || m == Material.NETHER_PORTAL || m == Material.END_PORTAL
                || m == Material.END_GATEWAY || m == Material.COBWEB;
    }
}
