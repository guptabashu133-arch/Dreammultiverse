package org.mydreamduels.dreammultiverse.command;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.command.CommandRouter.UsageException;
import org.mydreamduels.dreammultiverse.destination.Destination;
import org.mydreamduels.dreammultiverse.portal.Portal;
import org.mydreamduels.dreammultiverse.util.LocationUtil;
import org.mydreamduels.dreammultiverse.util.Msg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** /mvp - everything from Multiverse-Portals. */
public final class PortalCommand {

    private static final String P = "dreammultiverse.portal.";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");

    private final Dreammultiverse plugin;
    private final CommandRouter router = new CommandRouter("mvp", "Dreammultiverse Portals");

    public PortalCommand(Dreammultiverse plugin) {
        this.plugin = plugin;
        register();
    }

    public CommandRouter router() {
        return router;
    }

    private Portal portal(String name) {
        Portal p = plugin.portals().get(name);
        if (p == null) throw new UsageException("Unknown portal '" + name + "'.");
        return p;
    }

    private List<String> destinations() {
        List<String> out = new ArrayList<>(plugin.worlds().loadedNames());
        out.addAll(Destination.PREFIXES);
        for (String a : plugin.anchors().names()) out.add("a:" + a);
        for (String p : plugin.portals().names()) out.add("p:" + p);
        return out;
    }

    private static List<String> fills() {
        return List.of("water", "lava", "nether_portal", "end_gateway", "end_portal", "air", "cobweb", "light_blue_stained_glass");
    }

