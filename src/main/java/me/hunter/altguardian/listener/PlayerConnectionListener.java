package me.hunter.altguardian.listener;

import me.hunter.altguardian.AltGuardianPlugin;
import me.hunter.altguardian.config.ConfigManager;
import me.hunter.altguardian.config.MessageManager;
import me.hunter.altguardian.manager.AltDetectionManager;
import me.hunter.altguardian.manager.FlagManager;
import me.hunter.altguardian.manager.ImpersonationManager;
import me.hunter.altguardian.manager.PlayerDataManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * Listens for player connection events to trigger impersonation and alt checks.
 */
public class PlayerConnectionListener implements Listener {

    private final AltGuardianPlugin plugin;
    private final PlayerDataManager playerDataManager;
    private final AltDetectionManager altDetectionManager;
    private final ImpersonationManager impersonationManager;
    private final FlagManager flagManager;
    private final ConfigManager configManager;
    private final MessageManager messageManager;

    public PlayerConnectionListener(AltGuardianPlugin plugin, PlayerDataManager playerDataManager, AltDetectionManager altDetectionManager, ImpersonationManager impersonationManager, FlagManager flagManager, ConfigManager configManager, MessageManager messageManager) {
        this.plugin = plugin;
        this.playerDataManager = playerDataManager;
        this.altDetectionManager = altDetectionManager;
        this.impersonationManager = impersonationManager;
        this.flagManager = flagManager;
        this.configManager = configManager;
        this.messageManager = messageManager;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        final String username = event.getName();
        final InetAddress address = event.getAddress();
        final UUID playerUUID = event.getUniqueId();
        String ipAddress = "UNKNOWN_IP"; // Default

        // <<< Simplified IP Address retrieval slightly based on warning >>>
        try {
            if (address != null) { // Keep basic null check just in case
                ipAddress = address.getHostAddress();
                if (ipAddress == null) { // Host address itself could theoretically be null
                    ipAddress = "UNKNOWN_IP";
                }
            }
        } catch (Exception e) {
            // Catch potential errors during getHostAddress if address object is weird
            messageManager.logWarning("Error getting host address for " + username + ": " + e.getMessage());
            ipAddress = "UNKNOWN_IP";
        }


        if ("UNKNOWN_IP".equals(ipAddress)) {
            messageManager.logWarning("Could not determine IP address for connecting player: " + username + ". Skipping AltGuardian checks.");
            return;
        }

        messageManager.logDebug("AsyncPlayerPreLogin triggered for " + username + " [" + ipAddress + "] UUID: " + playerUUID);

        // --- Impersonation Check ---
        CompletableFuture<ImpersonationManager.ImpersonationInfo> impersonationFuture;
        if (configManager.isImpersonationEnabled()) { impersonationFuture = impersonationManager.checkImpersonationOnFirstJoinAsync(username); }
        else { impersonationFuture = CompletableFuture.completedFuture(new ImpersonationManager.ImpersonationInfo(ImpersonationManager.ImpersonationCheckResult.PASSED, null)); }

        try {
            ImpersonationManager.ImpersonationInfo impersonationInfo = impersonationFuture.get(10, TimeUnit.SECONDS);
            messageManager.logDebug("Impersonation check result for " + username + ": " + impersonationInfo.result());

            switch (impersonationInfo.result()) {
                case PREVENT:
                    final String similarUser = impersonationInfo.similarMatch() != null ? impersonationInfo.similarMatch() : "another player";
                    final Component kickComponent = messageManager.get("prevent-impersonation", MessageManager.username(username), MessageManager.similarTo(similarUser));
                    // Deprecated method, but correct usage with serializer
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickComponent);
                    messageManager.logInfo("Player " + username + " connection prevented due to impersonation similarity to " + similarUser);
                    return;
                case FLAG: messageManager.logDebug("Player " + username + " flagged for impersonation, allowing login."); break;
                case WARN: messageManager.logDebug("Player " + username + " triggered impersonation warning, allowing login."); break;
                case ERROR: messageManager.logError("Impersonation check resulted in ERROR for " + username + ". Allowing login as failsafe."); break;
                case PASSED: default: messageManager.logDebug("Impersonation check passed for " + username + "."); break;
            }
        } catch (TimeoutException e) { messageManager.logError("Impersonation check timed out for " + username + "! Allowing login as failsafe.", e); }
        catch (Exception e) { messageManager.logError("Error during impersonation check for " + username + ". Allowing login as failsafe.", e); }

