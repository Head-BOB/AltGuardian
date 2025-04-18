package me.hunter.altguardian.manager;

import me.hunter.altguardian.config.MessageManager;
import me.hunter.altguardian.database.DatabaseManager;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender; // Import ConsoleCommandSender

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

    private final DatabaseManager databaseManager;
    private final PlayerDataManager playerDataManager;
    private final MessageManager messageManager;
    private final ExecutorService databaseExecutor;

    public FlagManager(PlayerDataManager playerDataManager, DatabaseManager databaseManager, MessageManager messageManager) {
        this.playerDataManager = Objects.requireNonNull(playerDataManager, "PlayerDataManager cannot be null");
        this.databaseManager = Objects.requireNonNull(databaseManager, "DatabaseManager cannot be null");
        this.messageManager = Objects.requireNonNull(messageManager, "MessageManager cannot be null");
        this.databaseExecutor = databaseManager.getExecutor();
    }

    /**
     * Asynchronously adds a flag to a player's account.
     */
    public CompletableFuture<Boolean> addFlagAsync(String username, String flagReason, String actor) {
        long timestamp = Instant.now().toEpochMilli();
        String actorName = actor != null ? actor : "System"; // Use System if actor is null

        return playerDataManager.getAccountIdAsync(username).thenComposeAsync(accountIdOpt -> {
            if (accountIdOpt.isPresent()) {
                int accountId = accountIdOpt.get();
                return databaseManager.addFlagAsync(accountId, flagReason, timestamp).thenApplyAsync(added -> {
                    if (added) {
                        messageManager.logInfo("Flag '" + flagReason + "' added to player " + username + " by " + actorName);
                        // Call notification helper
                        notifyFlagChange(username, flagReason, true, actorName);
                    } else {
                        messageManager.logDebug("Flag '" + flagReason + "' already exists for player " + username + ".");
                    }
                    return added;
                }, databaseExecutor);
            } else {
                messageManager.logWarning("Attempted to add flag '" + flagReason + "' to non-existent player: " + username);
                return CompletableFuture.completedFuture(false); // Player doesn't exist
            }
        }, databaseExecutor);
    }


    /**
     * Asynchronously removes a flag from a player's account.
     */
    public CompletableFuture<Boolean> removeFlagAsync(String username, String flagReason, String actor) {
        String actorName = actor != null ? actor : "System"; // Use System if actor is null

        return playerDataManager.getAccountIdAsync(username).thenComposeAsync(accountIdOpt -> {
            if (accountIdOpt.isPresent()) {
                int accountId = accountIdOpt.get();
                return databaseManager.removeFlagAsync(accountId, flagReason).thenApplyAsync(removed -> {
                    if (removed) {
                        messageManager.logInfo("Flag '" + flagReason + "' removed from player " + username + " by " + actorName);
                        // Call notification helper
                        notifyFlagChange(username, flagReason, false, actorName);
                    } else {
                        messageManager.logDebug("Flag '" + flagReason + "' did not exist for player " + username + " or removal failed.");
                    }
                    return removed;
                }, databaseExecutor);
            } else {
                messageManager.logWarning("Attempted to remove flag '" + flagReason + "' from non-existent player: " + username);
                return CompletableFuture.completedFuture(false); // Player doesn't exist
            }
        }, databaseExecutor);
    }

    /**
     * Asynchronously retrieves all flags associated with a player.
     */
    public CompletableFuture<Set<String>> getFlagsAsync(String username) {
        return playerDataManager.getAccountIdAsync(username).thenComposeAsync(accountIdOpt ->
                        accountIdOpt.map(databaseManager::getFlagsAsync)
                                .orElse(CompletableFuture.completedFuture(Collections.emptySet()))
                , databaseExecutor);
    }


    /**
     * Sends notifications about flag changes to relevant parties.
     */
    private void notifyFlagChange(String username, String flag, boolean added, String actorName) {
        String messageKey = added ? "admin-notify-flag-added" : "admin-notify-flag-removed";
        // --- FIX: Add placeholders for admin-notify-flag-added/removed ---
        messageManager.broadcastToPermission("altguardian.notify", messageKey,
                MessageManager.actor(actorName),   // {actor}
                MessageManager.flag(flag),       // {flag}
                MessageManager.player(username)    // {player}
        );
    }

    /** Helper to get CommandSender name safely */
    public String getActorName(CommandSender sender) {
        if (sender == null) return "System"; // Default if sender is null
        // Use instanceof check for ConsoleCommandSender
        if (sender instanceof ConsoleCommandSender) {
            return messageManager.getRawString("console-name", "Console"); // Get name from messages.yml
        }
        return sender.getName(); // Get player name
    }


    /** Call this method when config reloads if any internal state needs updating */
    public void notifyConfigReloaded() {
        messageManager.logDebug("FlagManager notified of config reload.");
        // No config values cached here that need updating
    }
}