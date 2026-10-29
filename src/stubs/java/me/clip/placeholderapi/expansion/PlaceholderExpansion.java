package me.clip.placeholderapi.expansion;

import org.bukkit.OfflinePlayer;

/**
 * COMPILE-TIME STUB ONLY - never packaged (see the jar plugin excludes in
 * pom.xml). PlaceholderAPI's repository isn't reachable from every build
 * environment, so this mirrors just the signatures Dreammultiverse uses;
 * at runtime the real class from PlaceholderAPI is used.
 */
public abstract class PlaceholderExpansion {

    public abstract String getIdentifier();

    public abstract String getAuthor();

    public abstract String getVersion();

    public boolean persist() {
        return false;
    }

    public String onRequest(OfflinePlayer player, String params) {
        return null;
    }

    public boolean register() {
        throw new UnsupportedOperationException("stub");
    }
}
