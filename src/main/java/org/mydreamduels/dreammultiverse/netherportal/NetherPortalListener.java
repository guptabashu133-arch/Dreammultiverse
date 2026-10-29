package org.mydreamduels.dreammultiverse.netherportal;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.platform.PlatformSupport;
import org.mydreamduels.dreammultiverse.util.Msg;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper/Spigot: rewrites PlayerPortalEvent/EntityPortalEvent so the server's
 * own portal search/creation runs against the linked world.
 *
 * Folia never fires those events (its portal logic is async and only knows
 * the three level-name worlds), so there EntityPortalEnterEvent is used:
 * portals with a custom rule are cancelled and the player is moved by us
 * after the usual portal wait, landing on a safe spot at the scaled
 * coordinates (Folia gives plugins no portal-creation API).
 */
public final class NetherPortalListener implements Listener {

    private final Dreammultiverse plugin;
    private final NetherPortalManager links;
    /** Folia: when the player first stood in the portal, and when we last saw them there. */
    private final Map<UUID, long[]> inPortal = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public NetherPortalListener(Dreammultiverse plugin, NetherPortalManager links) {
        this.plugin = plugin;
        this.links = links;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("nether-portals.enabled", true);
    }

    private void noDestination(Player player) {
        if (!plugin.getConfig().getBoolean("nether-portals.send-no-destination-message", true)) return;
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < 3000) return;
        lastMessage.put(player.getUniqueId(), now);
        Msg.err(player, "This portal doesn't lead anywhere.");
    }

    // ------------------------------------------------------------------ Paper / Spigot

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!enabled()) return;
        PortalType type;
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) type = PortalType.NETHER;
        else if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) type = PortalType.ENDER;
        else return;

        Location from = event.getFrom();
        World target = links.target(from.getWorld(), type);
        if (target == null) {
            event.setCancelled(true);
            noDestination(event.getPlayer());
            return;
        }
        Location to = destination(event.getPlayer(), from, target, type);
        if (to == null) {
            event.setCancelled(true);
            noDestination(event.getPlayer());
            return;
        }
        event.setTo(to);
        if (type == PortalType.NETHER) event.setCanCreatePortal(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!enabled() || event.getTo() == null) return;
        if (!plugin.getConfig().getBoolean("nether-portals.teleport-entities", true)) {
            event.setCancelled(true);
            return;
        }
        PortalType type;
        if (PlatformSupport.hasUnifiedSchedulers()) {
            if (event.getPortalType() != PortalType.NETHER && event.getPortalType() != PortalType.ENDER) return;
            type = event.getPortalType();
        } else {
            // Spigot has no portal type on this event - an end world on either side means an end portal
            boolean end = event.getFrom().getWorld().getEnvironment() == World.Environment.THE_END
                    || event.getTo().getWorld().getEnvironment() == World.Environment.THE_END;
            type = end ? PortalType.ENDER : PortalType.NETHER;
        }
        World target = links.target(event.getFrom().getWorld(), type);
        if (target == null) {
            event.setCancelled(true);
            return;
        }
        Location to = destination(null, event.getFrom(), target, type);
        if (to == null) event.setCancelled(true);
        else event.setTo(to);
    }

    /** Main-thread (Paper/Spigot) destination - may place the end platform directly. */
    private Location destination(Player player, Location from, World target, PortalType type) {
        if (type == PortalType.NETHER) return links.scaled(from, target);
        if (target.getEnvironment() == World.Environment.THE_END) {
            Location platform = NetherPortalManager.endPlatform(target);
            buildPlatform(platform);
            return platform;
        }
        // Leaving the end: own bed/anchor in that world if they have one, else its spawn
        if (player != null) {
            Location bed = PlatformSupport.hasUnifiedSchedulers() ? player.getRespawnLocation(false) : player.getRespawnLocation();
            if (bed != null && target.equals(bed.getWorld())) return bed;
        }
        return plugin.worlds().spawnOf(target);
    }

    /** 5x5 obsidian with 3 blocks of air above, exactly like vanilla. Caller must own the region. */
    static void buildPlatform(Location centre) {
        World w = centre.getWorld();
        int cx = centre.getBlockX(), cy = centre.getBlockY() - 1, cz = centre.getBlockZ();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                w.getBlockAt(cx + dx, cy, cz + dz).setType(Material.OBSIDIAN, false);
                for (int dy = 1; dy <= 3; dy++) {
                    Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Folia

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalEnter(EntityPortalEnterEvent event) {
        if (!PlatformSupport.isFolia() || !enabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        PortalType type = event.getPortalType();
        if (type != PortalType.NETHER && type != PortalType.ENDER) return;
        World from = player.getWorld();
        if (!links.hasExplicitRule(from, type)) return; // Folia's own routing already matches the naming convention

        event.setCancelled(true);
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long cd = cooldown.get(id);
        if (cd != null && cd > now) return;

        World target = links.target(from, type);
        if (target == null) {
            noDestination(player);
            return;
        }

        long[] state = inPortal.compute(id, (k, v) -> (v == null || now - v[1] > 500) ? new long[]{now, now} : new long[]{v[0], now});
        boolean instant = type == PortalType.ENDER || player.getGameMode() == GameMode.CREATIVE;
        if (!instant && now - state[0] < 4000) return;

        inPortal.remove(id);
        cooldown.put(id, now + 3000);
        Location to;
        if (type == PortalType.NETHER) {
            to = links.scaled(player.getLocation(), target);
            plugin.teleports().teleportTo(player, player, to, true, null, null);
        } else if (target.getEnvironment() == World.Environment.THE_END) {
            Location platform = NetherPortalManager.endPlatform(target);
            plugin.scheduler().runAtLocation(platform, () -> {
                buildPlatform(platform);
                plugin.teleports().teleportTo(player, player, platform, false, null, null);
            });
        } else {
            Location bed = player.getRespawnLocation(false);
            to = bed != null && target.equals(bed.getWorld()) ? bed : plugin.worlds().spawnOf(target);
            plugin.teleports().teleportTo(player, player, to, true, null, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        inPortal.remove(id);
        cooldown.remove(id);
        lastMessage.remove(id);
    }
}
