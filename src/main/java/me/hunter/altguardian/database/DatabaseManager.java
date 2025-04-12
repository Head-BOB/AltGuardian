package me.hunter.altguardian.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.hunter.altguardian.AltGuardianPlugin;
import me.hunter.altguardian.api.model.AccountDetails;
import me.hunter.altguardian.api.model.IpHistoryRecord;
import me.hunter.altguardian.config.ConfigManager;
import me.hunter.altguardian.config.MessageManager;
import me.hunter.altguardian.database.queries.Schema;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages the connection pool (HikariCP) and all interactions with the SQLite database.
 * All database operations are performed asynchronously off the main server thread.
 */
public class DatabaseManager {

    private final AltGuardianPlugin plugin;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private HikariDataSource dataSource;
    private final ExecutorService databaseExecutor;

    public DatabaseManager(AltGuardianPlugin plugin, ConfigManager configManager, MessageManager messageManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.databaseExecutor = Executors.newFixedThreadPool(
                Math.max(2, configManager.getDbMaxPoolSize() / 2),
                runnable -> {
                    Thread t = Executors.defaultThreadFactory().newThread(runnable);
                    t.setName("AltGuardian-DB-Pool-Thread");
                    t.setDaemon(true);
                    return t;
                }
        );
    }

    /**
     * Initializes the HikariCP connection pool and sets up the database schema.
     *
     * @return true if initialization was successful, false otherwise.
     */
    public boolean initializeDatabase() {
        messageManager.logInfo("Initializing database connection...");
        try {
            // Ensure the data folder exists
            if (!plugin.getDataFolder().exists()) {
                if (!plugin.getDataFolder().mkdirs()) {
                    messageManager.logSevere("Failed to create plugin data folder!");
                    return false;
                }
            }

            File dbFile = new File(plugin.getDataFolder(), "altguardian_data.db");

            // <<< Create HikariConfig using helper method >>>
            HikariConfig hikariConfig = createHikariConfig(dbFile.getAbsolutePath());

            dataSource = new HikariDataSource(hikariConfig);
            messageManager.logInfo("Database connection pool initialized.");

            // Setup the database schema asynchronously
            setupSchema().join(); // Wait for schema set up to complete during init

            messageManager.logInfo("Database schema verified/created.");
            return true;

        } catch (Exception e) {
            messageManager.logSevere("Failed to initialize database!", e);
            dataSource = null; // Ensure dataSource is null on failure
            return false;
        }
    }

