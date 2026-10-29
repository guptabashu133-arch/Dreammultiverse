package org.mydreamduels.dreammultiverse.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mydreamduels.dreammultiverse.util.Msg;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Dangerous commands (delete, regen, remove) only run after /mv confirm. */
public final class ConfirmManager {

    private record Pending(Runnable action, long expires) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final long timeoutMillis;

    public ConfirmManager(long timeoutSeconds) {
        this.timeoutMillis = Math.max(5, timeoutSeconds) * 1000L;
    }

    private static String key(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId().toString() : "console";
    }

    public void ask(CommandSender sender, String what, Runnable action) {
        pending.put(key(sender), new Pending(action, System.currentTimeMillis() + timeoutMillis));
        Msg.send(sender, "&e" + what + " &7Type &a/mv confirm &7within " + timeoutMillis / 1000 + "s to continue.");
    }

    public void confirm(CommandSender sender) {
        Pending p = pending.remove(key(sender));
        if (p == null || p.expires < System.currentTimeMillis()) {
            Msg.err(sender, "Nothing to confirm.");
            return;
        }
        p.action.run();
    }
}
