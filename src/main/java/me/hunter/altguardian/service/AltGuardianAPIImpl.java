package me.hunter.altguardian.service;

import me.hunter.altguardian.api.AltGuardianAPI;
import me.hunter.altguardian.api.model.AccountDetails;
import me.hunter.altguardian.api.model.IpHistoryRecord;
import me.hunter.altguardian.config.ConfigManager;
import me.hunter.altguardian.database.DatabaseManager; // <<< Added Import for DatabaseManager
import me.hunter.altguardian.manager.FlagManager;
import me.hunter.altguardian.manager.PlayerDataManager;
// Unused import removed: import org.bukkit.Bukkit;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Implementation of the AltGuardianAPI interface.
 * Delegates calls to the appropriate managers.
 */
public class AltGuardianAPIImpl implements AltGuardianAPI {

    private final PlayerDataManager playerDataManager;
    private final FlagManager flagManager;
    private final ConfigManager configManager;
    private final Executor databaseExecutor; // Use the DB executor

    // Constructor updated to accept DatabaseManager for executor access
    public AltGuardianAPIImpl(PlayerDataManager playerDataManager, FlagManager flagManager, ConfigManager configManager, DatabaseManager databaseManager) { // <<< Added DatabaseManager parameter
        this.playerDataManager = playerDataManager;
        this.flagManager = flagManager;
        this.configManager = configManager;
        // <<< Get executor from the passed DatabaseManager >>>
        this.databaseExecutor = databaseManager.getExecutor();
    }

    @Override
    public CompletableFuture<Optional<AccountDetails>> getAccountDetailsAsync(String username) {
        // Delegate directly, PlayerDataManager handles async and execution
        return playerDataManager.getAccountDetailsAsync(username);
    }

    @Override
    public CompletableFuture<List<IpHistoryRecord>> getIpHistoryAsync(String username) {
        // Use a reasonable default limit for API calls if not specified, e.g., 50
        final int defaultLimit = 50;
        // Delegate directly
        return playerDataManager.getIpHistoryAsync(username, defaultLimit);
    }

    @Override
    public CompletableFuture<List<String>> getRecentAccountsByIpAsync(String ipAddress) {
        long sinceMillis = Instant.now().toEpochMilli() - (configManager.getCheckTimeframeSeconds() * 1000);
        // Get IDs, then map to usernames
        return playerDataManager.getRecentAccountIdsByIp(ipAddress, sinceMillis)
                .thenComposeAsync(playerDataManager::getUsernamesByIdsAsync, databaseExecutor); // Map IDs to usernames on DB executor
    }

    @Override
    public CompletableFuture<List<String>> getPotentialAltsAsync(String username) {
        final int historyCheckLimit = 10; // Check last 10 unique IPs
        final long timeframeSeconds = configManager.getCheckTimeframeSeconds();
        final long sinceMillis = Instant.now().toEpochMilli() - (timeframeSeconds * 1000);

        // 1. Get the source player's ID first
        return playerDataManager.getAccountIdAsync(username).thenComposeAsync(sourceIdOpt -> {
            if (sourceIdOpt.isEmpty()) {
                // Source player not found, return empty list
                return CompletableFuture.completedFuture(Collections.emptyList()); // <<< Removed explicit type arg
            }
            final int sourceAccountId = sourceIdOpt.get();

            // 2. Get recent IPs for the source user
            return playerDataManager.getIpHistoryAsync(username, historyCheckLimit)
                    .thenComposeAsync(history -> {
                        if (history.isEmpty()) {
                            return CompletableFuture.completedFuture(Collections.emptyList()); // No IPs, no alts
                        }

                        // 3. Get all unique IPs from the recent history
                        // <<< Use record accessor ipAddress() >>>
                        Set<String> recentIPs = history.stream()
                                .map(IpHistoryRecord::ipAddress)
                                .collect(Collectors.toSet());

                        // 4. For each IP, get account IDs that used it recently
                        List<CompletableFuture<Set<Integer>>> ipAccountFutures = recentIPs.stream()
                                .map(ip -> playerDataManager.getRecentAccountIdsByIp(ip, sinceMillis))
                                .toList(); // Use toList() - Java 16+

                        // 5. Combine all account IDs, filter out the source player's ID
                        return CompletableFuture.allOf(ipAccountFutures.toArray(new CompletableFuture[0]))
                                .thenApplyAsync(v ->
                                                ipAccountFutures.stream() // <<< Directly return stream result
                                                        .map(CompletableFuture::join) // Get the Set<Integer> results
                                                        .flatMap(Set::stream)          // Flatten into a stream of Integers
                                                        .filter(id -> id != sourceAccountId) // Exclude the source player ID
                                                        .collect(Collectors.toSet())   // Collect unique alt IDs
                                        , databaseExecutor)
                                // 6. Convert the filtered IDs to usernames
                                .thenComposeAsync(altIds -> {
                                    if (altIds.isEmpty()) {
                                        return CompletableFuture.completedFuture(Collections.emptyList()); // <<< Removed explicit type arg
                                    }
                                    return playerDataManager.getUsernamesByIdsAsync(altIds);
                                }, databaseExecutor); // Run final ID->Username mapping on DB executor

                    }, databaseExecutor); // Run IP history and subsequent steps on DB executor

        }, databaseExecutor); // Run initial ID lookup on DB executor
    }


    @Override
    public CompletableFuture<Set<String>> getPlayerFlagsAsync(String username) {
        // Delegate directly
        return flagManager.getFlagsAsync(username);
    }

    @Override
    public CompletableFuture<Boolean> addPlayerFlagAsync(String username, String flag, String actor) {
        // Delegate directly
        return flagManager.addFlagAsync(username, flag, actor);
    }

    @Override
    public CompletableFuture<Boolean> removePlayerFlagAsync(String username, String flag, String actor) {
        // Delegate directly
        return flagManager.removeFlagAsync(username, flag, actor);
    }

    @Override
    public boolean isIpExempt(String ipAddress) {
        // Directly use ConfigManager's cached value (synchronous)
        return configManager.isIpExempt(ipAddress);
    }

    @Override
    public boolean isUsernameExempt(String username) {
        // Check both exemption lists from ConfigManager (synchronous)
        return configManager.isAltUsernameExempt(username) || configManager.isImpersonationUsernameExempt(username);
    }

    @Override
    public String getConfiguredDisplayTimezone() {
        // Directly use ConfigManager's cached value (synchronous)
        return configManager.getDisplayTimezone();
    }
}