        // --- Initial Account Record Creation ---
        try {
            long now = Instant.now().toEpochMilli();
            playerDataManager.getOrCreateAccountIdAsync(username, now, playerUUID)
                    .exceptionally(ex -> { messageManager.logError("Failed to get/create account ID for " + username + " during pre-login!", ex); return null; })
                    .join();
            messageManager.logDebug("Ensured account record exists for " + username);
        } catch(Exception e) { messageManager.logError("Critical error ensuring account record for " + username + " during pre-login. Login might fail.", e); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerLogin(PlayerLoginEvent event) {
        final Player player = event.getPlayer();
        final String username = player.getName();
        String ipAddress = "UNKNOWN_IP";
        try {
            if (event.getAddress() != null) ipAddress = event.getAddress().getHostAddress();
            if (ipAddress == null) ipAddress = "UNKNOWN_IP";
        } catch (Exception e) { messageManager.logWarning("Error getting host address for " + username + " in PlayerLoginEvent: " + e.getMessage()); }

        final long now = Instant.now().toEpochMilli();

        if ("UNKNOWN_IP".equals(ipAddress)) { messageManager.logWarning("IP address unknown for " + username + " at PlayerLoginEvent. Skipping AltGuardian checks."); event.allow(); return; }

        messageManager.logDebug("PlayerLoginEvent triggered for " + username + " [" + ipAddress + "]");

        // --- Alt Check ---
        CompletableFuture<Boolean> altCheckFuture;
        if (configManager.isAltDetectionEnabled()) { altCheckFuture = altDetectionManager.checkAltStatusOnLoginAsync(player, username, ipAddress, now); }
        else { altCheckFuture = CompletableFuture.completedFuture(false); }

        try {
            boolean disallowLogin = altCheckFuture.get(10, TimeUnit.SECONDS);
            messageManager.logDebug("Alt check result for " + username + ": Disallow = " + disallowLogin);
            if (disallowLogin) { event.setResult(PlayerLoginEvent.Result.KICK_OTHER); messageManager.logInfo("Player " + username + " login denied due to alt limit check result."); return; }

            // --- Record Login IF Alt Check Passed ---
            playerDataManager.recordSuccessfulLoginAsync(username, ipAddress, now) .exceptionally(ex -> { messageManager.logError("Failed to record successful login details for " + username + " after checks passed.", ex); return null; });
            messageManager.logDebug("Login allowed and details queued for recording for " + username);
        } catch (TimeoutException e) { messageManager.logError("Alt check timed out for " + username + "! Allowing login as failsafe.", e); event.allow(); }
        catch (Exception e) { messageManager.logError("Error during alt check or recording login for " + username + ". Allowing login as failsafe.", e); event.allow(); }
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();

        messageManager.logDebug("PlayerJoinEvent triggered for " + username);

        // Check if player needs an impersonation warning
        flagManager.getFlagsAsync(username).thenAcceptAsync(flags -> {
            Optional<String> impersonationFlagOpt = flags.stream()
                    .filter(flag -> flag != null && flag.startsWith("Potential Impersonation (Similar to: "))
                    .findFirst();

            if (impersonationFlagOpt.isPresent()) {
                // <<< Call extracted helper method >>>
                String similarTo = extractSimilarToFromFlag(impersonationFlagOpt.get());

                String configAction = configManager.getImpersonationActionOnRegister();
                if ("WARN".equals(configAction) || "FLAG".equals(configAction)) {
                    Component warnMessage = messageManager.get("warn-impersonation", MessageManager.username(username), MessageManager.similarTo(similarTo));
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            // <<< Suppress the try-with-resources warning here >>>
                            // Reason: BukkitAudiences instance is managed by plugin lifecycle
                            //noinspectionresource closing the audience here would break it for other uses
                            plugin.adventure().player(player).sendMessage(warnMessage);
                        }
                    });
                }
            }
        }, plugin.getDatabaseManager().getExecutor());
    }

    /**
     * Helper method to extract the 'similar to' username from the flag string.
     * @param impersonationFlag The full flag string.
     * @return The extracted username, or a default string if parsing fails.
     */
    private String extractSimilarToFromFlag(@NotNull String impersonationFlag) {
        final String prefix = "Similar to: ";
        final String defaultName = "another player";
        try {
            int startIndex = impersonationFlag.indexOf(prefix);
            if (startIndex == -1) return defaultName; // Prefix not found

            startIndex += prefix.length(); // Move start index past the prefix

            int endIndex = impersonationFlag.lastIndexOf(')');
            if (endIndex == -1 || endIndex <= startIndex) return defaultName; // Closing parenthesis not found or invalid position

            return impersonationFlag.substring(startIndex, endIndex).trim();
        } catch (Exception e) {
            // Log parsing error? Optional, as it might spam if flags are malformed often.
            // messageManager.logWarning("Failed to parse 'similarTo' from flag: " + impersonationFlag);
            return defaultName; // Return default on any parsing error
        }
    }
}