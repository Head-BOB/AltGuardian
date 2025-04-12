package me.hunter.altguardian.api;

import me.hunter.altguardian.api.model.AccountDetails;
import me.hunter.altguardian.api.model.IpHistoryRecord;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Defines the publicly accessible methods for interacting with AltGuardian's data
 * from other plugins.
 *
 * Use Bukkit's ServicesManager to get an instance of this API:
 *
 * <pre>{@code
 * RegisteredServiceProvider<AltGuardianAPI> provider = getServer().getServicesManager().getRegistration(AltGuardianAPI.class);
 * if (provider != null) {
 *     AltGuardianAPI altApi = provider.getProvider();
 *     // Use altApi methods here...
 * } else {
 *     // AltGuardian plugin or API not found/enabled
 * }
 * }</pre>
 */
// No blank line before interface declaration
public interface AltGuardianAPI {

    /**
     * Asynchronously retrieves detailed information about a specific player account known to AltGuardian.
     *
     * @param username The case-insensitive username of the player.
     * @return A CompletableFuture containing an Optional with the AccountDetails if found,
     *         or an empty Optional if the player is not tracked by AltGuardian.
     */
    @SuppressWarnings("unused") // API Method
    CompletableFuture<Optional<AccountDetails>> getAccountDetailsAsync(String username);

    /**
     * Asynchronously retrieves the recent IP address history for a specific player account.
     * The history is limited by the `database.ip-history-retention-days` config setting.
     * Results are typically ordered from most recent to oldest usage.
     *
     * @param username The case-insensitive username of the player.
     * @return A CompletableFuture containing a List of IpHistoryRecord objects, or an empty list
     *         if the player is not tracked or has no recorded IP history within the retention period.
     */
    @SuppressWarnings("unused") // API Method
    CompletableFuture<List<IpHistoryRecord>> getIpHistoryAsync(String username);

    /**
     * Asynchronously retrieves a list of usernames known to have logged in from a specific IP address
     * within the `alt-detection.check-timeframe-seconds` configured in AltGuardian.
     *
     * @param ipAddress The IP address to query.
     * @return A CompletableFuture containing a List of usernames, or an empty list if no accounts
     *         were found using that IP within the timeframe.
     */
    @SuppressWarnings("unused") // API Method
    CompletableFuture<List<String>> getRecentAccountsByIpAsync(String ipAddress);

    /**
     * Asynchronously retrieves a list of usernames potentially linked to the given player
     * based on shared IP address history within the configured timeframe.
     * This provides a list of potential alt accounts for investigation.
     *
     * @param username The case-insensitive username of the primary player to check for alts.
     * @return A CompletableFuture containing a List of usernames considered potential alts
     *         (excluding the input username itself), or an empty list if none are found.
     */
    @SuppressWarnings("unused") // API Method
    CompletableFuture<List<String>> getPotentialAltsAsync(String username);

    /**
     * Asynchronously retrieves the set of flags currently active for a specific player account.
     *
     * @param username The case-insensitive username of the player.
     * @return A CompletableFuture containing a Set of flag strings (e.g., "Potential Alt", "Impersonation"),
     *         or an empty set if the player is not tracked or has no active flags.
     */
    @SuppressWarnings("unused") // API Method
    CompletableFuture<Set<String>> getPlayerFlagsAsync(String username);

    /**
     * Asynchronously adds a flag to a player's account.
     * This is intended for administrative use or integration with other moderation plugins.
     *
     * @param username The case-insensitive username of the player to flag.
     * @param flag     The reason or identifier for the flag (e.g., "Manual Alt Watch", "Suspicious Activity").
     * @param actor    Optional: The username or identifier of who added the flag (e.g., admin name, "AutoDetect"). Can be null.
     * @return A CompletableFuture that completes when the flag has been added (or fails if the player doesn't exist).
     *         The future contains Boolean: true if flag was newly added, false if it already existed.
     */
    @SuppressWarnings("unused") // API Method
    CompletableFuture<Boolean> addPlayerFlagAsync(String username, String flag, String actor);

    /**
     * Asynchronously removes a specific flag from a player's account.
     *
     * @param username The case-insensitive username of the player.
     * @param flag     The specific flag string to remove.
     * @param actor    Optional: The username or identifier of who removed the flag. Can be null.
     * @return A CompletableFuture that completes when the flag removal attempt is finished.
     *         The future contains Boolean: true if flag existed and was removed, false otherwise.
     */
    @SuppressWarnings("unused") // API Method
    CompletableFuture<Boolean> removePlayerFlagAsync(String username, String flag, String actor);

    /**
     * Checks if a specific IP address is currently on the alt-detection exemption list.
     * Note: This is a synchronous check as exemption lists are typically cached in memory.
     *
     * @param ipAddress The IP address to check.
     * @return true if the IP address is exempt, false otherwise.
     */
    @SuppressWarnings("unused") // API Method
    boolean isIpExempt(String ipAddress);

    /**
     * Checks if a specific username is currently on the alt-detection or impersonation exemption lists.
     * Note: This is a synchronous check as exemption lists are typically cached in memory.
     *
     * @param username The case-insensitive username to check.
     * @return true if the username is exempt from alt counting or impersonation checks, false otherwise.
     */
    @SuppressWarnings("unused") // API Method
    boolean isUsernameExempt(String username);

    /**
     * Gets the configured display timezone ID (e.g., "UTC", "America/New_York").
     * Useful for consuming plugins that need to format raw timestamps provided by other API methods.
     * Note: This is a synchronous check reading from cached config.
     *
     * @return The configured timezone ID string.
     */
    @SuppressWarnings("unused") // API Method
    String getConfiguredDisplayTimezone();

}