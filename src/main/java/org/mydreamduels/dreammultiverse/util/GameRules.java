package org.mydreamduels.dreammultiverse.util;

import org.bukkit.GameRule;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed gamerule access by name. Names are matched loosely (case, '_' and
 * the "minecraft:" prefix ignored) because Mojang renamed every rule from
 * camelCase (keepInventory) to snake_case (keep_inventory) - both work.
 */
public final class GameRules {

    private GameRules() {
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace("_", "");
    }

    public static GameRule<?> find(String name) {
        GameRule<?> direct = GameRule.getByName(name);
        if (direct != null) return direct;
        String n = norm(name);
        for (GameRule<?> r : GameRule.values()) {
            if (norm(r.getName()).equals(n) || norm(r.getKey().getKey()).equals(n)) return r;
        }
        return null;
    }

    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (GameRule<?> r : GameRule.values()) out.add(r.getName());
        out.sort(String::compareTo);
        return out;
    }

    /** Current values of every rule this world has. */
    public static Map<GameRule<?>, Object> snapshot(World world) {
        Map<GameRule<?>, Object> out = new LinkedHashMap<>();
        for (GameRule<?> r : GameRule.values()) {
            Object v = world.getGameRuleValue(r);
            if (v != null) out.put(r, v);
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void restore(World world, Map<GameRule<?>, Object> values) {
        for (Map.Entry<GameRule<?>, Object> e : values.entrySet()) {
            try {
                world.setGameRule((GameRule) e.getKey(), e.getValue());
            } catch (RuntimeException ignored) {
                // rule not applicable to this world (e.g. feature-flagged)
            }
        }
    }

    /** Null on success, else the problem. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String set(World world, GameRule<?> rule, String raw) {
        Object value;
        if (rule.getType() == Boolean.class) {
            if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) return rule.getName() + " must be true or false.";
            value = Boolean.parseBoolean(raw);
        } else if (rule.getType() == Integer.class) {
            try {
                value = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return rule.getName() + " must be a whole number.";
            }
        } else {
            return "Unsupported gamerule type.";
        }
        return world.setGameRule((GameRule) rule, value) ? null : "The server rejected that value.";
    }
}
