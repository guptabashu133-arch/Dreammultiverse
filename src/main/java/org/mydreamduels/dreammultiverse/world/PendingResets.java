package org.mydreamduels.dreammultiverse.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Regenerate on next restart" - the only way to reset a world that can't
 * be unloaded while the server runs (every world on Folia, and the main
 * world everywhere). Only terrain folders (region/entities/poi) are
 * scheduled, never level.dat or playerdata, so player inventories, stats
 * and the seed survive. The folders are wiped in onLoad, which every
 * platform calls before any world is loaded.
 */
public final class PendingResets {

    private static final String[] TERRAIN = {"region", "entities", "poi"};

    private final Dreammultiverse plugin;
    private final File file;

    public PendingResets(Dreammultiverse plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pending-resets.yml");
    }

    /** Returns an error, or null once scheduled. */
    public synchronized String schedule(World world) {
        File base = world.getWorldFolder();
        File dimension = switch (world.getEnvironment()) {
            case NETHER -> new File(base, "DIM-1");
            case THE_END -> new File(base, "DIM1");
            default -> base;
        };
        if (!dimension.isDirectory()) dimension = base;

        List<String> targets = new ArrayList<>();
        for (String t : TERRAIN) {
            File dir = new File(dimension, t);
            for (World other : Bukkit.getWorlds()) {
                if (!other.equals(world) && FileUtil.isInside(other.getWorldFolder(), dir)) {
                    return "World '" + other.getName() + "' is stored inside " + dir + " - refusing to reset it.";
                }
            }
            if (!FileUtil.isInside(dir, Bukkit.getWorldContainer())) return "Unexpected world folder location: " + dir;
            targets.add(dir.getAbsolutePath());
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> all = new ArrayList<>(yaml.getStringList("folders"));
        for (String t : targets) if (!all.contains(t)) all.add(t);
        yaml.set("folders", all);
        plugin.io().write(file, yaml.saveToString());
        return null;
    }

    /** Whole world folder, removed on the next startup (Folia datapack worlds). */
    public synchronized String scheduleFolderDelete(File folder) {
        if (!FileUtil.isInside(folder, Bukkit.getWorldContainer()) || FileUtil.isInside(Bukkit.getWorldContainer(), folder)) {
            return "Unexpected world folder location: " + folder;
        }
        for (World other : Bukkit.getWorlds()) {
            if (!other.getWorldFolder().equals(folder) && FileUtil.isInside(other.getWorldFolder(), folder)) {
                return "World '" + other.getName() + "' is stored inside " + folder + " - refusing to delete it.";
            }
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> all = new ArrayList<>(yaml.getStringList("folders"));
        if (!all.contains(folder.getAbsolutePath())) all.add(folder.getAbsolutePath());
        yaml.set("folders", all);
        plugin.io().write(file, yaml.saveToString());
        return null;
    }

    public synchronized boolean cancel(World world) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> all = new ArrayList<>(yaml.getStringList("folders"));
        String base = world.getWorldFolder().getAbsolutePath();
        boolean removed = all.removeIf(p -> p.startsWith(base + File.separator));
        yaml.set("folders", all);
        plugin.io().write(file, yaml.saveToString());
        return removed;
    }

    /** Called from onLoad - before any world exists. Runs synchronously on purpose. */
    public void processOnStartup() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String path : yaml.getStringList("folders")) {
            File dir = new File(path);
            if (!FileUtil.isInside(dir, Bukkit.getWorldContainer()) || !dir.exists()) continue;
            try {
                FileUtil.deleteRecursively(dir);
                plugin.getLogger().info("Regenerating: wiped " + dir);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not wipe " + dir + ": " + e);
            }
        }
        if (!file.delete()) plugin.getLogger().warning("Could not remove " + file.getName());
    }
}
