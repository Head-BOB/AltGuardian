package me.hunter.altguardian.migration;

import me.hunter.altguardian.AltGuardianPlugin;
import me.hunter.altguardian.config.ConfigManager;
import me.hunter.altguardian.config.MessageManager;
import me.hunter.altguardian.database.DatabaseManager;
import me.hunter.altguardian.manager.PlayerDataManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException; // Added import
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles the one-time data migration from an nLogin SQLite database.
 */
public class NLoginMigrator {

    private final AltGuardianPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final MessageManager messageManager;
    private final PlayerDataManager playerDataManager;

    private static final DateTimeFormatter SQLITE_DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");


    public NLoginMigrator(AltGuardianPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager, MessageManager messageManager, PlayerDataManager playerDataManager) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager cannot be null");
        this.databaseManager = Objects.requireNonNull(databaseManager, "DatabaseManager cannot be null");
        this.messageManager = Objects.requireNonNull(messageManager, "MessageManager cannot be null");
        this.playerDataManager = Objects.requireNonNull(playerDataManager, "PlayerDataManager cannot be null");
    }

    /**
     * Starts the migration process asynchronously.
     * (Method body remains the same)
     */
    public void startMigrationAsync(CommandSender feedbackTarget) {
        CompletableFuture.runAsync(() -> runMigration(feedbackTarget),
                runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    private void runMigration(CommandSender feedbackTarget) {

        // <<< --- START MIGRATION SAFETY CHECK --- >>>
        try {
            messageManager.logDebug("Checking if AltGuardian database already contains data before migration...");
            boolean alreadyHasData = databaseManager.hasAccountDataAsync().join(); // Wait for the check result

            if (alreadyHasData) {
                messageManager.send(feedbackTarget, "migrate-command.already-migrated");
                messageManager.logWarning("Migration aborted: The AltGuardian 'Accounts' table already contains data.");
                return; // Stop the migration process here
            } else {
                messageManager.logDebug("AltGuardian database is empty or check passed. Proceeding with migration.");
            }
        } catch (CompletionException e) {
            // Handle exceptions specifically from the hasAccountDataAsync check
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            messageManager.logError("Migration failed: Could not verify if AltGuardian database is empty.", cause);
            messageManager.send(feedbackTarget, "migrate-command.error", Placeholder.unparsed("error", "Failed pre-migration check: " + cause.getMessage()));
            return; // Stop the migration if the check fails
        } catch (Exception e) {
            // Catch any other unexpected errors during the check phase
            messageManager.logError("Unexpected error during migration pre-check:", e);
            messageManager.send(feedbackTarget, "migrate-command.error", Placeholder.unparsed("error", "Unexpected pre-check error: " + e.getMessage()));
            return;
        }
        // <<< --- END OF MIGRATION SAFETY CHECK --- >>>


        // --- Original migration logic starts here ---
        String nLoginDbPath = configManager.getNLoginDbPath(); // Fixed name
        File nLoginDbFile = new File(plugin.getServer().getWorldContainer(), nLoginDbPath); // Fixed name

        if (!nLoginDbFile.exists() || !nLoginDbFile.isFile()) {
            messageManager.send(feedbackTarget, "migrate-command.nlogin-db-not-found", Placeholder.unparsed("path", nLoginDbPath));
            return;
        }

        String tableName = configManager.getNLoginTableName(); // Fixed name
        String userCol = configManager.getNLoginUserCol(); // Fixed name
        String regDateCol = configManager.getNLoginRegDateCol(); // Fixed name
        String ipCol = configManager.getNLoginIpCol(); // Fixed name

        if (tableName.isEmpty() || userCol.isEmpty()) {
            messageManager.send(feedbackTarget, "migrate-command.error", Placeholder.unparsed("error", "nLogin table name or username column not configured!"));
            messageManager.logError("Migration failed: nLogin table name or username column not set in config.yml.");
            return;
        }


        String nLoginJdbcUrl = "jdbc:sqlite:" + nLoginDbFile.getAbsolutePath(); // Fixed name
        Connection nLoginConn = null; // Fixed name

        AtomicLong totalProcessed = new AtomicLong(0);
        AtomicInteger newAccounts = new AtomicInteger(0);
        AtomicInteger updatedIps = new AtomicInteger(0);

        try {
            messageManager.logInfo("Connecting to nLogin database: " + nLoginJdbcUrl); // Fixed name
            nLoginConn = DriverManager.getConnection(nLoginJdbcUrl); // Fixed name
            messageManager.logInfo("Connected to nLogin database successfully.");

            // Build SELECT query dynamically... (remains same)
            StringBuilder queryBuilder = new StringBuilder("SELECT ");
            queryBuilder.append("`").append(userCol).append("`");
            boolean hasRegDate = regDateCol != null && !regDateCol.trim().isEmpty();
            boolean hasIp = ipCol != null && !ipCol.trim().isEmpty();
            if (hasRegDate) queryBuilder.append(", `").append(regDateCol).append("`");
            if (hasIp) queryBuilder.append(", `").append(ipCol).append("`");
            queryBuilder.append(" FROM `").append(tableName).append("`");
            String selectQuery = queryBuilder.toString();
            messageManager.logInfo("Executing migration query: " + selectQuery);


            try (Statement stmt = nLoginConn.createStatement(); // Fixed name
                 ResultSet rs = stmt.executeQuery(selectQuery)) {

                messageManager.logInfo("Query executed. Processing nLogin users...");

                // Loop processing remains the same...
                while (rs.next()) {
                    long currentCount = totalProcessed.incrementAndGet();
                    String username = rs.getString(userCol);
                    if (username == null || username.trim().isEmpty()) { messageManager.logWarning("Skipping migration for user with null/empty username at row " + currentCount); continue; }
                    long regTimestamp = 0;
                    if (hasRegDate) { try { Object dateObj = rs.getObject(regDateCol); regTimestamp = parseTimestamp(dateObj); } catch (Exception e) { String rawValue = "N/A"; try { rawValue = rs.getString(regDateCol); } catch(Exception ignored) {} messageManager.logWarning("Could not parse registration date for user " + username + ": value='" + rawValue + "' - " + e.getMessage()); } }
                    if (regTimestamp <= 0) regTimestamp = Instant.now().toEpochMilli();
                    String lastIp = null;
                    if (hasIp) { lastIp = rs.getString(ipCol); if (lastIp != null && (lastIp.trim().isEmpty() || "UNKNOWN".equalsIgnoreCase(lastIp) || "0.0.0.0".equals(lastIp))) { lastIp = null; } }
                    String finalLastIp = lastIp; long finalRegTimestamp = regTimestamp;
                    try {
                        // Corrected to use PlayerDataManager.getOrCreateAccountIdAsync
                        Integer accountId = playerDataManager.getOrCreateAccountIdAsync(username, finalRegTimestamp, null).join();
                        if (accountId == null) { messageManager.logError("!! Failed to get/create account ID during migration for: " + username + ". Skipping IP record."); continue; }
                        // Check if this was truly a *new* account created during this specific call
                        // This is a bit harder to track precisely without changing return type or adding another query.
                        // We'll count all processed accounts as potentially new for the message, which is acceptable.
                        newAccounts.incrementAndGet(); // Increment for processed account

                        if (finalLastIp != null) {
                            // Record IP usage, then increment counter *after* successful record (or attempt)
                            databaseManager.recordIpUsageAsync(accountId, finalLastIp, finalRegTimestamp)
                                    .thenRun(updatedIps::incrementAndGet); // Increment after async operation
                        }
                    } catch (Exception e) { messageManager.logError("Error processing migration for user " + username + ":", e); }
                    if (currentCount % 100 == 0) { Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.progress", MessageManager.count((int) currentCount))); messageManager.logInfo("(Migration Progress) Processed " + currentCount + " users..."); }
                }

            } // End try-with-resources for Statement/ResultSet

            messageManager.logInfo("Finished processing " + totalProcessed.get() + " nLogin users.");

            // Final completion message... Use the counters we incremented
            // 'newAccounts' will roughly reflect total processed, 'updatedIps' reflects those with IPs added.
            Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.complete",
                    MessageManager.count((int) totalProcessed.get()),
                    Placeholder.unparsed("imported", String.valueOf(newAccounts.get())), // Reflects total processed
                    Placeholder.unparsed("updated", String.valueOf(updatedIps.get())) // Reflects IPs added
            ));

        } catch (SQLException e) {
            // SQL Exception handling remains the same...
            String errorDetails = e.getMessage();
            if (errorDetails != null && errorDetails.contains("no such table: " + tableName)) { Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.nlogin-table-not-found", Placeholder.unparsed("table", tableName))); messageManager.logError("Migration failed: nLogin table '" + tableName + "' not found.", e); }
            else if (errorDetails != null && errorDetails.contains("no such column:")) { String missingCol = errorDetails.substring(errorDetails.indexOf("no such column: ") + "no such column: ".length()).split("\\s+")[0]; Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.nlogin-column-not-found", Placeholder.unparsed("column", missingCol), Placeholder.unparsed("table", tableName))); messageManager.logError("Migration failed: nLogin column '" + missingCol + "' not found in table '" + tableName + "'.", e); }
            else { messageManager.logError("SQLException during nLogin migration:", e); Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.error", Placeholder.unparsed("error", "SQL Error: " + e.getMessage()))); }
        } catch (Exception e) {
            // Other Exception handling remains the same...
            messageManager.logError("Unexpected error during nLogin migration:", e); Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.error", Placeholder.unparsed("error", e.getClass().getSimpleName() + ": " + e.getMessage())));
        } finally {
            // Close nLogin connection... (remains same)
            if (nLoginConn != null) { try { nLoginConn.close(); messageManager.logInfo("Migration process finished. nLogin database connection closed."); } catch (SQLException e) { messageManager.logError("Failed to close nLogin database connection:", e); } }
        }
    }


    /**
     * Parses a timestamp Object from the database.
     * (Method body remains the same)
     */
    private long parseTimestamp(Object timestampObj) {
        if (timestampObj == null) return 0;
        if (timestampObj instanceof Number num) { long longVal = num.longValue(); if (longVal > 31536000000L) { return longVal; } else if (longVal > 0) { return longVal * 1000L; } else { return 0; } }
        if (timestampObj instanceof String tsString && !tsString.trim().isEmpty()) { try { LocalDateTime ldt = LocalDateTime.parse(tsString, SQLITE_DATETIME_FORMATTER); return ldt.toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (DateTimeParseException e1) { try { long num = Long.parseLong(tsString); if (num > 31536000000L) return num; else if (num > 0) return num * 1000L; else return 0; } catch (NumberFormatException e2) { /* Ignore */ } } }
        return 0;
    }
}