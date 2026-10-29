package org.mydreamduels.dreammultiverse.teleport;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.destination.Destination;
import org.mydreamduels.dreammultiverse.platform.PlatformSupport;
import org.mydreamduels.dreammultiverse.util.Msg;
import org.mydreamduels.dreammultiverse.util.SafeLocations;
import org.mydreamduels.dreammultiverse.world.MVWorld;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Every Dreammultiverse teleport goes through here:
 *  1. resolve the destination
 *  2. world entry rules (AccessChecker) on the player's own thread
 *  3. optional safe-spot search, done on the thread that owns the
 *     destination region (reading blocks anywhere else breaks on Folia)
 *  4. teleportAsync (Paper/Folia - Folia has no synchronous teleport at
 *     all) or plain teleport (Spigot)
 *  5. tell WorldChangeTracker immediately, apply cannon velocity
 */
public final class TeleportService {

    private final Dreammultiverse plugin;
    /** Players whose next world-changing PlayerTeleportEvent was already checked by us. */
    private final Set<UUID> preApproved = ConcurrentHashMap.newKeySet();

    public TeleportService(Dreammultiverse plugin) {
        this.plugin = plugin;
    }

    public boolean consumePreApproval(UUID id) {
        return preApproved.remove(id);
    }

    /** No checks at all - completes with whether the teleport happened. */
    public CompletableFuture<Boolean> teleportRaw(Entity entity, Location location) {
        if (PlatformSupport.hasUnifiedSchedulers()) {
            return entity.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        plugin.scheduler().runForEntity(entity, () -> result.complete(entity.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN)));
        return result;
    }

    /** Teleport to a parsed destination, enforcing world rules. initiator gets the feedback (may be the player). */
    public void teleport(CommandSender initiator, Player player, Destination destination) {
        teleport(initiator, player, destination, null);
    }

    public void teleport(CommandSender initiator, Player player, Destination destination, Consumer<Boolean> done) {
        boolean scheduled = plugin.scheduler().runForEntity(player, () -> {
            Location target = destination.resolve(plugin, player);
            if (target == null || target.getWorld() == null) {
                Msg.err(initiator, "That destination isn't available right now (" + destination.describe(plugin) + "§c).");
                finish(done, false);
                return;
            }
            boolean safe = destination.wantsSafety() && plugin.getConfig().getBoolean("safe-teleport", true);
            teleportTo(initiator, player, target, safe, destination.velocity(target), done);
        });
        if (!scheduled) finish(done, false);
    }

    /** Teleport to a location with world-entry rules. Call from anywhere. */
    public void teleportTo(CommandSender initiator, Player player, Location target, boolean safe, Vector velocity, Consumer<Boolean> done) {
        plugin.scheduler().runForEntity(player, () -> {
            String denied = plugin.access().check(player, player.getWorld(), target.getWorld(), true);
            if (denied != null) {
                Msg.err(player, denied);
                if (initiator != player) Msg.err(initiator, player.getName() + ": " + Msg.strip(denied));
                finish(done, false);
                return;
            }
            if (!safe) {
                go(initiator, player, target, velocity, done);
                return;
            }
            findSafe(target, found -> {
                if (found == null) {
                    Msg.err(initiator, "No safe spot was found near the destination - teleport cancelled.");
                    finish(done, false);
                    return;
                }
                go(initiator, player, found, velocity, done);
            });
        });
    }

    /** Async-friendly safe-spot search; result handed back on the destination's thread (null = none). */
    public void findSafe(Location target, Consumer<Location> result) {
        if (PlatformSupport.hasUnifiedSchedulers()) {
            target.getWorld().getChunkAtAsync(target).whenComplete((chunk, error) ->
                    plugin.scheduler().runAtLocation(target, () -> result.accept(SafeLocations.find(target))));
        } else {
            plugin.scheduler().runAtLocation(target, () -> result.accept(SafeLocations.find(target)));
        }
    }

    private void go(CommandSender initiator, Player player, Location target, Vector velocity, Consumer<Boolean> done) {
        boolean crossWorld = !player.getWorld().equals(target.getWorld());
        if (crossWorld) preApproved.add(player.getUniqueId());
        teleportRaw(player, target).whenComplete((ok, error) -> {
            preApproved.remove(player.getUniqueId());
            boolean success = error == null && Boolean.TRUE.equals(ok);
            if (!success) {
                Msg.err(initiator, "The teleport was cancelled.");
                finish(done, false);
                return;
            }
            plugin.scheduler().runForEntity(player, () -> {
                plugin.tracker().check(player);
                if (velocity != null) player.setVelocity(velocity);
                finish(done, true);
            });
        });
    }

    private static void finish(Consumer<Boolean> done, boolean value) {
        if (done != null) done.accept(value);
    }

    /** Teleport to a world's spawn with all the rules - used by /mv spawn, joins, respawn redirects. */
    public void toSpawn(CommandSender initiator, Player player, MVWorld world, Consumer<Boolean> done) {
        Location spawn = world.getSpawnLocation();
        if (spawn == null) {
            Msg.err(initiator, "World " + world.getName() + " isn't loaded.");
            finish(done, false);
            return;
        }
        teleportTo(initiator, player, spawn, world.isAdjustSpawn() && plugin.getConfig().getBoolean("safe-teleport", true), null, done);
    }
}
