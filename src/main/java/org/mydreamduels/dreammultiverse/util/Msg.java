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
        if (to != null) deliver(to, prefix + color("&#F2F2F2" + message));
    }

    /** No prefix - for list rows, help lines etc. */
    public static void raw(CommandSender to, String message) {
        if (to != null) deliver(to, color(message));
    }

    public static void ok(CommandSender to, String message) {
        send(to, "&#7CF5A6✔ &#F2F2F2" + message);
    }

    public static void err(CommandSender to, String message) {
        send(to, "&#FF6B81✖ " + message);
    }

    private static volatile Boolean shadows;

    /** Players get a solid black text shadow (the TaraashMC look); consoles get plain text. */
    private static void deliver(CommandSender to, String legacy) {
        if (to instanceof org.bukkit.entity.Player p && shadowsSupported()) {
            try {
                Shadow.send(p, legacy);
                return;
            } catch (Throwable t) {
                shadows = false;
            }
        }
        to.sendMessage(legacy);
    }

    private static boolean shadowsSupported() {
        Boolean s = shadows;
        if (s == null) {
            try {
                Class.forName("net.kyori.adventure.text.format.ShadowColor");
                org.bukkit.entity.Player.class.getMethod("sendMessage", Class.forName("net.kyori.adventure.text.Component"));
                s = true;
            } catch (Throwable t) {
                s = false;
            }
            shadows = s;
        }
        return s;
    }

    private static final class Shadow {
        static void send(org.bukkit.entity.Player p, String legacy) {
            p.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .deserialize(legacy).shadowColor(net.kyori.adventure.text.format.ShadowColor.shadowColor(0xFF000000)));
        }
    }
}
