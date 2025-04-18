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
import java.util.concurrent.CompletionException;
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
     */
    public void startMigrationAsync(CommandSender feedbackTarget) {
        CompletableFuture.runAsync(() -> runMigration(feedbackTarget),
                runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    private void runMigration(CommandSender feedbackTarget) {

        // Migration Safety Check
        try {
            messageManager.logDebug("Checking if AltGuardian database already contains data before migration...");
            boolean alreadyHasData = databaseManager.hasAccountDataAsync().join();

            if (alreadyHasData) {
                // No placeholders needed
                messageManager.send(feedbackTarget, "migrate-command.already-migrated");
                messageManager.logWarning("Migration aborted: The AltGuardian 'Accounts' table already contains data.");
                return;
            } else {
                messageManager.logDebug("AltGuardian database is empty or check passed. Proceeding with migration.");
            }
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            messageManager.logError("Migration failed: Could not verify if AltGuardian database is empty.", cause);
            // Pass error placeholder
            messageManager.send(feedbackTarget, "migrate-command.error", Placeholder.unparsed("error", "Failed pre-migration check: " + cause.getMessage()));
            return;
        } catch (Exception e) {
            messageManager.logError("Unexpected error during migration pre-check:", e);
            // Pass error placeholder
            messageManager.send(feedbackTarget, "migrate-command.error", Placeholder.unparsed("error", "Unexpected pre-check error: " + e.getMessage()));
            return;
        }

        // Original migration logic
        String nLoginDbPath = configManager.getNLoginDbPath();
        File nLoginDbFile = new File(plugin.getServer().getWorldContainer(), nLoginDbPath);

        if (!nLoginDbFile.exists() || !nLoginDbFile.isFile()) {
            // Pass path placeholder
            messageManager.send(feedbackTarget, "migrate-command.nlogin-db-not-found", Placeholder.unparsed("path", nLoginDbPath));
            return;
        }

        String tableName = configManager.getNLoginTableName();
        String userCol = configManager.getNLoginUserCol();
        String regDateCol = configManager.getNLoginRegDateCol();
        String ipCol = configManager.getNLoginIpCol();

        if (tableName.isEmpty() || userCol.isEmpty()) {
            // Pass error placeholder
            messageManager.send(feedbackTarget, "migrate-command.error", Placeholder.unparsed("error", "nLogin table name or username column not configured!"));
            messageManager.logError("Migration failed: nLogin table name or username column not set in config.yml.");
            return;
        }


        String nLoginJdbcUrl = "jdbc:sqlite:" + nLoginDbFile.getAbsolutePath();
        Connection nLoginConn = null;

        AtomicLong totalProcessed = new AtomicLong(0);
        AtomicInteger newAccounts = new AtomicInteger(0); // Count accounts actually processed/created
        AtomicInteger ipsAdded = new AtomicInteger(0); // Count IPs added/updated

        try {
            messageManager.logInfo("Connecting to nLogin database: " + nLoginJdbcUrl);
            nLoginConn = DriverManager.getConnection(nLoginJdbcUrl);
            messageManager.logInfo("Connected to nLogin database successfully.");

            StringBuilder queryBuilder = new StringBuilder("SELECT ");
            queryBuilder.append("`").append(userCol).append("`");
            boolean hasRegDate = regDateCol != null && !regDateCol.trim().isEmpty();
            boolean hasIp = ipCol != null && !ipCol.trim().isEmpty();
            if (hasRegDate) queryBuilder.append(", `").append(regDateCol).append("`");
            if (hasIp) queryBuilder.append(", `").append(ipCol).append("`");
            queryBuilder.append(" FROM `").append(tableName).append("`");
            String selectQuery = queryBuilder.toString();
            messageManager.logInfo("Executing migration query: " + selectQuery);


            try (Statement stmt = nLoginConn.createStatement();
                 ResultSet rs = stmt.executeQuery(selectQuery)) {

                messageManager.logInfo("Query executed. Processing nLogin users...");

                while (rs.next()) {
                    long currentCount = totalProcessed.incrementAndGet();
                    String username = rs.getString(userCol);
                    if (username == null || username.trim().isEmpty()) { messageManager.logWarning("Skipping migration for user with null/empty username at row " + currentCount); continue; }

                    long regTimestamp = 0;
                    if (hasRegDate) { try { Object dateObj = rs.getObject(regDateCol); regTimestamp = parseTimestamp(dateObj); } catch (Exception e) { String rawValue = "N/A"; try { rawValue = rs.getString(regDateCol); } catch(Exception ignored) {} messageManager.logWarning("Could not parse registration date for user " + username + ": value='" + rawValue + "' - " + e.getMessage()); } }
                    if (regTimestamp <= 0) regTimestamp = Instant.now().toEpochMilli(); // Default to now if parsing fails

                    String lastIp = null;
                    if (hasIp) { lastIp = rs.getString(ipCol); if (lastIp != null && (lastIp.trim().isEmpty() || "UNKNOWN".equalsIgnoreCase(lastIp) || "0.0.0.0".equals(lastIp))) { lastIp = null; } }

                    String finalLastIp = lastIp;
                    long finalRegTimestamp = regTimestamp;

                    try {
                        // getOrCreate is async, but we block here as migration is a background task anyway
                        Integer accountId = playerDataManager.getOrCreateAccountIdAsync(username, finalRegTimestamp, null).join();
                        if (accountId == null) {
                            messageManager.logError("!! Failed to get/create account ID during migration for: " + username + ". Skipping IP record.");
                            continue; // Skip if we can't get an ID
                        }
                        newAccounts.incrementAndGet(); // Count successful processing

                        if (finalLastIp != null) {
                            // Record IP usage - fire and forget, but count attempt
                            databaseManager.recordIpUsageAsync(accountId, finalLastIp, finalRegTimestamp);
                            ipsAdded.incrementAndGet(); // Count IP addition attempts
                        }
                    } catch (Exception e) {
                        messageManager.logError("Error processing migration for user " + username + ":", e);
                    }

                    if (currentCount % 100 == 0) {
                        // Pass count placeholder
                        Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.progress", MessageManager.count((int) currentCount)));
                        messageManager.logInfo("(Migration Progress) Processed " + currentCount + " users...");
                    }
                }

            } // End try-with-resources for Statement/ResultSet

            messageManager.logInfo("Finished processing " + totalProcessed.get() + " nLogin users.");

            // Pass count, imported, updated placeholders
            Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.complete",
                    MessageManager.count((int) totalProcessed.get()),
                    Placeholder.unparsed("imported", String.valueOf(newAccounts.get())),
                    Placeholder.unparsed("updated", String.valueOf(ipsAdded.get()))
            ));

        } catch (SQLException e) {
            String errorDetails = e.getMessage();
            if (errorDetails != null && errorDetails.contains("no such table: " + tableName)) {
                // Pass table placeholder
                Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.nlogin-table-not-found", Placeholder.unparsed("table", tableName)));
                messageManager.logError("Migration failed: nLogin table '" + tableName + "' not found.", e);
            } else if (errorDetails != null && errorDetails.contains("no such column:")) {
                String missingCol = errorDetails.substring(errorDetails.indexOf("no such column: ") + "no such column: ".length()).split("\\s+")[0];
                // Pass column, table placeholders
                Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.nlogin-column-not-found", Placeholder.unparsed("column", missingCol), Placeholder.unparsed("table", tableName)));
                messageManager.logError("Migration failed: nLogin column '" + missingCol + "' not found in table '" + tableName + "'.", e);
            } else {
                messageManager.logError("SQLException during nLogin migration:", e);
                // Pass error placeholder
                Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.error", Placeholder.unparsed("error", "SQL Error: " + e.getMessage())));
            }
        } catch (Exception e) {
            messageManager.logError("Unexpected error during nLogin migration:", e);
            // Pass error placeholder
            Bukkit.getScheduler().runTask(plugin, () -> messageManager.send(feedbackTarget, "migrate-command.error", Placeholder.unparsed("error", e.getClass().getSimpleName() + ": " + e.getMessage())));
        } finally {
            if (nLoginConn != null) { try { nLoginConn.close(); messageManager.logInfo("Migration process finished. nLogin database connection closed."); } catch (SQLException e) { messageManager.logError("Failed to close nLogin database connection:", e); } }
        }
    }


    /**
     * Parses a timestamp Object from the database.
     */
    private long parseTimestamp(Object timestampObj) {
        // ... (implementation remains the same) ...
        if (timestampObj == null) return 0;
        if (timestampObj instanceof Number num) { long longVal = num.longValue(); if (longVal > 31536000000L) { return longVal; } else if (longVal > 0) { return longVal * 1000L; } else { return 0; } }
        if (timestampObj instanceof String tsString && !tsString.trim().isEmpty()) { try { LocalDateTime ldt = LocalDateTime.parse(tsString, SQLITE_DATETIME_FORMATTER); return ldt.toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (DateTimeParseException e1) { try { long num = Long.parseLong(tsString); if (num > 31536000000L) return num; else if (num > 0) return num * 1000L; else return 0; } catch (NumberFormatException e2) { /* Ignore */ } } }
        return 0;
    }
}