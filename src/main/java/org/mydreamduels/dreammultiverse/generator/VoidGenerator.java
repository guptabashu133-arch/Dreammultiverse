package org.mydreamduels.dreammultiverse.generator;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Completely empty world - not a single block, no bedrock, no mobs, no
 * structures. Meant for pasting a lobby/spawn/arena with WorldEdit.
 *
 * Usable three ways:
 *   /mv create spawn normal -t void
 *   /mv create spawn normal -g void            (or -g Dreammultiverse:void)
 *   bukkit.yml  worlds: <name>: generator: Dreammultiverse:void   (also works for Folia's main world)
 * An id after the colon picks the biome, e.g. Dreammultiverse:the_void (default plains, so
 * grass/leaves in pasted builds keep their normal colours).
 */
public final class VoidGenerator extends ChunkGenerator {

    /** Spawn of a fresh void world - players arrive here until /mv setspawn is used. */
    public static final int SPAWN_Y = 64;

    private final Biome biome;

    public VoidGenerator(String biomeId) {
        this.biome = parseBiome(biomeId);
    }

    @SuppressWarnings("deprecation")
    private static Biome parseBiome(String id) {
        if (id == null || id.isBlank()) return Biome.PLAINS;
        String key = id.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        Biome b = Registry.BIOME.get(NamespacedKey.minecraft(key));
        return b == null ? Biome.PLAINS : b;
    }

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateBedrock() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
    @Override public boolean isParallelCapable() { return true; }

    @Override
    public boolean canSpawn(World world, int x, int z) {
        return true;
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, SPAWN_Y, 0.5);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo info, int x, int y, int z) {
                return biome;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo info) {
                return List.of(biome);
            }
        };
    }

    /** "void", "Dreammultiverse", "Dreammultiverse:void", "Dreammultiverse:the_void"... */
    public static boolean isVoidSpec(String generator) {
        if (generator == null) return false;
        String g = generator.toLowerCase(Locale.ROOT);
        return g.equals("void") || g.startsWith("void:") || g.equals("dreammultiverse") || g.startsWith("dreammultiverse:");
    }

    /** Canonical form stored in worlds.yml. */
    public static String normalize(String generator) {
        String g = generator.toLowerCase(Locale.ROOT);
        String id = g.contains(":") ? g.substring(g.indexOf(':') + 1) : "";
        if (id.equals("void")) id = "";
        return "Dreammultiverse:" + (id.isEmpty() ? "void" : id);
    }

    /** Biome id from a spec ("void" means default). */
    public static String biomeOf(String generator) {
        String n = normalize(generator);
        String id = n.substring(n.indexOf(':') + 1);
        return id.equals("void") ? null : id;
    }
}
