package org.mydreamduels.dreammultiverse.world;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.mydreamduels.dreammultiverse.util.Msg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Everything Dreammultiverse knows about one world: how to (re)create it,
 * plus every per-world rule that /mv modify can change. Fields are
 * volatile since on Folia they're read from many region threads at once
 * (event handlers) while a command on another thread changes them.
 */
public final class MVWorld {

    private final String name;

    // --- how the world gets created/loaded ---
    private volatile World.Environment environment = World.Environment.NORMAL;
    private volatile Long seed;
    private volatile String generator;
    private volatile String worldType = "NORMAL";
    private volatile boolean structures = true;

    // --- properties ---
    private volatile String alias = "";
    private volatile GameMode gameMode = GameMode.SURVIVAL;
    private volatile Difficulty difficulty = Difficulty.NORMAL;
    private volatile boolean pvp = true;
    private volatile boolean allowAnimals = true;
    private volatile boolean allowMonsters = true;
    private final List<String> animalExceptions = new CopyOnWriteArrayList<>();
    private final List<String> monsterExceptions = new CopyOnWriteArrayList<>();
    private volatile boolean hunger = true;
    private volatile boolean autoHeal = true;
    private volatile boolean allowFlight = true;
    private volatile boolean allowWeather = true;
    private volatile int playerLimit = -1;
    private volatile double entryFee = 0;
    private volatile String currency = "money";
    private volatile String respawnWorld = "";
    private volatile boolean bedRespawn = true;
    private volatile boolean adjustSpawn = true;
    private volatile boolean autoLoad = true;
    private volatile boolean hidden = false;
    private volatile PortalForm portalForm = PortalForm.ALL;
    private volatile double scale = 1.0;
    private final List<String> worldBlacklist = new CopyOnWriteArrayList<>();
    private volatile double[] spawn; // x, y, z, yaw, pitch - null = use the world's own spawn

    public MVWorld(String name) {
        this.name = name;
    }

