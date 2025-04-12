package me.hunter.altguardian.config;

import me.hunter.altguardian.AltGuardianPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Manages loading, accessing, and reloading the plugin's config.yml file.
 */
public class ConfigManager {

    private final AltGuardianPlugin plugin;
    private FileConfiguration config;
    private final File configFile;

    // Cached values for performance
    private boolean debugMode;
    // Alt Detection
    private boolean altDetectionEnabled;
    private int maxAccountsPerIp;
    private long checkTimeframeSeconds;
    private String altLimitExceededAction;
    private Set<String> exemptIps;
    private Set<String> exemptAltUsernames;
    // Impersonation
    private boolean impersonationEnabled;
    private String impersonationAlgorithm;
    private int levenshteinThreshold;
    private String impersonationActionOnRegister;
    private boolean impersonationIgnoreCase;
    private Set<String> exemptImpersonationUsernames;
    // Database
    private int ipHistoryRetentionDays;
    private long purgeIntervalMinutes;
    private long purgeInitialDelayMinutes;
    private int dbMaxPoolSize;
    private int dbMinIdle;
    private long dbConnectionTimeout;
    private long dbIdleTimeout;
    private long dbMaxLifetime;
    // Migration
    private String nLoginDbPath;
    private String nLoginTableName;
    private String nLoginUserCol;
    private String nLoginRegDateCol;
    private String nLoginIpCol;
    // API
    private String displayTimezone;

    public ConfigManager(AltGuardianPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
    }

