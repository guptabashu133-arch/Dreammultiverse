package org.mydreamduels.dreammultiverse.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.generator.VoidGenerator;
import org.mydreamduels.dreammultiverse.platform.PlatformSupport;
import org.mydreamduels.dreammultiverse.util.FileUtil;
import org.mydreamduels.dreammultiverse.util.GameRules;
import org.mydreamduels.dreammultiverse.util.SafeLocations;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Owns worlds.yml and every create/import/load/unload/delete/clone/regen
 * operation. All Bukkit world calls happen on the global thread (= main
 * thread on Paper/Spigot). On Folia the server refuses to create or
 * unload worlds at runtime, so those operations return FOLIA_* errors
 * instead - and /mv regen falls back to "reset the terrain on the next
 * restart" (see PendingResets), which works on every platform.
 */
public final class WorldManager {

    public static final String FOLIA_NO_LOAD = "This server (Folia) does not allow loading or creating worlds while it is running - "
            + "only the worlds it loads itself (level-name + its nether/end) exist. Use Paper for extra worlds, "
            + "or see the README for Folia options (bukkit.yml void generator).";
    public static final String FOLIA_NO_UNLOAD = "Folia does not allow unloading worlds while the server is running.";

    /** create() result prefix: not loaded yet, will exist after a restart (message follows). */
    public static final String PENDING = "~";

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    private final Dreammultiverse plugin;
    private final File file;
    private final Map<String, MVWorld> worlds = new ConcurrentHashMap<>();

