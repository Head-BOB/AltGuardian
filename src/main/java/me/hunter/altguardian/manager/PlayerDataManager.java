package me.hunter.altguardian.manager;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import me.hunter.altguardian.api.model.AccountDetails;
import me.hunter.altguardian.api.model.IpHistoryRecord;
import me.hunter.altguardian.config.MessageManager;
import me.hunter.altguardian.database.DatabaseManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class PlayerDataManager {

    private final DatabaseManager databaseManager;
    private final MessageManager messageManager;
    private final ExecutorService databaseExecutor; // Still useful for other async ops
    private final Cache<String, Optional<Integer>> accountIdCache;
    private Set<String> allUsernamesCache = ConcurrentHashMap.newKeySet();
    private long lastUsernameCacheUpdateTime = 0;
    private static final long USERNAME_CACHE_DURATION_MS = TimeUnit.MINUTES.toMillis(10);

    public PlayerDataManager(DatabaseManager databaseManager, MessageManager messageManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "DatabaseManager cannot be null");
        this.messageManager = Objects.requireNonNull(messageManager, "MessageManager cannot be null");
        this.databaseExecutor = databaseManager.getExecutor();
        this.accountIdCache = CacheBuilder.newBuilder()
                .maximumSize(5000)
                .expireAfterAccess(1, TimeUnit.HOURS)
                .build();
        // Initial cache population (can still be async on enable)
        refreshAllUsernamesCacheAsync();
    }

    // getOrCreateAccountIdAsync remains asynchronous as it involves potential creation
    public CompletableFuture<Integer> getOrCreateAccountIdAsync(@NotNull String username, long registrationTimeMillis, @Nullable UUID offlineUuid) {
        String lowerUsername = username.toLowerCase();
        Optional<Integer> cachedIdOptional = accountIdCache.getIfPresent(lowerUsername);
        if (cachedIdOptional != null) {
            if (cachedIdOptional.isPresent()) {
                messageManager.logDebug("Account ID cache hit for: " + lowerUsername);
                return CompletableFuture.completedFuture(cachedIdOptional.get());
            } else {
                messageManager.logDebug("Account ID cache negative hit for: " + lowerUsername);
                // Fall through to DB lookup if negative cache hit
            }
        } else {
            messageManager.logDebug("Account ID cache miss for: " + lowerUsername);
        }
        return fetchAndCacheAccountId(username, lowerUsername, registrationTimeMillis, offlineUuid);
    }

    private CompletableFuture<Integer> fetchAndCacheAccountId(String username, String lowerUsername, long registrationTimeMillis, @Nullable UUID offlineUuid) {
        return databaseManager.findAccountIdAsync(lowerUsername)
                .thenComposeAsync(accountIdOpt -> {
                    if (accountIdOpt != null) {
                        messageManager.logDebug("Found account ID " + accountIdOpt + " for " + lowerUsername + " in DB.");
                        accountIdCache.put(lowerUsername, Optional.of(accountIdOpt));
                        return CompletableFuture.completedFuture(accountIdOpt);
                    } else {
                        messageManager.logDebug("Account not found for " + lowerUsername + ". Creating new entry...");
                        return databaseManager.createAccountAsync(username, registrationTimeMillis, offlineUuid)
                                .thenApply(newAccountId -> {
                                    if (newAccountId != null) {
                                        messageManager.logDebug("Created new account for " + username + " with ID: " + newAccountId);
                                        accountIdCache.put(lowerUsername, Optional.of(newAccountId));
                                        if (allUsernamesCache != null) allUsernamesCache.add(lowerUsername);
                                        return newAccountId;
                                    } else {
                                        messageManager.logError("Failed to create database entry for new player: " + username);
                                        accountIdCache.put(lowerUsername, Optional.empty());
                                        throw new RuntimeException("Failed to create account ID for " + username);
                                    }
                                });
                    }
                }, databaseExecutor);
    }

    // recordSuccessfulLoginAsync remains async
    public CompletableFuture<Void> recordSuccessfulLoginAsync(String username, String ipAddress, long loginTimeMillis) {
        messageManager.logDebug("Recording successful login for " + username + " from IP " + ipAddress);
        return getOrCreateAccountIdAsync(username, loginTimeMillis, null).thenComposeAsync(accountId -> {
            if (accountId == null) {
                messageManager.logError("Cannot record login: Account ID not found or creation failed earlier for player " + username + ".");
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> updateLoginTime = databaseManager.updateLastLoginAsync(accountId, loginTimeMillis);
            CompletableFuture<Void> recordIp = databaseManager.recordIpUsageAsync(accountId, ipAddress, loginTimeMillis);
            return CompletableFuture.allOf(updateLoginTime, recordIp);
        }, databaseExecutor);
    }

    // getAccountIdAsync remains async
    public CompletableFuture<Optional<Integer>> getAccountIdAsync(String username) {
        String lowerUsername = username.toLowerCase();
        Optional<Integer> cachedIdOptional = accountIdCache.getIfPresent(lowerUsername);
        if (cachedIdOptional != null) {
            return CompletableFuture.completedFuture(cachedIdOptional);
        }
        return databaseManager.findAccountIdAsync(lowerUsername).thenApplyAsync(id -> {
            Optional<Integer> result = Optional.ofNullable(id);
            accountIdCache.put(lowerUsername, result);
            return result;
        }, databaseExecutor);
    }


    // --- MODIFIED METHOD ---
    /**
     * Synchronously retrieves all usernames in lowercase from the cache or database.
     * WARNING: This method now blocks if a cache refresh is needed!
     *
     * @return Collection<String> of usernames.
     */
    public Collection<String> getAllUsernamesLowerSync() { // <-- RENAMED and made SYNCHRONOUS
        long now = System.currentTimeMillis();
        if (allUsernamesCache != null && !allUsernamesCache.isEmpty() && (now - lastUsernameCacheUpdateTime < USERNAME_CACHE_DURATION_MS)) {
            messageManager.logDebug("Using cached username list (" + allUsernamesCache.size() + " entries).");
            return Collections.unmodifiableCollection(allUsernamesCache);
        }
        // Refresh synchronously and block
        return refreshAllUsernamesCacheSync(); // <-- Call the new sync refresh
    }

    // --- MODIFIED METHOD ---
    /**
     * Synchronously refreshes the all-usernames cache from the database.
     * WARNING: This method BLOCKS the calling thread!
     *
     * @return Collection<String> of usernames.
     */
    private Collection<String> refreshAllUsernamesCacheSync() { // <-- RENAMED and made SYNCHRONOUS
        messageManager.logDebug("Refreshing all-usernames cache from database (SYNCHRONOUSLY)...");
        try {
            // Block and wait for the database result using join()
            Set<String> usernames = databaseManager.getAllUsernamesLowerAsync().join(); // <-- BLOCKING CALL

            messageManager.logDebug("Username cache refreshed. Found " + usernames.size() + " usernames.");
            Set<String> newCache = ConcurrentHashMap.newKeySet(usernames.size());
            newCache.addAll(usernames);
            this.allUsernamesCache = newCache;
            this.lastUsernameCacheUpdateTime = System.currentTimeMillis();
            return Collections.unmodifiableCollection(this.allUsernamesCache);
        } catch (Exception e) {
            messageManager.logError("Failed to synchronously refresh username cache!", e);
            return Collections.emptyList(); // Return empty on error
        }
    }

    // --- Keep original async refresh for initial load if desired ---
    private CompletableFuture<Collection<String>> refreshAllUsernamesCacheAsync() {
        messageManager.logDebug("Refreshing all-usernames cache from database (ASYNCHRONOUSLY - for internal use)...");
        return databaseManager.getAllUsernamesLowerAsync().thenApplyAsync(usernames -> {
            messageManager.logDebug("Async Username cache refreshed. Found " + usernames.size() + " usernames.");
            Set<String> newCache = ConcurrentHashMap.newKeySet(usernames.size());
            newCache.addAll(usernames);
            this.allUsernamesCache = newCache;
            this.lastUsernameCacheUpdateTime = System.currentTimeMillis();
            return Collections.unmodifiableCollection(this.allUsernamesCache);
        }, databaseExecutor);
    }


    // --- Other methods remain the same ---
    public CompletableFuture<Optional<AccountDetails>> getAccountDetailsAsync(String username) { return databaseManager.getAccountDetailsAsync(username); }
    public CompletableFuture<List<IpHistoryRecord>> getIpHistoryAsync(String username, int limit) { return getAccountIdAsync(username).thenComposeAsync(accIdOpt -> accIdOpt.map(id -> databaseManager.getIpHistoryAsync(id, limit)).orElse(CompletableFuture.completedFuture(Collections.emptyList())), databaseExecutor); }
    public CompletableFuture<Set<Integer>> getRecentAccountIdsByIp(String ipAddress, long sinceMillis) { return databaseManager.getAccountIdsByIpSinceAsync(ipAddress, sinceMillis); }
    public CompletableFuture<List<String>> getUsernamesByIdsAsync(Collection<Integer> accountIds) { if (accountIds == null || accountIds.isEmpty()) { return CompletableFuture.completedFuture(Collections.emptyList()); } List<CompletableFuture<String>> futures = accountIds.stream().map(databaseManager::findUsernameByIdAsync).toList(); return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApplyAsync(v -> futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList(), databaseExecutor); }


    public void clearCache() {
        accountIdCache.invalidateAll();
        if (allUsernamesCache != null) allUsernamesCache.clear();
        lastUsernameCacheUpdateTime = 0;
        messageManager.logInfo("Player data caches cleared.");
    }
}