package org.mydreamduels.dreammultiverse.economy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Entry fees / portal prices. Currency is either "money" (Vault, looked up
 * reflectively so Vault stays a pure soft-dependency) or a Material name,
 * in which case that many items are taken from the player's inventory.
 * Must be called on the player's own thread.
 */
public final class EconomyHook {

    private final Logger logger;
    private Object vault;
    private Method has;
    private Method withdraw;
    private Method format;
    private Method success;

    public EconomyHook(Logger logger) {
        this.logger = logger;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void hook() {
        vault = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        try {
            Class economy = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider rsp = Bukkit.getServicesManager().getRegistration(economy);
            if (rsp == null) {
                logger.info("Vault found but no economy plugin is registered - money fees disabled.");
                return;
            }
            vault = rsp.getProvider();
            has = economy.getMethod("has", OfflinePlayer.class, double.class);
            withdraw = economy.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            format = economy.getMethod("format", double.class);
            success = Class.forName("net.milkbowl.vault.economy.EconomyResponse").getMethod("transactionSuccess");
            logger.info("Hooked into Vault economy.");
        } catch (Throwable t) {
            vault = null;
            logger.warning("Could not hook into Vault: " + t);
        }
    }

    public boolean hasVault() {
        return vault != null;
    }

    public static boolean isMoney(String currency) {
        return currency == null || currency.isBlank() || currency.equalsIgnoreCase("money");
    }

    public String describe(double amount, String currency) {
        if (isMoney(currency)) {
            if (vault != null) {
                try {
                    return (String) format.invoke(vault, amount);
                } catch (Throwable ignored) {
                }
            }
            return String.format(Locale.ROOT, "%.2f", amount);
        }
        return ((int) Math.ceil(amount)) + "x " + currency.toUpperCase(Locale.ROOT);
    }

    /** Takes the fee. Returns null on success, otherwise the reason it failed. */
    public String charge(Player player, double amount, String currency) {
        if (amount <= 0) return null;
        if (isMoney(currency)) {
            if (vault == null) return null; // no economy - nothing to charge, don't block the player
            try {
                if (!(boolean) has.invoke(vault, player, amount)) {
                    return "You need " + describe(amount, currency) + " to do that.";
                }
                Object response = withdraw.invoke(vault, player, amount);
                return (boolean) success.invoke(response) ? null : "Payment failed.";
            } catch (Throwable t) {
                logger.warning("Vault withdraw failed: " + t);
                return "Payment failed.";
            }
        }

        Material material = Material.matchMaterial(currency);
        if (material == null || !material.isItem()) return null;
        int needed = (int) Math.ceil(amount);
        if (!player.getInventory().containsAtLeast(new ItemStack(material), needed)) {
            return "You need " + describe(amount, currency) + " to do that.";
        }
        player.getInventory().removeItem(new ItemStack(material, needed));
        return null;
    }
}
