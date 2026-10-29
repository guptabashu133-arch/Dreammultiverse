package org.mydreamduels.dreammultiverse.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Legacy &-colour messaging (plus &#RRGGBB hex) - works the same on Spigot, Paper and Folia. */
public final class Msg {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static volatile String prefix = "";

    private Msg() {
    }

    public static void setPrefix(String raw) {
        prefix = color(raw == null ? "" : raw);
    }

    @SuppressWarnings("deprecation")
    public static String color(String text) {
        if (text == null) return "";
        Matcher m = HEX.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    @SuppressWarnings("deprecation")
    public static String strip(String text) {
        return ChatColor.stripColor(color(text));
    }

    public static void send(CommandSender to, String message) {
        if (to != null) to.sendMessage(prefix + color(message));
    }

    /** No prefix - for list rows, help lines etc. */
    public static void raw(CommandSender to, String message) {
        if (to != null) to.sendMessage(color(message));
    }

    public static void ok(CommandSender to, String message) {
        send(to, "&a" + message);
    }

    public static void err(CommandSender to, String message) {
        send(to, "&c" + message);
    }
}
