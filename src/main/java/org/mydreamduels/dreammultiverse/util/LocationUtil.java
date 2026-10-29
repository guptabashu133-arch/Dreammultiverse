package org.mydreamduels.dreammultiverse.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

public final class LocationUtil {

    private LocationUtil() {
    }

    public static void write(ConfigurationSection section, Location loc) {
        section.set("world", loc.getWorld() == null ? null : loc.getWorld().getName());
        section.set("x", round(loc.getX()));
        section.set("y", round(loc.getY()));
        section.set("z", round(loc.getZ()));
        section.set("yaw", (double) round(loc.getYaw()));
        section.set("pitch", (double) round(loc.getPitch()));
    }

    /** Null if the section is missing or its world isn't loaded. */
    public static Location read(ConfigurationSection section) {
        if (section == null) return null;
        World world = Bukkit.getWorld(section.getString("world", ""));
        if (world == null) return null;
        return new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    /** "x,y,z" or "x,y,z:pitch:yaw" style coordinates used by destinations. */
    public static Location parseCoords(World world, String coords, String pitch, String yaw) {
        String[] p = coords.split(",");
        if (p.length != 3) return null;
        try {
            Location loc = new Location(world, Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
            if (pitch != null) loc.setPitch(Float.parseFloat(pitch));
            if (yaw != null) loc.setYaw(Float.parseFloat(yaw));
            return loc;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String format(Location loc) {
        return String.format(Locale.ROOT, "%s %.1f, %.1f, %.1f (yaw %.0f, pitch %.0f)",
                loc.getWorld() == null ? "?" : loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    public static String toDestination(Location loc) {
        return String.format(Locale.ROOT, "e:%s:%.2f,%.2f,%.2f:%.1f:%.1f", loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getPitch(), loc.getYaw());
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
