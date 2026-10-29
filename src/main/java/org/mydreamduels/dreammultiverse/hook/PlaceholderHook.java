package org.mydreamduels.dreammultiverse.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.mydreamduels.dreammultiverse.Dreammultiverse;
import org.mydreamduels.dreammultiverse.inventory.InvGroup;
import org.mydreamduels.dreammultiverse.world.MVWorld;

import java.util.List;
import java.util.Locale;

/**
 * %dreammultiverse_...% placeholders. Only ever loaded when PlaceholderAPI
 * is installed (Dreammultiverse checks first), so the PlaceholderAPI
 * classes are never touched otherwise.
 *
 *   world, world_alias, world_gamemode, world_difficulty, world_pvp,
 *   world_players, world_limit, world_environment, world_seed,
 *   world_fee, world_group, worlds_loaded, worlds_total,
 *   players_<world>, alias_<world>
 */
public final class PlaceholderHook extends PlaceholderExpansion {

    private final Dreammultiverse plugin;

    public PlaceholderHook(Dreammultiverse plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "dreammultiverse";
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @SuppressWarnings("deprecation")
    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @SuppressWarnings("deprecation")
    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.equals("worlds_loaded")) return String.valueOf(plugin.getServer().getWorlds().size());
        if (p.equals("worlds_total")) return String.valueOf(plugin.worlds().all().size());
        if (p.startsWith("players_")) return String.valueOf(count(plugin.getServer().getWorld(params.substring(8))));
        if (p.startsWith("alias_")) {
            MVWorld mv = plugin.worlds().get(params.substring(6));
            return mv == null ? "" : mv.getDisplayName();
        }

        Player player = offline == null ? null : offline.getPlayer();
        if (player == null) return "";
        World world = player.getWorld();
        MVWorld mv = plugin.worlds().get(world);
        return switch (p) {
            case "world" -> world.getName();
            case "world_alias" -> mv == null ? world.getName() : mv.getDisplayName();
            case "world_gamemode" -> mv == null ? "" : mv.getGameMode().name().toLowerCase(Locale.ROOT);
            case "world_difficulty" -> world.getDifficulty().name().toLowerCase(Locale.ROOT);
            case "world_pvp" -> String.valueOf(world.getPVP());
            case "world_players" -> String.valueOf(count(world));
            case "world_limit" -> mv == null || mv.getPlayerLimit() < 0 ? "∞" : String.valueOf(mv.getPlayerLimit());
            case "world_environment" -> world.getEnvironment().name().toLowerCase(Locale.ROOT);
            case "world_seed" -> String.valueOf(world.getSeed());
            case "world_fee" -> mv == null ? "0" : plugin.economy().describe(mv.getEntryFee(), mv.getCurrency());
            case "world_group" -> {
                List<InvGroup> groups = plugin.inventories().groupsOf(world.getName());
                yield groups.isEmpty() ? "" : groups.get(0).getName();
            }
            default -> null;
        };
    }

    private int count(World world) {
        if (world == null) return 0;
        int n = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) if (p.getWorld().equals(world)) n++;
        return n;
    }
}