    // loadConfig method... (remains same)
    public void loadConfig() {
        if (!configFile.exists()) { plugin.getLogger().info("config.yml not found, creating default configuration..."); plugin.saveDefaultConfig(); plugin.getLogger().info("Default config.yml created. Please configure it!"); }
        else { plugin.getLogger().info("Loading configuration from config.yml..."); }
        config = YamlConfiguration.loadConfiguration(configFile);
        InputStream defaultConfigStream = plugin.getResource("config.yml");
        if (defaultConfigStream != null) { YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8)); config.setDefaults(defaultConfig); config.options().copyDefaults(true); }
        else { plugin.getLogger().warning("Could not find default config.yml in JAR!"); }
        loadValues();
        plugin.getLogger().info("Configuration loaded.");
    }

    // reloadConfig method... (remains same)
    public boolean reloadConfig() {
        plugin.getLogger().info("Reloading configuration from config.yml...");
        try {
            config = YamlConfiguration.loadConfiguration(configFile);
            InputStream defaultConfigStream = plugin.getResource("config.yml");
            if (defaultConfigStream != null) { YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8)); config.setDefaults(defaultConfig); }
            loadValues();
            plugin.getLogger().info("Configuration reloaded successfully.");
            return true;
        } catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Could not reload config.yml!", e); return false; }
    }

    // saveConfig method... (remains same)
    public void saveConfig() { if (config == null || configFile == null) { plugin.getLogger().severe("Cannot save config - configuration is not loaded!"); return; } try { config.save(configFile); plugin.getLogger().fine("Configuration saved to config.yml."); } catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Could not save config.yml!", e); } }

    // Internal loadValues method... (remains same)
    private void loadValues() {
        if (config == null) { plugin.getLogger().severe("Cannot load config values - FileConfiguration is null!"); exemptIps = Collections.emptySet(); exemptAltUsernames = Collections.emptySet(); exemptImpersonationUsernames = Collections.emptySet(); return; }
        debugMode = config.getBoolean("debug-mode", false);
        altDetectionEnabled = config.getBoolean("alt-detection.enabled", true); maxAccountsPerIp = config.getInt("alt-detection.max-accounts-per-ip", 3); checkTimeframeSeconds = config.getLong("alt-detection.check-timeframe-seconds", 86400); altLimitExceededAction = config.getString("alt-detection.limit-exceeded-action", "FLAG").toUpperCase(); exemptIps = loadLowercaseStringSet("alt-detection.exempt-ips"); exemptAltUsernames = loadLowercaseStringSet("alt-detection.exempt-usernames");
        impersonationEnabled = config.getBoolean("impersonation.enabled", true); impersonationAlgorithm = config.getString("impersonation.algorithm", "LEVENSHTEIN").toUpperCase(); levenshteinThreshold = config.getInt("impersonation.levenshtein-threshold", 2); impersonationActionOnRegister = config.getString("impersonation.detection-action-on-register", "FLAG").toUpperCase(); impersonationIgnoreCase = config.getBoolean("impersonation.ignore-case", true); exemptImpersonationUsernames = loadLowercaseStringSet("impersonation.exempt-usernames");
        ipHistoryRetentionDays = config.getInt("database.ip-history-retention-days", 90); purgeIntervalMinutes = config.getLong("database.purge.interval-minutes", 1440); purgeInitialDelayMinutes = config.getLong("database.purge.initial-delay-minutes", 10); dbMaxPoolSize = config.getInt("database.pool-settings.maximum-pool-size", 10); dbMinIdle = config.getInt("database.pool-settings.minimum-idle", 2); dbConnectionTimeout = config.getLong("database.pool-settings.connection-timeout", 30000); dbIdleTimeout = config.getLong("database.pool-settings.idle-timeout", 600000); dbMaxLifetime = config.getLong("database.pool-settings.max-lifetime", 1800000);
        nLoginDbPath = config.getString("migration.nlogin-database-path", "plugins/nLogin/database.db"); nLoginTableName = config.getString("migration.nlogin-table-name", "users"); nLoginUserCol = config.getString("migration.nlogin-column-names.username", "username"); nLoginRegDateCol = config.getString("migration.nlogin-column-names.registration_date", "regdate"); nLoginIpCol = config.getString("migration.nlogin-column-names.last_ip", "ip");
        displayTimezone = config.getString("api.display-timezone", "UTC");
        plugin.getLogger().fine("Cached configuration values loaded/reloaded.");
    }

    // loadLowercaseStringSet helper... (remains same)
    private Set<String> loadLowercaseStringSet(String path) { List<String> list = config.getStringList(path); if (list.isEmpty()) { return Collections.emptySet(); } return list.stream().filter(s -> s != null && !s.trim().isEmpty()).map(String::toLowerCase).collect(Collectors.toCollection(HashSet::new)); }

    // --- Getters for cached configuration values ---
    public boolean isDebugEnabled() { return debugMode; }
    public boolean isAltDetectionEnabled() { return altDetectionEnabled; }
    public int getMaxAccountsPerIp() { return maxAccountsPerIp; }
    public long getCheckTimeframeSeconds() { return checkTimeframeSeconds; }
    public String getAltLimitExceededAction() { return altLimitExceededAction; }
    public boolean isIpExempt(String ip) { return exemptIps != null && exemptIps.contains(ip.toLowerCase()); }
    public boolean isAltUsernameExempt(String username) { return exemptAltUsernames != null && exemptAltUsernames.contains(username.toLowerCase()); }
    public boolean isImpersonationEnabled() { return impersonationEnabled; }
    public String getImpersonationAlgorithm() { return impersonationAlgorithm; }
    public int getLevenshteinThreshold() { return levenshteinThreshold; }
    public String getImpersonationActionOnRegister() { return impersonationActionOnRegister; }
    public boolean isImpersonationIgnoreCase() { return impersonationIgnoreCase; }
    public boolean isImpersonationUsernameExempt(String username) { return exemptImpersonationUsernames != null && exemptImpersonationUsernames.contains(username.toLowerCase()); }
    public int getIpHistoryRetentionDays() { return ipHistoryRetentionDays; }
    public long getPurgeIntervalMinutes() { return purgeIntervalMinutes; }
    public long getPurgeInitialDelayMinutes() { return purgeInitialDelayMinutes; }
    public int getDbMaxPoolSize() { return dbMaxPoolSize; }
    public int getDbMinIdle() { return dbMinIdle; }
    public long getDbConnectionTimeout() { return dbConnectionTimeout; }
    public long getDbIdleTimeout() { return dbIdleTimeout; }
    public long getDbMaxLifetime() { return dbMaxLifetime; }

    // Migration Getters - Suppress unused warnings as they ARE used by NLoginMigrator
    @SuppressWarnings("unused") public String getNLoginDbPath() { return nLoginDbPath != null ? nLoginDbPath : ""; }
    @SuppressWarnings("unused") public String getNLoginTableName() { return nLoginTableName != null ? nLoginTableName : ""; }
    @SuppressWarnings("unused") public String getNLoginUserCol() { return nLoginUserCol != null ? nLoginUserCol : ""; }
    @SuppressWarnings("unused") public String getNLoginRegDateCol() { return nLoginRegDateCol != null ? nLoginRegDateCol : ""; }
    @SuppressWarnings("unused") public String getNLoginIpCol() { return nLoginIpCol != null ? nLoginIpCol : ""; }

    // API Getters
    public String getDisplayTimezone() { return displayTimezone != null ? displayTimezone : "UTC"; }

    // getRawConfig method... (remains same)
    public FileConfiguration getRawConfig() { if (config == null) { plugin.getLogger().warning("Attempted to get raw config before it was loaded! Loading now..."); loadConfig(); if (config == null) { plugin.getLogger().severe("Failed to load config even on demand!"); return new YamlConfiguration(); } } return config; }
    // Exemption Setters... (remain same)
    public void addExemptIp(String ip) { if (this.exemptIps == null || this.exemptIps == Collections.EMPTY_SET) { this.exemptIps = new HashSet<>(); } if (this.exemptIps.add(ip.toLowerCase())) { updateConfigStringList("alt-detection.exempt-ips", this.exemptIps); plugin.getLogger().info("Added IP '" + ip + "' to exemptions."); } }
    public void removeExemptIp(String ip) { if (this.exemptIps == null) return; if (this.exemptIps.remove(ip.toLowerCase())) { updateConfigStringList("alt-detection.exempt-ips", this.exemptIps); plugin.getLogger().info("Removed IP '" + ip + "' from exemptions."); } }
    public void addExemptUsername(String username) { String lowerUser = username.toLowerCase(); if (this.exemptAltUsernames == null || this.exemptAltUsernames == Collections.EMPTY_SET) { this.exemptAltUsernames = new HashSet<>(); } if (this.exemptImpersonationUsernames == null || this.exemptImpersonationUsernames == Collections.EMPTY_SET) { this.exemptImpersonationUsernames = new HashSet<>(); } boolean changed = this.exemptAltUsernames.add(lowerUser); changed |= this.exemptImpersonationUsernames.add(lowerUser); if (changed) { updateConfigStringList("alt-detection.exempt-usernames", this.exemptAltUsernames); updateConfigStringList("impersonation.exempt-usernames", this.exemptImpersonationUsernames); plugin.getLogger().info("Added username '" + username + "' to exemptions."); } }
    public void removeExemptUsername(String username) { String lowerUser = username.toLowerCase(); boolean changed = false; if (this.exemptAltUsernames != null) { changed = this.exemptAltUsernames.remove(lowerUser); } if (this.exemptImpersonationUsernames != null) { changed |= this.exemptImpersonationUsernames.remove(lowerUser); } if (changed) { updateConfigStringList("alt-detection.exempt-usernames", this.exemptAltUsernames); updateConfigStringList("impersonation.exempt-usernames", this.exemptImpersonationUsernames); plugin.getLogger().info("Removed username '" + username + "' from exemptions."); } }
    // updateConfigStringList helper... (remains same)
    private void updateConfigStringList(String path, Set<String> values) { if (config == null) { plugin.getLogger().severe("Cannot update config list '" + path + "' - config is not loaded!"); return; } config.set(path, new ArrayList<>(values)); saveConfig(); }
    // Exemption Getters (unused warning suppressed)
    @SuppressWarnings("unused") public Set<String> getExemptIps() { return Collections.unmodifiableSet(exemptIps != null ? exemptIps : Collections.emptySet()); }
    @SuppressWarnings("unused") public Set<String> getCombinedExemptUsernames() { Set<String> combined = new HashSet<>(exemptAltUsernames != null ? exemptAltUsernames : Collections.emptySet()); if (exemptImpersonationUsernames != null) { combined.addAll(exemptImpersonationUsernames); } return Collections.unmodifiableSet(combined); }
}