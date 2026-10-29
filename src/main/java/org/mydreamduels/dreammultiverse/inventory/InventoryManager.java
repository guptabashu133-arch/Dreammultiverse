package org.mydreamduels.dreammultiverse.inventory;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.platform.PlatformSupport;
import org.mydreamduels.dreammultiverse.util.LocationUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-world / per-group player data, the way Multiverse-Inventories does it.
 *
 * Every player has one file (inventories/players/UUID.yml) holding a
 * profile per world ("worlds.NAME.GAMEMODE") and per group
 * ("groups.NAME.GAMEMODE"). On a world change A -> B, for every sharable:
 *   - a group containing both A and B shares it  -> keep what they have
 *   - otherwise a group containing B shares it   -> load the group profile
 *   - otherwise                                  -> load B's world profile
 * Files are read ahead of time in AsyncPlayerPreLoginEvent and written by
 * the IO queue, so no player/region thread ever touches the disk.
 */
public final class InventoryManager {

    private final Dreammultiverse plugin;
    private final File groupsFile;
    private final File playersDir;
    private final Map<String, InvGroup> groups = new ConcurrentHashMap<>();
    private final Map<UUID, YamlConfiguration> cache = new ConcurrentHashMap<>();

    public InventoryManager(Dreammultiverse plugin) {
        this.plugin = plugin;
        File base = new File(plugin.getDataFolder(), "inventories");
        this.groupsFile = new File(base, "groups.yml");
        this.playersDir = new File(base, "players");
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("inventories.enabled", true);
    }

    private boolean perGamemode() {
        return plugin.getConfig().getBoolean("inventories.separate-gamemode-inventories", false);
    }

    // ------------------------------------------------------------------ groups

    public void loadGroups() {
        groups.clear();
        if (!groupsFile.isFile()) {
            // First start: the server's own overworld/nether/end share everything, like vanilla.
            InvGroup def = new InvGroup("default");
            for (World w : Bukkit.getWorlds()) def.addWorld(w.getName());
            groups.put("default", def);
            saveGroups();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(groupsFile);
        ConfigurationSection root = yaml.getConfigurationSection("groups");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            InvGroup g = new InvGroup(name);
            for (String w : root.getStringList(name + ".worlds")) g.addWorld(w);
            Set<Sharable> shares = EnumSet.noneOf(Sharable.class);
            for (String s : root.getStringList(name + ".shares")) {
                if (s.equalsIgnoreCase("all")) shares.addAll(Sharable.all());
                Sharable sh = Sharable.parse(s);
                if (sh != null) shares.add(sh);
            }
            g.setShares(shares);
            groups.put(name.toLowerCase(Locale.ROOT), g);
        }
    }

