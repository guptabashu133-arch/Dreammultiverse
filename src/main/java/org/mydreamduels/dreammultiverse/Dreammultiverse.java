package org.mydreamduels.dreammultiverse;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.mydreamduels.dreammultiverse.anchor.AnchorManager;
import org.mydreamduels.dreammultiverse.command.CommandRouter;
import org.mydreamduels.dreammultiverse.command.ConfirmManager;
import org.mydreamduels.dreammultiverse.command.InventoryCommand;
import org.mydreamduels.dreammultiverse.command.MvCommand;
import org.mydreamduels.dreammultiverse.command.NetherPortalCommand;
import org.mydreamduels.dreammultiverse.command.PortalCommand;
import org.mydreamduels.dreammultiverse.economy.EconomyHook;
import org.mydreamduels.dreammultiverse.inventory.InventoryListener;
import org.mydreamduels.dreammultiverse.inventory.InventoryManager;
import org.mydreamduels.dreammultiverse.netherportal.NetherPortalListener;
import org.mydreamduels.dreammultiverse.netherportal.NetherPortalManager;
import org.mydreamduels.dreammultiverse.platform.PaperSchedulerBridge;
import org.mydreamduels.dreammultiverse.platform.PlatformSupport;
import org.mydreamduels.dreammultiverse.platform.SchedulerBridge;
import org.mydreamduels.dreammultiverse.platform.SpigotSchedulerBridge;
import org.mydreamduels.dreammultiverse.portal.PortalListener;
import org.mydreamduels.dreammultiverse.portal.PortalManager;
import org.mydreamduels.dreammultiverse.portal.SelectionManager;
import org.mydreamduels.dreammultiverse.signportal.SignPortalListener;
import org.mydreamduels.dreammultiverse.teleport.AccessChecker;
import org.mydreamduels.dreammultiverse.teleport.AccessListener;
import org.mydreamduels.dreammultiverse.teleport.TeleportService;
import org.mydreamduels.dreammultiverse.util.IoQueue;
import org.mydreamduels.dreammultiverse.util.Msg;
import org.mydreamduels.dreammultiverse.world.DatapackWorlds;
import org.mydreamduels.dreammultiverse.world.PendingResets;
import org.mydreamduels.dreammultiverse.world.WorldChangeTracker;
import org.mydreamduels.dreammultiverse.world.WorldListener;
import org.mydreamduels.dreammultiverse.world.WorldManager;

/**
 * Dreammultiverse - Multiverse-Core, -Portals, -NetherPortals,
 * -Inventories and -SignPortals in one plugin, running on Paper, Folia and
 * Spigot. See platform/PlatformSupport for what differs on Folia.
 */
public class Dreammultiverse extends JavaPlugin {

    private SchedulerBridge scheduler;
    private IoQueue io;
    private EconomyHook economy;
    private WorldManager worlds;
    private PendingResets pendingResets;
    private DatapackWorlds datapackWorlds;
    private WorldChangeTracker tracker;
    private WorldListener worldListener;
    private AccessChecker access;
    private TeleportService teleports;
    private AnchorManager anchors;
    private PortalManager portals;
    private SelectionManager selections;
    private NetherPortalManager netherPortals;
    private InventoryManager inventories;
    private ConfirmManager confirms;
    private boolean placeholders;

