package org.mydreamduels.dreammultiverse.signportal;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.destination.Destination;
import org.mydreamduels.dreammultiverse.platform.PlatformSupport;
import org.mydreamduels.dreammultiverse.util.Msg;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs whose first line is [mv] (or [dmv]) teleport to the destination
 * written on lines 2+3 - on right-click, or for everyone standing next to
 * the sign when it receives a redstone signal.
 */
public final class SignPortalListener implements Listener {

    private static final BlockFace[] AROUND = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final Dreammultiverse plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public SignPortalListener(Dreammultiverse plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("sign-portals.enabled", true);
    }

    private static boolean isHeader(String line) {
        String s = Msg.strip(line == null ? "" : line).trim();
        return s.equalsIgnoreCase("[mv]") || s.equalsIgnoreCase("[dmv]");
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreate(SignChangeEvent event) {
        if (!enabled() || !isHeader(event.getLine(0))) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("dreammultiverse.signportal.create")) {
            Msg.err(player, "You don't have permission to make sign portals.");
            event.setCancelled(true);
            return;
        }
        String destination = (nz(event.getLine(1)) + nz(event.getLine(2))).trim();
        String[] error = new String[1];
        if (Destination.parse(plugin, destination, error) == null) {
            Msg.err(player, "Invalid destination: " + error[0]);
            event.setLine(0, Msg.color("&4[mv]"));
            return;
        }
        event.setLine(0, Msg.color("&1[mv]"));
        Msg.ok(player, "Sign portal created -> " + destination);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent event) {
        if (!enabled() || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        String destination = destinationOf(block);
        if (destination == null) return;
        event.setCancelled(true); // also stops the sign editor from opening
        Player player = event.getPlayer();
        if (!player.hasPermission("dreammultiverse.signportal.use")) {
            Msg.err(player, "You don't have permission to use sign portals.");
            return;
        }
        send(player, destination);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRedstone(BlockRedstoneEvent event) {
        if (!enabled() || !plugin.getConfig().getBoolean("sign-portals.redstone", true)) return;
        if (event.getOldCurrent() > 0 || event.getNewCurrent() <= 0) return;
        Block source = event.getBlock();
        for (BlockFace face : AROUND) {
            Block b = source.getRelative(face);
            String destination = destinationOf(b);
            if (destination == null) continue;
            try {
                for (Entity e : b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 2.5, 2.5, 2.5)) {
                    if (e instanceof Player p && p.hasPermission("dreammultiverse.signportal.use")) send(p, destination);
                }
            } catch (RuntimeException ignored) {
                // Folia: the area straddles a region we don't own right now - skip this pulse
            }
        }
    }

    private void send(Player player, String destination) {
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(player.getUniqueId());
        if (until != null && until > now) return;
        cooldowns.put(player.getUniqueId(), now + 1000);
        String[] error = new String[1];
        Destination dest = Destination.parse(plugin, destination, error);
        if (dest == null) {
            Msg.err(player, "This sign's destination is invalid: " + error[0]);
            return;
        }
        plugin.teleports().teleport(player, player, dest);
    }

    /** Destination text if this block is a [mv] sign, else null. */
    @SuppressWarnings("deprecation")
    private static String destinationOf(Block block) {
        BlockState state = PlatformSupport.hasUnifiedSchedulers() ? block.getState(false) : block.getState();
        if (!(state instanceof Sign sign)) return null;
        for (Side side : Side.values()) {
            SignSide s = sign.getSide(side);
            if (isHeader(s.getLine(0))) {
                String dest = (Msg.strip(s.getLine(1)) + Msg.strip(s.getLine(2))).trim();
                return dest.isEmpty() ? null : dest;
            }
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
