package me.hunter.altguardian.manager;

// Import removed: import me.hunter.altguardian.AltGuardianPlugin; // No longer needed
// Import removed: import me.hunter.altguardian.config.ConfigManager; // No longer needed

import me.hunter.altguardian.config.MessageManager;
import me.hunter.altguardian.database.DatabaseManager;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Manages adding, removing, and retrieving player flags.
 * Handles database interaction and notifications.
 */
public class FlagManager {

    // private final AltGuardianPlugin plugin; // <<< Removed unused field
    private final DatabaseManager databaseManager;
    private final PlayerDataManager playerDataManager;
    private final MessageManager messageManager;
    // private final ConfigManager configManager; // <<< Removed unused field
    private final ExecutorService databaseExecutor;

    // Constructor updated to remove plugin and configManager parameters
    public FlagManager(/* AltGuardianPlugin plugin, */ PlayerDataManager playerDataManager, DatabaseManager databaseManager, MessageManager messageManager /*, ConfigManager configManager */) {
        // this.plugin = plugin; // Removed assignment
        // Use Objects.requireNonNull for dependencies passed in constructor
        this.playerDataManager = Objects.requireNonNull(playerDataManager, "PlayerDataManager cannot be null");
        this.databaseManager = Objects.requireNonNull(databaseManager, "DatabaseManager cannot be null");
        this.messageManager = Objects.requireNonNull(messageManager, "MessageManager cannot be null");
        // this.configManager = configManager; // Removed assignment
        this.databaseExecutor = databaseManager.getExecutor();
    }

    /**
     * Asynchronously adds a flag to a player's account.
     * (Method body remains the same)
     */
    public CompletableFuture<Boolean> addFlagAsync(String username, String flagReason, String actor) {
        long timestamp = Instant.now().toEpochMilli();
        String actorName = actor != null ? actor : "System";
        return playerDataManager.getAccountIdAsync(username).thenComposeAsync(accountIdOpt -> {
            if (accountIdOpt.isPresent()) {
                int accountId = accountIdOpt.get();
                return databaseManager.addFlagAsync(accountId, flagReason, timestamp).thenApplyAsync(added -> {
                    if (added) {
                        messageManager.logInfo("Flag '" + flagReason + "' added to player " + username + " by " + actorName);
                        notifyFlagChange(username, flagReason, true, actorName);
                    } else { messageManager.logDebug("Flag '" + flagReason + "' already exists for player " + username + "."); }
                    return added;
                }, databaseExecutor);
            } else { messageManager.logWarning("Attempted to add flag '" + flagReason + "' to non-existent player: " + username); return CompletableFuture.completedFuture(false); }
        }, databaseExecutor);
    }


    /**
     * Asynchronously removes a flag from a player's account.
     * (Method body remains the same)
     */
    public CompletableFuture<Boolean> removeFlagAsync(String username, String flagReason, String actor) {
        String actorName = actor != null ? actor : "System";
        return playerDataManager.getAccountIdAsync(username).thenComposeAsync(accountIdOpt -> {
            if (accountIdOpt.isPresent()) {
                int accountId = accountIdOpt.get();
                return databaseManager.removeFlagAsync(accountId, flagReason).thenApplyAsync(removed -> {
                    if (removed) {
                        messageManager.logInfo("Flag '" + flagReason + "' removed from player " + username + " by " + actorName);
                        notifyFlagChange(username, flagReason, false, actorName);
                    } else { messageManager.logDebug("Flag '" + flagReason + "' did not exist for player " + username + " or removal failed."); }
                    return removed;
                }, databaseExecutor);
            } else { messageManager.logWarning("Attempted to remove flag '" + flagReason + "' from non-existent player: " + username); return CompletableFuture.completedFuture(false); }
        }, databaseExecutor);
    }

    /**
     * Asynchronously retrieves all flags associated with a player.
     * (Method body remains the same)
     */
    public CompletableFuture<Set<String>> getFlagsAsync(String username) {
        return playerDataManager.getAccountIdAsync(username).thenComposeAsync(accountIdOpt -> {
            if (accountIdOpt.isPresent()) { return databaseManager.getFlagsAsync(accountIdOpt.get()); }
            else { return CompletableFuture.completedFuture(Collections.emptySet()); }
        }, databaseExecutor);
    }


    /**
     * Sends notifications about flag changes to relevant parties.
     * (Method body remains the same)
     */
    private void notifyFlagChange(String username, String flag, boolean added, String actorName) {
        String messageKey = added ? "admin-notify-flag-added" : "admin-notify-flag-removed";
        messageManager.broadcastToPermission("altguardian.notify", messageKey,
                MessageManager.actor(actorName),
                MessageManager.flag(flag),
                MessageManager.player(username)
        );

        // <<< Removed commented out code block for self-notification >>>
    }

    /** Helper to get CommandSender name safely */
    public String getActorName(CommandSender sender) {
        if (sender == null) return "System";
        if (sender instanceof org.bukkit.command.ConsoleCommandSender) { return messageManager.getRawString("console-name", "Console"); }
        return sender.getName();
    }


    /** Call this method when config reloads if any internal state needs updating */
    public void notifyConfigReloaded() {
        messageManager.logDebug("FlagManager notified of config reload.");
        // Still no cached config values here that need updating
    }
}