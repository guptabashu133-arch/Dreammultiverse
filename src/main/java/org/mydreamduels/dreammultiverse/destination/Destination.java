package org.mydreamduels.dreammultiverse.destination;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.platform.PlatformSupport;
import org.mydreamduels.dreammultiverse.portal.Portal;
import org.mydreamduels.dreammultiverse.util.LocationUtil;
import org.mydreamduels.dreammultiverse.world.MVWorld;

import java.util.List;
import java.util.Locale;

/**
 * A teleport target, written the same way as Multiverse:
 *
 *   world / w:world                        spawn of a world (name or alias)
 *   e:world:x,y,z[:pitch:yaw]              exact location
 *   pl:player                              where another player is
 *   a:anchor                               a saved /mv anchor
 *   b: / b:player                          own (or someone's) bed / respawn anchor
 *   p:portal                               a Dreammultiverse portal
 *   ca:world:x,y,z:pitch:yaw:speed         cannon - teleport, then launch
 */
public final class Destination {

    public enum Type {
        WORLD("w"), EXACT("e"), PLAYER("pl"), ANCHOR("a"), BED("b"), PORTAL("p"), CANNON("ca");

        public final String prefix;

        Type(String prefix) {
            this.prefix = prefix;
        }
    }

    public static final List<String> PREFIXES = List.of("w:", "e:", "pl:", "a:", "b:", "p:", "ca:");

    private final Type type;
    private final String raw;
    private final String target;   // world / player / anchor / portal name
    private final Location exact;  // for e: and ca:
    private final double speed;    // for ca:

    private Destination(Type type, String raw, String target, Location exact, double speed) {
        this.type = type;
        this.raw = raw;
        this.target = target;
        this.exact = exact;
        this.speed = speed;
    }

    /** Null (with the reason in error[0]) if the text isn't a valid destination right now. */
    public static Destination parse(Dreammultiverse plugin, String input, String[] error) {
        if (input == null || input.isBlank()) {
            error[0] = "No destination given.";
            return null;
        }
        int colon = input.indexOf(':');
        String prefix = colon < 0 ? "" : input.substring(0, colon).toLowerCase(Locale.ROOT);
        String rest = colon < 0 ? input : input.substring(colon + 1);

        switch (prefix) {
            case "", "w" -> {
                MVWorld mv = plugin.worlds().find(rest);
                if (mv == null && Bukkit.getWorld(rest) != null) mv = plugin.worlds().getOrAdopt(Bukkit.getWorld(rest));
                if (mv == null || !mv.isLoaded()) {
                    error[0] = "Unknown or unloaded world '" + rest + "'.";
                    return null;
                }
                return new Destination(Type.WORLD, "w:" + mv.getName(), mv.getName(), null, 0);
            }
            case "e", "ca" -> {
                String[] p = rest.split(":");
                boolean cannon = prefix.equals("ca");
                if (p.length < 2 || (cannon && p.length < 5)) {
                    error[0] = cannon ? "Format: ca:world:x,y,z:pitch:yaw:speed" : "Format: e:world:x,y,z[:pitch:yaw]";
                    return null;
                }
                World world = Bukkit.getWorld(p[0]);
                if (world == null) {
                    MVWorld mv = plugin.worlds().find(p[0]);
                    world = mv == null ? null : mv.getBukkitWorld();
                }
                if (world == null) {
                    error[0] = "Unknown or unloaded world '" + p[0] + "'.";
                    return null;
                }
                Location loc = LocationUtil.parseCoords(world, p[1], p.length > 2 ? p[2] : null, p.length > 3 ? p[3] : null);
                if (loc == null) {
                    error[0] = "Coordinates must look like x,y,z (and numbers for pitch/yaw).";
                    return null;
                }
                double speed = 0;
                if (cannon) {
                    try {
                        speed = Double.parseDouble(p[4]);
                    } catch (NumberFormatException e) {
                        error[0] = "Cannon speed must be a number.";
                        return null;
                    }
                }
                return new Destination(cannon ? Type.CANNON : Type.EXACT, input, world.getName(), loc, speed);
            }
            case "pl" -> {
                return new Destination(Type.PLAYER, input, rest, null, 0);
            }
            case "a" -> {
                if (plugin.anchors().get(rest) == null) {
                    error[0] = "Unknown anchor '" + rest + "'.";
                    return null;
                }
                return new Destination(Type.ANCHOR, input, rest, null, 0);
            }
            case "b" -> {
                return new Destination(Type.BED, input, rest, null, 0);
            }
            case "p" -> {
                String name = rest.split(":")[0];
                if (plugin.portals().get(name) == null) {
                    error[0] = "Unknown portal '" + name + "'.";
                    return null;
                }
                return new Destination(Type.PORTAL, "p:" + name, name, null, 0);
            }
            default -> {
                // Not a known prefix - maybe a world whose name contains ':'
                MVWorld mv = plugin.worlds().find(input);
                if (mv != null && mv.isLoaded()) return new Destination(Type.WORLD, "w:" + mv.getName(), mv.getName(), null, 0);
                error[0] = "Unknown destination '" + input + "'. Use a world name or w: e: pl: a: b: p: ca:";
                return null;
            }
        }
    }

    /**
     * Where the teleportee should end up, or null if it can't be resolved
     * right now (player offline, world unloaded, no bed...).
     */
    public Location resolve(Dreammultiverse plugin, Player teleportee) {
        switch (type) {
            case WORLD -> {
                MVWorld mv = plugin.worlds().get(target);
                return mv == null ? null : mv.getSpawnLocation();
            }
            case EXACT, CANNON -> {
                World w = Bukkit.getWorld(target);
                if (w == null) return null;
                Location l = exact.clone();
                l.setWorld(w);
                return l;
            }
            case PLAYER -> {
                Player other = Bukkit.getPlayerExact(target);
                if (other == null) other = Bukkit.getPlayer(target);
                return other == null ? null : other.getLocation();
            }
            case ANCHOR -> {
                return plugin.anchors().get(target);
            }
            case BED -> {
                Player owner = target.isBlank() ? teleportee : Bukkit.getPlayerExact(target);
                if (owner == null) return null;
                Location bed = PlatformSupport.hasUnifiedSchedulers() ? owner.getRespawnLocation(false) : owner.getRespawnLocation();
                return bed;
            }
            case PORTAL -> {
                Portal portal = plugin.portals().get(target);
                return portal == null ? null : portal.getExitLocation();
            }
        }
        return null;
    }

    /** Extra velocity applied after arriving (cannons), or null. */
    public Vector velocity(Location arrived) {
        if (type != Type.CANNON) return null;
        return arrived.getDirection().normalize().multiply(speed);
    }

    /** Exact coordinates are honoured as-is; everything else is safety-checked when safe-teleport is on. */
    public boolean wantsSafety() {
        return type != Type.EXACT && type != Type.CANNON;
    }

    public Type getType() {
        return type;
    }

    public String getTarget() {
        return target;
    }

    public String serialize() {
        return raw;
    }

    public String permissionSuffix() {
        return type.prefix;
    }

    public String describe(Dreammultiverse plugin) {
        return switch (type) {
            case WORLD -> {
                MVWorld mv = plugin.worlds().get(target);
                yield mv == null ? target : mv.getDisplayName();
            }
            case EXACT -> LocationUtil.format(exact);
            case PLAYER -> target;
            case ANCHOR -> "anchor " + target;
            case BED -> target.isBlank() ? "your bed" : target + "'s bed";
            case PORTAL -> "portal " + target;
            case CANNON -> "cannon " + LocationUtil.format(exact);
        };
    }
}
