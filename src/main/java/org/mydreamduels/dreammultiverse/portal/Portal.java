package org.mydreamduels.dreammultiverse.portal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/** A cuboid region that teleports whoever walks into it. */
public final class Portal {

    private final String name;
    private volatile String world;
    private volatile int minX, minY, minZ, maxX, maxY, maxZ;
    private volatile String destination = "";
    private volatile String owner = "";
    private volatile double price = 0;
    private volatile String currency = "money";
    private volatile boolean safeTeleport = true;
    private volatile String fill = "";
    private volatile double[] exit; // x, y, z, yaw, pitch in this portal's world

    public Portal(String name, Location a, Location b) {
        this.name = name;
        setRegion(a, b);
    }

    private Portal(String name) {
        this.name = name;
    }

    public void setRegion(Location a, Location b) {
        this.world = a.getWorld().getName();
        this.minX = Math.min(a.getBlockX(), b.getBlockX());
        this.minY = Math.min(a.getBlockY(), b.getBlockY());
        this.minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        this.maxX = Math.max(a.getBlockX(), b.getBlockX());
        this.maxY = Math.max(a.getBlockY(), b.getBlockY());
        this.maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
    }

    public boolean contains(Location loc) {
        return loc.getWorld() != null && loc.getWorld().getName().equals(world)
                && contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    /** Where "p:name" destinations land - the spot it was created from, or the bottom centre. */
    public Location getExitLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        double[] e = exit;
        if (e != null) return new Location(w, e[0], e[1], e[2], (float) e[3], (float) e[4]);
        return new Location(w, (minX + maxX + 1) / 2.0, minY, (minZ + maxZ + 1) / 2.0);
    }

    public void setExit(Location loc) {
        exit = new double[]{loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()};
    }

    public void save(ConfigurationSection s) {
        s.set("world", world);
        s.set("min", minX + "," + minY + "," + minZ);
        s.set("max", maxX + "," + maxY + "," + maxZ);
        s.set("destination", destination);
        s.set("owner", owner);
        s.set("price", price);
        s.set("currency", currency);
        s.set("safe-teleport", safeTeleport);
        s.set("fill", fill);
        if (exit != null) {
            s.set("exit", exit[0] + "," + exit[1] + "," + exit[2] + "," + exit[3] + "," + exit[4]);
        }
    }

    public static Portal load(String name, ConfigurationSection s) {
        Portal p = new Portal(name);
        p.world = s.getString("world", "world");
        int[] min = ints(s.getString("min", "0,0,0"));
        int[] max = ints(s.getString("max", "0,0,0"));
        p.minX = Math.min(min[0], max[0]);
        p.minY = Math.min(min[1], max[1]);
        p.minZ = Math.min(min[2], max[2]);
        p.maxX = Math.max(min[0], max[0]);
        p.maxY = Math.max(min[1], max[1]);
        p.maxZ = Math.max(min[2], max[2]);
        p.destination = s.getString("destination", "");
        p.owner = s.getString("owner", "");
        p.price = s.getDouble("price", 0);
        p.currency = s.getString("currency", "money");
        p.safeTeleport = s.getBoolean("safe-teleport", true);
        p.fill = s.getString("fill", "");
        String exit = s.getString("exit");
        if (exit != null) {
            String[] e = exit.split(",");
            if (e.length == 5) {
                try {
                    p.exit = new double[]{Double.parseDouble(e[0]), Double.parseDouble(e[1]), Double.parseDouble(e[2]),
                            Double.parseDouble(e[3]), Double.parseDouble(e[4])};
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return p;
    }

    private static int[] ints(String csv) {
        String[] p = csv.split(",");
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public String getName() { return name; }
    public String getWorld() { return world; }
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination == null ? "" : destination; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = Math.max(0, price); }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public boolean isSafeTeleport() { return safeTeleport; }
    public void setSafeTeleport(boolean safeTeleport) { this.safeTeleport = safeTeleport; }
    public String getFill() { return fill; }
    public void setFill(String fill) { this.fill = fill == null ? "" : fill.toUpperCase(Locale.ROOT); }

    public String describeRegion() {
        return world + " (" + minX + "," + minY + "," + minZ + ") -> (" + maxX + "," + maxY + "," + maxZ + ")";
    }
}
