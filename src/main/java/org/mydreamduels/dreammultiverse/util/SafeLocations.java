package org.mydreamduels.dreammultiverse.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Finds a spot near a location where a player can stand without suffocating,
 * falling into the void or landing in lava. MUST be called on the thread
 * that owns the target location (TeleportService arranges that), since it
 * reads blocks.
 */
public final class SafeLocations {

    private static final int VERTICAL = 12;
    private static final int HORIZONTAL = 4;

    private SafeLocations() {
    }

    public static boolean isSafe(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        int y = loc.getBlockY();
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) return false;
        Block feet = world.getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        if (passable(feet) && passable(head) && solidGround(ground)) return true;
        return fitsExactly(world, loc, feet);
    }

    /**
     * The block check above only knows whole blocks, so a spawn set on a carpet, slab, stair or
     * path block failed it - and the search then often picked a spot UNDER the floor. Here the
     * player's real hitbox at the exact position is tested: nothing in the way, something to stand
     * on just below, no lava or fire.
     */
    private static boolean fitsExactly(World world, Location loc, Block feet) {
        try {
            double x = loc.getX(), y = loc.getY(), z = loc.getZ();
            Material t = feet.getType();
            if (t == Material.LAVA || t == Material.FIRE || t == Material.SOUL_FIRE || t == Material.POWDER_SNOW) return false;
            org.bukkit.util.BoundingBox body = new org.bukkit.util.BoundingBox(x - 0.29, y + 0.01, z - 0.29, x + 0.29, y + 1.79, z + 0.29);
            if (world.hasCollisionsIn(body)) return false;
            org.bukkit.util.BoundingBox under = new org.bukkit.util.BoundingBox(x - 0.29, y - 0.25, z - 0.29, x + 0.29, y + 0.01, z + 0.29);
            Material below = world.getBlockAt(loc.getBlockX(), (int) Math.floor(y - 0.25), loc.getBlockZ()).getType();
            if (below == Material.LAVA || below == Material.MAGMA_BLOCK || below == Material.CAMPFIRE || below == Material.SOUL_CAMPFIRE) return false;
            return world.hasCollisionsIn(under) || t == Material.WATER;
        } catch (Throwable notOnThisServer) {
            return false; // Spigot without hasCollisionsIn: the block check is all we have
        }
    }

    /** The original location if safe, else the nearest safe one, else null. */
    public static Location find(Location origin) {
        if (isSafe(origin)) return origin;
        World world = origin.getWorld();
        if (world == null) return null;

        for (int r = 0; r <= HORIZONTAL; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 0; dy <= VERTICAL; dy++) {
                        for (int sign : new int[]{1, -1}) {
                            if (dy == 0 && sign == -1) continue;
                            Location c = new Location(world, origin.getBlockX() + dx + 0.5, origin.getBlockY() + dy * sign,
                                    origin.getBlockZ() + dz + 0.5, origin.getYaw(), origin.getPitch());
                            if (isSafe(c)) return c;
                        }
                    }
                }
            }
        }

        // Last resort: top of the column (useless under a bedrock roof, so skipped in the nether)
        if (world.getEnvironment() != World.Environment.NETHER) {
            Block top = world.getHighestBlockAt(origin.getBlockX(), origin.getBlockZ());
            Location c = new Location(world, top.getX() + 0.5, top.getY() + 1, top.getZ() + 0.5, origin.getYaw(), origin.getPitch());
            if (isSafe(c)) return c;
        }
        return null;
    }

    private static boolean passable(Block block) {
        Material t = block.getType();
        if (t == Material.LAVA || t == Material.FIRE || t == Material.SOUL_FIRE || t == Material.SWEET_BERRY_BUSH
                || t == Material.POWDER_SNOW || t == Material.CACTUS) {
            return false;
        }
        return block.isPassable();
    }

    private static boolean solidGround(Block block) {
        Material t = block.getType();
        if (t == Material.LAVA || t == Material.MAGMA_BLOCK || t == Material.CACTUS || t == Material.CAMPFIRE
                || t == Material.SOUL_CAMPFIRE || t == Material.FIRE || t == Material.SOUL_FIRE) {
            return false;
        }
        return t.isSolid() || t == Material.WATER;
    }
}
