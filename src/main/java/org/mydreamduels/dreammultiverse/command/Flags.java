package org.mydreamduels.dreammultiverse.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "-s 123 -g Plugin:id -n" style options. valueFlags take the next
 * argument; anything else starting with '-' is a switch.
 * Long forms (--seed) are accepted for every flag via the aliases map.
 */
public final class Flags {

    public final List<String> positional = new ArrayList<>();
    private final Map<String, String> values = new HashMap<>();
    private final Set<String> switches = new HashSet<>();

    public static Flags parse(String[] args, Set<String> valueFlags, Map<String, String> aliases) {
        Flags f = new Flags();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("-") && a.length() > 1 && !isNumber(a)) {
                String key = a.replaceFirst("^-+", "").toLowerCase(Locale.ROOT);
                key = aliases.getOrDefault(key, key);
                if (valueFlags.contains(key)) {
                    if (i + 1 >= args.length) throw new CommandRouter.UsageException("Missing value for " + a);
                    f.values.put(key, args[++i]);
                } else {
                    f.switches.add(key);
                }
            } else {
                f.positional.add(a);
            }
        }
        return f;
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public String get(String key) {
        return values.get(key);
    }

    public boolean has(String key) {
        return switches.contains(key) || values.containsKey(key);
    }
}