    public WorldManager(Dreammultiverse plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "worlds.yml");
    }

    // ------------------------------------------------------------------ persistence

    public void load() {
        worlds.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("worlds");
        if (root != null) {
            for (String name : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(name);
                if (s != null) worlds.put(key(name), MVWorld.load(name, s, plugin.getLogger()));
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Dreammultiverse worlds - edit with /mv modify, or edit here and /mv reload."));
        for (MVWorld w : worlds.values()) {
            w.save(yaml.createSection("worlds." + w.getName()));
        }
        plugin.io().write(file, yaml.saveToString());
    }

    /**
     * Startup: adopt the server's own worlds, load every autoload world
     * (not possible on Folia - warns instead), then push properties.
     * Called from onEnable, i.e. on the main thread after the default worlds exist.
     */
    public void startup() {
        boolean changed = false;
        if (plugin.getConfig().getBoolean("auto-import-default-worlds", true)) {
            for (World w : Bukkit.getWorlds()) {
                if (!worlds.containsKey(key(w.getName()))) {
                    worlds.put(key(w.getName()), MVWorld.fromBukkit(w));
                    changed = true;
                }
            }
        }

        for (MVWorld mv : worlds.values()) {
            if (mv.isLoaded() || !mv.isAutoLoad()) continue;
            String error = loadNow(mv);
            if (error != null) plugin.getLogger().warning("Could not load world '" + mv.getName() + "': " + error);
        }

        // On Folia world state may only be changed from the global region thread.
        plugin.scheduler().runGlobal(() -> {
            for (MVWorld mv : worlds.values()) {
                World w = mv.getBukkitWorld();
                if (w != null) mv.applyTo(w);
            }
        });
        if (changed) save();
    }

    // ------------------------------------------------------------------ lookup

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public MVWorld get(String name) {
        return name == null ? null : worlds.get(key(name));
    }

    /** Same as get, but also matches a world's alias (colours ignored). */
    public MVWorld find(String nameOrAlias) {
        MVWorld w = get(nameOrAlias);
        if (w != null) return w;
        for (MVWorld mv : worlds.values()) {
            if (!mv.getAlias().isBlank() && org.mydreamduels.dreammultiverse.util.Msg.strip(mv.getAlias()).equalsIgnoreCase(nameOrAlias)) {
                return mv;
            }
        }
        return null;
    }

    public MVWorld get(World world) {
        return world == null ? null : worlds.get(key(world.getName()));
    }

    public Collection<MVWorld> all() {
        return worlds.values();
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (MVWorld w : worlds.values()) out.add(w.getName());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public List<String> loadedNames() {
        List<String> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) out.add(w.getName());
        return out;
    }

    /** Folders in the world container that look like a world but aren't loaded yet - /mv import suggestions. */
    public List<String> importableFolders() {
        List<String> out = new ArrayList<>();
        File[] dirs = Bukkit.getWorldContainer().listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File d : dirs) {
            if (new File(d, "level.dat").isFile() && Bukkit.getWorld(d.getName()) == null) out.add(d.getName());
        }
        return out;
    }

    public World defaultWorld() {
        return Bukkit.getWorlds().get(0);
    }

    public Location spawnOf(World world) {
        MVWorld mv = get(world);
        return mv != null ? mv.getSpawnLocation() : world.getSpawnLocation();
    }

    /** Ensures the server's own worlds (and any loaded by other plugins) always have an entry. */
    public MVWorld getOrAdopt(World world) {
        MVWorld mv = get(world);
        if (mv == null) {
            mv = MVWorld.fromBukkit(world);
            worlds.put(key(world.getName()), mv);
            save();
        }
        return mv;
    }

    // ------------------------------------------------------------------ create / import / load

    public String validateNewName(String name) {
        if (plugin.datapackWorlds().isPending(name)) return "'" + name + "' is already set up - restart the server to load it.";
        if (!VALID_NAME.matcher(name).matches()) return "World names may only use letters, numbers, _ - and .";
        if (get(name) != null || Bukkit.getWorld(name) != null) return "A world called '" + name + "' already exists.";
        return null;
    }

    /**
     * Must be called on the global/main thread. Returns null when created, an error, or
     * PENDING + message when the server can't add worlds at runtime and the world was set
     * up as a datapack dimension instead (Folia) - it appears after the next restart.
     */
    public String create(String name, World.Environment env, Long seed, String type, String generator, boolean structures, boolean adjustSpawn) {
        String invalid = validateNewName(name);
        if (invalid != null) return invalid;
        if (find(name) != null) return "A world called '" + name + "' already exists.";
        if (new File(Bukkit.getWorldContainer(), name).exists()) {
            return "A folder called '" + name + "' already exists - use /mv import to load it.";
        }
        MVWorld mv = new MVWorld(name);
        mv.setEnvironment(env);
        mv.setSeed(seed);
        mv.setWorldType(type == null ? "NORMAL" : type.toUpperCase(Locale.ROOT));
        boolean empty = VoidGenerator.isVoidSpec(generator);
        mv.setGenerator(empty ? VoidGenerator.normalize(generator) : generator);
        mv.setStructures(structures && !empty);
        // nothing to stand on in a void world, so there's no "safe" spawn to move to
        mv.setAdjustSpawn(adjustSpawn && !empty);
        String error = loadNew(mv);
        if (FOLIA_NO_LOAD.equals(error) && plugin.getConfig().getBoolean("startup-worlds", true)) {
            return createAtStartup(name, env, seed, type, generator, empty);
        }
        return error;
    }

    /** Folia path - see DatapackWorlds. */
    private String createAtStartup(String name, World.Environment env, Long seed, String type, String generator, boolean empty) {
        DatapackWorlds packs = plugin.datapackWorlds();
        String worldName = packs.worldName(name);
        if (get(worldName) != null || Bukkit.getWorld(worldName) != null) return "A world called '" + name + "' already exists.";
        String dimType = empty ? "VOID" : (type == null ? "NORMAL" : type);
        String pluginGenerator = empty ? null : generator;
        String error = packs.create(name, env, dimType, empty ? VoidGenerator.biomeOf(VoidGenerator.normalize(generator)) : null, pluginGenerator);
        if (error != null) return error;

        MVWorld mv = new MVWorld(worldName);
        mv.setEnvironment(env);
        mv.setAlias(name);
        mv.setWorldType(empty ? "NORMAL" : (type == null ? "NORMAL" : type));
        mv.setGenerator(empty ? VoidGenerator.normalize(generator) : pluginGenerator);
        mv.setStructures(!empty);
        mv.setAdjustSpawn(!empty);
        worlds.put(key(worldName), mv);
        save();
        return PENDING + "This server can't add worlds while running, so '" + name + "' was set up to load at startup. "
                + "Restart the server, then use /mv tp " + name + " (its full name is " + worldName + ")."
                + (seed != null ? " Note: worlds made this way share the main world's seed, so -s was ignored." : "");
    }

    public String importWorld(String name, World.Environment env, String generator) {
        if (Bukkit.getWorld(name) != null) {
            MVWorld existing = get(name);
            if (existing == null) {
                getOrAdopt(Bukkit.getWorld(name));
                return null;
            }
            return "'" + name + "' is already loaded and managed.";
        }
        if (get(name) != null) return "'" + name + "' is already managed - use /mv load " + name;
        File folder = new File(Bukkit.getWorldContainer(), name);
        if (!new File(folder, "level.dat").isFile()) return "No world folder (with a level.dat) called '" + name + "' was found.";
        MVWorld mv = new MVWorld(name);
        mv.setEnvironment(env);
        mv.setGenerator(VoidGenerator.isVoidSpec(generator) ? VoidGenerator.normalize(generator) : generator);
        mv.setAdjustSpawn(false);
        return loadNew(mv);
    }

    private String loadNew(MVWorld mv) {
        String error = loadNow(mv);
        if (error != null) return error;
        worlds.put(key(mv.getName()), mv);
        World world = mv.getBukkitWorld();
        mv.setSeed(world.getSeed());
        if (mv.isAdjustSpawn()) {
            Location safe = SafeLocations.find(world.getSpawnLocation());
            if (safe != null) world.setSpawnLocation(safe);
        }
        mv.applyTo(world);
        save();
        return null;
    }

    /** Loads a known-but-unloaded world. Global/main thread only. */
    public String load(String name) {
        MVWorld mv = get(name);
        if (mv == null) return "Unknown world '" + name + "'.";
        if (mv.isLoaded()) return "'" + mv.getName() + "' is already loaded.";
        String error = loadNow(mv);
        if (error == null) mv.applyTo(mv.getBukkitWorld());
        return error;
    }

    private String loadNow(MVWorld mv) {
        WorldCreator creator = new WorldCreator(mv.getName()).environment(mv.getEnvironment()).generateStructures(mv.isStructures());
        if (mv.getSeed() != null) creator.seed(mv.getSeed());
        WorldType type = parseType(mv.getWorldType());
        if (type != null) creator.type(type);
        if (VoidGenerator.isVoidSpec(mv.getGenerator())) {
            VoidGenerator gen = new VoidGenerator(VoidGenerator.biomeOf(mv.getGenerator()));
            creator.generator(gen).biomeProvider(gen.getDefaultBiomeProvider(null)).generateStructures(false);
        } else if (mv.getGenerator() != null && !mv.getGenerator().isBlank()) {
            ChunkGenerator gen = WorldCreator.getGeneratorForName(mv.getName(), mv.getGenerator(), Bukkit.getConsoleSender());
            if (gen == null) return "Generator '" + mv.getGenerator() + "' was not found (is its plugin installed?).";
            creator.generator(gen);
        }
        try {
            World world = creator.createWorld();
            return world == null ? "The server refused to load the world (see console)." : null;
        } catch (UnsupportedOperationException e) {
            return FOLIA_NO_LOAD;
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to load world " + mv.getName(), e);
            return "Error while loading the world: " + e.getMessage();
        }
    }

    /**
     * Accepts the enum names (NORMAL, FLAT, LARGE_BIOMES, AMPLIFIED) as well as
     * Bukkit's own internal names (DEFAULT, LARGEBIOMES) - getByName only knows the latter.
     */
    public static WorldType parseType(String name) {
        if (name == null) return null;
        if (name.equalsIgnoreCase("void")) return WorldType.NORMAL; // the void generator decides the terrain
        String n = name.toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return WorldType.valueOf(n);
        } catch (IllegalArgumentException e) {
            return WorldType.getByName(n.replace("_", ""));
        }
    }

    // ------------------------------------------------------------------ unload / remove / delete

    /** Moves everyone out, then unloads. done receives null on success or an error. */
    public void unload(String name, boolean save, Consumer<String> done) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            done.accept("'" + name + "' is not loaded.");
            return;
        }
        if (PlatformSupport.isFolia()) {
            done.accept(FOLIA_NO_UNLOAD);
            return;
        }
        if (world.equals(defaultWorld())) {
            done.accept("The server's main world can't be unloaded.");
            return;
        }
        evacuate(world, () -> {
            boolean ok;
            try {
                ok = Bukkit.unloadWorld(world, save);
            } catch (UnsupportedOperationException e) {
                done.accept(FOLIA_NO_UNLOAD);
                return;
            }
            done.accept(ok ? null : "The server refused to unload '" + name + "' (another plugin may be blocking it).");
        });
    }

    /** Forget the world (unloading it first if needed) but keep its files. */
    public void remove(String name, Consumer<String> done) {
        MVWorld mv = get(name);
        if (mv == null) {
            done.accept("Unknown world '" + name + "'.");
            return;
        }
        Runnable forget = () -> {
            worlds.remove(key(mv.getName()));
            save();
            done.accept(null);
        };
        if (!mv.isLoaded()) {
            forget.run();
            return;
        }
        unload(mv.getName(), true, error -> {
            if (error != null) done.accept(error);
            else forget.run();
        });
    }

    /** Unload (if loaded), forget, and delete the folder from disk. */
    public void delete(String name, Consumer<String> done) {
        MVWorld mv = get(name);
        World loaded = Bukkit.getWorld(name);
        File folder = loaded != null ? loaded.getWorldFolder() : new File(Bukkit.getWorldContainer(), mv != null ? mv.getName() : name);
        String unsafe = checkDeletable(folder, loaded);
        if (unsafe != null) {
            done.accept(unsafe);
            return;
        }
        if (mv == null && !folder.exists()) {
            done.accept("Unknown world '" + name + "'.");
            return;
        }
        Runnable wipe = () -> {
            if (mv != null) {
                worlds.remove(key(mv.getName()));
                save();
            }
            plugin.io().submit(() -> {
                String result = null;
                try {
                    FileUtil.deleteRecursively(folder);
                } catch (IOException e) {
                    result = "Could not delete every file (" + e.getMessage() + ") - check the console.";
                    plugin.getLogger().warning("Deleting " + folder + " failed: " + e);
                }
                String r = result;
                plugin.scheduler().runGlobal(() -> done.accept(r));
            });
        };
        if (loaded == null) {
            wipe.run();
            return;
        }
        if (PlatformSupport.isFolia()) {
            if (plugin.datapackWorlds().isDatapackWorld(loaded.getName())) {
                plugin.datapackWorlds().remove(loaded.getName());
                String err = plugin.pendingResets().scheduleFolderDelete(folder);
                if (err != null) {
                    done.accept(err);
                    return;
                }
                if (mv != null) {
                    worlds.remove(key(mv.getName()));
                    save();
                }
                done.accept(PENDING + "'" + loaded.getName() + "' will be gone after the next restart.");
                return;
            }
            done.accept(FOLIA_NO_UNLOAD + " Use /mv regen " + loaded.getName() + " to reset it on the next restart instead.");
            return;
        }
        unload(loaded.getName(), false, error -> {
            if (error != null) done.accept(error);
            else wipe.run();
        });
    }

    /** Refuses anything that isn't plainly a world folder inside the world container. */
    private String checkDeletable(File folder, World self) {
        File container = Bukkit.getWorldContainer();
        if (!FileUtil.isInside(folder, container) || FileUtil.isInside(container, folder)) {
            return "Refusing to delete '" + folder + "' - it is not a world folder inside the server directory.";
        }
        if (FileUtil.isInside(plugin.getDataFolder(), folder)) return "Refusing to delete a folder that contains plugin data.";
        for (World other : Bukkit.getWorlds()) {
            if (other.equals(self)) continue;
            if (FileUtil.isInside(other.getWorldFolder(), folder)) {
                return "Refusing to delete '" + folder.getName() + "' - world '" + other.getName() + "' is stored inside it.";
            }
        }
        if (self != null && self.equals(defaultWorld())) return "The server's main world can't be deleted while running.";
        return null;
    }

    // ------------------------------------------------------------------ clone

    public void cloneWorld(String sourceName, String targetName, Consumer<String> done) {
        MVWorld source = get(sourceName);
        World sourceWorld = Bukkit.getWorld(sourceName);
        File sourceFolder = sourceWorld != null ? sourceWorld.getWorldFolder() : new File(Bukkit.getWorldContainer(), sourceName);
        if (!new File(sourceFolder, "level.dat").isFile()) {
            done.accept("No world called '" + sourceName + "' was found.");
            return;
        }
        String invalid = validateNewName(targetName);
        if (invalid != null) {
            done.accept(invalid);
            return;
        }
        File targetFolder = new File(Bukkit.getWorldContainer(), targetName);
        if (targetFolder.exists()) {
            done.accept("A folder called '" + targetName + "' already exists.");
            return;
        }
        if (sourceWorld != null) sourceWorld.save();

        plugin.io().submit(() -> {
            try {
                FileUtil.copyWorld(sourceFolder, targetFolder);
            } catch (IOException e) {
                plugin.getLogger().warning("Cloning " + sourceName + " failed: " + e);
                plugin.scheduler().runGlobal(() -> done.accept("Copying the world files failed: " + e.getMessage()));
                return;
            }
            plugin.scheduler().runGlobal(() -> {
                MVWorld clone = new MVWorld(targetName);
                if (source != null) {
                    clone.setEnvironment(source.getEnvironment());
                    clone.setGenerator(source.getGenerator());
                    clone.setWorldType(source.getWorldType());
                    clone.setStructures(source.isStructures());
                    clone.setSeed(source.getSeed());
                    clone.copyPropertiesFrom(source);
                } else if (sourceWorld != null) {
                    clone.setEnvironment(sourceWorld.getEnvironment());
                }
                clone.setAdjustSpawn(false);
                String error = loadNew(clone);
                if (error != null) {
                    plugin.io().submit(() -> {
                        try {
                            FileUtil.deleteRecursively(targetFolder);
                        } catch (IOException ignored) {
                        }
                    });
                }
                if (error == null && source != null) clone.setAdjustSpawn(source.isAdjustSpawn());
                if (error == null) save();
                done.accept(error);
            });
        });
    }

    // ------------------------------------------------------------------ regen

    public enum SeedMode { SAME, RANDOM, CUSTOM }

    /**
     * Unload, wipe and recreate a world. Worlds that can't be unloaded right
     * now (Folia, or the main world anywhere) get their terrain reset on the
     * next restart instead - done's message says which happened.
     */
    public void regen(String name, SeedMode seedMode, Long customSeed, boolean keepGameRules, Consumer<String> done) {
        MVWorld mv = get(name);
        World world = Bukkit.getWorld(name);
        if (mv == null || world == null) {
            done.accept("!'" + name + "' must be a loaded, managed world.");
            return;
        }

        if (PlatformSupport.isFolia() || world.equals(defaultWorld())) {
            if (seedMode != SeedMode.SAME) {
                done.accept("!This world can only be reset on restart, which keeps the server's own seed - leave out the seed option.");
                return;
            }
            String error = plugin.pendingResets().schedule(world);
            done.accept(error != null ? "!" + error
                    : "Terrain of '" + world.getName() + "' will be regenerated on the next server restart.");
            return;
        }

        Map<org.bukkit.GameRule<?>, Object> rules = keepGameRules ? GameRules.snapshot(world) : Map.of();
        File folder = world.getWorldFolder();
        String unsafe = checkDeletable(folder, world);
        if (unsafe != null) {
            done.accept("!" + unsafe);
            return;
        }

        unload(world.getName(), false, error -> {
            if (error != null) {
                done.accept("!" + error);
                return;
            }
            plugin.io().submit(() -> {
                try {
                    FileUtil.deleteRecursively(folder);
                } catch (IOException e) {
                    plugin.getLogger().warning("Regen: could not wipe " + folder + ": " + e);
                }
                plugin.scheduler().runGlobal(() -> {
                    if (seedMode == SeedMode.RANDOM) mv.setSeed(new Random().nextLong());
                    else if (seedMode == SeedMode.CUSTOM) mv.setSeed(customSeed);
                    String loadError = loadNow(mv);
                    if (loadError != null) {
                        done.accept("!" + loadError);
                        return;
                    }
                    World fresh = mv.getBukkitWorld();
                    mv.setSeed(fresh.getSeed());
                    GameRules.restore(fresh, rules);
                    if (mv.isAdjustSpawn()) {
                        Location safe = SafeLocations.find(fresh.getSpawnLocation());
                        if (safe != null) fresh.setSpawnLocation(safe);
                    }
                    mv.applyTo(fresh);
                    save();
                    done.accept("'" + mv.getName() + "' has been regenerated (seed " + fresh.getSeed() + ").");
                });
            });
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Teleports every player out of a world to the main world's spawn, then runs next on the global thread. */
    private void evacuate(World world, Runnable next) {
        Location target = spawnOf(defaultWorld());
        List<CompletableFuture<Boolean>> pending = new ArrayList<>();
        for (Player p : new ArrayList<>(world.getPlayers())) {
            org.mydreamduels.dreammultiverse.util.Msg.send(p, "&eThis world is being unloaded - moving you to spawn.");
            pending.add(plugin.teleports().teleportRaw(p, target));
        }
        if (pending.isEmpty()) {
            plugin.scheduler().runGlobal(next);
            return;
        }
        CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                .whenComplete((v, t) -> plugin.scheduler().runGlobalDelayed(next, 2L));
    }

    /** Plugins that provide a world generator (override getDefaultWorldGenerator). */
    public List<String> generatorPlugins() {
        List<String> out = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            for (Class<?> c = p.getClass(); c != null && c != JavaPlugin.class; c = c.getSuperclass()) {
                try {
                    c.getDeclaredMethod("getDefaultWorldGenerator", String.class, String.class);
                    out.add(p.getName());
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Multiverse-Core migration

    /**
     * Copies world entries from an existing Multiverse-Core install
     * (v4 "worlds:" layout or v5 flat layout). Read with plain SnakeYAML
     * because v4 files carry "==: MVWorld" type tags that Bukkit's YAML
     * loader would try (and fail) to deserialize. Returns how many were imported.
     */
    @SuppressWarnings("unchecked")
    public int migrateFromMultiverse(List<String> log) {
        File mvFile = new File(plugin.getDataFolder().getParentFile(), "Multiverse-Core/worlds.yml");
        if (!mvFile.isFile()) {
            log.add("plugins/Multiverse-Core/worlds.yml was not found.");
            return 0;
        }
        Object rootObj;
        try (Reader reader = new FileReader(mvFile, StandardCharsets.UTF_8)) {
            rootObj = new org.yaml.snakeyaml.Yaml().load(reader);
        } catch (Exception e) {
            log.add("Could not read Multiverse's worlds.yml: " + e.getMessage());
            return 0;
        }
        if (!(rootObj instanceof Map<?, ?> root)) return 0;
        Map<String, Object> entries = root.get("worlds") instanceof Map<?, ?> v4 ? (Map<String, Object>) v4 : (Map<String, Object>) root;

        int count = 0;
        for (Map.Entry<String, Object> e : entries.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> raw)) continue;
            String name = e.getKey();
            if (get(name) != null) {
                log.add("Skipped '" + name + "' (already managed).");
                continue;
            }
            Map<String, Object> flat = new LinkedHashMap<>();
            flatten("", (Map<String, Object>) raw, flat);
            MVWorld mv = new MVWorld(name);
            Object env = pick(flat, "environment");
            if (env != null) {
                try {
                    mv.setEnvironment(World.Environment.valueOf(env.toString().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            Object seed = pick(flat, "seed");
            if (seed instanceof Number n) mv.setSeed(n.longValue());
            Object gen = pick(flat, "generator");
            if (gen != null && !gen.toString().isBlank() && !gen.toString().equals("null")) mv.setGenerator(gen.toString());

            String[][] mapping = {
                    {"alias", "alias"}, {"gamemode", "gamemode"}, {"difficulty", "difficulty"}, {"pvp", "pvp"},
                    {"hunger", "hunger"}, {"autoheal", "autoheal"}, {"allowflight", "allowflight"},
                    {"allowweather", "weather"}, {"playerlimit", "playerlimit"}, {"respawnworld", "respawnworld"},
                    {"bedrespawn", "bedrespawn"}, {"adjustspawn", "adjustspawn"}, {"autoload", "autoload"},
                    {"hidden", "hidden"}, {"portalform", "portalform"}, {"scale", "scale"},
                    {"spawning.animals.spawn", "animals"}, {"spawning.monsters.spawn", "monsters"},
                    {"entryfee.amount", "entryfee"},
            };
            for (String[] m : mapping) {
                Object v = pick(flat, m[0]);
                if (v == null) continue;
                try {
                    MVWorld.property(m[1]).setter().accept(mv, v.toString());
                } catch (RuntimeException ignored) {
                }
            }
            worlds.put(key(name), mv);
            count++;
            log.add("Imported '" + name + "' (" + mv.getEnvironment().name().toLowerCase(Locale.ROOT) + ").");
        }
        if (count > 0) {
            save();
            for (MVWorld mv : worlds.values()) {
                if (!mv.isLoaded() && mv.isAutoLoad()) {
                    String error = loadNow(mv);
                    if (error != null) log.add("Could not load '" + mv.getName() + "': " + error);
                    else mv.applyTo(mv.getBukkitWorld());
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> in, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String k = (prefix + e.getKey()).toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            if (e.getValue() instanceof Map<?, ?> m) flatten(prefix + e.getKey() + ".", (Map<String, Object>) m, out);
            else out.put(k, e.getValue());
        }
    }

    private static Object pick(Map<String, Object> flat, String key) {
        return flat.get(key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", ""));
    }
}
