package org.mydreamduels.dreammultiverse.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Extra worlds for servers that can't create worlds at runtime (Folia).
 *
 * Vanilla loads every dimension a datapack defines when the server
 * starts, and Paper/Folia turn each one into a real Bukkit world named
 * "<level-name>_<namespace>_<id>" (see PaperWorldLoader). So instead of
 * createWorld, Dreammultiverse writes a small datapack into the main
 * world's datapacks folder; after the next restart the world simply
 * exists. bukkit.yml's per-world "generator" is honoured for these worlds
 * too, which is how -g Plugin generators work here.
 */
public final class DatapackWorlds {

    public static final String NAMESPACE = "dmv";
    private static final Pattern ID = Pattern.compile("[a-z0-9_.\\-]{1,48}");

    private final Dreammultiverse plugin;

    public DatapackWorlds(Dreammultiverse plugin) {
        this.plugin = plugin;
    }

    private World main() {
        return Bukkit.getWorlds().get(0);
    }

    private File packDir() {
        return new File(main().getWorldFolder(), "datapacks/dreammultiverse");
    }

    private File dimensionFile(String id) {
        return new File(packDir(), "data/" + NAMESPACE + "/dimension/" + id + ".json");
    }

    public static String idOf(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public String validate(String name) {
        return ID.matcher(idOf(name)).matches() ? null : "World names here may only use letters, numbers, _ - and .";
    }

    /** The Bukkit world name the server will give this dimension. */
    public String worldName(String name) {
        return main().getName() + "_" + NAMESPACE + "_" + idOf(name);
    }

    public boolean isDatapackWorld(String worldName) {
        return worldName.startsWith(main().getName() + "_" + NAMESPACE + "_");
    }

    public boolean isPending(String name) {
        return dimensionFile(idOf(name)).isFile();
    }

    /** Writes the dimension. type = NORMAL, FLAT, LARGE_BIOMES, AMPLIFIED or VOID. Returns an error or null. */
    public String create(String name, World.Environment env, String type, String voidBiome, String generatorPlugin) {
        String invalid = validate(name);
        if (invalid != null) return invalid;
        String id = idOf(name);
        if (dimensionFile(id).exists()) return "'" + name + "' is already set up - restart the server to load it.";
        try {
            writePackMeta();
            FileUtil.writeAtomically(dimensionFile(id), dimensionJson(env, type, voidBiome));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write datapack dimension: " + e);
            return "Could not write the datapack: " + e.getMessage();
        }
        if (generatorPlugin != null && !generatorPlugin.isBlank()) {
            String err = setBukkitYmlGenerator(worldName(name), generatorPlugin);
            if (err != null) return err;
        }
        return null;
    }

    /** Removes the dimension so it's no longer loaded after the next restart. */
    public boolean remove(String worldName) {
        String prefix = main().getName() + "_" + NAMESPACE + "_";
        if (!worldName.startsWith(prefix)) return false;
        File f = dimensionFile(worldName.substring(prefix.length()));
        boolean removed = f.delete();
        setBukkitYmlGenerator(worldName, null);
        return removed;
    }

    // ------------------------------------------------------------------ json

    private static String dimensionJson(World.Environment env, String type, String voidBiome) {
        String dimType = switch (env) {
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
        String generator;
        String t = type == null ? "NORMAL" : type.toUpperCase(Locale.ROOT);
        if (t.equals("VOID")) {
            String biome = voidBiome == null || voidBiome.isBlank() ? "minecraft:plains"
                    : (voidBiome.contains(":") ? voidBiome : "minecraft:" + voidBiome).toLowerCase(Locale.ROOT);
            // flat with no layers and no features = not a single block
            generator = "{\"type\":\"minecraft:flat\",\"settings\":{\"biome\":\"" + biome
                    + "\",\"layers\":[],\"lakes\":false,\"features\":false,\"structure_overrides\":[]}}";
        } else if (t.equals("FLAT")) {
            generator = "{\"type\":\"minecraft:flat\",\"settings\":{\"biome\":\"minecraft:plains\",\"layers\":["
                    + "{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},"
                    + "{\"block\":\"minecraft:grass_block\",\"height\":1}],\"lakes\":false,\"features\":false,\"structure_overrides\":[]}}";
        } else if (env == World.Environment.NETHER) {
            generator = "{\"type\":\"minecraft:noise\",\"settings\":\"minecraft:nether\","
                    + "\"biome_source\":{\"type\":\"minecraft:multi_noise\",\"preset\":\"minecraft:nether\"}}";
        } else if (env == World.Environment.THE_END) {
            generator = "{\"type\":\"minecraft:noise\",\"settings\":\"minecraft:end\",\"biome_source\":{\"type\":\"minecraft:the_end\"}}";
        } else {
            String settings = switch (t) {
                case "LARGE_BIOMES" -> "minecraft:large_biomes";
                case "AMPLIFIED" -> "minecraft:amplified";
                default -> "minecraft:overworld";
            };
            generator = "{\"type\":\"minecraft:noise\",\"settings\":\"" + settings
                    + "\",\"biome_source\":{\"type\":\"minecraft:multi_noise\",\"preset\":\"minecraft:overworld\"}}";
        }
        return "{\n  \"type\": \"" + dimType + "\",\n  \"generator\": " + generator + "\n}\n";
    }

    /** pack.mcmeta in whichever schema this server version expects (it changed in 1.21.9). */
    private void writePackMeta() throws IOException {
        int format = packFormat();
        String body = format >= 82
                ? "\"description\": \"Dreammultiverse worlds\", \"min_format\": " + format + ", \"max_format\": " + format
                : "\"description\": \"Dreammultiverse worlds\", \"pack_format\": " + format;
        FileUtil.writeAtomically(new File(packDir(), "pack.mcmeta"), "{\n  \"pack\": { " + body + " }\n}\n");
    }

    private static final Map<String, Integer> FORMATS = Map.ofEntries(
            Map.entry("1.20.5", 41), Map.entry("1.20.6", 41), Map.entry("1.21", 48), Map.entry("1.21.1", 48),
            Map.entry("1.21.2", 57), Map.entry("1.21.3", 57), Map.entry("1.21.4", 61), Map.entry("1.21.5", 71),
            Map.entry("1.21.6", 80), Map.entry("1.21.7", 81), Map.entry("1.21.8", 81), Map.entry("1.21.9", 88),
            Map.entry("1.21.10", 88), Map.entry("1.21.11", 94));

    private int packFormat() {
        Integer known = FORMATS.get(Bukkit.getMinecraftVersion());
        if (known != null) return known;
        // Newer than this table: ask the server itself (Mojang-mapped since Paper 1.20.5)
        try {
            Object version = Class.forName("net.minecraft.SharedConstants").getMethod("getCurrentVersion").invoke(null);
            Class<?> packType = Class.forName("net.minecraft.server.packs.PackType");
            Object serverData = packType.getField("SERVER_DATA").get(null);
            for (Method m : version.getClass().getMethods()) {
                if ((m.getName().equals("packVersion") || m.getName().equals("getPackVersion")) && m.getParameterCount() == 1) {
                    Object v = m.invoke(version, serverData);
                    if (v instanceof Integer i) return i;
                    return (int) v.getClass().getMethod("major").invoke(v);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not detect the datapack format, assuming 94: " + t);
        }
        return 94;
    }

    // ------------------------------------------------------------------ bukkit.yml

    /** Paper reads worlds.<name>.generator from bukkit.yml for every world it loads at startup. */
    private String setBukkitYmlGenerator(String worldName, String generator) {
        File file = new File("bukkit.yml");
        if (!file.isFile()) return generator == null ? null : "bukkit.yml not found - can't set the generator.";
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (generator == null && !yaml.contains("worlds." + worldName)) return null;
        yaml.set("worlds." + worldName + ".generator", generator);
        if (generator == null && yaml.getConfigurationSection("worlds." + worldName) != null
                && yaml.getConfigurationSection("worlds." + worldName).getKeys(false).isEmpty()) {
            yaml.set("worlds." + worldName, null);
        }
        try {
            yaml.save(file);
            return null;
        } catch (IOException e) {
            return "Could not update bukkit.yml: " + e.getMessage();
        }
    }
}
