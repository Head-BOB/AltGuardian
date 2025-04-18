package me.hunter.altguardian.listener;

import me.hunter.altguardian.AltGuardianPlugin;
import me.hunter.altguardian.config.ConfigManager;
import me.hunter.altguardian.config.MessageManager;
import me.hunter.altguardian.manager.AltDetectionManager;
import me.hunter.altguardian.manager.FlagManager;
import me.hunter.altguardian.manager.ImpersonationManager;
import me.hunter.altguardian.manager.PlayerDataManager;
import net.kyori.adventure.text.Component;
// Import removed: import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer; // Not needed for event.disallow
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
        String ipAddress = "UNKNOWN_IP";

        try {
            if (address != null) {
                ipAddress = address.getHostAddress();
                if (ipAddress == null) {
                    ipAddress = "UNKNOWN_IP";
                }
            }
        } catch (Exception e) {
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
        if (configManager.isImpersonationEnabled()) {
            // Ensure ImpersonationManager uses the correct PlayerDataManager method internally
            impersonationFuture = impersonationManager.checkImpersonationOnFirstJoinAsync(username);
        } else {
            impersonationFuture = CompletableFuture.completedFuture(new ImpersonationManager.ImpersonationInfo(ImpersonationManager.ImpersonationCheckResult.PASSED, null));
        }

        try {
            // Use a reasonable timeout
            ImpersonationManager.ImpersonationInfo impersonationInfo = impersonationFuture.get(10, TimeUnit.SECONDS);
            messageManager.logDebug("Impersonation check result for " + username + ": " + impersonationInfo.result());

            switch (impersonationInfo.result()) {
                case PREVENT:
                    final String similarUser = impersonationInfo.similarMatch() != null ? impersonationInfo.similarMatch() : "another player";
                    // --- FIX: Add placeholders for prevent-impersonation message ---
                    final Component kickComponent = messageManager.get("prevent-impersonation",
                            MessageManager.username(username), // {username}
                            MessageManager.similarTo(similarUser) // {similar_to}
                    );
                    // Use the modern disallow method
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickComponent);
                    messageManager.logInfo("Player " + username + " connection prevented due to impersonation similarity to " + similarUser);
                    return; // Stop processing if disallowed
                case FLAG:
                    messageManager.logDebug("Player " + username + " flagged for impersonation, allowing login.");
                    break;
                case WARN:
                    messageManager.logDebug("Player " + username + " triggered impersonation warning, allowing login.");
                    break;
                case ERROR:
                    messageManager.logError("Impersonation check resulted in ERROR for " + username + ". Allowing login as failsafe.");
                    break;
                case PASSED:
                default:
                    messageManager.logDebug("Impersonation check passed for " + username + ".");
                    break;
            }
        } catch (TimeoutException e) {
            messageManager.logError("Impersonation check timed out for " + username + "! Allowing login as failsafe.", e);
        } catch (Exception e) {
            // Handle potential exceptions from join() if the future completed exceptionally
            messageManager.logError("Error during impersonation check for " + username + ". Allowing login as failsafe.", e);
        }

        // --- Initial Account Record Creation ---
        // Run this only if the player wasn't disallowed above
        try {
            long now = Instant.now().toEpochMilli();
            // Use join() here as pre-login needs this done before proceeding
            playerDataManager.getOrCreateAccountIdAsync(username, now, playerUUID)
                    .exceptionally(ex -> {
                        messageManager.logError("Failed to get/create account ID for " + username + " during pre-login!", ex);
                        return null; // Return null or a specific value indicating failure
                    })
                    .join(); // Wait for completion
            messageManager.logDebug("Ensured account record exists for " + username);
        } catch(Exception e) {
            // Log critical error, but potentially allow login if this fails? Depends on policy.
            messageManager.logError("Critical error ensuring account record for " + username + " during pre-login. Login might fail or lack tracking.", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerLogin(PlayerLoginEvent event) {
        final Player player = event.getPlayer();
        final String username = player.getName();
        String ipAddress = "UNKNOWN_IP";
        try {
            if (event.getAddress() != null) {
                ipAddress = event.getAddress().getHostAddress();
            }
            if (ipAddress == null) ipAddress = "UNKNOWN_IP";
        } catch (Exception e) {
            messageManager.logWarning("Error getting host address for " + username + " in PlayerLoginEvent: " + e.getMessage());
        }

        final long now = Instant.now().toEpochMilli();

        if ("UNKNOWN_IP".equals(ipAddress)) {
            messageManager.logWarning("IP address unknown for " + username + " at PlayerLoginEvent. Skipping AltGuardian checks.");
            event.allow(); // Allow login if IP is unknown
            return;
        }

        messageManager.logDebug("PlayerLoginEvent triggered for " + username + " [" + ipAddress + "]");

        // --- Alt Check ---
        CompletableFuture<Boolean> altCheckFuture;
        if (configManager.isAltDetectionEnabled()) {
            altCheckFuture = altDetectionManager.checkAltStatusOnLoginAsync(player, username, ipAddress, now);
        } else {
            altCheckFuture = CompletableFuture.completedFuture(false); // Assume login allowed if disabled
        }

        try {
            // Wait for the alt check result with a timeout
            boolean disallowLogin = altCheckFuture.get(10, TimeUnit.SECONDS); // Throws TimeoutException
            messageManager.logDebug("Alt check result for " + username + ": Disallow = " + disallowLogin);

            if (disallowLogin) {
                // Kick message is handled inside checkAltStatusOnLoginAsync now
                // We just need to set the result based on the boolean returned
                event.setResult(PlayerLoginEvent.Result.KICK_OTHER);
                // The actual kick message component was already prepared and scheduled
                // by AltDetectionManager if the action was KICK.
                messageManager.logInfo("Player " + username + " login denied due to alt limit check result.");
                return; // Stop processing
            }

            // --- Record Login IF Alt Check Passed ---
            // Don't wait for this, let it run in the background
            playerDataManager.recordSuccessfulLoginAsync(username, ipAddress, now)
                    .exceptionally(ex -> {
                        messageManager.logError("Failed to record successful login details for " + username + " after checks passed.", ex);
                        return null;
                    });
            messageManager.logDebug("Login allowed and details queued for recording for " + username);
            event.allow(); // Explicitly allow

        } catch (TimeoutException e) {
            messageManager.logError("Alt check timed out for " + username + "! Allowing login as failsafe.", e);
            event.allow(); // Allow login if check times out
        } catch (Exception e) {
            // Catch other exceptions like CompletionException from the future
            messageManager.logError("Error during alt check or recording login for " + username + ". Allowing login as failsafe.", e);
            event.allow(); // Allow login if any other error occurs
        }
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();

        messageManager.logDebug("PlayerJoinEvent triggered for " + username);

        // Check if player needs an impersonation warning (uses flags set during pre-login)
        flagManager.getFlagsAsync(username).thenAcceptAsync(flags -> {
            Optional<String> impersonationFlagOpt = flags.stream()
                    .filter(flag -> flag != null && flag.startsWith("Potential Impersonation (Similar to: "))
                    .findFirst();

            if (impersonationFlagOpt.isPresent()) {
                String similarTo = extractSimilarToFromFlag(impersonationFlagOpt.get());
                String configAction = configManager.getImpersonationActionOnRegister();

                // Send warning only if action was WARN or FLAG (PREVENT kicks before join)
                if ("WARN".equals(configAction) || "FLAG".equals(configAction)) {
                    // --- FIX: Add placeholders for warn-impersonation message ---
                    Component warnMessage = messageManager.get("warn-impersonation",
                            MessageManager.username(username),   // {username}
                            MessageManager.similarTo(similarTo) // {similar_to}
                    );
                    // Schedule message sending back to main thread
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            // Send message using Adventure audiences
                            //noinspectionresource
                            plugin.adventure().player(player).sendMessage(warnMessage);
                        }
                    });
                }
            }
        }, plugin.getDatabaseManager().getExecutor()); // Run DB check off main thread
    }

    /**
     * Helper method to extract the 'similar to' username from the flag string.
     */
    private String extractSimilarToFromFlag(@NotNull String impersonationFlag) {
        final String prefix = "Similar to: ";
        final String defaultName = "another player";
        try {
            int startIndex = impersonationFlag.indexOf(prefix);
            if (startIndex == -1) return defaultName;

            startIndex += prefix.length();

            int endIndex = impersonationFlag.lastIndexOf(')');
            if (endIndex == -1 || endIndex <= startIndex) return defaultName;

            return impersonationFlag.substring(startIndex, endIndex).trim();
        } catch (Exception e) {
            return defaultName;
        }
    }
}