    /** Fresh entry for a world that exists right now - copies its current state as the defaults. */
    @SuppressWarnings("deprecation")
    public static MVWorld fromBukkit(World world) {
        MVWorld w = new MVWorld(world.getName());
        w.environment = world.getEnvironment();
        w.seed = world.getSeed();
        w.difficulty = world.getDifficulty();
        w.pvp = world.getPVP();
        w.allowAnimals = world.getAllowAnimals();
        w.allowMonsters = world.getAllowMonsters();
        w.scale = defaultScale(world.getEnvironment());
        if (world.getGenerator() != null) {
            org.bukkit.plugin.Plugin owner = null;
            for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (p.getClass().getClassLoader() == world.getGenerator().getClass().getClassLoader()) {
                    owner = p;
                    break;
                }
            }
            if (owner != null) w.generator = owner.getName();
        }
        return w;
    }

    public static double defaultScale(World.Environment env) {
        return env == World.Environment.NETHER ? 8.0 : 1.0;
    }

    // ------------------------------------------------------------------ properties registry

    /** One /mv modify-able property. List properties support add/remove/clear on top of set/reset. */
    public record Property(String name, String description, Function<MVWorld, String> getter,
                           BiConsumer<MVWorld, String> setter, Function<MVWorld, List<String>> list,
                           List<String> suggestions) {
        public boolean isList() {
            return list != null;
        }
    }

    private static final Map<String, Property> PROPERTIES = new LinkedHashMap<>();

    private static void prop(String name, String desc, Function<MVWorld, String> get, BiConsumer<MVWorld, String> set, List<String> suggest) {
        PROPERTIES.put(name.toLowerCase(Locale.ROOT), new Property(name, desc, get, set, null, suggest));
    }

    private static void listProp(String name, String desc, Function<MVWorld, List<String>> list) {
        PROPERTIES.put(name.toLowerCase(Locale.ROOT), new Property(name, desc, w -> String.join(", ", list.apply(w)), null, list, List.of()));
    }

    private static final List<String> BOOL = List.of("true", "false");

    static {
        prop("alias", "Display name (& colours allowed)", w -> w.alias, (w, v) -> w.alias = v, List.of());
        prop("gamemode", "Gamemode players get in this world", w -> w.gameMode.name(), (w, v) -> w.gameMode = parseEnum(GameMode.class, v), enumNames(GameMode.class));
        prop("difficulty", "World difficulty", w -> w.difficulty.name(), (w, v) -> w.difficulty = parseEnum(Difficulty.class, v), enumNames(Difficulty.class));
        prop("pvp", "Players can hurt each other", w -> str(w.pvp), (w, v) -> w.pvp = bool(v), BOOL);
        prop("animals", "Animals spawn naturally", w -> str(w.allowAnimals), (w, v) -> w.allowAnimals = bool(v), BOOL);
        prop("monsters", "Monsters spawn naturally", w -> str(w.allowMonsters), (w, v) -> w.allowMonsters = bool(v), BOOL);
        prop("hunger", "Hunger drains", w -> str(w.hunger), (w, v) -> w.hunger = bool(v), BOOL);
        prop("autoheal", "Health regenerates naturally", w -> str(w.autoHeal), (w, v) -> w.autoHeal = bool(v), BOOL);
        prop("allowflight", "Players may fly (survival/adventure)", w -> str(w.allowFlight), (w, v) -> w.allowFlight = bool(v), BOOL);
        prop("weather", "Rain/thunder allowed", w -> str(w.allowWeather), (w, v) -> w.allowWeather = bool(v), BOOL);
        prop("playerlimit", "Max players (-1 = unlimited)", w -> String.valueOf(w.playerLimit), (w, v) -> w.playerLimit = Integer.parseInt(v), List.of("-1", "10", "50"));
        prop("entryfee", "Cost to enter (0 = free)", w -> String.valueOf(w.entryFee), (w, v) -> w.entryFee = Math.max(0, Double.parseDouble(v)), List.of("0", "100"));
        prop("currency", "'money' (Vault) or an item, e.g. DIAMOND", w -> w.currency, (w, v) -> w.currency = v, List.of("money", "DIAMOND", "EMERALD", "GOLD_INGOT"));
        prop("respawnworld", "World players respawn in after dying here (empty = this world)", w -> w.respawnWorld, (w, v) -> w.respawnWorld = v, List.of());
        prop("bedrespawn", "Beds/anchors are used for respawning", w -> str(w.bedRespawn), (w, v) -> w.bedRespawn = bool(v), BOOL);
        prop("adjustspawn", "Move unsafe spawns to a safe spot", w -> str(w.adjustSpawn), (w, v) -> w.adjustSpawn = bool(v), BOOL);
        prop("autoload", "Load this world on startup", w -> str(w.autoLoad), (w, v) -> w.autoLoad = bool(v), BOOL);
        prop("hidden", "Hide from /mv list for non-admins", w -> str(w.hidden), (w, v) -> w.hidden = bool(v), BOOL);
        prop("portalform", "Which vanilla portals can be lit", w -> w.portalForm.name(), (w, v) -> w.portalForm = parseEnum(PortalForm.class, v), enumNames(PortalForm.class));
        prop("scale", "Coordinate scale for nether-portal linking", w -> String.valueOf(w.scale), (w, v) -> {
            double d = Double.parseDouble(v);
            if (d <= 0) throw new IllegalArgumentException("Scale must be above 0");
            w.scale = d;
        }, List.of("1", "8"));
        listProp("animalexceptions", "Animals that ignore the 'animals' rule", w -> w.animalExceptions);
        listProp("monsterexceptions", "Monsters that ignore the 'monsters' rule", w -> w.monsterExceptions);
        listProp("blacklist", "Worlds you can't come here from", w -> w.worldBlacklist);
    }

    public static Map<String, Property> properties() {
        return Collections.unmodifiableMap(PROPERTIES);
    }

    public static Property property(String name) {
        return PROPERTIES.get(name.toLowerCase(Locale.ROOT));
    }

    /** Back to the value a brand new entry would have. */
    public void reset(Property p) {
        MVWorld fresh = new MVWorld(name);
        fresh.scale = defaultScale(environment);
        if (p.isList()) {
            p.list().apply(this).clear();
        } else {
            p.setter().accept(this, p.getter().apply(fresh));
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String v) {
        try {
            return Enum.valueOf(type, v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Must be one of: " + String.join(", ", enumNames(type)));
        }
    }

    private static <E extends Enum<E>> List<String> enumNames(Class<E> type) {
        List<String> out = new ArrayList<>();
        for (E e : type.getEnumConstants()) out.add(e.name().toLowerCase(Locale.ROOT));
        return out;
    }

    private static boolean bool(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1", "enable", "enabled" -> true;
            case "false", "no", "off", "0", "disable", "disabled" -> false;
            default -> throw new IllegalArgumentException("Must be true or false");
        };
    }

    private static String str(boolean b) {
        return String.valueOf(b);
    }

    // ------------------------------------------------------------------ persistence

    public void save(ConfigurationSection s) {
        s.set("environment", environment.name());
        s.set("seed", seed);
        s.set("generator", generator);
        s.set("world-type", worldType);
        s.set("structures", structures);
        for (Property p : PROPERTIES.values()) {
            String key = p.name();
            if (p.isList()) {
                s.set(key, new ArrayList<>(p.list().apply(this)));
            } else {
                s.set(key, p.getter().apply(this));
            }
        }
        if (spawn != null) {
            s.set("spawn.x", spawn[0]);
            s.set("spawn.y", spawn[1]);
            s.set("spawn.z", spawn[2]);
            s.set("spawn.yaw", spawn[3]);
            s.set("spawn.pitch", spawn[4]);
        } else {
            s.set("spawn", null);
        }
    }

    public static MVWorld load(String name, ConfigurationSection s, java.util.logging.Logger logger) {
        MVWorld w = new MVWorld(name);
        try {
            w.environment = World.Environment.valueOf(s.getString("environment", "NORMAL").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("World " + name + ": unknown environment, using NORMAL");
        }
        w.seed = s.contains("seed") && s.get("seed") != null ? s.getLong("seed") : null;
        w.generator = s.getString("generator");
        w.worldType = s.getString("world-type", "NORMAL");
        w.structures = s.getBoolean("structures", true);
        w.scale = defaultScale(w.environment);
        for (Property p : PROPERTIES.values()) {
            if (!s.contains(p.name())) continue;
            try {
                if (p.isList()) {
                    List<String> target = p.list().apply(w);
                    target.clear();
                    target.addAll(s.getStringList(p.name()));
                } else {
                    p.setter().accept(w, String.valueOf(s.get(p.name())));
                }
            } catch (RuntimeException e) {
                logger.warning("World " + name + ": bad value for '" + p.name() + "' (" + e.getMessage() + "), using default");
            }
        }
        if (s.isConfigurationSection("spawn")) {
            w.spawn = new double[]{s.getDouble("spawn.x"), s.getDouble("spawn.y"), s.getDouble("spawn.z"),
                    s.getDouble("spawn.yaw"), s.getDouble("spawn.pitch")};
        }
        return w;
    }

    // ------------------------------------------------------------------ live world

    public World getBukkitWorld() {
        return Bukkit.getWorld(name);
    }

    public boolean isLoaded() {
        return getBukkitWorld() != null;
    }

    /** Pushes the properties that live on the Bukkit World itself. Must run on the global thread. */
    @SuppressWarnings("deprecation")
    public void applyTo(World world) {
        world.setDifficulty(difficulty);
        world.setPVP(pvp);
        world.setSpawnFlags(allowMonsters || !monsterExceptions.isEmpty(), allowAnimals || !animalExceptions.isEmpty());
        if (!allowWeather && (world.hasStorm() || world.isThundering())) {
            world.setStorm(false);
            world.setThundering(false);
        }
    }

    public Location getSpawnLocation() {
        World world = getBukkitWorld();
        if (world == null) return null;
        double[] s = spawn;
        if (s == null) {
            Location l = world.getSpawnLocation();
            return new Location(world, l.getBlockX() + 0.5, l.getY(), l.getBlockZ() + 0.5, l.getYaw(), l.getPitch());
        }
        return new Location(world, s[0], s[1], s[2], (float) s[3], (float) s[4]);
    }

    public void setSpawn(Location loc) {
        spawn = new double[]{loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()};
    }

    // ------------------------------------------------------------------ getters

    public String getName() {
        return name;
    }

    /** Alias (coloured) if one is set, otherwise the plain name. */
    public String getDisplayName() {
        return alias == null || alias.isBlank() ? name : Msg.color(alias) + "§r";
    }

    public World.Environment getEnvironment() { return environment; }
    public void setEnvironment(World.Environment environment) { this.environment = environment; this.scale = defaultScale(environment); }
    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }
    public String getGenerator() { return generator; }
    public void setGenerator(String generator) { this.generator = generator; }
    public String getWorldType() { return worldType; }
    public void setWorldType(String worldType) { this.worldType = worldType; }
    public boolean isStructures() { return structures; }
    public void setStructures(boolean structures) { this.structures = structures; }
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
    public GameMode getGameMode() { return gameMode; }
    public void setGameMode(GameMode gameMode) { this.gameMode = gameMode; }
    public Difficulty getDifficulty() { return difficulty; }
    public void setDifficulty(Difficulty difficulty) { this.difficulty = difficulty; }
    public boolean isPvp() { return pvp; }
    public void setPvp(boolean pvp) { this.pvp = pvp; }
    public boolean isAllowAnimals() { return allowAnimals; }
    public boolean isAllowMonsters() { return allowMonsters; }
    public List<String> getAnimalExceptions() { return animalExceptions; }
    public List<String> getMonsterExceptions() { return monsterExceptions; }
    public boolean isHunger() { return hunger; }
    public boolean isAutoHeal() { return autoHeal; }
    public boolean isAllowFlight() { return allowFlight; }
    public boolean isAllowWeather() { return allowWeather; }
    public int getPlayerLimit() { return playerLimit; }
    public double getEntryFee() { return entryFee; }
    public String getCurrency() { return currency; }
    public String getRespawnWorld() { return respawnWorld; }
    public boolean isBedRespawn() { return bedRespawn; }
    public boolean isAdjustSpawn() { return adjustSpawn; }
    public void setAdjustSpawn(boolean adjustSpawn) { this.adjustSpawn = adjustSpawn; }
    public boolean isAutoLoad() { return autoLoad; }
    public boolean isHidden() { return hidden; }
    public PortalForm getPortalForm() { return portalForm; }
    public double getScale() { return scale; }
    public List<String> getWorldBlacklist() { return worldBlacklist; }

    /** Copies every property (not the creation settings or name) from another entry - used by clone. */
    public void copyPropertiesFrom(MVWorld other) {
        for (Property p : PROPERTIES.values()) {
            if (p.isList()) {
                List<String> target = p.list().apply(this);
                target.clear();
                target.addAll(p.list().apply(other));
            } else if (!p.name().equals("alias")) {
                p.setter().accept(this, p.getter().apply(other));
            }
        }
        this.spawn = other.spawn == null ? null : other.spawn.clone();
    }
}
