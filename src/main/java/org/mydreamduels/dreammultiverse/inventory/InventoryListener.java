package org.mydreamduels.dreammultiverse.inventory;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.mydreamduels.dreammultiverse.world.WorldChangeTracker;

public final class InventoryListener implements Listener, WorldChangeTracker.Handler {

    private final InventoryManager manager;

    public InventoryListener(InventoryManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) manager.preload(event.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        manager.join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        manager.changeGameMode(event.getPlayer(), event.getPlayer().getGameMode(), event.getNewGameMode());
    }

    @Override
    public void onWorldChange(Player player, World from, World to) {
        manager.changeWorld(player, from == null ? null : from.getName(), to.getName());
    }
}
