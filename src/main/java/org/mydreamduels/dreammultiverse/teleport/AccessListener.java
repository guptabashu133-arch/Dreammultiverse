package org.mydreamduels.dreammultiverse.teleport;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.util.Msg;

/**
 * Applies the world entry rules to teleports Dreammultiverse didn't start
 * itself (vanilla /tp, other plugins, vanilla portals). Paper/Spigot only
 * in practice - Folia never fires these events for cross-world moves.
 */
public final class AccessListener implements Listener {

    private final Dreammultiverse plugin;

    public AccessListener(Dreammultiverse plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null) return;
        if (event.getFrom().getWorld().equals(event.getTo().getWorld())) return;
        if (plugin.teleports().consumePreApproval(event.getPlayer().getUniqueId())) return;
        String denied = plugin.access().check(event.getPlayer(), event.getFrom().getWorld(), event.getTo().getWorld(), true);
        if (denied != null) {
            event.setCancelled(true);
            Msg.err(event.getPlayer(), denied);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null) return;
        if (event.getFrom().getWorld().equals(event.getTo().getWorld())) return;
        String denied = plugin.access().check(event.getPlayer(), event.getFrom().getWorld(), event.getTo().getWorld(), true);
        if (denied != null) {
            event.setCancelled(true);
            Msg.err(event.getPlayer(), denied);
        }
    }
}
