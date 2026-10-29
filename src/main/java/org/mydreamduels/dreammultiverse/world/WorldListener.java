package org.mydreamduels.dreammultiverse.world;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.platform.CancelHandle;
import org.mydreamduels.dreammultiverse.platform.PlatformSupport;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Enforces the per-world properties from MVWorld. */
public final class WorldListener implements Listener, WorldChangeTracker.Handler {

    /** Only natural spawns are filtered - spawners, eggs, breeding and commands always work. */
    private static final Set<CreatureSpawnEvent.SpawnReason> NATURAL = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.JOCKEY, CreatureSpawnEvent.SpawnReason.MOUNT,
            CreatureSpawnEvent.SpawnReason.PATROL, CreatureSpawnEvent.SpawnReason.RAID,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS, CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION,
            CreatureSpawnEvent.SpawnReason.TRAP, CreatureSpawnEvent.SpawnReason.NETHER_PORTAL);

    private final Dreammultiverse plugin;

    public WorldListener(Dreammultiverse plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ gamemode / flight

    @Override
    public void onWorldChange(Player player, World from, World to) {
        enforcePlayerRules(player, to);
    }

    public void enforcePlayerRules(Player player, World world) {
        MVWorld mv = plugin.worlds().get(world);
        if (mv == null) return;
        if (plugin.getConfig().getBoolean("enforce-gamemode", true)
                && !player.hasPermission("dreammultiverse.bypass.gamemode")
                && player.getGameMode() != mv.getGameMode()) {
            player.setGameMode(mv.getGameMode());
        }
        if (plugin.getConfig().getBoolean("enforce-flight", true) && !mv.isAllowFlight()
                && !player.hasPermission("dreammultiverse.bypass.flight")
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    // ------------------------------------------------------------------ join: first spawn / join destination

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String joinWorld = plugin.getConfig().getString("join-destination", "");
        String firstWorld = plugin.getConfig().getString("first-spawn-world", "");
        boolean first = !player.hasPlayedBefore() && plugin.getConfig().getBoolean("first-spawn-override", true);

        String targetName = null;
        if (joinWorld != null && !joinWorld.isBlank()) targetName = joinWorld;
        else if (first) targetName = firstWorld == null || firstWorld.isBlank() ? plugin.worlds().defaultWorld().getName() : firstWorld;

        if (targetName == null) {
            enforcePlayerRules(player, player.getWorld());
            return;
        }
        MVWorld target = plugin.worlds().find(targetName);
        if (target == null || !target.isLoaded()) {
            plugin.getLogger().warning("join/first-spawn world '" + targetName + "' is not loaded.");
            enforcePlayerRules(player, player.getWorld());
            return;
        }
        // One tick later - teleporting inside the join event itself is unreliable on every platform.
        plugin.scheduler().runForEntityDelayed(player, () -> {
            enforcePlayerRules(player, player.getWorld());
            plugin.teleports().toSpawn(Bukkit.getConsoleSender(), player, target, null);
        }, 1L);
    }

    // ------------------------------------------------------------------ respawn

    /** Paper/Spigot. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Location redirect = respawnTarget(event.getPlayer(), event.getPlayer().getWorld(), event.isBedSpawn() || event.isAnchorSpawn());
        if (redirect != null) event.setRespawnLocation(redirect);
    }

    /**
     * Folia never fires PlayerRespawnEvent, so remember the death and move
     * the player once they're alive again.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!PlatformSupport.isFolia()) return;
        Player player = event.getEntity();
        World deathWorld = player.getWorld();
        Location bed = player.getRespawnLocation(false);
        CancelHandle[] handle = new CancelHandle[1];
        int[] waited = {0};
        handle[0] = plugin.scheduler().runForEntityRepeating(player, () -> {
            if (!player.isOnline() || ++waited[0] > 2400) {
                if (handle[0] != null) handle[0].cancel();
                return;
            }
            if (player.isDead()) return;
            if (handle[0] != null) handle[0].cancel();
            boolean usedBed = bed != null && bed.getWorld() != null && bed.getWorld().equals(player.getWorld())
                    && bed.distanceSquared(player.getLocation()) < 16 * 16;
            Location redirect = respawnTarget(player, deathWorld, usedBed);
            if (redirect != null) plugin.teleports().teleportTo(Bukkit.getConsoleSender(), player, redirect, true, null, null);
        }, 5L, 5L);
    }

    /** Where a player who died in deathWorld should respawn, or null to leave vanilla's choice alone. */
    private Location respawnTarget(Player player, World deathWorld, boolean usedBedOrAnchor) {
        MVWorld mv = plugin.worlds().get(deathWorld);
        if (mv == null) return null;
        if (usedBedOrAnchor && mv.isBedRespawn()) return null;
        MVWorld target = mv;
        if (!mv.getRespawnWorld().isBlank()) {
            MVWorld r = plugin.worlds().find(mv.getRespawnWorld());
            if (r != null && r.isLoaded()) target = r;
        }
        return target.getSpawnLocation();
    }

    // ------------------------------------------------------------------ mob spawning

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!NATURAL.contains(event.getSpawnReason())) return;
        MVWorld mv = plugin.worlds().get(event.getLocation().getWorld());
        if (mv == null) return;
        Entity e = event.getEntity();
        String type = e.getType().name().toLowerCase(Locale.ROOT);
        if (e instanceof Enemy) {
            if (blocked(mv.isAllowMonsters(), mv.getMonsterExceptions(), type)) event.setCancelled(true);
        } else if (e instanceof Animals || e instanceof WaterMob || e instanceof Ambient) {
            if (blocked(mv.isAllowAnimals(), mv.getAnimalExceptions(), type)) event.setCancelled(true);
        }
    }

    /** Multiverse semantics: exceptions are the ONLY ones allowed when spawning is off, and the only ones blocked when it's on. */
    private static boolean blocked(boolean allowed, java.util.List<String> exceptions, String type) {
        boolean listed = false;
        for (String ex : exceptions) {
            if (ex.equalsIgnoreCase(type) || ex.equalsIgnoreCase("minecraft:" + type)) {
                listed = true;
                break;
            }
        }
        return allowed == listed;
    }

    // ------------------------------------------------------------------ hunger / autoheal / weather / portals

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        MVWorld mv = plugin.worlds().get(player.getWorld());
        if (mv != null && !mv.isHunger() && event.getFoodLevel() < player.getFoodLevel()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EntityRegainHealthEvent.RegainReason r = event.getRegainReason();
        if (r != EntityRegainHealthEvent.RegainReason.SATIATED && r != EntityRegainHealthEvent.RegainReason.REGEN) return;
        MVWorld mv = plugin.worlds().get(player.getWorld());
        if (mv != null && !mv.isAutoHeal()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        MVWorld mv = plugin.worlds().get(event.getWorld());
        if (mv != null && !mv.isAllowWeather() && event.toWeatherState()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThunder(ThunderChangeEvent event) {
        MVWorld mv = plugin.worlds().get(event.getWorld());
        if (mv != null && !mv.isAllowWeather() && event.toThunderState()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getReason() == PortalCreateEvent.CreateReason.END_PLATFORM) return; // landing platform, not a portal
        MVWorld mv = plugin.worlds().get(event.getWorld());
        if (mv == null || mv.getPortalForm().allowsNether()) return;
        event.setCancelled(true);
        if (event.getEntity() instanceof Player p) {
            org.mydreamduels.dreammultiverse.util.Msg.err(p, "Nether portals can't be made in this world.");
        }
    }

    /** End portals are made by filling frames with eyes - block that when the world disallows them. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEyeInFrame(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != org.bukkit.Material.END_PORTAL_FRAME) return;
        if (event.getItem() == null || event.getItem().getType() != org.bukkit.Material.ENDER_EYE) return;
        MVWorld mv = plugin.worlds().get(event.getClickedBlock().getWorld());
        if (mv == null || mv.getPortalForm().allowsEnd()) return;
        event.setCancelled(true);
        org.mydreamduels.dreammultiverse.util.Msg.err(event.getPlayer(), "End portals can't be made in this world.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        MVWorld mv = plugin.worlds().get(event.getWorld());
        if (mv != null) mv.applyTo(event.getWorld());
    }

    // ------------------------------------------------------------------ chat prefix

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getConfig().getBoolean("prefix-chat", false)) return;
        MVWorld mv = plugin.worlds().get(event.getPlayer().getWorld());
        if (mv == null) return;
        String format = plugin.getConfig().getString("prefix-chat-format", "&8[&r%world%&8]&r ");
        String prefix = org.mydreamduels.dreammultiverse.util.Msg.color(format.replace("%world%", mv.getDisplayName()))
                .replace("%", "%%");
        event.setFormat(prefix + event.getFormat());
    }
}