    private void register() {
        router.add(Sub.of("wand").perm(P + "create").playerOnly().desc("Get the selection wand (left = pos1, right = pos2)")
                .exec((s, a) -> {
                    Material m = Material.matchMaterial(plugin.getConfig().getString("portals.wand", "WOODEN_AXE"));
                    ((Player) s).getInventory().addItem(new ItemStack(m == null ? Material.WOODEN_AXE : m));
                    Msg.ok(s, "Left-click a block for position 1, right-click for position 2.");
                }));

        router.add(Sub.of("pos1").perm(P + "create").playerOnly().desc("Set position 1 where you stand")
                .exec((s, a) -> {
                    plugin.selections().setPos((Player) s, 0, ((Player) s).getLocation());
                    Msg.ok(s, "Position 1 set.");
                }));

        router.add(Sub.of("pos2").perm(P + "create").playerOnly().desc("Set position 2 where you stand")
                .exec((s, a) -> {
                    plugin.selections().setPos((Player) s, 1, ((Player) s).getLocation());
                    Msg.ok(s, "Position 2 set.");
                }));

        router.add(Sub.of("create").perm(P + "create").playerOnly().min(1).usage("<name> [destination]")
                .desc("Create a portal from your selection")
                .tab((s, a) -> a.length == 2 ? destinations() : List.of())
                .exec((s, a) -> {
                    Player player = (Player) s;
                    if (!NAME.matcher(a[0]).matches()) throw new UsageException("Portal names may use letters, numbers, _ and -.");
                    if (plugin.portals().get(a[0]) != null) throw new UsageException("A portal called '" + a[0] + "' already exists.");
                    Location[] sel = plugin.selections().get(player);
                    if (sel == null) throw new UsageException("Select two corners first (/mvp wand) in the same world.");
                    Portal portal = new Portal(a[0], sel[0], sel[1]);
                    long max = plugin.getConfig().getLong("portals.max-volume", 4096);
                    if (portal.volume() > max) throw new UsageException("That selection is " + portal.volume() + " blocks - the limit is " + max + ".");
                    portal.setOwner(player.getName());
                    portal.setExit(player.getLocation());
                    portal.setSafeTeleport(plugin.getConfig().getBoolean("portals.default-safe-teleport", true));
                    if (a.length > 1) {
                        String[] error = new String[1];
                        Destination dest = Destination.parse(plugin, a[1], error);
                        if (dest == null) throw new UsageException(error[0]);
                        portal.setDestination(dest.serialize());
                    }
                    plugin.portals().add(portal);
                    Msg.ok(s, "Portal '" + portal.getName() + "' created" + (a.length > 1 ? " -> " + portal.getDestination() : " (no destination yet - /mvp modify " + portal.getName() + " dest <destination>)") + ".");
                    Msg.send(s, "&7Fill it with &e/mvp fill " + portal.getName() + " <water|nether_portal|...>");
                }));

        router.add(Sub.of("remove").aliases("delete").perm(P + "remove").min(1).usage("<name>").desc("Delete a portal")
                .tab((s, a) -> plugin.portals().names())
                .exec((s, a) -> {
                    Portal p = portal(a[0]);
                    Runnable remove = () -> {
                        plugin.portals().remove(p.getName());
                        Msg.ok(s, "Portal '" + p.getName() + "' removed.");
                    };
                    if (p.getFill().isBlank()) remove.run();
                    else plugin.portals().fill(p, Material.AIR, n -> remove.run());
                }));

        router.add(Sub.of("list").perm(P + "list").usage("[world]").desc("List portals")
                .tab((s, a) -> plugin.worlds().loadedNames())
                .exec((s, a) -> {
                    List<Portal> list = new ArrayList<>();
                    for (Portal p : plugin.portals().all()) if (a.length == 0 || p.getWorld().equalsIgnoreCase(a[0])) list.add(p);
                    list.sort((x, y) -> x.getName().compareToIgnoreCase(y.getName()));
                    if (list.isEmpty()) {
                        Msg.send(s, "&7No portals.");
                        return;
                    }
                    for (Portal p : list) {
                        Msg.raw(s, "&e" + p.getName() + " &8(" + p.getWorld() + ") &7-> &f" + (p.getDestination().isBlank() ? "&cnone" : p.getDestination()));
                    }
                }));

        router.add(Sub.of("info").perm(P + "list").min(1).usage("<name>").desc("Portal details")
                .tab((s, a) -> plugin.portals().names())
                .exec((s, a) -> {
                    Portal p = portal(a[0]);
                    Msg.raw(s, "&8&m    &r &b" + p.getName() + " &8&m    ");
                    Msg.raw(s, "&7Region: &f" + p.describeRegion() + " &8(" + p.volume() + " blocks)");
                    Msg.raw(s, "&7Destination: &f" + (p.getDestination().isBlank() ? "&cnone" : p.getDestination()));
                    Msg.raw(s, "&7Owner: &f" + p.getOwner() + "  &7Price: &f" + plugin.economy().describe(p.getPrice(), p.getCurrency())
                            + "  &7Safe teleport: &f" + p.isSafeTeleport() + "  &7Fill: &f" + (p.getFill().isBlank() ? "-" : p.getFill().toLowerCase(Locale.ROOT)));
                    Location exit = p.getExitLocation();
                    if (exit != null) Msg.raw(s, "&7Exit (p:" + p.getName() + "): &f" + LocationUtil.format(exit));
                }));

        router.add(Sub.of("select").perm(P + "create").playerOnly().min(1).usage("<name>").desc("Load a portal's region as your selection")
                .tab((s, a) -> plugin.portals().names())
                .exec((s, a) -> {
                    Portal p = portal(a[0]);
                    var w = plugin.getServer().getWorld(p.getWorld());
                    if (w == null) throw new UsageException("That portal's world isn't loaded.");
                    plugin.selections().setPos((Player) s, 0, new Location(w, p.getMinX(), p.getMinY(), p.getMinZ()));
                    plugin.selections().setPos((Player) s, 1, new Location(w, p.getMaxX(), p.getMaxY(), p.getMaxZ()));
                    Msg.ok(s, "Selected the region of '" + p.getName() + "'.");
                }));

        router.add(Sub.of("modify").aliases("m").perm(P + "modify").min(2)
                .usage("<name> <dest|price|currency|safe|owner|exit|region> [value]").desc("Change a portal")
                .tab((s, a) -> {
                    if (a.length == 1) return plugin.portals().names();
                    if (a.length == 2) return Tabs.of("dest", "price", "currency", "safe", "owner", "exit", "region");
                    return switch (a[1].toLowerCase(Locale.ROOT)) {
                        case "dest", "destination" -> destinations();
                        case "safe" -> Tabs.of("true", "false");
                        case "currency" -> Tabs.of("money", "diamond", "emerald", "gold_ingot");
                        case "owner" -> Tabs.players();
                        default -> List.of();
                    };
                })
                .exec((s, a) -> modify(s, a)));

        router.add(Sub.of("fill").perm(P + "modify").min(2).usage("<name> <material>").desc("Fill a portal (water, nether_portal, air...)")
                .tab((s, a) -> a.length == 1 ? plugin.portals().names() : fills())
                .exec((s, a) -> {
                    Portal p = portal(a[0]);
                    String m = a[1].equalsIgnoreCase("portal") ? "NETHER_PORTAL" : a[1];
                    Material material = Material.matchMaterial(m);
                    if (material == null || !material.isBlock()) throw new UsageException("Unknown block '" + a[1] + "'.");
                    plugin.portals().fill(p, material, n -> {
                        if (n < 0) Msg.err(s, "That portal's world isn't loaded.");
                        else Msg.ok(s, "Filled " + n + " blocks of '" + p.getName() + "' with " + material.name().toLowerCase(Locale.ROOT) + ".");
                    });
                }));
    }

