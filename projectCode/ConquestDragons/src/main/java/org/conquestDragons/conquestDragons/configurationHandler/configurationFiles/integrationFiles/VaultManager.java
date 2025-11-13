package org.conquestDragons.conquestDragons.configurationHandler.configurationFiles.integrationFiles;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.conquestClans.conquestclans.ConquestClans;

import java.util.logging.Logger;

/**
 * 🔌 VaultManager
 * Handles optional Vault (Economy) integration with full fallback safety.
 * Behavior:
 *  - If disabled via config, integration is OFF.
 *  - If enabled but Vault plugin is missing, gracefully falls back to OFF.
 *  - If enabled and Vault is present but no Economy provider is registered,
 *    gracefully falls back to OFF.
 */
public final class VaultManager {

    private static final ConquestClans plugin = ConquestClans.getInstance();
    private static final Logger log = plugin.getLogger();

    private static boolean requestedEnabled = false; // what config asked for
    private static boolean active = false;           // actual state after checks
    private static Economy economy = null;

    private VaultManager() {
        // Utility class — no instantiation
    }

    /**
     * Initialize Vault integration based on a config flag.
     * If Vault plugin or Economy provider is missing, we fall back to inactive.
     */
    public static void initialize(boolean enabledByConfig) {
        requestedEnabled = enabledByConfig;

        if (!enabledByConfig) {
            disable("⛔  Vault integration disabled via config.");
            return;
        }

        // Check Vault plugin presence
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            disable("⚠️  Vault plugin not found or not enabled. Continuing without integration.");
            return;
        }

        // Hook economy provider
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            disable("⛔  No Vault Economy provider found. Continuing without economy.");
            return;
        }

        economy = rsp.getProvider();

        active = true;
        log.info("🔌  Vault connected. Economy provider: " + economy.getName());
    }

    /**
     * Explicit shutdown/unhook. Safe to call on plugin disable.
     */
    public static void shutdown() {
        active = false;
        economy = null;
        log.info("🔻  Vault integration shut down.");
    }

    private static void disable(String reason) {
        active = false;
        economy = null;
        log.info(reason);
    }

    // ---------------- Public API (all null-safe) ----------------

    /**
     * @return true if config requested Vault AND we successfully hooked an Economy provider.
     */
    public static boolean isActive() {
        return active;
    }

    /**
     * @return true if an Economy provider is available.
     */
    public static boolean hasEconomy() {
        return active && economy != null;
    }

    /**
     * Deposit money if economy is available.
     * @return true on success or if economy is not active (no-op success).
     */
    public static boolean deposit(Player player, double amount) {
        if (!hasEconomy() || player == null || amount <= 0) return false;
        return economy.depositPlayer(player, amount).transactionSuccess();
    }

    /**
     * Withdraw money if economy is available.
     * @return true on success or if economy is not active (no-op failure = false).
     */
    public static boolean withdraw(Player player, double amount) {
        if (!hasEconomy() || player == null || amount <= 0) return false;
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    /**
     * Format currency using the provider if present; otherwise a simple fallback.
     */
    public static String format(double amount) {
        if (hasEconomy()) {
            try {
                return economy.format(amount);
            } catch (Throwable ignored) { /* provider-specific quirks */ }
        }
        return String.format("%.2f", amount);
    }

    /**
     * Access to the raw Vault Economy instance, if needed.
     * Prefer using helper methods above for safety.
     */
    public static Economy getEconomy() {
        return economy;
    }

    /**
     * Whether the config requested integration (regardless of hook success).
     */
    public static boolean wasRequestedEnabled() {
        return requestedEnabled;
    }
}
