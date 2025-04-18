package me.hunter.altguardian;

import co.aikar.commands.BukkitCommandCompletionContext;
import co.aikar.commands.PaperCommandManager;
import me.hunter.altguardian.api.AltGuardianAPI;
import me.hunter.altguardian.command.AltGuardianCommands;
import me.hunter.altguardian.config.ConfigManager;
import me.hunter.altguardian.config.MessageManager;
import me.hunter.altguardian.database.DatabaseManager;
import me.hunter.altguardian.listener.PlayerConnectionListener;
import me.hunter.altguardian.manager.AltDetectionManager;
import me.hunter.altguardian.manager.FlagManager;
import me.hunter.altguardian.manager.ImpersonationManager;
import me.hunter.altguardian.manager.PlayerDataManager;
import me.hunter.altguardian.migration.NLoginMigrator;
import me.hunter.altguardian.service.AltGuardianAPIImpl;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

public final class AltGuardianPlugin extends JavaPlugin {

    // Fields
    private static AltGuardianPlugin instance;

    // Managers
    private ConfigManager configManager;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private PlayerDataManager playerDataManager;
    private AltDetectionManager altDetectionManager;
    private ImpersonationManager impersonationManager;
    private FlagManager flagManager;
    private NLoginMigrator nLoginMigrator;

    // API
    private AltGuardianAPI apiImplementation;

    // Adventure
    private BukkitAudiences adventure;

    // Commands (Using ACF)
    private PaperCommandManager commandManager;

    // Scheduled Tasks
    private BukkitTask ipPurgeTask;

    @Override
    public void onLoad() {
        instance = this;
        configManager = new ConfigManager(this);
        configManager.loadConfig();
    }

