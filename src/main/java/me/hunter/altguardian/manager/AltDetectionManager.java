package me.hunter.altguardian.manager;

import me.hunter.altguardian.AltGuardianPlugin;
import me.hunter.altguardian.config.ConfigManager;
import me.hunter.altguardian.config.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors; // Import Collectors

/**
 * Handles the logic for detecting potential alt accounts based on IP address usage.
 */
public class AltDetectionManager {

    private final AltGuardianPlugin plugin;
    private final PlayerDataManager playerDataManager;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final FlagManager flagManager;
    private final ExecutorService databaseExecutor;


    public AltDetectionManager(AltGuardianPlugin plugin, PlayerDataManager playerDataManager, ConfigManager configManager, MessageManager messageManager, FlagManager flagManager) {
        this.plugin = plugin;
        this.playerDataManager = playerDataManager;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.flagManager = flagManager;
        this.databaseExecutor = plugin.getDatabaseManager().getExecutor();
    }

    /**
     * Checks if a player logging in potentially violates the configured alt limits.
     * Takes appropriate action based on configuration (flag, kick, notify).
     *
     * @param playerBukkit The Bukkit Player object (needed for kick).
     * @param username     The username attempting to log in.
     * @param ipAddress    The IP address they are connecting from.
     * @param loginTimeMillis The timestamp of the login attempt.
     * @return CompletableFuture<Boolean> indicating if the login should be DISALLOWED (true = kick, false = allow/flag/notify).
     */
    public CompletableFuture<Boolean> checkAltStatusOnLoginAsync(Player playerBukkit, String username, String ipAddress, long loginTimeMillis) {
        if (!configManager.isAltDetectionEnabled()) {
            messageManager.logDebug("Alt detection disabled, skipping check for " + username);
            return CompletableFuture.completedFuture(false);
        }
        if (configManager.isIpExempt(ipAddress)) {
            messageManager.logDebug("IP " + ipAddress + " is exempt, skipping alt check for " + username);
            return CompletableFuture.completedFuture(false);
        }
        if (configManager.isAltUsernameExempt(username)) {
            messageManager.logDebug("User " + username + " is exempt, skipping alt check.");
            return CompletableFuture.completedFuture(false);
        }

        final int maxAccounts = configManager.getMaxAccountsPerIp();
        if (maxAccounts <= 0) {
            messageManager.logDebug("Max accounts per IP is <= 0, skipping alt check for " + username);
            return CompletableFuture.completedFuture(false);
        }

        final long checkSinceMillis = loginTimeMillis - (configManager.getCheckTimeframeSeconds() * 1000);

        messageManager.logDebug("Checking alts for " + username + " from " + ipAddress + " (Max: " + maxAccounts + ", Timeframe: " + configManager.getCheckTimeframeSeconds() + "s)");

        return playerDataManager.getRecentAccountIdsByIp(ipAddress, checkSinceMillis)
                .thenComposeAsync(recentAccountIds -> {
                    messageManager.logDebug("Found " + recentAccountIds.size() + " recent unique account IDs for IP " + ipAddress);

                    // Use getOrCreate to ensure the player has an ID before checking limits
                    // Pass loginTimeMillis as the registration time if they are new
                    return playerDataManager.getOrCreateAccountIdAsync(username, loginTimeMillis, playerBukkit.getUniqueId()).thenComposeAsync(currentAccountId -> {
                        // Create a mutable copy to potentially add the current user
                        Set<Integer> allRelevantIds = new java.util.HashSet<>(recentAccountIds);

                        // Add the current player's ID to the set
                        allRelevantIds.add(currentAccountId); // Add the ID we just got/created

                        final int distinctAccountCount = allRelevantIds.size();
                        messageManager.logDebug("Distinct account count including " + username + " from " + ipAddress + ": " + distinctAccountCount);

                        if (distinctAccountCount > maxAccounts) {
                            messageManager.logInfo("Alt limit exceeded for user " + username + " from IP " + ipAddress + " (" + distinctAccountCount + "/" + maxAccounts + ")");

                            final String action = configManager.getAltLimitExceededAction();
                            final String flagReason = "Potential Alt (Limit: " + maxAccounts + " IP: " + ipAddress + ")";

                            // Fetch usernames for notification message
                            return playerDataManager.getUsernamesByIdsAsync(allRelevantIds).thenApplyAsync(names -> {
                                String involvedUsers = names.stream().collect(Collectors.joining(", ")); // Simple join

                                // --- FIX: Add placeholders for admin-notify-alt-limit ---
                                messageManager.broadcastToPermission("altguardian.notify", "admin-notify-alt-limit",
                                        MessageManager.player(username),            // {player}
                                        MessageManager.ip(ipAddress),               // {ip}
                                        MessageManager.count(distinctAccountCount), // {count}
                                        MessageManager.max(maxAccounts),            // {max}
                                        MessageManager.action(action),              // {action}
                                        Placeholder.unparsed("involved_players", involvedUsers) // Use the generated list
                                );

                                if ("FLAG".equals(action)) {
                                    try {
                                        flagManager.addFlagAsync(username, flagReason, "AutoDetect").join();
                                    } catch (Exception e) {
                                        messageManager.logError("Failed to add alt flag for " + username, e);
                                    }
                                    return false; // Allow login
                                } else if ("KICK".equals(action)) {
                                    // --- FIX: Add placeholders for kick-alt-limit-exceeded ---
                                    final Component kickMessage = messageManager.get("kick-alt-limit-exceeded",
                                            MessageManager.count(distinctAccountCount), // {count}
                                            MessageManager.max(maxAccounts)             // {max}
                                    );
                                    // Schedule kick to main thread
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if (playerBukkit != null && playerBukkit.isOnline()) {
                                            playerBukkit.kick(kickMessage);
                                        }
                                    });
                                    return true; // Disallow login
                                } else { // NOTIFY or NONE
                                    return false; // Allow login
                                }
                            }, databaseExecutor); // Execute name lookup and action logic on DB executor

                        } else {
                            // Limit not exceeded
                            messageManager.logDebug("Alt check passed for " + username + " from " + ipAddress + ".");
                            return CompletableFuture.completedFuture(false); // Allow login
                        }
                    }, databaseExecutor); // Continue on DB executor after getting/creating ID

                }, databaseExecutor); // Run initial IP check on DB executor
    }


    /** Call this method when config reloads if any internal state needs updating */
    public void notifyConfigReloaded() {
        messageManager.logDebug("AltDetectionManager notified of config reload.");
    }

}