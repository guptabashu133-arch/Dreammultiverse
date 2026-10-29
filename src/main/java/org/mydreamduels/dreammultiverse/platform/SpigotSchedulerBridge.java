package org.mydreamduels.dreammultiverse.platform;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Plain Spigot/CraftBukkit: only the original Bukkit scheduler exists and
 * there is a single server thread, so "global", "for an entity" and "at a
 * location" all mean "the main thread".
 */
public class SpigotSchedulerBridge implements SchedulerBridge {

    private final Plugin plugin;

    public SpigotSchedulerBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void runGlobal(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void runGlobalDelayed(Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L, delayTicks));
    }

    @Override
    public CancelHandle runGlobalRepeating(Runnable task, long initialDelayTicks, long periodTicks) {
        BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(plugin, task, Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
        return scheduled::cancel;
    }

    @Override
    public boolean runForEntity(Entity entity, Runnable task) {
        if (!entity.isValid() && !(entity instanceof org.bukkit.entity.Player)) return false;
        runGlobal(task);
        return true;
    }

    @Override
    public boolean runForEntityDelayed(Entity entity, Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L, delayTicks));
        return true;
    }

    @Override
    public CancelHandle runForEntityRepeating(Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (entity instanceof org.bukkit.entity.Player p ? !p.isOnline() : !entity.isValid()) {
                holder[0].cancel();
                return;
            }
            task.run();
        }, Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
        return holder[0]::cancel;
    }

    @Override
    public void runAtLocation(Location location, Runnable task) {
        runGlobal(task);
    }

    @Override
    public void cancelAll() {
        Bukkit.getScheduler().cancelTasks(plugin);
    }
}
