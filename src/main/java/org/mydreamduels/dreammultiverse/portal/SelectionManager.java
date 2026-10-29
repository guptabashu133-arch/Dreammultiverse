package org.mydreamduels.dreammultiverse.portal;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The two corner points each player has picked with the portal wand. */
public final class SelectionManager {

    private final Map<UUID, Location[]> selections = new ConcurrentHashMap<>();

    public void setPos(Player player, int index, Location loc) {
        selections.computeIfAbsent(player.getUniqueId(), k -> new Location[2])[index] = loc.getBlock().getLocation();
    }

    /** Both corners, in the same world - otherwise null. */
    public Location[] get(Player player) {
        Location[] s = selections.get(player.getUniqueId());
        if (s == null || s[0] == null || s[1] == null) return null;
        if (!s[0].getWorld().equals(s[1].getWorld())) return null;
        return s;
    }

    public Location[] raw(Player player) {
        return selections.get(player.getUniqueId());
    }

    public void clear(Player player) {
        selections.remove(player.getUniqueId());
    }
}
