package org.mydreamduels.dreammultiverse.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;


/** Used whenever PlatformSupport.hasUnifiedSchedulers() is true - covers both regular Paper and Folia. */
public class PaperSchedulerBridge implements SchedulerBridge {

    private final Plugin plugin;

    public PaperSchedulerBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, st -> task.run());
    }

    @Override
    public void runGlobal(Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, st -> task.run());
    }

    @Override
    public void runGlobalDelayed(Runnable task, long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, st -> task.run(), Math.max(1L, delayTicks));
    }

    @Override
    public CancelHandle runGlobalRepeating(Runnable task, long initialDelayTicks, long periodTicks) {
        ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, st -> task.run(),
                Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
        return scheduled::cancel;
    }

    @Override
    public boolean runForEntity(Entity entity, Runnable task) {
        return entity.getScheduler().run(plugin, st -> task.run(), null) != null;
    }

    @Override
    public boolean runForEntityDelayed(Entity entity, Runnable task, long delayTicks) {
        return entity.getScheduler().runDelayed(plugin, st -> task.run(), null, Math.max(1L, delayTicks)) != null;
    }

    @Override
    public CancelHandle runForEntityRepeating(Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        ScheduledTask scheduled = entity.getScheduler().runAtFixedRate(plugin, st -> task.run(), null,
                Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
        return scheduled == null ? null : scheduled::cancel;
    }

    @Override
    public void runAtLocation(Location location, Runnable task) {
        Bukkit.getRegionScheduler().run(plugin, location, st -> task.run());
    }

    @Override
    public void cancelAll() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }
}