    private void modify(CommandSender s, String[] a) {
        Portal p = portal(a[0]);
        String prop = a[1].toLowerCase(Locale.ROOT);
        String value = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : null;
        switch (prop) {
            case "dest", "destination" -> {
                if (value == null) throw new UsageException("Give a destination.");
                String[] error = new String[1];
                Destination dest = Destination.parse(plugin, value, error);
                if (dest == null) throw new UsageException(error[0]);
                p.setDestination(dest.serialize());
            }
            case "price" -> {
                try {
                    p.setPrice(Double.parseDouble(value == null ? "0" : value));
                } catch (NumberFormatException e) {
                    throw new UsageException("Price must be a number.");
                }
            }
            case "currency" -> {
                if (value == null) throw new UsageException("Give 'money' or an item.");
                if (!value.equalsIgnoreCase("money") && Material.matchMaterial(value) == null) throw new UsageException("Unknown item '" + value + "'.");
                p.setCurrency(value);
            }
            case "safe", "safeteleport", "safe-teleport" -> {
                if (value == null || !(value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) throw new UsageException("Must be true or false.");
                p.setSafeTeleport(Boolean.parseBoolean(value));
            }
            case "owner" -> {
                if (value == null) throw new UsageException("Give a player name.");
                p.setOwner(value);
            }
            case "exit" -> {
                if (!(s instanceof Player player)) throw new UsageException("Only players can set the exit.");
                if (!player.getWorld().getName().equals(p.getWorld())) throw new UsageException("Stand in the portal's world.");
                p.setExit(player.getLocation());
            }
            case "region" -> {
                if (!(s instanceof Player player)) throw new UsageException("Only players have a selection.");
                Location[] sel = plugin.selections().get(player);
                if (sel == null) throw new UsageException("Select two corners first.");
                Portal test = new Portal("test", sel[0], sel[1]);
                long max = plugin.getConfig().getLong("portals.max-volume", 4096);
                if (test.volume() > max) throw new UsageException("That selection is " + test.volume() + " blocks - the limit is " + max + ".");
                p.setRegion(sel[0], sel[1]);
            }
            default -> throw new UsageException("Unknown property '" + a[1] + "'.");
        }
        plugin.portals().save();
        Msg.ok(s, "Portal '" + p.getName() + "' updated.");
    }
}
