package org.mydreamduels.dreammultiverse.inventory;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Everything a world group can share between its worlds. */
public enum Sharable {
    INVENTORY, ARMOR, OFFHAND, ENDER_CHEST, HEALTH, HUNGER, EXPERIENCE, POTIONS, BED_SPAWN;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Sharable parse(String s) {
        String n = s.toUpperCase(Locale.ROOT).replace('-', '_');
        if (n.equals("ENDERCHEST")) n = "ENDER_CHEST";
        if (n.equals("XP") || n.equals("EXP")) n = "EXPERIENCE";
        if (n.equals("FOOD")) n = "HUNGER";
        if (n.equals("BED") || n.equals("BEDSPAWN")) n = "BED_SPAWN";
        if (n.equals("EFFECTS")) n = "POTIONS";
        try {
            return valueOf(n);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Set<Sharable> all() {
        return EnumSet.allOf(Sharable.class);
    }
}