    // <<< Extracted private helper method for creating HikariConfig >>>
    private HikariConfig createHikariConfig(String dbFilePath) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFilePath);
        config.setPoolName("AltGuardianDBPool");

        // SQLite specific settings
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        // HikariCP Pool settings from config.yml
        config.setMaximumPoolSize(configManager.getDbMaxPoolSize());
        config.setMinimumIdle(configManager.getDbMinIdle());
        config.setConnectionTimeout(configManager.getDbConnectionTimeout());
        config.setIdleTimeout(configManager.getDbIdleTimeout());
        config.setMaxLifetime(configManager.getDbMaxLifetime());

        // Driver class name
        config.setDriverClassName("org.sqlite.JDBC");

        return config;
    }


    /**
     * Closes the database connection pool and shuts down the executor.
     * (Method body remains the same)
     */
    public void close() {
        messageManager.logInfo("Closing database connection pool...");
        if (dataSource != null && !dataSource.isClosed()) { dataSource.close(); }
        messageManager.logInfo("Shutting down database executor service...");
        databaseExecutor.shutdown();
        try { if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) { messageManager.logWarning("Database executor did not terminate cleanly after 5 seconds. Forcing shutdown."); databaseExecutor.shutdownNow(); } }
        catch (InterruptedException e) { messageManager.logWarning("Interrupted while waiting for database executor shutdown."); databaseExecutor.shutdownNow(); Thread.currentThread().interrupt(); }
        messageManager.logInfo("Database resources closed.");
    }

    /**
     * Executes schema creation statements asynchronously.
     * (Method body remains the same)
     */
    private CompletableFuture<Void> setupSchema() {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                messageManager.logDebug("Executing database schema set up..."); // Corrected grammar
                stmt.execute(Schema.CREATE_ACCOUNTS_TABLE); messageManager.logDebug("Executed: CREATE_ACCOUNTS_TABLE");
                stmt.execute(Schema.CREATE_IP_HISTORY_TABLE); messageManager.logDebug("Executed: CREATE_IP_HISTORY_TABLE");
                stmt.execute(Schema.CREATE_FLAGS_TABLE); messageManager.logDebug("Executed: CREATE_FLAGS_TABLE");
                stmt.execute(Schema.CREATE_IDX_ACCOUNTS_USERNAME); messageManager.logDebug("Executed: CREATE_IDX_ACCOUNTS_USERNAME");
                stmt.execute(Schema.CREATE_IDX_IP_HISTORY_ACCOUNT_ID); messageManager.logDebug("Executed: CREATE_IDX_IP_HISTORY_ACCOUNT_ID");
                stmt.execute(Schema.CREATE_IDX_IP_HISTORY_IP_ADDRESS); messageManager.logDebug("Executed: CREATE_IDX_IP_HISTORY_IP_ADDRESS");
                stmt.execute(Schema.CREATE_IDX_IP_HISTORY_LAST_USED); messageManager.logDebug("Executed: CREATE_IDX_IP_HISTORY_LAST_USED");
                stmt.execute(Schema.CREATE_IDX_FLAGS_ACCOUNT_ID); messageManager.logDebug("Executed: CREATE_IDX_FLAGS_ACCOUNT_ID");
                messageManager.logDebug("Schema set up complete."); // Corrected grammar
            } catch (SQLException e) { messageManager.logError("!!!!!!!! Failed to set up database schema !!!!!!!!"); logSqlExceptionDetails(e, "setupSchema"); throw new CompletionException(e); }
        }, databaseExecutor);
    }

    // <<< --- START NEW METHOD --- >>>
    /**
     * Asynchronously checks if the Accounts table contains any data.
     * Used to prevent accidental re-runs of migrations.
     *
     * @return CompletableFuture<Boolean> - true if data exists, false otherwise.
     *         The future completes exceptionally if the check fails.
     */
    public CompletableFuture<Boolean> hasAccountDataAsync() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT 1 FROM Accounts LIMIT 1;"; // Efficiently check for any row
            messageManager.logDebug("Executing check for existing account data...");
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                boolean hasData = rs.next(); // Returns true if a row was found
                messageManager.logDebug("Check complete. Account data exists: " + hasData);
                return hasData;
            } catch (SQLException e) {
                logSqlExceptionDetails(e, "hasAccountDataAsync", sql);
                // Wrap the SQL exception so the caller knows the check failed
                throw new CompletionException("Failed database check for existing account data", e);
            } catch (Exception e) {
                // Catch other potential exceptions during check
                messageManager.logError("Unexpected error during hasAccountDataAsync check", e);
                throw new CompletionException("Unexpected error checking existing account data", e);
            }
        }, databaseExecutor);
    }
    // <<< --- END NEW METHOD --- >>>


    // --- Other database methods (findAccountIdAsync, findUsernameByIdAsync, etc.) remain the same ---
    public CompletableFuture<Integer> findAccountIdAsync(String username) { return CompletableFuture.supplyAsync(() -> { String sql = "SELECT AccountID FROM Accounts WHERE LOWER(Username) = ?;"; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) { pstmt.setString(1, username.toLowerCase()); try (ResultSet rs = pstmt.executeQuery()) { if (rs.next()) { Object idObject = rs.getObject("AccountID"); return (idObject != null) ? (Integer) idObject : null; } } } catch (SQLException e) { logSqlExceptionDetails(e, "findAccountIdAsync", sql); } return null; }, databaseExecutor); }
    public CompletableFuture<String> findUsernameByIdAsync(int accountId) { return CompletableFuture.supplyAsync(() -> { String sql = "SELECT Username FROM Accounts WHERE AccountID = ?;"; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) { pstmt.setInt(1, accountId); try (ResultSet rs = pstmt.executeQuery()) { if (rs.next()) { return rs.getString("Username"); } } } catch (SQLException e) { logSqlExceptionDetails(e, "findUsernameByIdAsync", sql); } return null; }, databaseExecutor); }
    public CompletableFuture<Integer> createAccountAsync(String username, long registrationTimeMillis, UUID offlineUuid) { return CompletableFuture.supplyAsync(() -> { String sql = "INSERT INTO Accounts (Username, RegistrationDate, LastLoginDate, OfflineUUID) VALUES (?, ?, ?, ?);"; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) { pstmt.setString(1, username); pstmt.setLong(2, registrationTimeMillis); pstmt.setLong(3, 0); pstmt.setString(4, offlineUuid != null ? offlineUuid.toString() : null); int affectedRows = pstmt.executeUpdate(); if (affectedRows > 0) { try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) { if (generatedKeys.next()) { Object idObject = generatedKeys.getObject(1); return (idObject != null) ? ((Number) idObject).intValue() : null; } } } } catch (SQLException e) { if (e.getErrorCode() == 19 && e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed: Accounts.Username")) { messageManager.logDebug("Attempted to create account for already existing username: " + username); return findAccountIdAsync(username).join(); } else { logSqlExceptionDetails(e, "createAccountAsync", sql); } } return null; }, databaseExecutor); }
    public CompletableFuture<Void> recordIpUsageAsync(int accountId, String ipAddress, long timestampMillis) { return CompletableFuture.runAsync(() -> { String sql = "INSERT INTO IP_History (AccountID, IPAddress, FirstUsed, LastUsed) VALUES (?, ?, ?, ?) ON CONFLICT(AccountID, IPAddress) DO UPDATE SET LastUsed = excluded.LastUsed WHERE excluded.LastUsed > IP_History.LastUsed;"; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) { pstmt.setInt(1, accountId); pstmt.setString(2, ipAddress); pstmt.setLong(3, timestampMillis); pstmt.setLong(4, timestampMillis); pstmt.executeUpdate(); } catch (SQLException e) { logSqlExceptionDetails(e, "recordIpUsageAsync", sql); } }, databaseExecutor); }
    public CompletableFuture<Void> updateLastLoginAsync(int accountId, long timestampMillis) { return CompletableFuture.runAsync(() -> { String sql = "UPDATE Accounts SET LastLoginDate = ? WHERE AccountID = ?;"; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) { pstmt.setLong(1, timestampMillis); pstmt.setInt(2, accountId); pstmt.executeUpdate(); } catch (SQLException e) { logSqlExceptionDetails(e, "updateLastLoginAsync", sql); } }, databaseExecutor); }
    public CompletableFuture<Optional<AccountDetails>> getAccountDetailsAsync(String username) { return CompletableFuture.supplyAsync(() -> { String sqlAccount = "SELECT AccountID, Username, RegistrationDate, LastLoginDate, OfflineUUID FROM Accounts WHERE LOWER(Username) = ?;"; String sqlFlags = "SELECT FlagReason FROM Flags WHERE AccountID = ?;"; AccountDetails details = null; try (Connection conn = getConnection(); PreparedStatement pstmtAccount = conn.prepareStatement(sqlAccount)) { pstmtAccount.setString(1, username.toLowerCase()); try (ResultSet rsAccount = pstmtAccount.executeQuery()) { if (rsAccount.next()) { int accountId = rsAccount.getInt("AccountID"); String storedUsername = rsAccount.getString("Username"); long regDate = rsAccount.getLong("RegistrationDate"); long lastLogin = rsAccount.getLong("LastLoginDate"); String uuidString = rsAccount.getString("OfflineUUID"); UUID offlineUuid = null; if (uuidString != null && !uuidString.isEmpty()){ try { offlineUuid = UUID.fromString(uuidString); } catch (IllegalArgumentException e) { messageManager.logWarning("Found invalid UUID string '" + uuidString + "' for user " + storedUsername); } } Set<String> flags = new HashSet<>(); try (PreparedStatement pstmtFlags = conn.prepareStatement(sqlFlags)) { pstmtFlags.setInt(1, accountId); try (ResultSet rsFlags = pstmtFlags.executeQuery()) { while (rsFlags.next()) { flags.add(rsFlags.getString("FlagReason")); } } } details = new AccountDetails(storedUsername, regDate, lastLogin, offlineUuid, flags); } } } catch (SQLException e) { logSqlExceptionDetails(e, "getAccountDetailsAsync"); } return Optional.ofNullable(details); }, databaseExecutor); }
    public CompletableFuture<List<IpHistoryRecord>> getIpHistoryAsync(int accountId, int limit) { return CompletableFuture.supplyAsync(() -> { List<IpHistoryRecord> history = new ArrayList<>(); String sql = "SELECT IPAddress, FirstUsed, LastUsed FROM IP_History WHERE AccountID = ? ORDER BY LastUsed DESC LIMIT ?;"; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) { pstmt.setInt(1, accountId); pstmt.setInt(2, limit); try (ResultSet rs = pstmt.executeQuery()) { while (rs.next()) { history.add(new IpHistoryRecord(rs.getString("IPAddress"), rs.getLong("FirstUsed"), rs.getLong("LastUsed"))); } } } catch (SQLException e) { logSqlExceptionDetails(e, "getIpHistoryAsync", sql); } return history; }, databaseExecutor); }
    public CompletableFuture<Set<Integer>> getAccountIdsByIpSinceAsync(String ipAddress, long sinceTimestampMillis) { return CompletableFuture.supplyAsync(() -> { Set<Integer> accountIds = new HashSet<>(); String sql = "SELECT DISTINCT AccountID FROM IP_History WHERE IPAddress = ? AND LastUsed >= ?;"; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) { pstmt.setString(1, ipAddress); pstmt.setLong(2, sinceTimestampMillis); try (ResultSet rs = pstmt.executeQuery()) { while (rs.next()) { accountIds.add(rs.getInt("AccountID")); } } } catch (SQLException e) { logSqlExceptionDetails(e, "getAccountIdsByIpSinceAsync", sql); } return accountIds; }, databaseExecutor); }
    public CompletableFuture<Integer> purgeOldIpHistoryAsync() { return CompletableFuture.supplyAsync(() -> { int retentionDays = configManager.getIpHistoryRetentionDays(); if (retentionDays <= 0) { return 0; } long purgeBeforeTimestamp = Instant.now().minus(retentionDays, java.time.temporal.ChronoUnit.DAYS).toEpochMilli(); String sql = "DELETE FROM IP_History WHERE LastUsed < ?;"; int deletedRows = 0; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) { pstmt.setLong(1, purgeBeforeTimestamp); deletedRows = pstmt.executeUpdate(); if (configManager.isDebugEnabled() && deletedRows > 0) { messageManager.logDebug("Purged " + deletedRows + " IP history records older than " + retentionDays + " days."); } } catch (SQLException e) { logSqlExceptionDetails(e, "purgeOldIpHistoryAsync", sql); } return deletedRows; }, databaseExecutor); }
    public CompletableFuture<Set<String>> getAllUsernamesLowerAsync() { return CompletableFuture.supplyAsync(() -> { Set<String> usernames = new HashSet<>(); String sql = "SELECT LOWER(Username) AS lcUsername FROM Accounts;"; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql); ResultSet rs = pstmt.executeQuery()) { while (rs.next()) { usernames.add(rs.getString("lcUsername")); } } catch (SQLException e) { logSqlExceptionDetails(e, "getAllUsernamesLowerAsync", sql); } return usernames; }, databaseExecutor); }
    public CompletableFuture<Boolean> addFlagAsync(int accountId, String flagReason, long timestampMillis) { return CompletableFuture.supplyAsync(() -> { String sql = "INSERT OR IGNORE INTO Flags (AccountID, FlagReason, FlaggedAt) VALUES (?, ?, ?);"; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) { pstmt.setInt(1, accountId); pstmt.setString(2, flagReason); pstmt.setLong(3, timestampMillis); int affectedRows = pstmt.executeUpdate(); return affectedRows > 0; } catch (SQLException e) { logSqlExceptionDetails(e, "addFlagAsync", sql); return false; } }, databaseExecutor); }
    public CompletableFuture<Boolean> removeFlagAsync(int accountId, String flagReason) { return CompletableFuture.supplyAsync(() -> { String sql = "DELETE FROM Flags WHERE AccountID = ? AND FlagReason = ?;"; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) { pstmt.setInt(1, accountId); pstmt.setString(2, flagReason); int affectedRows = pstmt.executeUpdate(); return affectedRows > 0; } catch (SQLException e) { logSqlExceptionDetails(e, "removeFlagAsync", sql); return false; } }, databaseExecutor); }
    public CompletableFuture<Set<String>> getFlagsAsync(int accountId) { return CompletableFuture.supplyAsync(() -> { Set<String> flags = new HashSet<>(); String sql = "SELECT FlagReason FROM Flags WHERE AccountID = ?;"; try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) { pstmt.setInt(1, accountId); try (ResultSet rs = pstmt.executeQuery()) { while (rs.next()) { flags.add(rs.getString("FlagReason")); } } } catch (SQLException e) { logSqlExceptionDetails(e, "getFlagsAsync", sql); } return flags; }, databaseExecutor); }
    protected Connection getConnection() throws SQLException { if (dataSource == null || dataSource.isClosed()) { throw new SQLException("Database connection pool is not available or closed."); } return dataSource.getConnection(); }
    private void logSqlExceptionDetails(SQLException e, String operation, String... sql) { String errorMsg = String.format("SQLException during operation '%s'! Error Code: %d, SQLState: %s", operation, e.getErrorCode(), e.getSQLState()); if (sql.length > 0 && sql[0] != null) { errorMsg += "\nSQL (base): " + sql[0]; } messageManager.logError(errorMsg, e); SQLException nextEx = e.getNextException(); while (nextEx != null) { messageManager.logError("Next SQLException chained:", nextEx); nextEx = nextEx.getNextException(); } }
    public void notifyConfigReloaded() { messageManager.logDebug("DatabaseManager notified of config reload. Note: Pool settings changes require a server restart."); }
    public ExecutorService getExecutor() { return databaseExecutor; }

} // End of DatabaseManager class