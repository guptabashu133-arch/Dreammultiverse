package org.mydreamduels.dreammultiverse.command;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.WaterMob;
import org.bukkit.entity.Mob;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.command.CommandRouter.UsageException;
import org.mydreamduels.dreammultiverse.destination.Destination;
import org.mydreamduels.dreammultiverse.platform.PlatformSupport;
import org.mydreamduels.dreammultiverse.util.GameRules;
import org.mydreamduels.dreammultiverse.util.LocationUtil;
import org.mydreamduels.dreammultiverse.util.Msg;
import org.mydreamduels.dreammultiverse.world.MVWorld;
import org.mydreamduels.dreammultiverse.world.WorldManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** /mv - everything from Multiverse-Core. */
public final class MvCommand {

    private static final String P = "dreammultiverse.command.";

    private final Dreammultiverse plugin;
    private final CommandRouter router;

    public MvCommand(Dreammultiverse plugin) {
        this.plugin = plugin;
        this.router = new CommandRouter("mv", "Dreammultiverse");
        register();
    }

    public CommandRouter router() {
        return router;
    }

    // ------------------------------------------------------------------ helpers

    private MVWorld world(String name) {
        MVWorld mv = plugin.worlds().find(name);
        if (mv == null && Bukkit.getWorld(name) != null) mv = plugin.worlds().getOrAdopt(Bukkit.getWorld(name));
        if (mv == null) throw new UsageException("Unknown world '" + name + "'.");
        return mv;
    }

