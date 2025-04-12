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

/**
 * Handles the logic for detecting potential alt accounts based on IP address usage.
 */
public class AltDetectionManager {

    private final AltGuardianPlugin plugin; // <<< Added field
    private final PlayerDataManager playerDataManager;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final FlagManager flagManager; // Added dependency
    private final ExecutorService databaseExecutor; // <<< Added field


    // Constructor updated to accept plugin
    public AltDetectionManager(AltGuardianPlugin plugin, PlayerDataManager playerDataManager, ConfigManager configManager, MessageManager messageManager, FlagManager flagManager) {
        this.plugin = plugin; // <<< Store plugin
        this.playerDataManager = playerDataManager;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.flagManager = flagManager; // Store FlagManager
        // Get executor from PlayerDataManager's DatabaseManager reference (requires PlayerDataManager to expose it or pass DBManager here too)
        // Assuming PlayerDataManager provides access or we get it from plugin:
        this.databaseExecutor = plugin.getDatabaseManager().getExecutor(); // <<< Get executor
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
            return CompletableFuture.completedFuture(false); // Allow login
        }
        if (configManager.isIpExempt(ipAddress)) {
            messageManager.logDebug("IP " + ipAddress + " is exempt, skipping alt check for " + username);
            return CompletableFuture.completedFuture(false); // Allow login
        }
        if (configManager.isAltUsernameExempt(username)) {
            messageManager.logDebug("User " + username + " is exempt, skipping alt check.");
            return CompletableFuture.completedFuture(false); // Allow login
        }

        final int maxAccounts = configManager.getMaxAccountsPerIp();
        if (maxAccounts <= 0) {
            messageManager.logDebug("Max accounts per IP is <= 0, skipping alt check for " + username);
            return CompletableFuture.completedFuture(false); // Allow login (feature disabled)
        }

        final long checkSinceMillis = loginTimeMillis - (configManager.getCheckTimeframeSeconds() * 1000);

        messageManager.logDebug("Checking alts for " + username + " from " + ipAddress + " (Max: " + maxAccounts + ", Timeframe: " + configManager.getCheckTimeframeSeconds() + "s)");

        // Get account IDs that used this IP recently (runs on DB executor via PlayerDataManager)
        return playerDataManager.getRecentAccountIdsByIp(ipAddress, checkSinceMillis)
                .thenComposeAsync(recentAccountIds -> {
                    messageManager.logDebug("Found " + recentAccountIds.size() + " recent unique account IDs for IP " + ipAddress);

                    // Get the ID of the player currently logging in (might be new or existing)
                    // Runs on DB executor via PlayerDataManager
                    return playerDataManager.getAccountIdAsync(username).thenComposeAsync(currentAccountIdOpt -> {
                        // Create a mutable copy to potentially add the current user
                        Set<Integer> allRelevantIds = new java.util.HashSet<>(recentAccountIds);

                        // Add the current player's ID to the set if they have one,
                        // otherwise they count as a potential new account towards the limit.
                        currentAccountIdOpt.ifPresent(allRelevantIds::add);

                        final int distinctAccountCount = allRelevantIds.size();
                        messageManager.logDebug("Distinct account count including " + username + " from " + ipAddress + ": " + distinctAccountCount);

                        if (distinctAccountCount > maxAccounts) {
                            messageManager.logInfo("Alt limit exceeded for user " + username + " from IP " + ipAddress + " (" + distinctAccountCount + "/" + maxAccounts + ")");

                            // Limit exceeded, determine action
                            final String action = configManager.getAltLimitExceededAction();
                            final String flagReason = "Potential Alt (Limit: " + maxAccounts + " IP: " + ipAddress + ")";

                            // Fetch usernames for notification message (runs on DB executor via PlayerDataManager)
                            return playerDataManager.getUsernamesByIdsAsync(allRelevantIds).thenApplyAsync(names -> {
                                String involvedUsers = String.join(", ", names);
                                // Broadcast notification (MessageManager handles thread safety for broadcast)
                                messageManager.broadcastToPermission("altguardian.notify", "admin-notify-alt-limit",
                                        MessageManager.player(username),
                                        MessageManager.ip(ipAddress),
                                        MessageManager.count(distinctAccountCount),
                                        MessageManager.max(maxAccounts),
                                        MessageManager.action(action),
                                        Placeholder.unparsed("involved_players", involvedUsers) // Add involved players placeholder
                                );

                                if ("FLAG".equals(action)) {
                                    // Flag the user currently logging in
                                    // Need to ensure flag is added before allowing login, use join() here
                                    try {
                                        flagManager.addFlagAsync(username, flagReason, "AutoDetect").join();
                                    } catch (Exception e) {
                                        messageManager.logError("Failed to add alt flag for " + username, e);
                                        // Allow login even if flagging failed? Or treat as error? Let's allow.
                                    }
                                    return false; // Allow login, but flagged (or attempted flag)
                                } else if ("KICK".equals(action)) {
                                    // We need to kick the player. This MUST happen on the main thread.
                                    final Component kickMessage = messageManager.get("kick-alt-limit-exceeded",
                                            MessageManager.count(distinctAccountCount),
                                            MessageManager.max(maxAccounts));
                                    // Schedule the kick on the main server thread
                                    Bukkit.getScheduler().runTask(plugin, () -> { // <<< Use Bukkit scheduler for main thread task
                                        // Check if player is still online before kicking
                                        if (playerBukkit != null && playerBukkit.isOnline()) {
                                            playerBukkit.kick(kickMessage); // Use component kick
                                        }
                                    });
                                    return true; // Indicate login should be disallowed
                                } else if ("NOTIFY".equals(action)) {
                                    // Only notify, already done above.
                                    return false; // Allow login
                                } else { // NONE or unknown action
                                    return false; // Allow login
                                }
                            }, databaseExecutor); // Run the final name mapping and action logic on DB executor


                        } else {
                            // Limit not exceeded
                            messageManager.logDebug("Alt check passed for " + username + " from " + ipAddress + ".");
                            return CompletableFuture.completedFuture(false); // Allow login
                        }
                    }, databaseExecutor); // Ensure the composition after getAccountIdAsync runs on DB executor

                }, databaseExecutor); // Ensure the composition after getRecentAccountIdsByIp runs on DB executor
    }


    /** Call this method when config reloads if any internal state needs updating */
    public void notifyConfigReloaded() {
        messageManager.logDebug("AltDetectionManager notified of config reload.");
        // No cached config values here currently, but could clear internal rate limiters etc. if added later.
    }

}