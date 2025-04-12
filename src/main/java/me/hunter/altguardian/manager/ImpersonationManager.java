package me.hunter.altguardian.manager;

import me.hunter.altguardian.AltGuardianPlugin; // Needed for constructor -> getDatabaseManager
import me.hunter.altguardian.config.ConfigManager;
import me.hunter.altguardian.config.MessageManager;
import me.hunter.altguardian.database.DatabaseManager;
import org.apache.commons.text.similarity.LevenshteinDistance;
// Import removed: import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

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

    // Constructor updated to get DatabaseManager directly
    public ImpersonationManager(AltGuardianPlugin plugin, PlayerDataManager playerDataManager, ConfigManager configManager, FlagManager flagManager, MessageManager messageManager) {
        this.playerDataManager = playerDataManager;
        this.configManager = configManager;
        this.flagManager = flagManager;
        this.messageManager = messageManager;
        DatabaseManager databaseManager = plugin.getDatabaseManager(); // Get DB Manager from plugin
        this.databaseExecutor = databaseManager.getExecutor();
    }

    /**
     * Represents the result of an impersonation check.
     * (Enum definition remains the same)
     */
    public enum ImpersonationCheckResult { PASSED, WARN, FLAG, PREVENT, ERROR }

    /**
     * Record to return result and matched username.
     * (Record definition remains the same)
     */
    public record ImpersonationInfo(ImpersonationCheckResult result, String similarMatch) {}


    /**
     * Checks a new username against existing ones for potential impersonation.
     * (Method body updated)
     */
    public CompletableFuture<ImpersonationInfo> checkImpersonationOnFirstJoinAsync(String joiningUsername) {
        if (!configManager.isImpersonationEnabled()) { return CompletableFuture.completedFuture(new ImpersonationInfo(ImpersonationCheckResult.PASSED, null)); }
        if (configManager.isImpersonationUsernameExempt(joiningUsername)) { messageManager.logDebug("Username " + joiningUsername + " is exempt from impersonation checks."); return CompletableFuture.completedFuture(new ImpersonationInfo(ImpersonationCheckResult.PASSED, null)); }

        final boolean ignoreCase = configManager.isImpersonationIgnoreCase();
        final String usernameToCheck = ignoreCase ? joiningUsername.toLowerCase() : joiningUsername;
        final int threshold = configManager.getLevenshteinThreshold();
        final String algorithm = configManager.getImpersonationAlgorithm();

        if (!"LEVENSHTEIN".equalsIgnoreCase(algorithm)) { messageManager.logWarning("Unsupported impersonation algorithm: " + algorithm); return CompletableFuture.completedFuture(new ImpersonationInfo(ImpersonationCheckResult.PASSED, null)); }

        messageManager.logDebug("Checking impersonation for " + joiningUsername + " (Threshold: " + threshold + ", IgnoreCase: " + ignoreCase + ")");

        return playerDataManager.getAllUsernamesLowerAsync().thenApplyAsync(existingUsernames -> {
            String similarMatch = null;

            for (String existing : existingUsernames) {
                // <<< Removed redundant conditional expression >>>
                // PlayerDataManager already provides lowercase 'existing' if ignoreCase is true in config,
                // so we just use 'existing' directly for comparison value.
                final String compareExisting = existing;

                // Don't compare against self
                // Use equalsIgnoreCase if ignoreCase is true, otherwise use equals
                if (ignoreCase ? usernameToCheck.equalsIgnoreCase(compareExisting) : usernameToCheck.equals(compareExisting)) { continue; }
                // Don't check against other exempt usernames
                if (configManager.isImpersonationUsernameExempt(compareExisting)) { continue; }

                int distance = levenshtein.apply(usernameToCheck, compareExisting);
                if (distance >= 0 && distance <= threshold) {
                    messageManager.logInfo("Potential impersonation detected: '" + joiningUsername + "' is similar to '" + compareExisting + "' (Distance: " + distance + ")");
                    similarMatch = compareExisting;
                    break;
                }
            }

            // --- Handle Result --- (Remains the same)
            if (similarMatch != null) {
                String action = configManager.getImpersonationActionOnRegister();
                String flagReason = "Potential Impersonation (Similar to: " + similarMatch + ")";
                if (!"NONE".equals(action)) { messageManager.broadcastToPermission("altguardian.notify", "admin-notify-impersonation", MessageManager.player(joiningUsername), MessageManager.similarTo(similarMatch), MessageManager.action(action)); }
                switch (action) {
                    case "PREVENT": messageManager.logDebug("Action PREVENT for impersonation of " + joiningUsername); return new ImpersonationInfo(ImpersonationCheckResult.PREVENT, similarMatch);
                    case "FLAG": messageManager.logDebug("Action FLAG for impersonation of " + joiningUsername); try { flagManager.addFlagAsync(joiningUsername, flagReason, "AutoDetect").join(); return new ImpersonationInfo(ImpersonationCheckResult.FLAG, similarMatch); } catch (Exception e) { messageManager.logError("Failed to add impersonation flag for " + joiningUsername, e); return new ImpersonationInfo(ImpersonationCheckResult.WARN, similarMatch); }
                    case "WARN": messageManager.logDebug("Action WARN for impersonation of " + joiningUsername); return new ImpersonationInfo(ImpersonationCheckResult.WARN, similarMatch);
                    case "NOTIFY": case "NONE": default: messageManager.logDebug("Action NONE/NOTIFY for impersonation of " + joiningUsername); return new ImpersonationInfo(ImpersonationCheckResult.PASSED, null);
                }
            } else { messageManager.logDebug("Impersonation check passed for " + joiningUsername); return new ImpersonationInfo(ImpersonationCheckResult.PASSED, null); }
        }, databaseExecutor);
    }


    /** Call this method when config reloads */
    public void notifyConfigReloaded() {
        messageManager.logDebug("ImpersonationManager notified of config reload.");
    }
}