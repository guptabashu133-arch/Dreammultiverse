package org.mydreamduels.dreammultiverse.world;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.platform.CancelHandle;
import org.mydreamduels.dreammultiverse.platform.PlatformSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One reliable "player changed world" signal for the whole plugin.
 *
 * Paper/Spigot fire PlayerChangedWorldEvent. Folia does NOT (its async
 * teleport/portal code bypasses it), so on Folia each online player gets a
 * tiny per-player task that compares their current world with the last one
 * seen. TeleportService also calls check() the moment one of our own
 * teleports completes, so for /mv tp, portals etc. there's no delay at all.
 */
public final class WorldChangeTracker implements Listener {

    public interface Handler {
        /** Runs on the player's own thread. */
        void onWorldChange(Player player, World from, World to);
    }

    private final Dreammultiverse plugin;
    private final List<Handler> handlers = new CopyOnWriteArrayList<>();
    private final Map<UUID, String> lastWorld = new ConcurrentHashMap<>();
    private final Map<UUID, CancelHandle> tasks = new ConcurrentHashMap<>();

    public WorldChangeTracker(Dreammultiverse plugin) {
        this.plugin = plugin;
    }

    public void addHandler(Handler handler) {
        handlers.add(handler);
    }

    public void start(Player player) {
        lastWorld.put(player.getUniqueId(), player.getWorld().getName());
        if (!PlatformSupport.isFolia()) return;
        CancelHandle old = tasks.remove(player.getUniqueId());
        if (old != null) old.cancel();
        CancelHandle handle = plugin.scheduler().runForEntityRepeating(player, () -> check(player), 2L, 2L);
        if (handle != null) tasks.put(player.getUniqueId(), handle);
    }

    public void stop(Player player) {
        lastWorld.remove(player.getUniqueId());
        CancelHandle handle = tasks.remove(player.getUniqueId());
        if (handle != null) handle.cancel();
    }

    /** Must be called on the player's thread. Fires the handlers if the world changed since last time. */
    public void check(Player player) {
        if (!player.isOnline()) return;
        World now = player.getWorld();
        String before = lastWorld.put(player.getUniqueId(), now.getName());
        if (before == null || before.equals(now.getName())) return;
        World from = plugin.getServer().getWorld(before);
        fire(player, from, now);
    }

    private void fire(Player player, World from, World to) {
        for (Handler h : handlers) {
            try {
                h.onWorldChange(player, from, to);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "World change handler failed", t);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        start(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        // Paper/Spigot path. check() de-duplicates against the last world we saw.
        check(event.getPlayer());
    }

    public void shutdown() {
        for (CancelHandle h : tasks.values()) h.cancel();
        tasks.clear();
        lastWorld.clear();
    }
}
