package org.mydreamduels.dreammultiverse.inventory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A set of worlds that share some (or all) player data. */
public final class InvGroup {

    private final String name;
    private final Set<String> worlds = ConcurrentHashMap.newKeySet();
    private volatile Set<Sharable> shares = EnumSet.allOf(Sharable.class);

    public InvGroup(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean contains(String world) {
        return worlds.contains(world.toLowerCase(Locale.ROOT));
    }

    public void addWorld(String world) {
        worlds.add(world.toLowerCase(Locale.ROOT));
    }

    public boolean removeWorld(String world) {
        return worlds.remove(world.toLowerCase(Locale.ROOT));
    }

    public Set<String> getWorlds() {
        return Collections.unmodifiableSet(worlds);
    }

    public boolean shares(Sharable s) {
        return shares.contains(s);
    }

    public Set<Sharable> getShares() {
        return Collections.unmodifiableSet(shares);
    }

    public void setShares(Set<Sharable> shares) {
        this.shares = shares.isEmpty() ? EnumSet.noneOf(Sharable.class) : EnumSet.copyOf(shares);
    }
}
