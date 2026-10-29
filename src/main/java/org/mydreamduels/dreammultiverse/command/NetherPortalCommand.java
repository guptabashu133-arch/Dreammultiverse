package org.mydreamduels.dreammultiverse.command;

import org.bukkit.Bukkit;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.command.CommandRouter.UsageException;
import org.mydreamduels.dreammultiverse.netherportal.NetherPortalManager;
import org.mydreamduels.dreammultiverse.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /mvnp - Multiverse-NetherPortals links. */
public final class NetherPortalCommand {

    private static final String P = "dreammultiverse.netherportals.";

    private final Dreammultiverse plugin;
    private final CommandRouter router = new CommandRouter("mvnp", "Dreammultiverse Nether Portals");

    public NetherPortalCommand(Dreammultiverse plugin) {
        this.plugin = plugin;
        register();
    }

    public CommandRouter router() {
        return router;
    }

    private static PortalType type(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "nether", "n" -> PortalType.NETHER;
            case "end", "e", "ender" -> PortalType.ENDER;
            default -> throw new UsageException("Type must be nether or end.");
        };
    }

    private void register() {
        NetherPortalManager links = plugin.netherPortals();

        router.add(Sub.of("link").perm(P + "link").min(3).usage("<nether|end> <from world> <to world|none>")
                .desc("Choose where a world's nether/end portals lead")
                .tab((s, a) -> {
                    if (a.length == 1) return Tabs.of("nether", "end");
                    List<String> out = new ArrayList<>(plugin.worlds().names());
                    if (a.length == 3) out.add(NetherPortalManager.NONE);
                    return out;
                })
                .exec((s, a) -> {
                    PortalType type = type(a[0]);
                    if (plugin.worlds().get(a[1]) == null && Bukkit.getWorld(a[1]) == null) throw new UsageException("Unknown world '" + a[1] + "'.");
                    String to = a[2];
                    if (!to.equalsIgnoreCase(NetherPortalManager.NONE)) {
                        var mv = plugin.worlds().find(to);
                        if (mv == null && Bukkit.getWorld(to) == null) throw new UsageException("Unknown world '" + to + "'.");
                        if (mv != null) to = mv.getName();
                    }
                    links.link(type, a[1], to);
                    Msg.ok(s, (type == PortalType.ENDER ? "End" : "Nether") + " portals in " + a[1] + " now lead to "
                            + (to.equalsIgnoreCase(NetherPortalManager.NONE) ? "nowhere (disabled)" : to) + ".");
                }));

        router.add(Sub.of("unlink").perm(P + "link").min(2).usage("<nether|end> <from world>").desc("Back to the naming convention")
                .tab((s, a) -> {
                    if (a.length == 1) return Tabs.of("nether", "end");
                    PortalType type = a[0].toLowerCase(Locale.ROOT).startsWith("e") ? PortalType.ENDER : PortalType.NETHER;
                    return new ArrayList<>(links.view(type).keySet());
                })
                .exec((s, a) -> {
                    PortalType type = type(a[0]);
                    if (links.unlink(type, a[1])) Msg.ok(s, "Link removed - " + a[1] + " uses the naming convention again.");
                    else Msg.err(s, a[1] + " had no " + a[0].toLowerCase(Locale.ROOT) + " link.");
                }));

        router.add(Sub.of("show").aliases("list").perm(P + "show").desc("Where every world's portals lead")
                .exec((s, a) -> {
                    for (PortalType type : new PortalType[]{PortalType.NETHER, PortalType.ENDER}) {
                        Msg.raw(s, "&b" + (type == PortalType.NETHER ? "Nether" : "End") + " portals:");
                        Map<String, String> explicit = links.view(type);
                        for (World w : Bukkit.getWorlds()) {
                            World target = links.target(w, type);
                            String how = explicit.containsKey(w.getName().toLowerCase(Locale.ROOT)) ? " &8[linked]" : "";
                            Msg.raw(s, "  &e" + w.getName() + " &7-> " + (target == null ? "&cnowhere" : "&f" + target.getName()) + how);
                        }
                        for (Map.Entry<String, String> e : explicit.entrySet()) {
                            if (Bukkit.getWorld(e.getKey()) == null) Msg.raw(s, "  &8" + e.getKey() + " -> " + e.getValue() + " (not loaded)");
                        }
                    }
                }));
    }
}
