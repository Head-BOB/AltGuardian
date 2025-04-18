package me.hunter.altguardian.manager;

import me.hunter.altguardian.AltGuardianPlugin;
import me.hunter.altguardian.config.ConfigManager;
import me.hunter.altguardian.config.MessageManager;
import me.hunter.altguardian.database.DatabaseManager;
import org.apache.commons.text.similarity.LevenshteinDistance;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.Objects;

/**
 * Handles checking usernames for similarity to detect potential impersonation attempts.
 */
public class ImpersonationManager {

    private final PlayerDataManager playerDataManager;
    private final ConfigManager configManager;
    private final FlagManager flagManager;
    private final MessageManager messageManager;
    private final ExecutorService databaseExecutor;

    private final LevenshteinDistance levenshtein = LevenshteinDistance.getDefaultInstance();

    public ImpersonationManager(AltGuardianPlugin plugin, PlayerDataManager playerDataManager, ConfigManager configManager, FlagManager flagManager, MessageManager messageManager) {
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.playerDataManager = Objects.requireNonNull(playerDataManager, "PlayerDataManager cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager cannot be null");
        this.flagManager = Objects.requireNonNull(flagManager, "FlagManager cannot be null");
        this.messageManager = Objects.requireNonNull(messageManager, "MessageManager cannot be null");
        DatabaseManager databaseManager = plugin.getDatabaseManager();
        this.databaseExecutor = Objects.requireNonNull(databaseManager.getExecutor(), "DatabaseExecutor cannot be null");
    }

    public enum ImpersonationCheckResult { PASSED, WARN, FLAG, PREVENT, ERROR }

    public record ImpersonationInfo(ImpersonationCheckResult result, String similarMatch) {}


    /**
     * Checks a new username against existing ones for potential impersonation.
     * Uses the synchronous PlayerDataManager method as per previous fix.
     */
    public CompletableFuture<ImpersonationInfo> checkImpersonationOnFirstJoinAsync(String joiningUsername) {
        if (!configManager.isImpersonationEnabled()) {
            return CompletableFuture.completedFuture(new ImpersonationInfo(ImpersonationCheckResult.PASSED, null));
        }
        if (configManager.isImpersonationUsernameExempt(joiningUsername)) {
            messageManager.logDebug("Username " + joiningUsername + " is exempt from impersonation checks.");
            return CompletableFuture.completedFuture(new ImpersonationInfo(ImpersonationCheckResult.PASSED, null));
        }

        final boolean ignoreCase = configManager.isImpersonationIgnoreCase();
        final String usernameToCheck = ignoreCase ? joiningUsername.toLowerCase() : joiningUsername;
        final int threshold = configManager.getLevenshteinThreshold();
        final String algorithm = configManager.getImpersonationAlgorithm();

        if (!"LEVENSHTEIN".equalsIgnoreCase(algorithm)) {
            messageManager.logWarning("Unsupported impersonation algorithm: " + algorithm);
            return CompletableFuture.completedFuture(new ImpersonationInfo(ImpersonationCheckResult.PASSED, null));
        }

        messageManager.logDebug("Checking impersonation for " + joiningUsername + " (Threshold: " + threshold + ", IgnoreCase: " + ignoreCase + ")");

        return CompletableFuture.supplyAsync(() -> {
            Collection<String> existingUsernames;
            try {
                existingUsernames = playerDataManager.getAllUsernamesLowerSync(); // Using sync method
            } catch (Exception e) {
                messageManager.logError("Failed to get usernames synchronously for impersonation check", e);
                return new ImpersonationInfo(ImpersonationCheckResult.ERROR, null);
            }

            String similarMatch = null;
            if (existingUsernames != null) {
                for (String existing : existingUsernames) {
                    if (ignoreCase ? usernameToCheck.equalsIgnoreCase(existing) : usernameToCheck.equals(existing)) { continue; }
                    if (configManager.isImpersonationUsernameExempt(existing)) { continue; }

                    int distance = levenshtein.apply(usernameToCheck, existing);
                    if (distance >= 0 && distance <= threshold) {
                        messageManager.logInfo("Potential impersonation detected: '" + joiningUsername + "' is similar to '" + existing + "' (Distance: " + distance + ")");
                        similarMatch = existing;
                        break;
                    }
                }
            } else {
                messageManager.logWarning("Received null username collection from sync method.");
            }

            // --- Handle Result ---
            if (similarMatch != null) {
                String action = configManager.getImpersonationActionOnRegister();
                String flagReason = "Potential Impersonation (Similar to: " + similarMatch + ")";

                if (!"NONE".equals(action)) {
                    // --- FIX: Add placeholders for admin-notify-impersonation ---
                    messageManager.broadcastToPermission("altguardian.notify", "admin-notify-impersonation",
                            MessageManager.player(joiningUsername), // {player}
                            MessageManager.similarTo(similarMatch), // {similar_to}
                            MessageManager.action(action)           // {action}
                    );
                }
                switch (action) {
                    case "PREVENT":
                        messageManager.logDebug("Action PREVENT for impersonation of " + joiningUsername);
                        return new ImpersonationInfo(ImpersonationCheckResult.PREVENT, similarMatch);
                    case "FLAG":
                        messageManager.logDebug("Action FLAG for impersonation of " + joiningUsername);
                        try {
                            flagManager.addFlagAsync(joiningUsername, flagReason, "AutoDetect").join();
                            return new ImpersonationInfo(ImpersonationCheckResult.FLAG, similarMatch);
                        } catch (Exception e) {
                            messageManager.logError("Failed to add impersonation flag for " + joiningUsername, e);
                            return new ImpersonationInfo(ImpersonationCheckResult.WARN, similarMatch);
                        }
                    case "WARN":
                        messageManager.logDebug("Action WARN for impersonation of " + joiningUsername);
                        return new ImpersonationInfo(ImpersonationCheckResult.WARN, similarMatch);
                    case "NOTIFY":
                    case "NONE":
                    default:
                        messageManager.logDebug("Action NONE/NOTIFY for impersonation of " + joiningUsername);
                        return new ImpersonationInfo(ImpersonationCheckResult.PASSED, null);
                }
            } else {
                messageManager.logDebug("Impersonation check passed for " + joiningUsername);
                return new ImpersonationInfo(ImpersonationCheckResult.PASSED, null);
            }
        }, databaseExecutor);
    }

    public void notifyConfigReloaded() {
        messageManager.logDebug("ImpersonationManager notified of config reload.");
    }
}