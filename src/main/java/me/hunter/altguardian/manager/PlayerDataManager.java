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
    private final ExecutorService databaseExecutor;
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
        refreshAllUsernamesCacheAsync();
    }

    public CompletableFuture<Integer> getOrCreateAccountIdAsync(@NotNull String username, long registrationTimeMillis, @Nullable UUID offlineUuid) {
        String lowerUsername = username.toLowerCase();
        Optional<Integer> cachedIdOptional = accountIdCache.getIfPresent(lowerUsername);
        if (cachedIdOptional != null) {
            return cachedIdOptional.map(CompletableFuture::completedFuture)
                    .orElseGet(() -> fetchAndCacheAccountId(username, lowerUsername, registrationTimeMillis, offlineUuid));
        }
        return fetchAndCacheAccountId(username, lowerUsername, registrationTimeMillis, offlineUuid);
    }

    private CompletableFuture<Integer> fetchAndCacheAccountId(String username, String lowerUsername, long registrationTimeMillis, @Nullable UUID offlineUuid) {
        messageManager.logDebug("Account ID cache miss/negative-hit for: " + lowerUsername);
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


    public CompletableFuture<Void> recordSuccessfulLoginAsync(String username, String ipAddress, long loginTimeMillis) {
        messageManager.logDebug("Recording successful login for " + username + " from IP " + ipAddress);
        return getOrCreateAccountIdAsync(username, loginTimeMillis, null).thenComposeAsync(accountId -> {
            if (accountId == null) { // Should not happen if getOrCreate handles errors, but check anyway
                messageManager.logError("Cannot record login: Account ID not found or creation failed earlier for player " + username + ".");
                return CompletableFuture.completedFuture(null); // Or completed exceptionally
            }
            CompletableFuture<Void> updateLoginTime = databaseManager.updateLastLoginAsync(accountId, loginTimeMillis);
            CompletableFuture<Void> recordIp = databaseManager.recordIpUsageAsync(accountId, ipAddress, loginTimeMillis);
            return CompletableFuture.allOf(updateLoginTime, recordIp);
        }, databaseExecutor);
    }


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


    public CompletableFuture<Collection<String>> getAllUsernamesLowerAsync() { // <-- Return Collection
        long now = System.currentTimeMillis();
        if (allUsernamesCache != null && !allUsernamesCache.isEmpty() && (now - lastUsernameCacheUpdateTime < USERNAME_CACHE_DURATION_MS)) {
            messageManager.logDebug("Using cached username list (" + allUsernamesCache.size() + " entries).");
            return CompletableFuture.completedFuture(Collections.unmodifiableCollection(allUsernamesCache));
        }
        return refreshAllUsernamesCacheAsync();
    }


    private CompletableFuture<Collection<String>> refreshAllUsernamesCacheAsync() { // <-- Return Collection
        messageManager.logDebug("Refreshing all-usernames cache from database...");
        return databaseManager.getAllUsernamesLowerAsync().thenApplyAsync(usernames -> {
            messageManager.logDebug("Username cache refreshed. Found " + usernames.size() + " usernames.");
            Set<String> newCache = ConcurrentHashMap.newKeySet(usernames.size());
            newCache.addAll(usernames);
            this.allUsernamesCache = newCache;
            this.lastUsernameCacheUpdateTime = System.currentTimeMillis();
            return Collections.unmodifiableCollection(this.allUsernamesCache);
        }, databaseExecutor);
    }


    public CompletableFuture<Optional<AccountDetails>> getAccountDetailsAsync(String username) { return databaseManager.getAccountDetailsAsync(username); }
    public CompletableFuture<List<IpHistoryRecord>> getIpHistoryAsync(String username, int limit) { return getAccountIdAsync(username).thenComposeAsync(accIdOpt -> accIdOpt.map(id -> databaseManager.getIpHistoryAsync(id, limit)).orElse(CompletableFuture.completedFuture(Collections.emptyList())), databaseExecutor); }
    public CompletableFuture<Set<Integer>> getRecentAccountIdsByIp(String ipAddress, long sinceMillis) { return databaseManager.getAccountIdsByIpSinceAsync(ipAddress, sinceMillis); }
    public CompletableFuture<List<String>> getUsernamesByIdsAsync(Collection<Integer> accountIds) { if (accountIds == null || accountIds.isEmpty()) { return CompletableFuture.completedFuture(Collections.emptyList()); } List<CompletableFuture<String>> futures = accountIds.stream().map(databaseManager::findUsernameByIdAsync).toList(); return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApplyAsync(v -> futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList(), databaseExecutor); }


    public void clearCache() { // <-- Added Method
        accountIdCache.invalidateAll();
        if (allUsernamesCache != null) allUsernamesCache.clear();
        lastUsernameCacheUpdateTime = 0;
        messageManager.logInfo("Player data caches cleared.");
    }
}