    private static World.Environment env(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "normal", "overworld", "world" -> World.Environment.NORMAL;
            case "nether", "hell" -> World.Environment.NETHER;
            case "end", "the_end", "theend", "starwars" -> World.Environment.THE_END;
            default -> throw new UsageException("Environment must be normal, nether or end.");
        };
    }

    /** Numbers are used as-is, any other text is hashed like vanilla does with level-seed. */
    private static long seed(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode();
        }
    }

    private static Player self(CommandSender sender) {
        if (sender instanceof Player p) return p;
        throw new UsageException("Only players can do that without naming a world/player.");
    }

    private static Player online(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) p = Bukkit.getPlayer(name);
        if (p == null) throw new UsageException("Player '" + name + "' is not online.");
        return p;
    }

    private MVWorld currentOrNamed(CommandSender sender, String[] args, int index) {
        if (args.length > index) return world(args[index]);
        return plugin.worlds().getOrAdopt(self(sender).getWorld());
    }

    private List<String> worldNames() {
        return plugin.worlds().names();
    }

    private List<String> destinations(CommandSender sender) {
        List<String> out = new ArrayList<>(plugin.worlds().loadedNames());
        out.addAll(Destination.PREFIXES);
        for (String a : plugin.anchors().names()) out.add("a:" + a);
        for (String p : plugin.portals().names()) out.add("p:" + p);
        for (String pl : Tabs.players()) out.add("pl:" + pl);
        if (sender instanceof Player p) {
            Location l = p.getLocation();
            out.add(LocationUtil.toDestination(l));
        }
        return out;
    }

    /** Messages starting with '!' from WorldManager.regen are errors. */
    private static void report(CommandSender sender, String message, String success) {
        if (message == null) Msg.ok(sender, success);
        else if (message.startsWith("!")) Msg.err(sender, message.substring(1));
        else Msg.ok(sender, message);
    }

    private void reply(CommandSender sender, String error, String success) {
        if (error == null) Msg.ok(sender, success);
        else Msg.err(sender, error);
    }

    // ------------------------------------------------------------------ registration

    private void register() {
        WorldManager wm = plugin.worlds();

        router.add(Sub.of("create").perm(P + "create").min(2)
                .usage("<name> <normal|nether|end> [-s seed] [-g generator] [-t normal|flat|large_biomes|amplified] [-n] [-a]")
                .desc("Create a new world (-n no structures, -a don't adjust spawn)")
                .tab((s, a) -> a.length == 2 ? Tabs.of("normal", "nether", "end")
                        : List.of("-s", "-g", "-t", "-n", "-a", "flat", "amplified", "large_biomes", "normal"))
                .exec((s, a) -> {
                    Flags f = Flags.parse(a, Set.of("s", "g", "t"), Map.of("seed", "s", "generator", "g", "type", "t",
                            "nostructures", "n", "no-structures", "n", "noadjust", "a", "no-adjust-spawn", "a"));
                    if (f.positional.size() < 2) throw new UsageException(null);
                    String name = f.positional.get(0);
                    World.Environment env = env(f.positional.get(1));
                    Long seed = f.get("s") == null ? null : seed(f.get("s"));
                    WorldType parsed = WorldManager.parseType(f.get("t") == null ? "NORMAL" : f.get("t"));
                    if (parsed == null) throw new UsageException("Unknown world type '" + f.get("t") + "'.");
                    String type = parsed.name();
                    String gen = f.get("g");
                    boolean structures = !f.has("n");
                    boolean adjust = !f.has("a");
                    Msg.send(s, "&7Creating world &e" + name + "&7...");
                    plugin.scheduler().runGlobal(() -> reply(s, wm.create(name, env, seed, type, gen, structures, adjust),
                            "World '" + name + "' created! Use /mv tp " + name));
                }));

        router.add(Sub.of("import").perm(P + "import").min(2).usage("<folder> <normal|nether|end> [-g generator]")
                .desc("Load an existing world folder")
                .tab((s, a) -> a.length == 1 ? wm.importableFolders() : a.length == 2 ? Tabs.of("normal", "nether", "end") : Tabs.of("-g"))
                .exec((s, a) -> {
                    Flags f = Flags.parse(a, Set.of("g"), Map.of("generator", "g"));
                    if (f.positional.size() < 2) throw new UsageException(null);
                    String name = f.positional.get(0);
                    World.Environment env = env(f.positional.get(1));
                    Msg.send(s, "&7Importing &e" + name + "&7...");
                    plugin.scheduler().runGlobal(() -> reply(s, wm.importWorld(name, env, f.get("g")), "Imported '" + name + "'."));
                }));

        router.add(Sub.of("load").perm(P + "load").min(1).usage("<world>").desc("Load a world that is managed but unloaded")
                .tab((s, a) -> worldNames())
                .exec((s, a) -> plugin.scheduler().runGlobal(() -> reply(s, wm.load(a[0]), "Loaded '" + a[0] + "'."))));

        router.add(Sub.of("unload").perm(P + "unload").min(1).usage("<world> [--nosave]").desc("Unload a world (stays managed)")
                .tab((s, a) -> plugin.worlds().loadedNames())
                .exec((s, a) -> {
                    boolean save = !Arrays.asList(a).contains("--nosave");
                    plugin.scheduler().runGlobal(() -> wm.unload(a[0], save, err -> reply(s, err, "Unloaded '" + a[0] + "'.")));
                }));

        router.add(Sub.of("remove").perm(P + "remove").min(1).usage("<world>").desc("Stop managing a world (files are kept)")
                .tab((s, a) -> worldNames())
                .exec((s, a) -> {
                    MVWorld mv = world(a[0]);
                    plugin.confirms().ask(s, "Remove '" + mv.getName() + "' from Dreammultiverse (files are kept)?",
                            () -> plugin.scheduler().runGlobal(() -> wm.remove(mv.getName(), err -> reply(s, err, "Removed '" + mv.getName() + "'."))));
                }));

        router.add(Sub.of("delete").perm(P + "delete").min(1).usage("<world>").desc("Delete a world and ALL its files")
                .tab((s, a) -> {
                    List<String> out = new ArrayList<>(worldNames());
                    out.addAll(wm.importableFolders());
                    return out;
                })
                .exec((s, a) -> plugin.confirms().ask(s, "&c&lDELETE &e'" + a[0] + "' and all of its files permanently?",
                        () -> plugin.scheduler().runGlobal(() -> wm.delete(a[0], err -> reply(s, err, "Deleted '" + a[0] + "'."))))));

        router.add(Sub.of("clone").aliases("copy").perm(P + "clone").min(2).usage("<world> <new name>").desc("Copy a world")
                .tab((s, a) -> a.length == 1 ? worldNames() : List.of())
                .exec((s, a) -> {
                    Msg.send(s, "&7Cloning &e" + a[0] + " &7-> &e" + a[1] + "&7...");
                    plugin.scheduler().runGlobal(() -> wm.cloneWorld(a[0], a[1], err -> reply(s, err, "Cloned '" + a[0] + "' to '" + a[1] + "'.")));
                }));

        router.add(Sub.of("regen").perm(P + "regen").min(1).usage("<world> [-s seed | --random] [--keep-gamerules]")
                .desc("Regenerate a world (Folia/main world: on next restart)")
                .tab((s, a) -> a.length == 1 ? plugin.worlds().loadedNames() : Tabs.of("-s", "--random", "--keep-gamerules"))
                .exec((s, a) -> {
                    Flags f = Flags.parse(a, Set.of("s"), Map.of("seed", "s", "keepgamerules", "keep-gamerules", "r", "random"));
                    if (f.positional.isEmpty()) throw new UsageException(null);
                    MVWorld mv = world(f.positional.get(0));
                    WorldManager.SeedMode mode = f.get("s") != null ? WorldManager.SeedMode.CUSTOM
                            : f.has("random") ? WorldManager.SeedMode.RANDOM : WorldManager.SeedMode.SAME;
                    Long custom = f.get("s") == null ? null : seed(f.get("s"));
                    boolean keepRules = f.has("keep-gamerules");
                    plugin.confirms().ask(s, "&c&lREGENERATE &e'" + mv.getName() + "'? Everything built there will be lost.",
                            () -> plugin.scheduler().runGlobal(() -> wm.regen(mv.getName(), mode, custom, keepRules, msg -> report(s, msg, "Done."))));
                }));

        router.add(Sub.of("confirm").desc("Confirm a dangerous command").exec((s, a) -> plugin.confirms().confirm(s)));

        router.add(Sub.of("list").aliases("worlds").perm(P + "list").usage("[page]").desc("List all worlds")
                .exec((s, a) -> list(s, a.length > 0 ? parseInt(a[0], 1) : 1)));

        router.add(Sub.of("info").aliases("i").perm(P + "info").usage("[world]").desc("Show every setting of a world")
                .tab((s, a) -> worldNames())
                .exec((s, a) -> info(s, currentOrNamed(s, a, 0))));

        router.add(Sub.of("tp").aliases("teleport").perm(P + "tp").min(1).usage("[player] <destination>")
                .desc("Teleport to a world or destination (w: e: pl: a: b: p: ca:)")
                .tab((s, a) -> {
                    if (a.length == 1) {
                        List<String> out = destinations(s);
                        out.addAll(Tabs.players());
                        return out;
                    }
                    return a.length == 2 ? destinations(s) : List.of();
                })
                .exec((s, a) -> {
                    Player target;
                    String destText;
                    boolean other;
                    if (a.length >= 2) {
                        target = online(a[0]);
                        destText = a[1];
                        other = !target.equals(s);
                    } else {
                        target = self(s);
                        destText = a[0];
                        other = false;
                    }
                    String[] error = new String[1];
                    Destination dest = Destination.parse(plugin, destText, error);
                    if (dest == null) throw new UsageException(error[0]);
                    String node = "dreammultiverse.teleport." + (other ? "other." : "self.") + dest.permissionSuffix();
                    if (!s.hasPermission(node) && !s.hasPermission("dreammultiverse.admin")) {
                        Msg.err(s, "You don't have permission to teleport " + (other ? "others " : "") + "to that (" + node + ").");
                        return;
                    }
                    plugin.teleports().teleport(s, target, dest, ok -> {
                        if (!Boolean.TRUE.equals(ok)) return;
                        if (other) Msg.ok(s, "Teleported " + target.getName() + " to " + dest.describe(plugin) + "§a.");
                        Msg.send(target, "&7Teleported to &e" + dest.describe(plugin) + "&7.");
                    });
                }));

        router.add(Sub.of("spawn").perm(P + "spawn").usage("[player]").desc("Go to this world's spawn")
                .tab((s, a) -> s.hasPermission(P + "spawn.other") ? Tabs.players() : List.of())
                .exec((s, a) -> {
                    Player target = a.length > 0 ? online(a[0]) : self(s);
                    if (!target.equals(s) && !s.hasPermission(P + "spawn.other") && !s.hasPermission("dreammultiverse.admin")) {
                        Msg.err(s, "You don't have permission to send others to spawn.");
                        return;
                    }
                    MVWorld mv = plugin.worlds().getOrAdopt(target.getWorld());
                    plugin.teleports().toSpawn(s, target, mv, ok -> {
                        if (Boolean.TRUE.equals(ok)) Msg.send(target, "&7Teleported to the spawn of " + mv.getDisplayName() + "&7.");
                    });
                }));

        router.add(Sub.of("setspawn").perm(P + "setspawn").usage("[world x y z [yaw pitch]]").desc("Set a world's spawn point")
                .tab((s, a) -> a.length == 1 ? worldNames() : List.of())
                .exec((s, a) -> {
                    Location loc;
                    if (a.length >= 4) {
                        World w = world(a[0]).getBukkitWorld();
                        if (w == null) throw new UsageException("That world isn't loaded.");
                        loc = LocationUtil.parseCoords(w, a[1] + "," + a[2] + "," + a[3], a.length > 5 ? a[5] : null, a.length > 4 ? a[4] : null);
                        if (loc == null) throw new UsageException("Coordinates must be numbers.");
                    } else {
                        loc = self(s).getLocation();
                    }
                    MVWorld mv = plugin.worlds().getOrAdopt(loc.getWorld());
                    mv.setSpawn(loc);
                    plugin.worlds().save();
                    Location finalLoc = loc;
                    plugin.scheduler().runGlobal(() -> finalLoc.getWorld().setSpawnLocation(finalLoc));
                    Msg.ok(s, "Spawn of " + mv.getName() + " set to " + LocationUtil.format(loc) + ".");
                }));

        router.add(Sub.of("coord").aliases("coords", "where").perm(P + "coord").playerOnly().desc("Show where you are")
                .exec((s, a) -> {
                    Player p = (Player) s;
                    MVWorld mv = plugin.worlds().getOrAdopt(p.getWorld());
                    Location l = p.getLocation();
                    Msg.raw(s, "&7World: &f" + mv.getDisplayName() + " &8(" + mv.getName() + ", "
                            + p.getWorld().getEnvironment().name().toLowerCase(Locale.ROOT) + ")");
                    Msg.raw(s, String.format(Locale.ROOT, "&7Position: &f%.2f, %.2f, %.2f &7facing &f%s &8(yaw %.1f, pitch %.1f)",
                            l.getX(), l.getY(), l.getZ(), p.getFacing().name().toLowerCase(Locale.ROOT), l.getYaw(), l.getPitch()));
                    Msg.raw(s, "&7Destination: &e" + LocationUtil.toDestination(l));
                }));

        router.add(Sub.of("who").perm(P + "who").usage("[world]").desc("Who is in which world")
                .tab((s, a) -> plugin.worlds().loadedNames())
                .exec((s, a) -> {
                    for (World w : Bukkit.getWorlds()) {
                        if (a.length > 0 && !w.getName().equalsIgnoreCase(world(a[0]).getName())) continue;
                        MVWorld mv = plugin.worlds().get(w);
                        if (mv != null && mv.isHidden() && !s.hasPermission("dreammultiverse.admin")) continue;
                        List<String> names = new ArrayList<>();
                        for (Player p : Bukkit.getOnlinePlayers()) if (p.getWorld().equals(w)) names.add(p.getName());
                        Msg.raw(s, "&e" + (mv == null ? w.getName() : mv.getDisplayName()) + " &7(" + names.size() + "): &f"
                                + (names.isEmpty() ? "&8nobody" : String.join(", ", names)));
                    }
                }));

        router.add(Sub.of("modify").aliases("m").perm(P + "modify").min(2)
                .usage("<world> <set|reset|add|remove|clear|list> [property] [value]").desc("Change a world setting")
                .tab((s, a) -> {
                    if (a.length == 1) return worldNames();
                    if (a.length == 2) return Tabs.of("set", "reset", "add", "remove", "clear", "list");
                    boolean listOp = a[1].equalsIgnoreCase("add") || a[1].equalsIgnoreCase("remove") || a[1].equalsIgnoreCase("clear");
                    if (a.length == 3) {
                        List<String> props = new ArrayList<>();
                        for (MVWorld.Property p : MVWorld.properties().values()) if (p.isList() == listOp) props.add(p.name());
                        return props;
                    }
                    MVWorld.Property prop = MVWorld.property(a[2]);
                    if (prop == null) return List.of();
                    if (prop.name().equals("blacklist") || prop.name().equals("respawnworld")) return worldNames();
                    if (prop.name().endsWith("exceptions")) {
                        List<String> out = new ArrayList<>();
                        for (EntityType t : EntityType.values()) if (t.isAlive()) out.add(t.name().toLowerCase(Locale.ROOT));
                        return out;
                    }
                    return prop.suggestions();
                })
                .exec((s, a) -> modify(s, a)));

        router.add(Sub.of("gamerule").aliases("gr").perm(P + "gamerule").min(2).usage("<rule> <value> [world]").desc("Set a gamerule")
                .tab((s, a) -> {
                    if (a.length == 1) return GameRules.names();
                    if (a.length == 2) return Tabs.of("true", "false");
                    return worldNames();
                })
                .exec((s, a) -> {
                    MVWorld mv = currentOrNamed(s, a, 2);
                    World w = mv.getBukkitWorld();
                    if (w == null) throw new UsageException("That world isn't loaded.");
                    GameRule<?> rule = GameRules.find(a[0]);
                    if (rule == null) throw new UsageException("Unknown gamerule '" + a[0] + "'.");
                    plugin.scheduler().runGlobal(() -> reply(s, GameRules.set(w, rule, a[1]),
                            "Gamerule " + rule.getName() + " = " + a[1] + " in " + w.getName() + "."));
                }));

        router.add(Sub.of("gamerules").aliases("rules").perm(P + "gamerule").usage("[world]").desc("List a world's gamerules")
                .tab((s, a) -> worldNames())
                .exec((s, a) -> {
                    MVWorld mv = currentOrNamed(s, a, 0);
                    World w = mv.getBukkitWorld();
                    if (w == null) throw new UsageException("That world isn't loaded.");
                    plugin.scheduler().runGlobal(() -> {
                        StringBuilder sb = new StringBuilder();
                        for (Map.Entry<GameRule<?>, Object> e : GameRules.snapshot(w).entrySet()) {
                            sb.append("&e").append(e.getKey().getName()).append("&7=&f").append(e.getValue()).append("&8, ");
                        }
                        Msg.raw(s, "&7Gamerules of &e" + w.getName() + "&7: " + sb);
                    });
                }));

        router.add(Sub.of("purge").perm(P + "purge").usage("[world|all] [all|animals|monsters|<entity>]")
                .desc("Remove mobs (named/tamed mobs are kept)")
                .tab((s, a) -> {
                    if (a.length == 1) {
                        List<String> out = new ArrayList<>(plugin.worlds().loadedNames());
                        out.add("all");
                        return out;
                    }
                    List<String> out = new ArrayList<>(List.of("all", "animals", "monsters"));
                    for (EntityType t : EntityType.values()) if (t.isAlive() && t != EntityType.PLAYER) out.add(t.name().toLowerCase(Locale.ROOT));
                    return out;
                })
                .exec((s, a) -> purge(s, a)));

        router.add(Sub.of("anchor").perm(P + "anchor").min(1).usage("<name> | -d <name>").desc("Save (or -d delete) a named location")
                .tab((s, a) -> a.length == 1 ? List.of("-d") : plugin.anchors().names())
                .exec((s, a) -> {
                    if (a[0].equals("-d") || a[0].equalsIgnoreCase("delete")) {
                        if (a.length < 2) throw new UsageException(null);
                        reply(s, plugin.anchors().remove(a[1]) ? null : "No anchor called '" + a[1] + "'.", "Anchor '" + a[1] + "' deleted.");
                        return;
                    }
                    Player p = self(s);
                    plugin.anchors().set(a[0], p.getLocation());
                    Msg.ok(s, "Anchor '" + a[0] + "' saved. Use /mv tp a:" + a[0]);
                }));

        router.add(Sub.of("anchors").perm(P + "anchor").desc("List anchors")
                .exec((s, a) -> {
                    List<String> names = plugin.anchors().names();
                    if (names.isEmpty()) {
                        Msg.send(s, "&7No anchors yet.");
                        return;
                    }
                    for (String n : names) {
                        Location l = plugin.anchors().get(n);
                        Msg.raw(s, "&e" + n + " &8- &7" + (l == null ? "&cworld not loaded" : LocationUtil.format(l)));
                    }
                }));

        router.add(Sub.of("bed").perm(P + "bed").playerOnly().desc("Teleport to your bed / respawn anchor")
                .exec((s, a) -> {
                    String[] error = new String[1];
                    Destination dest = Destination.parse(plugin, "b:", error);
                    plugin.teleports().teleport(s, (Player) s, dest);
                }));

        router.add(Sub.of("generators").aliases("gens").perm(P + "generators").desc("Installed world generator plugins")
                .exec((s, a) -> {
                    List<String> gens = wm.generatorPlugins();
                    Msg.send(s, gens.isEmpty() ? "&7No generator plugins installed."
                            : "&7Generators: &e" + String.join("&7, &e", gens) + " &8(use -g Name or -g Name:id)");
                }));

        router.add(Sub.of("environments").aliases("env", "envs").perm(P + "create").desc("Valid environments and world types")
                .exec((s, a) -> {
                    Msg.raw(s, "&7Environments: &enormal&7, &enether&7, &eend");
                    List<String> types = new ArrayList<>();
                    for (WorldType t : WorldType.values()) types.add(t.name().toLowerCase(Locale.ROOT));
                    Msg.raw(s, "&7World types (-t): &e" + String.join("&7, &e", types));
                }));

        router.add(Sub.of("migrate").perm("dreammultiverse.admin").min(1).usage("multiverse").desc("Import worlds from Multiverse-Core's worlds.yml")
                .tab((s, a) -> Tabs.of("multiverse"))
                .exec((s, a) -> {
                    if (!a[0].equalsIgnoreCase("multiverse")) throw new UsageException(null);
                    plugin.scheduler().runGlobal(() -> {
                        List<String> log = new ArrayList<>();
                        int n = wm.migrateFromMultiverse(log);
                        for (String line : log) Msg.raw(s, "&7- " + line);
                        Msg.ok(s, "Migrated " + n + " world(s).");
                    });
                }));

        router.add(Sub.of("config").perm("dreammultiverse.admin").min(1).usage("<key> [value]").desc("View or change config.yml")
                .tab((s, a) -> a.length == 1 ? new ArrayList<>(plugin.getConfig().getKeys(true)) : Tabs.of("true", "false"))
                .exec((s, a) -> config(s, a)));

        router.add(Sub.of("reload").perm(P + "reload").desc("Reload config and every data file")
                .exec((s, a) -> plugin.scheduler().runGlobal(() -> {
                    plugin.reloadAll();
                    Msg.ok(s, "Dreammultiverse reloaded.");
                })));

        router.add(Sub.of("version").aliases("ver").perm(P + "version").desc("Version and platform info")
                .exec((s, a) -> {
                    @SuppressWarnings("deprecation") String version = plugin.getDescription().getVersion();
                    Msg.raw(s, "&bDreammultiverse &f" + version + " &7on &f" + PlatformSupport.name() + " " + Bukkit.getBukkitVersion());
                    Msg.raw(s, "&7Worlds: &f" + wm.all().size() + " &7(" + Bukkit.getWorlds().size() + " loaded) &7Portals: &f"
                            + plugin.portals().all().size() + " &7Anchors: &f" + plugin.anchors().names().size()
                            + " &7Inventory groups: &f" + plugin.inventories().groups().size());
                    Msg.raw(s, "&7Vault: " + (plugin.economy().hasVault() ? "&ahooked" : "&8no") + " &7PlaceholderAPI: "
                            + (plugin.hasPlaceholders() ? "&ahooked" : "&8no"));
                    if (PlatformSupport.isFolia()) {
                        Msg.raw(s, "&eFolia: creating/loading/unloading worlds at runtime is not possible; /mv regen resets on restart.");
                    }
                }));
    }

    // ------------------------------------------------------------------ bodies

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void list(CommandSender s, int page) {
        boolean admin = s.hasPermission("dreammultiverse.admin") || s.hasPermission(P + "list.hidden");
        List<MVWorld> shown = new ArrayList<>();
        for (String n : plugin.worlds().names()) {
            MVWorld mv = plugin.worlds().get(n);
            if (mv != null && (!mv.isHidden() || admin)) shown.add(mv);
        }
        int per = 10;
        int pages = Math.max(1, (shown.size() + per - 1) / per);
        page = Math.max(1, Math.min(pages, page));
        Msg.raw(s, "&8&m     &r &bWorlds &7(" + page + "/" + pages + ") &8&m     ");
        for (int i = (page - 1) * per; i < Math.min(shown.size(), page * per); i++) {
            MVWorld mv = shown.get(i);
            World w = mv.getBukkitWorld();
            int players = 0;
            if (w != null) for (Player p : Bukkit.getOnlinePlayers()) if (p.getWorld().equals(w)) players++;
            String color = switch (mv.getEnvironment()) {
                case NETHER -> "&c";
                case THE_END -> "&d";
                default -> "&a";
            };
            Msg.raw(s, (w != null ? "&a● " : "&c○ ") + color + mv.getName()
                    + (mv.getAlias().isBlank() ? "" : " &7(" + mv.getDisplayName() + "&7)")
                    + " &8- &7" + mv.getEnvironment().name().toLowerCase(Locale.ROOT)
                    + (w != null ? ", " + players + " online" : ", unloaded") + (mv.isHidden() ? " &8[hidden]" : ""));
        }
    }

    private void info(CommandSender s, MVWorld mv) {
        World w = mv.getBukkitWorld();
        Msg.raw(s, "&8&m     &r &b" + mv.getName() + " &8&m     ");
        Msg.raw(s, "&7Display: &f" + mv.getDisplayName() + "  &7Loaded: " + (w != null ? "&ayes" : "&cno")
                + "  &7Environment: &f" + mv.getEnvironment().name().toLowerCase(Locale.ROOT));
        Msg.raw(s, "&7Seed: &f" + (w != null ? w.getSeed() : mv.getSeed()) + "  &7Type: &f" + mv.getWorldType().toLowerCase(Locale.ROOT)
                + "  &7Generator: &f" + (mv.getGenerator() == null ? "default" : mv.getGenerator())
                + "  &7Structures: &f" + mv.isStructures());
        StringBuilder sb = new StringBuilder();
        for (MVWorld.Property p : MVWorld.properties().values()) {
            if (p.name().equals("alias")) continue;
            String v = p.getter().apply(mv);
            sb.append("&7").append(p.name()).append(": &f").append(v.isEmpty() ? "&8-" : v).append("  ");
        }
        Msg.raw(s, sb.toString());
        Location spawn = mv.getSpawnLocation();
        if (spawn != null) Msg.raw(s, "&7Spawn: &f" + LocationUtil.format(spawn));
        if (w != null) {
            int players = 0;
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getWorld().equals(w)) players++;
            Msg.raw(s, "&7Players: &f" + players + "  &7Time: &f" + w.getTime() + "  &7Weather: &f"
                    + (w.isThundering() ? "thunder" : w.hasStorm() ? "rain" : "clear"));
        }
    }

    private void modify(CommandSender s, String[] a) {
        MVWorld mv = world(a[0]);
        String op = a[1].toLowerCase(Locale.ROOT);
        if (op.equals("list")) {
            for (MVWorld.Property p : MVWorld.properties().values()) {
                Msg.raw(s, "&e" + p.name() + " &7= &f" + p.getter().apply(mv) + " &8(" + p.description() + ")");
            }
            return;
        }
        if (a.length < 3) throw new UsageException(null);
        MVWorld.Property prop = MVWorld.property(a[2]);
        if (prop == null) throw new UsageException("Unknown property '" + a[2] + "'. See /mv modify " + mv.getName() + " list");
        String value = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : null;

        try {
            switch (op) {
                case "set" -> {
                    if (prop.isList()) throw new UsageException(prop.name() + " is a list - use add/remove/clear.");
                    if (value == null) throw new UsageException("Give a value.");
                    prop.setter().accept(mv, value);
                }
                case "reset" -> mv.reset(prop);
                case "add", "remove" -> {
                    if (!prop.isList()) throw new UsageException(prop.name() + " is not a list - use set.");
                    if (value == null) throw new UsageException("Give a value.");
                    List<String> list = prop.list().apply(mv);
                    if (op.equals("add")) {
                        if (list.stream().noneMatch(v -> v.equalsIgnoreCase(value))) list.add(value);
                    } else {
                        list.removeIf(v -> v.equalsIgnoreCase(value));
                    }
                }
                case "clear" -> {
                    if (!prop.isList()) throw new UsageException(prop.name() + " is not a list.");
                    prop.list().apply(mv).clear();
                }
                default -> throw new UsageException(null);
            }
        } catch (NumberFormatException e) {
            throw new UsageException("That must be a number.");
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }

        plugin.worlds().save();
        World w = mv.getBukkitWorld();
        if (w != null) {
            plugin.scheduler().runGlobal(() -> mv.applyTo(w));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld().equals(w)) plugin.scheduler().runForEntity(p, () -> plugin.worldListener().enforcePlayerRules(p, p.getWorld()));
            }
        }
        Msg.ok(s, mv.getName() + ": " + prop.name() + " = " + prop.getter().apply(mv));
    }

    private void purge(CommandSender s, String[] a) {
        String worldArg = a.length > 0 ? a[0] : (s instanceof Player p ? p.getWorld().getName() : "all");
        String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "all";
        List<World> worlds = new ArrayList<>();
        if (worldArg.equalsIgnoreCase("all")) worlds.addAll(Bukkit.getWorlds());
        else {
            World w = world(worldArg).getBukkitWorld();
            if (w == null) throw new UsageException("That world isn't loaded.");
            worlds.add(w);
        }
        EntityType specific = null;
        if (!what.equals("all") && !what.equals("animals") && !what.equals("monsters")) {
            try {
                specific = EntityType.valueOf(what.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new UsageException("Unknown entity type '" + what + "'.");
            }
        }
        EntityType type = specific;
        Predicate<Entity> match = e -> {
            if (e instanceof Player || !(e instanceof Mob)) return false;
            if (isNamed(e)) return false;
            if (e instanceof Tameable t && t.isTamed()) return false;
            if (type != null) return e.getType() == type;
            return switch (what) {
                case "animals" -> e instanceof Animals || e instanceof WaterMob || e instanceof Ambient;
                case "monsters" -> e instanceof Enemy;
                default -> true;
            };
        };

        AtomicInteger removed = new AtomicInteger();
        if (!PlatformSupport.isFolia()) {
            plugin.scheduler().runGlobal(() -> {
                for (World w : worlds) {
                    for (Entity e : w.getEntities()) {
                        if (match.test(e)) {
                            e.remove();
                            removed.incrementAndGet();
                        }
                    }
                }
                Msg.ok(s, "Removed " + removed.get() + " entities.");
            });
            return;
        }
        // Folia: entities may only be touched by the region that owns them, and there's no safe
        // "every entity in the world" view - so purge around each player instead, on their thread.
        double radius = plugin.getConfig().getDouble("folia-purge-radius", 64);
        int scheduled = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!worlds.contains(p.getWorld())) continue;
            scheduled++;
            plugin.scheduler().runForEntity(p, () -> {
                List<Entity> nearby;
                try {
                    nearby = p.getNearbyEntities(radius, radius, radius);
                } catch (RuntimeException e) {
                    nearby = p.getNearbyEntities(16, 16, 16); // box crossed into another region - stay local
                }
                for (Entity e : nearby) {
                    if (match.test(e)) {
                        e.remove();
                        removed.incrementAndGet();
                    }
                }
            });
        }
        Msg.ok(s, "Purging within " + (int) radius + " blocks of " + scheduled + " player(s) (Folia only allows nearby purges).");
    }

    @SuppressWarnings("deprecation")
    private static boolean isNamed(Entity e) {
        return e.getCustomName() != null;
    }

    private void config(CommandSender s, String[] a) {
        String key = a[0];
        var cfg = plugin.getConfig();
        if (!cfg.contains(key) || cfg.isConfigurationSection(key)) throw new UsageException("Unknown config key '" + key + "'.");
        if (a.length == 1) {
            Msg.send(s, "&e" + key + " &7= &f" + cfg.get(key));
            return;
        }
        String raw = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
        Object current = cfg.get(key);
        Object value;
        try {
            if (current instanceof Boolean) {
                if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) throw new UsageException("Must be true or false.");
                value = Boolean.parseBoolean(raw);
            } else if (current instanceof Integer) value = Integer.parseInt(raw);
            else if (current instanceof Long) value = Long.parseLong(raw);
            else if (current instanceof Double) value = Double.parseDouble(raw);
            else value = raw;
        } catch (NumberFormatException e) {
            throw new UsageException("Must be a number.");
        }
        cfg.set(key, value);
        plugin.saveConfig();
        plugin.applyConfig();
        Msg.ok(s, key + " = " + value);
    }
}