    public void saveGroups() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Dreammultiverse inventory groups. shares: all, or any of "
                + String.join(", ", Sharable.all().stream().map(Sharable::id).toList())));
        for (InvGroup g : groups.values()) {
            yaml.set("groups." + g.getName() + ".worlds", new ArrayList<>(g.getWorlds()));
            List<String> shares = g.getShares().containsAll(Sharable.all()) ? List.of("all")
                    : g.getShares().stream().map(Sharable::id).toList();
            yaml.set("groups." + g.getName() + ".shares", new ArrayList<>(shares));
        }
        plugin.io().write(groupsFile, yaml.saveToString());
    }

    public InvGroup group(String name) {
        return groups.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<InvGroup> groups() {
        return groups.values();
    }

    public InvGroup createGroup(String name) {
        InvGroup g = new InvGroup(name);
        groups.put(name.toLowerCase(Locale.ROOT), g);
        saveGroups();
        return g;
    }

    public boolean deleteGroup(String name) {
        boolean removed = groups.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) saveGroups();
        return removed;
    }

    public List<InvGroup> groupsOf(String world) {
        List<InvGroup> out = new ArrayList<>();
        for (InvGroup g : groups.values()) if (g.contains(world)) out.add(g);
        return out;
    }

    // ------------------------------------------------------------------ player files

    private File fileOf(UUID id) {
        return new File(playersDir, id + ".yml");
    }

    /**
     * Called from AsyncPlayerPreLoginEvent. Runs through the IO queue (and waits for it),
     * so a quick rejoin always sees the save from the previous quit.
     */
    public void preload(UUID id) {
        if (!enabled()) return;
        CompletableFuture<YamlConfiguration> f = new CompletableFuture<>();
        plugin.io().submit(() -> f.complete(YamlConfiguration.loadConfiguration(fileOf(id))));
        try {
            cache.put(id, f.get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not preload inventory data for " + id + ": " + e);
        }
    }

    private YamlConfiguration data(Player player) {
        return cache.computeIfAbsent(player.getUniqueId(), id -> YamlConfiguration.loadConfiguration(fileOf(id)));
    }

    private void write(Player player, YamlConfiguration data) {
        plugin.io().write(fileOf(player.getUniqueId()), data.saveToString());
    }

    private String gm(GameMode mode) {
        return perGamemode() ? mode.name().toLowerCase(Locale.ROOT) : "default";
    }

    private static ConfigurationSection section(YamlConfiguration data, String path) {
        ConfigurationSection s = data.getConfigurationSection(path);
        return s != null ? s : data.createSection(path);
    }

    // ------------------------------------------------------------------ swapping

    /** Player's thread. Swaps profiles for a move from one world to another (from == null: nothing is kept). */
    public void changeWorld(Player player, String from, String to) {
        if (!enabled() || player.hasPermission("dreammultiverse.inventory.bypass")) return;
        if (from == null || from.equalsIgnoreCase(to)) return;
        YamlConfiguration data = data(player);
        String mode = gm(player.getGameMode());
        store(player, data, from, mode);
        restore(player, data, from, to, mode);
        data.set("last-world", to);
        write(player, data);
    }

    /** Player's thread, before the gamemode actually changes. */
    public void changeGameMode(Player player, GameMode oldMode, GameMode newMode) {
        if (!enabled() || !perGamemode() || oldMode == newMode || player.hasPermission("dreammultiverse.inventory.bypass")) return;
        YamlConfiguration data = data(player);
        String world = player.getWorld().getName();
        store(player, data, world, gm(oldMode));
        restore(player, data, null, world, gm(newMode));
        write(player, data);
    }

    /** Join: catch up if the player was moved while offline, then remember where they are. */
    public void join(Player player) {
        if (!enabled()) return;
        YamlConfiguration data = data(player);
        String last = data.getString("last-world");
        String now = player.getWorld().getName();
        if (last != null && !last.equalsIgnoreCase(now)) {
            changeWorld(player, last, now);
        } else if (last == null) {
            data.set("last-world", now);
            write(player, data);
        }
    }

    public void quit(Player player) {
        YamlConfiguration data = cache.remove(player.getUniqueId());
        if (data == null || !enabled() || player.hasPermission("dreammultiverse.inventory.bypass")) return;
        String world = player.getWorld().getName();
        store(player, data, world, gm(player.getGameMode()));
        data.set("last-world", world);
        write(player, data);
    }

    /** onDisable - best effort for everyone still online. */
    public void saveAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                quit(p);
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not save inventory of " + p.getName() + ": " + t);
            }
        }
    }

    private void store(Player player, YamlConfiguration data, String world, String mode) {
        writeState(player, section(data, "worlds." + world.toLowerCase(Locale.ROOT) + "." + mode), Sharable.all());
        for (InvGroup g : groupsOf(world)) {
            writeState(player, section(data, "groups." + g.getName().toLowerCase(Locale.ROOT) + "." + mode), g.getShares());
        }
    }

    private void restore(Player player, YamlConfiguration data, String from, String to, String mode) {
        List<InvGroup> fromGroups = from == null ? List.of() : groupsOf(from);
        List<InvGroup> toGroups = groupsOf(to);
        for (Sharable s : Sharable.values()) {
            boolean kept = false;
            for (InvGroup g : toGroups) {
                if (g.shares(s) && fromGroups.contains(g)) {
                    kept = true;
                    break;
                }
            }
            if (kept) continue;
            String path = null;
            for (InvGroup g : toGroups) {
                if (g.shares(s)) {
                    path = "groups." + g.getName().toLowerCase(Locale.ROOT) + "." + mode;
                    break;
                }
            }
            if (path == null) path = "worlds." + to.toLowerCase(Locale.ROOT) + "." + mode;
            readState(player, data.getConfigurationSection(path), EnumSet.of(s));
        }
        player.updateInventory();
    }

    // ------------------------------------------------------------------ player state <-> yaml

    private static void writeState(Player p, ConfigurationSection s, Set<Sharable> what) {
        PlayerInventory inv = p.getInventory();
        if (what.contains(Sharable.INVENTORY)) s.set("inventory", list(inv.getStorageContents()));
        if (what.contains(Sharable.ARMOR)) s.set("armor", list(inv.getArmorContents()));
        if (what.contains(Sharable.OFFHAND)) s.set("offhand", inv.getItemInOffHand());
        if (what.contains(Sharable.ENDER_CHEST)) s.set("ender-chest", list(p.getEnderChest().getContents()));
        if (what.contains(Sharable.HEALTH)) s.set("health", p.getHealth());
        if (what.contains(Sharable.HUNGER)) {
            s.set("food", p.getFoodLevel());
            s.set("saturation", (double) p.getSaturation());
            s.set("exhaustion", (double) p.getExhaustion());
        }
        if (what.contains(Sharable.EXPERIENCE)) {
            s.set("level", p.getLevel());
            s.set("exp", (double) p.getExp());
            s.set("total-exp", p.getTotalExperience());
        }
        if (what.contains(Sharable.POTIONS)) s.set("potions", new ArrayList<>(p.getActivePotionEffects()));
        if (what.contains(Sharable.BED_SPAWN)) {
            Location bed = PlatformSupport.hasUnifiedSchedulers() ? p.getRespawnLocation(false) : p.getRespawnLocation();
            if (bed == null || bed.getWorld() == null) s.set("bed", null);
            else LocationUtil.write(s.createSection("bed"), bed);
        }
    }

    /** Missing data (s == null or key absent) means "fresh": empty inventory, full health and food... */
    private static void readState(Player p, ConfigurationSection s, Set<Sharable> what) {
        PlayerInventory inv = p.getInventory();
        if (what.contains(Sharable.INVENTORY)) inv.setStorageContents(items(s, "inventory", inv.getStorageContents().length));
        if (what.contains(Sharable.ARMOR)) inv.setArmorContents(items(s, "armor", inv.getArmorContents().length));
        if (what.contains(Sharable.OFFHAND)) inv.setItemInOffHand(s == null ? null : s.getItemStack("offhand"));
        if (what.contains(Sharable.ENDER_CHEST)) p.getEnderChest().setContents(items(s, "ender-chest", p.getEnderChest().getSize()));
        if (what.contains(Sharable.HEALTH)) {
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            double cap = max == null ? 20.0 : max.getValue();
            double health = s == null ? cap : s.getDouble("health", cap);
            p.setHealth(Math.max(0.5, Math.min(cap, health)));
        }
        if (what.contains(Sharable.HUNGER)) {
            p.setFoodLevel(s == null ? 20 : s.getInt("food", 20));
            p.setSaturation(s == null ? 5f : (float) s.getDouble("saturation", 5));
            p.setExhaustion(s == null ? 0f : (float) s.getDouble("exhaustion", 0));
        }
        if (what.contains(Sharable.EXPERIENCE)) {
            p.setLevel(s == null ? 0 : s.getInt("level", 0));
            p.setExp(s == null ? 0f : Math.max(0f, Math.min(0.9999f, (float) s.getDouble("exp", 0))));
            p.setTotalExperience(s == null ? 0 : s.getInt("total-exp", 0));
        }
        if (what.contains(Sharable.POTIONS)) {
            for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            if (s != null) {
                for (Object o : s.getList("potions", List.of())) if (o instanceof PotionEffect e) p.addPotionEffect(e);
            }
        }
        if (what.contains(Sharable.BED_SPAWN)) {
            Location bed = s == null ? null : LocationUtil.read(s.getConfigurationSection("bed"));
            p.setRespawnLocation(bed, true);
        }
    }

    private static List<ItemStack> list(ItemStack[] items) {
        List<ItemStack> out = new ArrayList<>(items.length);
        for (ItemStack i : items) out.add(i == null ? null : i.clone());
        return out;
    }

    private static ItemStack[] items(ConfigurationSection s, String key, int size) {
        ItemStack[] out = new ItemStack[size];
        if (s == null) return out;
        List<?> raw = s.getList(key);
        if (raw == null) return out;
        for (int i = 0; i < size && i < raw.size(); i++) {
            if (raw.get(i) instanceof ItemStack item) out[i] = item;
        }
        return out;
    }
}
