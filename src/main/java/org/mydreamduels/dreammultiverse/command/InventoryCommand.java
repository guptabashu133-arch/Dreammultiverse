package org.mydreamduels.dreammultiverse.command;

import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.command.CommandRouter.UsageException;
import org.mydreamduels.dreammultiverse.inventory.InvGroup;
import org.mydreamduels.dreammultiverse.inventory.InventoryManager;
import org.mydreamduels.dreammultiverse.inventory.Sharable;
import org.mydreamduels.dreammultiverse.util.Msg;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** /mvinv - Multiverse-Inventories groups. */
public final class InventoryCommand {

    private static final String P = "dreammultiverse.inventory.";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");

    private final Dreammultiverse plugin;
    private final CommandRouter router = new CommandRouter("mvinv", "Dreammultiverse Inventories");

    public InventoryCommand(Dreammultiverse plugin) {
        this.plugin = plugin;
        register();
    }

    public CommandRouter router() {
        return router;
    }

    private InvGroup group(String name) {
        InvGroup g = plugin.inventories().group(name);
        if (g == null) throw new UsageException("Unknown group '" + name + "'.");
        return g;
    }

    private List<String> groupNames() {
        List<String> out = new ArrayList<>();
        for (InvGroup g : plugin.inventories().groups()) out.add(g.getName());
        return out;
    }

    private static List<String> sharables() {
        List<String> out = new ArrayList<>();
        out.add("all");
        for (Sharable s : Sharable.values()) out.add(s.id());
        return out;
    }

    private static Set<Sharable> parseShares(String[] args, int from) {
        Set<Sharable> out = EnumSet.noneOf(Sharable.class);
        for (int i = from; i < args.length; i++) {
            for (String part : args[i].split(",")) {
                if (part.equalsIgnoreCase("all")) {
                    out.addAll(Sharable.all());
                    continue;
                }
                Sharable s = Sharable.parse(part);
                if (s == null) throw new UsageException("Unknown sharable '" + part + "'. Options: " + String.join(", ", sharables()));
                out.add(s);
            }
        }
        return out;
    }