    @Override
    public void onEnable() {
        // 1. Initialize Adventure
        this.adventure = BukkitAudiences.create(this);
        if (this.adventure == null) {
            getLogger().severe("Failed to initialize Adventure!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Initialize Managers
        messageManager = new MessageManager(this, configManager);
        databaseManager = new DatabaseManager(this, configManager, messageManager);
        if (!databaseManager.initializeDatabase()) {
            getServer().getPluginManager().disablePlugin(this);
            if (this.adventure != null) {
                this.adventure.close();
                this.adventure = null;
            }
            return;
        }

        // 3. Continue Manager Initialization
        playerDataManager = new PlayerDataManager(databaseManager, messageManager);
        flagManager = new FlagManager(playerDataManager, databaseManager, messageManager);
        altDetectionManager = new AltDetectionManager(this, playerDataManager, configManager, messageManager, flagManager);
        impersonationManager = new ImpersonationManager(this, playerDataManager, configManager, flagManager, messageManager);
        nLoginMigrator = new NLoginMigrator(this, configManager, databaseManager, messageManager, playerDataManager);

        // 4. Initialize and Register API
        apiImplementation = new AltGuardianAPIImpl(playerDataManager, flagManager, configManager, databaseManager);
        getServer().getServicesManager().register(AltGuardianAPI.class, apiImplementation, this, ServicePriority.Normal);
        getLogger().info("AltGuardian API registered.");

        // 5. Register Event Listeners
        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(this, playerDataManager, altDetectionManager, impersonationManager, flagManager, configManager, messageManager),
                this
        );

        // 6. Register Commands
        setupCommands(); // Call the corrected method

        // 7. Schedule background tasks
        scheduleIpPurgeTask();

        // Use PluginMeta (Ignore unstable API warnings)
        //noinspection UnstableApiUsage
        messageManager.logInfo("AltGuardian v" + getPluginMeta().getVersion() + " enabled successfully!");
        if (configManager.isDebugEnabled()) {
            messageManager.logWarning("Debug mode is enabled.");
        }
    }

    @Override
    public void onDisable() {
        // Standard disable logic
        getLogger().info("Disabling AltGuardian...");
        if (ipPurgeTask != null && !ipPurgeTask.isCancelled()) {
            ipPurgeTask.cancel();
        }
        if (commandManager != null) {
            try {
                commandManager.unregisterCommands();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error unregistering commands", e);
            }
        }
        try {
            if (apiImplementation != null) {
                getServer().getServicesManager().unregister(AltGuardianAPI.class, apiImplementation);
                getLogger().info("AltGuardian API unregistered.");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Exception while unregistering API (may be normal if enable failed)", e);
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        if (this.adventure != null) {
            this.adventure.close();
            this.adventure = null;
        }
        configManager = null;
        messageManager = null;
        databaseManager = null;
        playerDataManager = null;
        altDetectionManager = null;
        impersonationManager = null;
        flagManager = null;
        nLoginMigrator = null;
        apiImplementation = null;
        commandManager = null;
        ipPurgeTask = null;
        instance = null;
        getLogger().info("AltGuardian disabled.");
    }

    // --- Command Setup (ACF) ---
    private void setupCommands() {
        // Initialize the PaperCommandManager
        commandManager = new PaperCommandManager(this);

        // --- FIX: Enable unstable 'help' API ---
        try {
            commandManager.enableUnstableAPI("help"); // Add this line
            getLogger().info("Enabled unstable ACF 'help' API.");
        } catch (Exception e) {
            messageManager.logWarning("Failed to enable unstable ACF 'help' API: " + e.getMessage());
            // Help command might not work as expected
        }
        // --- End Fix ---

        // Enable Brigadier integration (optional, Paper-specific)
        try {
            commandManager.enableUnstableAPI("brigadier");
            getLogger().info("Enabled Brigadier integration for ACF.");
        } catch (Exception | NoClassDefFoundError e) {
            messageManager.logWarning("Brigadier integration not available/failed (requires Paper). Commands will still work.");
        }

        // Using synchronous completion for players as workaround
        commandManager.getCommandCompletions().registerCompletion("@altguardianplayers",
                (BukkitCommandCompletionContext context) ->
                        playerDataManager.getAllUsernamesLowerSync() // Call the sync method
        );

        // Synchronous completion for flags
        commandManager.getCommandCompletions().registerCompletion("@flags",
                context -> List.of("Potential Alt", "Impersonation", "Suspicious Activity")
        );

        // Register the command class
        commandManager.registerCommand(new AltGuardianCommands(this, messageManager, playerDataManager, nLoginMigrator, configManager, flagManager));
        getLogger().info("Registered AltGuardian commands.");
    }

    // --- Reload Logic ---
    public boolean reloadPlugin() {
        MessageManager currentMessageManager = messageManager != null ? messageManager : new MessageManager(this, configManager);
        currentMessageManager.logInfo("Reloading AltGuardian configuration...");
        try {
            if (ipPurgeTask != null && !ipPurgeTask.isCancelled()) {
                ipPurgeTask.cancel();
                ipPurgeTask = null;
                currentMessageManager.logDebug("Cancelled existing IP purge task.");
            } else if (ipPurgeTask != null) {
                ipPurgeTask = null;
            }

            if (!configManager.reloadConfig()) {
                currentMessageManager.logSevere("Failed to reload configuration files!");
                return false;
            }

            messageManager.loadMessages();
            currentMessageManager.logDebug("Messages reloaded.");

            databaseManager.notifyConfigReloaded();
            if (playerDataManager != null) {
                playerDataManager.clearCache();
            }
            altDetectionManager.notifyConfigReloaded();
            impersonationManager.notifyConfigReloaded();
            flagManager.notifyConfigReloaded();
            currentMessageManager.logDebug("Notified managers of config reload.");

            scheduleIpPurgeTask();

            messageManager.logInfo("AltGuardian configuration reloaded successfully.");
            if (configManager.isDebugEnabled()) {
                messageManager.logWarning("Debug mode is enabled after reload.");
            }
            return true;
        } catch (Exception e) {
            currentMessageManager.logSevere("An error occurred during plugin reload:", e);
            return false;
        }
    }

    // --- Task Scheduling ---
    // (scheduleIpPurgeTask and scheduleIpPurgeTaskInternal methods remain the same)
    private void scheduleIpPurgeTask() {
        if (configManager == null || databaseManager == null) {
            getLogger().severe("Cannot schedule IP purge task: ConfigManager or DatabaseManager not initialized.");
            return;
        }

        long intervalMinutes = configManager.getPurgeIntervalMinutes();
        long delayMinutes = configManager.getPurgeInitialDelayMinutes();
        int retentionDays = configManager.getIpHistoryRetentionDays();

        if (intervalMinutes <= 0 || retentionDays <= 0) {
            messageManager.logInfo("IP history purging is disabled (interval or retention <= 0 in config).");
            return;
        }

        long intervalTicks = intervalMinutes * 60 * 20;
        long delayTicks = Math.max(1, delayMinutes * 60 * 20);

        messageManager.logInfo("Scheduling IP history purge task to run every " + intervalMinutes + " minutes (" + intervalTicks + " ticks) after an initial delay of " + delayMinutes + " minutes (" + delayTicks + " ticks). Retention: " + retentionDays + " days.");

        if (!isEnabled()) {
            getLogger().warning("Plugin not fully enabled, delaying IP purge task scheduling.");
            Bukkit.getScheduler().runTaskLater(this, this::scheduleIpPurgeTaskInternal, 20L);
        } else {
            scheduleIpPurgeTaskInternal();
        }
    }

    private void scheduleIpPurgeTaskInternal() {
        if (ipPurgeTask != null && !ipPurgeTask.isCancelled()) {
            messageManager.logWarning("IP Purge task scheduling attempted again, but task already running/pending. Cancelling old task.");
            ipPurgeTask.cancel();
        }
        ipPurgeTask = null;

        if (!isEnabled()) {
            getLogger().severe("Cannot schedule IP purge task: Plugin is disabled.");
            return;
        }

        long intervalTicks = configManager.getPurgeIntervalMinutes() * 60 * 20;
        long delayTicks = Math.max(1, configManager.getPurgeInitialDelayMinutes() * 60 * 20);

        ipPurgeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (!isEnabled() || databaseManager == null || messageManager == null || configManager == null) {
                return; // Plugin disabled or managers missing
            }
            if (configManager.isDebugEnabled()) {
                messageManager.logDebug("Running scheduled IP history purge task...");
            }
            try {
                databaseManager.purgeOldIpHistoryAsync().thenAccept(purgedCount -> {
                    if (purgedCount > 0) {
                        messageManager.logInfo("Purged " + purgedCount + " old IP history records (older than " + configManager.getIpHistoryRetentionDays() + " days).");
                    } else if (configManager.isDebugEnabled()) {
                        messageManager.logDebug("No old IP history records needed purging.");
                    }
                }).exceptionally(ex -> {
                    messageManager.logError("Error during scheduled IP history purge:", ex);
                    return null;
                });
            } catch (Exception e) {
                messageManager.logError("Unexpected synchronous error starting IP history purge task:", e);
            }
        }, delayTicks, intervalTicks);

        if (ipPurgeTask == null) {
            messageManager.logError("!!! Failed to schedule IP history purge task! Bukkit scheduler returned null. !!!");
        } else {
            messageManager.logDebug("IP history purge task successfully scheduled with ID: " + ipPurgeTask.getTaskId());
        }
    }

    // --- Getters ---
    // (Getters remain the same)
    @SuppressWarnings("unused") public static AltGuardianPlugin getInstance() { return Objects.requireNonNull(instance, "AltGuardianPlugin instance is not yet available!"); }
    @SuppressWarnings("unused") public ConfigManager getConfigManager() { return Objects.requireNonNull(configManager, "ConfigManager is not initialized!"); }
    @SuppressWarnings("unused") public MessageManager getMessageManager() { return Objects.requireNonNull(messageManager, "MessageManager is not initialized!"); }
    @SuppressWarnings("unused") public DatabaseManager getDatabaseManager() { return Objects.requireNonNull(databaseManager, "DatabaseManager is not initialized!"); }
    @SuppressWarnings("unused") public PlayerDataManager getPlayerDataManager() { return Objects.requireNonNull(playerDataManager, "PlayerDataManager is not initialized!"); }
    @SuppressWarnings("unused") public AltDetectionManager getAltDetectionManager() { return Objects.requireNonNull(altDetectionManager, "AltDetectionManager is not initialized!"); }
    @SuppressWarnings("unused") public ImpersonationManager getImpersonationManager() { return Objects.requireNonNull(impersonationManager, "ImpersonationManager is not initialized!"); }
    @SuppressWarnings("unused") public FlagManager getFlagManager() { return Objects.requireNonNull(flagManager, "FlagManager is not initialized!"); }
    @SuppressWarnings("unused") public NLoginMigrator getNLoginMigrator() { return Objects.requireNonNull(nLoginMigrator, "NLoginMigrator is not initialized!"); }
    @SuppressWarnings("unused") public AltGuardianAPI getApi() { return Objects.requireNonNull(apiImplementation, "API Implementation is not initialized!"); }
    public BukkitAudiences adventure() {
        if (this.adventure == null) {
            throw new IllegalStateException("Tried to access Adventure after it was closed or not initialized.");
        }
        return this.adventure;
    }
}