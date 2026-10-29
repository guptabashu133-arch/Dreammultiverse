package org.mydreamduels.dreammultiverse.portal;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.destination.Destination;
import org.mydreamduels.dreammultiverse.platform.PlatformSupport;
import org.mydreamduels.dreammultiverse.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wand selection + using portals. Detection is purely "did the player's
 * block position move into a portal region" (PlayerMoveEvent), which works
 * identically on Spigot, Paper and Folia, whatever the portal is filled
 * with. Vanilla nether/end behaviour inside our regions is suppressed so
 * a portal filled with nether-portal blocks goes where WE say.
 */
public final class PortalListener implements Listener {

    private final Dreammultiverse plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public PortalListener(Dreammultiverse plugin) {
        this.plugin = plugin;
    }

    private Material wand() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("portals.wand", "WOODEN_AXE"));
        return m == null ? Material.WOODEN_AXE : m;
    }

    // ------------------------------------------------------------------ wand

    @EventHandler(priority = EventPriority.LOW)
    public void onWand(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        if (event.getItem() == null || event.getItem().getType() != wand()) return;
        if (!player.hasPermission("dreammultiverse.portal.create")) return;
        int index;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) index = 0;
        else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) index = 1;
        else return;
        event.setCancelled(true);
        Block b = event.getClickedBlock();
        plugin.selections().setPos(player, index, b.getLocation());
        Msg.send(player, "&7Position " + (index + 1) + " set to &e" + b.getX() + ", " + b.getY() + ", " + b.getZ()
                + volumeHint(player));
    }

    private String volumeHint(Player player) {
        Location[] s = plugin.selections().get(player);
        if (s == null) return "";
        long v = (long) (Math.abs(s[0].getBlockX() - s[1].getBlockX()) + 1)
                * (Math.abs(s[0].getBlockY() - s[1].getBlockY()) + 1)
                * (Math.abs(s[0].getBlockZ() - s[1].getBlockZ()) + 1);
        return " &8(" + v + " blocks)";
    }

    // ------------------------------------------------------------------ walking into portals

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ() && from.getWorld().equals(to.getWorld()))) {
            return;
        }
        Portal portal = plugin.portals().at(to);
        if (portal == null || portal.contains(from)) return;
        Player player = event.getPlayer();
        if (player.isInsideVehicle()) return; // VehicleMoveEvent handles riders
        if (use(player, portal, from) && plugin.getConfig().getBoolean("portals.bounce-back", true)) {
            // denied: push them back out so they don't stand in the portal
            event.setTo(from);
        }
    }

    /**
     * Tries to send the player through. Returns true if they were DENIED
     * (so the caller can bounce them back), false if teleporting or ignored.
     */
    private boolean use(Player player, Portal portal, Location from) {
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(player.getUniqueId());
        if (until != null && until > now) return false;
        cooldowns.put(player.getUniqueId(), now + plugin.getConfig().getLong("portals.cooldown-ms", 1000));

        String name = portal.getName().toLowerCase(Locale.ROOT);
        if (plugin.getConfig().getBoolean("portals.enforce-access", false)
                && !player.hasPermission("dreammultiverse.portal.access.*")
                && !player.hasPermission("dreammultiverse.portal.access." + name)) {
            Msg.err(player, "You may not use the portal " + portal.getName() + ".");
            return true;
        }
        if (portal.getDestination().isBlank()) {
            Msg.err(player, "This portal (" + portal.getName() + ") has no destination yet.");
            return true;
        }
        String[] error = new String[1];
        Destination dest = Destination.parse(plugin, portal.getDestination(), error);
        if (dest == null) {
            Msg.err(player, "This portal's destination is invalid: " + error[0]);
            return true;
        }
        if (portal.getPrice() > 0 && !player.hasPermission("dreammultiverse.portal.exempt.*")
                && !player.hasPermission("dreammultiverse.portal.exempt." + name)) {
            String failed = plugin.economy().charge(player, portal.getPrice(), portal.getCurrency());
            if (failed != null) {
                Msg.err(player, failed);
                return true;
            }
            Msg.send(player, "&7Paid &e" + plugin.economy().describe(portal.getPrice(), portal.getCurrency()) + "&7 to use this portal.");
        }
        Location target = dest.resolve(plugin, player);
        if (target == null) {
            Msg.err(player, "This portal's destination isn't available right now.");
            return true;
        }
        boolean safe = portal.isSafeTeleport() && dest.wantsSafety();
        plugin.teleports().teleportTo(player, player, target, safe, dest.velocity(target), ok -> {
            if (Boolean.TRUE.equals(ok)) cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + 1500);
        });
        return false;
    }

    // ------------------------------------------------------------------ vehicles

    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!plugin.getConfig().getBoolean("portals.teleport-vehicles", true)) return;
        Location to = event.getTo();
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Portal portal = plugin.portals().at(to);
        if (portal == null || portal.contains(from)) return;
        Vehicle vehicle = event.getVehicle();
        List<Player> riders = new ArrayList<>();
        for (Entity e : vehicle.getPassengers()) if (e instanceof Player p) riders.add(p);
        if (riders.isEmpty()) return;
        Player driver = riders.get(0);

        long now = System.currentTimeMillis();
        Long until = cooldowns.get(driver.getUniqueId());
        if (until != null && until > now) return;

        String[] error = new String[1];
        Destination dest = portal.getDestination().isBlank() ? null : Destination.parse(plugin, portal.getDestination(), error);
        Location target = dest == null ? null : dest.resolve(plugin, driver);
        if (target == null) {
            use(driver, portal, from); // reuse the normal error messages + cooldown
            return;
        }
        // Riders go through the regular checks one by one, the vehicle follows the driver.
        cooldowns.put(driver.getUniqueId(), now + plugin.getConfig().getLong("portals.cooldown-ms", 1000));
        String denied = plugin.access().check(driver, driver.getWorld(), target.getWorld(), false);
        if (denied != null) {
            Msg.err(driver, denied);
            return;
        }
        vehicle.eject();
        for (Player rider : riders) {
            plugin.teleports().teleportTo(rider, rider, target, false, null, ok -> {
                if (!Boolean.TRUE.equals(ok)) return;
                plugin.teleports().teleportRaw(vehicle, target).thenAccept(moved -> {
                    if (Boolean.TRUE.equals(moved)) plugin.scheduler().runForEntity(vehicle, () -> vehicle.addPassenger(rider));
                });
            });
        }
    }

    // ------------------------------------------------------------------ suppress vanilla inside our portals

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPortalEnter(EntityPortalEnterEvent event) {
        // Only cancellable on Paper/Folia; on Spigot onVanillaPortal below does the job.
        if (PlatformSupport.hasUnifiedSchedulers() && plugin.portals().at(event.getLocation()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVanillaPortal(PlayerPortalEvent event) {
        if (plugin.portals().at(event.getFrom()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        Material t = event.getBlock().getType();
        if (!PortalManager.isFillMaterial(t)) return;
        Block b = event.getBlock();
        if (plugin.portals().at(b.getWorld(), b.getX(), b.getY(), b.getZ()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block b = event.getBlock();
        if (plugin.portals().at(b.getWorld(), b.getX(), b.getY(), b.getZ()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
        plugin.selections().clear(event.getPlayer());
    }
}
