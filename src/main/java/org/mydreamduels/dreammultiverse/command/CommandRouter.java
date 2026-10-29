package org.mydreamduels.dreammultiverse.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.mydreamduels.dreammultiverse.util.Msg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /root <sub> [args...] dispatcher with paged help and tab completion. */
public final class CommandRouter implements TabExecutor {

    private static final int PER_PAGE = 9;

    private final String root;
    private final String title;
    private final Map<String, Sub> byName = new LinkedHashMap<>();
    private final List<Sub> ordered = new ArrayList<>();

    public CommandRouter(String root, String title) {
        this.root = root;
        this.title = title;
    }

    public CommandRouter add(Sub sub) {
        ordered.add(sub);
        byName.put(sub.name, sub);
        for (String a : sub.aliases) byName.put(a, sub);
        return this;
    }

    private static boolean allowed(CommandSender sender, Sub sub) {
        return sub.permission == null || sender.hasPermission(sub.permission) || sender.hasPermission("dreammultiverse.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equals("?")) {
            int page = 1;
            if (args.length > 1) {
                try {
                    page = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {
                }
            }
            help(sender, page);
            return true;
        }
        dispatch(sender, args[0], Arrays.copyOfRange(args, 1, args.length));
        return true;
    }

    /** Also used by the /mvtp, /mvspawn... shortcut commands. */
    public void dispatch(CommandSender sender, String subName, String[] args) {
        Sub sub = byName.get(subName.toLowerCase(Locale.ROOT));
        if (sub == null) {
            Msg.err(sender, "Unknown command. Try /" + root + " help");
            return;
        }
        if (!allowed(sender, sub)) {
            Msg.err(sender, "You don't have permission to do that.");
            return;
        }
        if (sub.playerOnly && !(sender instanceof Player)) {
            Msg.err(sender, "Only players can use that.");
            return;
        }
        if (args.length < sub.minArgs) {
            usage(sender, sub);
            return;
        }
        try {
            sub.exec.run(sender, args);
        } catch (UsageException e) {
            if (e.getMessage() != null) Msg.err(sender, e.getMessage());
            usage(sender, sub);
        }
    }

    public void usage(CommandSender sender, Sub sub) {
        Msg.raw(sender, "&7Usage: &e/" + root + " " + sub.name + " " + sub.usage);
    }

    private void help(CommandSender sender, int page) {
        List<Sub> visible = new ArrayList<>();
        for (Sub s : ordered) if (allowed(sender, s)) visible.add(s);
        int pages = Math.max(1, (visible.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(1, Math.min(pages, page));
        Msg.raw(sender, "&8&m      &r &b" + title + " &7(" + page + "/" + pages + ") &8&m      ");
        for (int i = (page - 1) * PER_PAGE; i < Math.min(visible.size(), page * PER_PAGE); i++) {
            Sub s = visible.get(i);
            Msg.raw(sender, "&e/" + root + " " + s.name + (s.usage.isEmpty() ? "" : " &6" + s.usage) + " &8- &7" + s.description);
        }
        if (page < pages) Msg.raw(sender, "&7Next page: &e/" + root + " help " + (page + 1));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            for (Sub s : ordered) if (allowed(sender, s)) names.add(s.name);
            names.add("help");
            return Tabs.filter(names, args[0]);
        }
        Sub sub = byName.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null || !allowed(sender, sub)) return List.of();
        return tabFor(sender, sub.name, Arrays.copyOfRange(args, 1, args.length));
    }

    public List<String> tabFor(CommandSender sender, String subName, String[] args) {
        Sub sub = byName.get(subName.toLowerCase(Locale.ROOT));
        if (sub == null || !allowed(sender, sub) || args.length == 0) return List.of();
        List<String> out = sub.tab.complete(sender, args);
        return out == null ? List.of() : Tabs.filter(out, args[args.length - 1]);
    }

    /** Thrown by a subcommand to print an error (optional) followed by its usage line. */
    public static final class UsageException extends RuntimeException {
        public UsageException(String message) {
            super(message, null, false, false);
        }
    }
}