    @Override
    public void onLoad() {
        // Must happen before the server loads any world - see PendingResets.
        pendingResets = new PendingResets(this);
        pendingResets.processOnStartup();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        scheduler = PlatformSupport.hasUnifiedSchedulers() ? new PaperSchedulerBridge(this) : new SpigotSchedulerBridge(this);
        io = new IoQueue(getLogger());
        economy = new EconomyHook(getLogger());
        worlds = new WorldManager(this);
        datapackWorlds = new DatapackWorlds(this);
        tracker = new WorldChangeTracker(this);
        worldListener = new WorldListener(this);
        access = new AccessChecker(this);
        teleports = new TeleportService(this);
        anchors = new AnchorManager(this);
        portals = new PortalManager(this);
        selections = new SelectionManager();
        netherPortals = new NetherPortalManager(this);
        inventories = new InventoryManager(this);
        applyConfig();

        worlds.load();
        worlds.startup();
        anchors.load();
        portals.load();
        netherPortals.load();
        inventories.loadGroups();

        InventoryListener inventoryListener = new InventoryListener(inventories);
        // Inventories must swap BEFORE world rules change the gamemode, so its handler goes first.
        tracker.addHandler(inventoryListener);
        tracker.addHandler(worldListener);

        var pm = getServer().getPluginManager();
        pm.registerEvents(tracker, this);
        pm.registerEvents(inventoryListener, this);
        pm.registerEvents(worldListener, this);
        pm.registerEvents(new AccessListener(this), this);
        pm.registerEvents(new PortalListener(this), this);
        pm.registerEvents(new NetherPortalListener(this, netherPortals), this);
        pm.registerEvents(new SignPortalListener(this), this);

        MvCommand mv = new MvCommand(this);
        bind("dreammultiverse", mv.router());
        bind("dreamportals", new PortalCommand(this).router());
        bind("dreamnetherportals", new NetherPortalCommand(this).router());
        bind("dreaminventories", new InventoryCommand(this).router());
        shortcut("mvtp", mv.router(), "tp");
        shortcut("mvspawn", mv.router(), "spawn");
        shortcut("mvsetspawn", mv.router(), "setspawn");
        shortcut("mvlist", mv.router(), "list");
        shortcut("mvwho", mv.router(), "who");
        shortcut("mvcoord", mv.router(), "coord");
        shortcut("mvconfirm", mv.router(), "confirm");

        // Soft dependencies may enable after us - hook once everything is up.
        scheduler.runGlobalDelayed(this::hookSoftDependencies, 1L);

        // /reload or a plugin manager: players already online
        for (Player p : getServer().getOnlinePlayers()) tracker.start(p);

        getLogger().info("Enabled on " + PlatformSupport.name() + " with " + worlds.all().size() + " worlds, "
                + portals.all().size() + " portals.");
        if (PlatformSupport.isFolia()) {
            getLogger().info("Folia: /mv create sets new worlds up to load at the next restart; everything else is fully supported.");
        }
    }

    @Override
    public void onDisable() {
        if (inventories != null) inventories.saveAll();
        if (tracker != null) tracker.shutdown();
        if (scheduler != null) scheduler.cancelAll();
        if (io != null) io.shutdown();
    }

    private void hookSoftDependencies() {
        economy.hook();
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null && !placeholders) {
            try {
                placeholders = new org.mydreamduels.dreammultiverse.hook.PlaceholderHook(this).register();
                if (placeholders) getLogger().info("Registered PlaceholderAPI placeholders (%dreammultiverse_...%).");
            } catch (Throwable t) {
                getLogger().warning("Could not register PlaceholderAPI placeholders: " + t);
            }
        }
    }

    private void bind(String name, CommandRouter router) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("Command " + name + " is missing from plugin.yml");
            return;
        }
        cmd.setExecutor(router);
        cmd.setTabCompleter(router);
    }

    private void shortcut(String name, CommandRouter router, String sub) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) return;
        cmd.setExecutor((sender, command, label, args) -> {
            router.dispatch(sender, sub, args);
            return true;
        });
        cmd.setTabCompleter((sender, command, alias, args) -> router.tabFor(sender, sub, args));
    }

    /** Lets bukkit.yml and other plugins use "Dreammultiverse" / "Dreammultiverse:<biome>" as a void generator. */
    @Override
    public org.bukkit.generator.ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new org.mydreamduels.dreammultiverse.generator.VoidGenerator(id == null || id.equalsIgnoreCase("void") ? null : id);
    }

    /** Re-reads values that are cached outside getConfig(). */
    public void applyConfig() {
        Msg.setPrefix(getConfig().getString("prefix", "&8[&bDreamMV&8] &r"));
        confirms = new ConfirmManager(getConfig().getLong("confirm-timeout-seconds", 15));
    }

    /** /mv reload - global thread. */
    public void reloadAll() {
        io.flush();
        reloadConfig();
        applyConfig();
        worlds.load();
        worlds.startup();
        anchors.load();
        portals.load();
        netherPortals.load();
        inventories.loadGroups();
        economy.hook();
    }

    public SchedulerBridge scheduler() { return scheduler; }
    public IoQueue io() { return io; }
    public EconomyHook economy() { return economy; }
    public WorldManager worlds() { return worlds; }
    public PendingResets pendingResets() { return pendingResets; }
    public DatapackWorlds datapackWorlds() { return datapackWorlds; }
    public WorldChangeTracker tracker() { return tracker; }
    public WorldListener worldListener() { return worldListener; }
    public AccessChecker access() { return access; }
    public TeleportService teleports() { return teleports; }
    public AnchorManager anchors() { return anchors; }
    public PortalManager portals() { return portals; }
    public SelectionManager selections() { return selections; }
    public NetherPortalManager netherPortals() { return netherPortals; }
    public InventoryManager inventories() { return inventories; }
    public ConfirmManager confirms() { return confirms; }
    public boolean hasPlaceholders() { return placeholders; }
}