    private void register() {
        InventoryManager inv = plugin.inventories();

        router.add(Sub.of("create").perm(P + "group").min(1).usage("<group> [worlds...]").desc("Create a group (shares everything by default)")
                .tab((s, a) -> a.length == 1 ? List.of() : plugin.worlds().names())
                .exec((s, a) -> {
                    if (!NAME.matcher(a[0]).matches()) throw new UsageException("Group names may use letters, numbers, _ and -.");
                    if (inv.group(a[0]) != null) throw new UsageException("Group '" + a[0] + "' already exists.");
                    InvGroup g = inv.createGroup(a[0]);
                    for (int i = 1; i < a.length; i++) g.addWorld(a[i]);
                    inv.saveGroups();
                    Msg.ok(s, "Group '" + g.getName() + "' created with " + (a.length - 1) + " world(s).");
                }));

        router.add(Sub.of("delete").aliases("remove").perm(P + "group").min(1).usage("<group>").desc("Delete a group")
                .tab((s, a) -> groupNames())
                .exec((s, a) -> {
                    if (inv.deleteGroup(a[0])) Msg.ok(s, "Group '" + a[0] + "' deleted.");
                    else Msg.err(s, "Unknown group '" + a[0] + "'.");
                }));

        router.add(Sub.of("addworld").perm(P + "group").min(2).usage("<group> <world>").desc("Add a world to a group")
                .tab((s, a) -> a.length == 1 ? groupNames() : plugin.worlds().names())
                .exec((s, a) -> {
                    group(a[0]).addWorld(a[1]);
                    inv.saveGroups();
                    Msg.ok(s, a[1] + " added to " + a[0] + ".");
                }));

        router.add(Sub.of("removeworld").perm(P + "group").min(2).usage("<group> <world>").desc("Remove a world from a group")
                .tab((s, a) -> {
                    if (a.length == 1) return groupNames();
                    InvGroup g = inv.group(a[0]);
                    return g == null ? List.of() : new ArrayList<>(g.getWorlds());
                })
                .exec((s, a) -> {
                    if (group(a[0]).removeWorld(a[1])) {
                        inv.saveGroups();
                        Msg.ok(s, a[1] + " removed from " + a[0] + ".");
                    } else {
                        Msg.err(s, a[1] + " is not in " + a[0] + ".");
                    }
                }));

        router.add(Sub.of("share").perm(P + "group").min(2).usage("<group> <sharable|all>...").desc("Start sharing something in a group")
                .tab((s, a) -> a.length == 1 ? groupNames() : sharables())
                .exec((s, a) -> {
                    InvGroup g = group(a[0]);
                    Set<Sharable> shares = EnumSet.noneOf(Sharable.class);
                    shares.addAll(g.getShares());
                    shares.addAll(parseShares(a, 1));
                    g.setShares(shares);
                    inv.saveGroups();
                    Msg.ok(s, g.getName() + " now shares: " + describe(g));
                }));

        router.add(Sub.of("unshare").perm(P + "group").min(2).usage("<group> <sharable|all>...").desc("Stop sharing something in a group")
                .tab((s, a) -> a.length == 1 ? groupNames() : sharables())
                .exec((s, a) -> {
                    InvGroup g = group(a[0]);
                    Set<Sharable> shares = EnumSet.noneOf(Sharable.class);
                    shares.addAll(g.getShares());
                    shares.removeAll(parseShares(a, 1));
                    g.setShares(shares);
                    inv.saveGroups();
                    Msg.ok(s, g.getName() + " now shares: " + describe(g));
                }));

        router.add(Sub.of("list").perm(P + "info").desc("List groups")
                .exec((s, a) -> {
                    if (inv.groups().isEmpty()) {
                        Msg.send(s, "&7No groups - every world keeps its own inventory.");
                        return;
                    }
                    for (InvGroup g : inv.groups()) {
                        Msg.raw(s, "&e" + g.getName() + " &7worlds: &f" + String.join(", ", g.getWorlds()) + " &7shares: &f" + describe(g));
                    }
                }));

        router.add(Sub.of("info").perm(P + "info").min(1).usage("<group|world>").desc("Group details, or which groups a world is in")
                .tab((s, a) -> {
                    List<String> out = groupNames();
                    out.addAll(plugin.worlds().names());
                    return out;
                })
                .exec((s, a) -> {
                    InvGroup g = inv.group(a[0]);
                    if (g != null) {
                        Msg.raw(s, "&b" + g.getName() + "&7: worlds &f" + String.join(", ", g.getWorlds()) + "&7, shares &f" + describe(g));
                        return;
                    }
                    List<InvGroup> groups = inv.groupsOf(a[0]);
                    if (groups.isEmpty()) Msg.send(s, "&e" + a[0] + " &7is in no group - it has its own separate inventory.");
                    for (InvGroup grp : groups) Msg.raw(s, "&e" + a[0] + " &7is in &b" + grp.getName() + " &7(shares " + describe(grp) + ")");
                }));

        router.add(Sub.of("gamemode").perm("dreammultiverse.admin").min(1).usage("<true|false>")
                .desc("Separate inventories per gamemode")
                .tab((s, a) -> Tabs.of("true", "false"))
                .exec((s, a) -> {
                    if (!a[0].equalsIgnoreCase("true") && !a[0].equalsIgnoreCase("false")) throw new UsageException(null);
                    plugin.getConfig().set("inventories.separate-gamemode-inventories", Boolean.parseBoolean(a[0]));
                    plugin.saveConfig();
                    Msg.ok(s, "Per-gamemode inventories " + (Boolean.parseBoolean(a[0]) ? "enabled" : "disabled") + ".");
                }));

        router.add(Sub.of("reload").perm(P + "group").desc("Reload groups.yml")
                .exec((s, a) -> {
                    inv.loadGroups();
                    Msg.ok(s, "Inventory groups reloaded.");
                }));
    }

    private static String describe(InvGroup g) {
        if (g.getShares().containsAll(Sharable.all())) return "all";
        if (g.getShares().isEmpty()) return "nothing";
        List<String> out = new ArrayList<>();
        for (Sharable s : g.getShares()) out.add(s.id());
        return String.join(", ", out);
    }
}
