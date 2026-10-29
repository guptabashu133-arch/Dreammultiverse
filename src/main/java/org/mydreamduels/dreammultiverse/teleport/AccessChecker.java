package org.mydreamduels.dreammultiverse.teleport;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.world.MVWorld;

import java.util.Locale;

/**
 * The rules for entering a world: access permission (when enforce-access
 * is on), the destination's blacklist, its player limit and its entry fee.
 * Used both before our own teleports and (on Paper/Spigot) for every
 * other teleport via AccessListener.
 */
public final class AccessChecker {

    private final Dreammultiverse plugin;

    public AccessChecker(Dreammultiverse plugin) {
        this.plugin = plugin;
    }

    public static boolean hasAccess(Player player, String world) {
        return player.hasPermission("dreammultiverse.access.*")
                || player.hasPermission("dreammultiverse.access." + world.toLowerCase(Locale.ROOT));
    }

    /**
     * Null if the player may enter (fee taken when charge is true), else
     * why not. Must run on the player's thread (item fees touch the inventory).
     */
    public String check(Player player, World from, World to, boolean charge) {
        if (to == null || (from != null && from.equals(to))) return null;
        MVWorld mv = plugin.worlds().get(to);
        if (mv == null) return null;

        if (plugin.getConfig().getBoolean("enforce-access", false) && !hasAccess(player, mv.getName())) {
            return "You don't have access to " + mv.getDisplayName() + "§c.";
        }
        if (from != null && !player.hasPermission("dreammultiverse.bypass.blacklist")) {
            for (String banned : mv.getWorldBlacklist()) {
                if (banned.equalsIgnoreCase(from.getName())) {
                    return "You can't travel to " + mv.getDisplayName() + "§c from here.";
                }
            }
        }
        if (mv.getPlayerLimit() >= 0 && !player.hasPermission("dreammultiverse.bypass.playerlimit")) {
            long inside = Bukkit.getOnlinePlayers().stream().filter(p -> p.getWorld().equals(to)).count();
            if (inside >= mv.getPlayerLimit()) return mv.getDisplayName() + "§c is full (" + mv.getPlayerLimit() + " players).";
        }
        if (charge && mv.getEntryFee() > 0
                && !player.hasPermission("dreammultiverse.exempt.*")
                && !player.hasPermission("dreammultiverse.exempt." + mv.getName().toLowerCase(Locale.ROOT))) {
            String error = plugin.economy().charge(player, mv.getEntryFee(), mv.getCurrency());
            if (error != null) return error;
            org.mydreamduels.dreammultiverse.util.Msg.send(player, "&7Paid &e" + plugin.economy().describe(mv.getEntryFee(), mv.getCurrency())
                    + "&7 to enter " + mv.getDisplayName() + "&7.");
        }
        return null;
    }
}
