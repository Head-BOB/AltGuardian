package me.hunter.altguardian.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.annotation.*;
import me.hunter.altguardian.AltGuardianPlugin;
import me.hunter.altguardian.api.model.AccountDetails;
import me.hunter.altguardian.api.model.IpHistoryRecord;
import me.hunter.altguardian.config.ConfigManager;
import me.hunter.altguardian.config.MessageManager;
import me.hunter.altguardian.manager.FlagManager;
import me.hunter.altguardian.manager.PlayerDataManager;
import me.hunter.altguardian.migration.NLoginMigrator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Handles all administrative commands for the AltGuardian plugin using ACF.
 */
@CommandAlias("altguardian|ag") // Base command and alias
public class AltGuardianCommands extends BaseCommand {

    private final AltGuardianPlugin plugin;
    private final MessageManager messageManager;
    private final PlayerDataManager playerDataManager;
    private final NLoginMigrator nLoginMigrator;
    private final ConfigManager configManager;
    private final FlagManager flagManager;
    private final Executor mainThreadExecutor;

    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.){3}(25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)$");


    public AltGuardianCommands(AltGuardianPlugin plugin, MessageManager messageManager, PlayerDataManager playerDataManager, NLoginMigrator nLoginMigrator, ConfigManager configManager, FlagManager flagManager) {
        this.plugin = plugin;
        this.messageManager = messageManager;
        this.playerDataManager = playerDataManager;
        this.nLoginMigrator = nLoginMigrator;
        this.configManager = configManager;
        this.flagManager = flagManager;
        this.mainThreadExecutor = runnable -> Bukkit.getScheduler().runTask(plugin, runnable);
    }

    // --- Help Command ---
    @Default
    @HelpCommand
    @Subcommand("help")
    @Description("Shows AltGuardian command help.")
    @SuppressWarnings("unused")
    public void onHelp(CommandSender sender, CommandHelp help) {
        // The 'prefix' message itself has no placeholders, but we add one here
        messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" AltGuardian Commands:", NamedTextColor.GOLD)));
        help.showHelp();
    }

    // --- Reload Command ---
    @Subcommand("reload")
    @CommandPermission("altguardian.command.reload")
    @Description("Reloads the AltGuardian configuration.")
    @SuppressWarnings("unused")
    public void onReload(CommandSender sender) {
        if (plugin.reloadPlugin()) {
            // reload-command.success has no placeholders
            messageManager.send(sender, "reload-command.success");
        } else {
            // error message uses {reason}
            messageManager.send(sender, "error", MessageManager.reason("Failed to reload configuration. Check console for details."));
        }
    }

    // --- Check Player Command ---
    @Subcommand("check|lookup|info")
    @CommandPermission("altguardian.command.check")
    @Description("Checks alt/impersonation info for a player.")
    @Syntax("<player_name>")
    @CommandCompletion("@altguardianplayers") // Use synchronous completion defined in AltGuardianPlugin
    @SuppressWarnings("unused")
    public void onCheckPlayer(CommandSender sender, String playerName) {
        // Use placeholder for the temporary message
        messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" Checking player ", NamedTextColor.GRAY).append(Component.text(playerName, NamedTextColor.YELLOW)).append(Component.text("...", NamedTextColor.GRAY))));

        playerDataManager.getAccountDetailsAsync(playerName).whenCompleteAsync((detailsOpt, error) -> {
            if (error != null) {
                messageManager.logError("Error checking player " + playerName, error);
                // Pass reason placeholder
                messageManager.send(sender, "error", MessageManager.reason("Database error during player check."));
                return;
            }
            if (detailsOpt.isEmpty()) {
                // Pass player placeholder
                messageManager.send(sender, "player-not-found-db", MessageManager.player(playerName));
                return;
            }

            AccountDetails details = detailsOpt.get();
            // Pass player placeholder
            messageManager.send(sender, "check-header", MessageManager.player(details.username()));
            // Pass reg_date and last_login placeholders
            messageManager.send(sender, "check-details",
                    Placeholder.unparsed("reg_date", messageManager.formatTimestamp(details.registrationTimestamp())),
                    Placeholder.unparsed("last_login", messageManager.formatTimestamp(details.lastLoginTimestamp()))
            );
            // Pass uuid placeholder
            if (details.offlineUuidAtRegistration() != null) {
                messageManager.send(sender, "check-uuid", Placeholder.unparsed("uuid", details.offlineUuidAtRegistration().toString()));
            }

            Set<String> flags = details.flags();
            if (flags.isEmpty()) {
                // No placeholders
                messageManager.send(sender, "check-no-flags");
            } else {
                // Pass flags placeholder (as Component)
                // Note: The message uses {flags}, but it expects a Component, so we use Placeholder.component
                Component flagComponent = Component.text(String.join("</yellow>, <yellow>", flags), NamedTextColor.YELLOW);
                messageManager.send(sender, "check-flags", Placeholder.component("flags", flagComponent));
            }

            // Fetch and display IP history
            playerDataManager.getIpHistoryAsync(details.username(), 10).thenAcceptAsync(ipHistory -> {
                if (ipHistory.isEmpty()) {
                    // No placeholders
                    messageManager.send(sender, "check-ip-history-empty");
                    // Pass originalPlayer and empty set to helper
                    findPotentialAltsByIPs(sender, details.username(), Collections.emptySet());
                } else {
                    // Pass count placeholder
                    messageManager.send(sender, "check-ip-history-header", MessageManager.count(ipHistory.size()));
                    for (IpHistoryRecord record : ipHistory) {
                        // Pass ip, first_used, last_used placeholders
                        messageManager.send(sender, "check-ip-history-entry",
                                MessageManager.ip(record.ipAddress()),
                                Placeholder.unparsed("first_used", messageManager.formatTimestamp(record.firstUsedTimestamp())),
                                Placeholder.unparsed("last_used", messageManager.formatTimestamp(record.lastUsedTimestamp()))
                        );
                    }
                    Set<String> recentIPs = ipHistory.stream().map(IpHistoryRecord::ipAddress).collect(Collectors.toSet());
                    // Pass originalPlayer and recent IPs to helper
                    findPotentialAltsByIPs(sender, details.username(), recentIPs);
                }
            }, mainThreadExecutor); // Ensure UI updates happen on main thread
        }, mainThreadExecutor); // Ensure UI updates happen on main thread
    }


    private void findPotentialAltsByIPs(CommandSender sender, String originalPlayer, Set<String> ips) {
        if (ips.isEmpty()) {
            // No placeholders
            mainThreadExecutor.execute(() -> messageManager.send(sender, "check-potential-alts-empty"));
            return;
        }

        List<CompletableFuture<List<String>>> futures = new ArrayList<>();
        long sinceMillis = Instant.now().toEpochMilli() - (configManager.getCheckTimeframeSeconds() * 1000);
        Executor dbExecutor = plugin.getDatabaseManager().getExecutor();

        for (String ip : ips) {
            futures.add(
                    playerDataManager.getRecentAccountIdsByIp(ip, sinceMillis)
                            .thenComposeAsync(playerDataManager::getUsernamesByIdsAsync, dbExecutor)
            );
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenCompleteAsync((v, error) -> {
            if (error != null) {
                messageManager.logError("Error gathering potential alts for " + originalPlayer, error);
                // Pass reason placeholder
                messageManager.send(sender, "error", MessageManager.reason("Could not fetch potential alt data."));
                return;
            }

            // Collect unique alt usernames (case-insensitive comparison for filtering)
            Set<String> potentialAlts = futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .filter(name -> !name.equalsIgnoreCase(originalPlayer))
                    .collect(Collectors.toCollection(TreeSet::new)); // Use TreeSet for sorting

            if (potentialAlts.isEmpty()) {
                // No placeholders
                messageManager.send(sender, "check-potential-alts-empty");
            } else {
                // No placeholders in header
                messageManager.send(sender, "check-potential-alts-header");
                // Pass alt_player placeholder
                // Note: The check-potential-alts-entry message uses {alt_player} and {ip}.
                // This helper function doesn't easily have the specific IP associated with each alt here.
                // We'll just provide the alt_player name for now. Modify message or logic if IP is crucial here.
                for (String alt : potentialAlts) { // Already sorted by TreeSet
                    messageManager.send(sender, "check-potential-alts-entry", MessageManager.player(alt)); // Use MessageManager.player for {alt_player} convention
                }
            }
        }, mainThreadExecutor); // Ensure UI updates happen on main thread
    }


    // --- Check IP Command ---
    @Subcommand("checkip")
    @CommandPermission("altguardian.command.checkip")
    @Description("Lists accounts associated with a specific IP address recently.")
    @Syntax("<ip_address>")
    @SuppressWarnings("unused")
    public void onCheckIp(CommandSender sender, String ipAddress) {
        if (!IP_PATTERN.matcher(ipAddress).matches()) {
            // Pass ip placeholder
            messageManager.send(sender, "invalid-ip", MessageManager.ip(ipAddress));
            return;
        }
        // Pass ip placeholder for temporary message
        messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" Checking IP ", NamedTextColor.GRAY).append(Component.text(ipAddress, NamedTextColor.YELLOW)).append(Component.text("...", NamedTextColor.GRAY))));

        long sinceMillis = Instant.now().toEpochMilli() - (configManager.getCheckTimeframeSeconds() * 1000);
        Executor dbExecutor = plugin.getDatabaseManager().getExecutor();

        playerDataManager.getRecentAccountIdsByIp(ipAddress, sinceMillis)
                .thenComposeAsync(playerDataManager::getUsernamesByIdsAsync, dbExecutor)
                .whenCompleteAsync((usernames, error) -> {
                    if (error != null) {
                        messageManager.logError("Error checking IP " + ipAddress, error);
                        // Pass reason placeholder
                        messageManager.send(sender, "error", MessageManager.reason("Database error during IP check."));
                        return;
                    }
                    if (usernames.isEmpty()) {
                        // Pass ip placeholder
                        messageManager.send(sender, "ip-not-found-db", MessageManager.ip(ipAddress));
                        return;
                    }
                    // Pass ip placeholder
                    messageManager.send(sender, "check-ip-header", MessageManager.ip(ipAddress));
                    // Pass ip placeholder
                    messageManager.send(sender, "check-ip-accounts-header", MessageManager.ip(ipAddress));

                    List<String> sortedUsers = new ArrayList<>(usernames);
                    sortedUsers.sort(String.CASE_INSENSITIVE_ORDER);

                    for (String user : sortedUsers) {
                        // Pass player placeholder
                        // Note: The message uses {player} and {last_used}. We only have player name here.
                        // Modify message or logic if last_used timestamp is required for this view.
                        messageManager.send(sender, "check-ip-accounts-entry", MessageManager.player(user));
                    }
                }, mainThreadExecutor); // Ensure UI updates happen on main thread
    }


    // --- Migration Command ---
    @Subcommand("migrate nlogin")
    @CommandPermission("altguardian.command.migrate")
    @Description("Starts the one-time data migration from the nLogin database.")
    @SuppressWarnings("unused")
    public void onMigrate(CommandSender sender) {
        // No placeholders in start message
        messageManager.send(sender, "migrate-command.start");
        nLoginMigrator.startMigrationAsync(sender); // Migration class handles its own messages/placeholders
    }


    // --- Exempt Command ---
    @Subcommand("exempt")
    @CommandPermission("altguardian.command.exempt")
    @Description("Manages IP and Username exemptions.")
    @Syntax("<ip|user> <add|remove> <value>")
    @CommandCompletion("ip|user add|remove @altguardianplayers|0.0.0.0") // Use synchronous completion
    @SuppressWarnings("unused")
    public void onExempt(CommandSender sender, String type, String action, String value) {
        String lowerType = type.toLowerCase();
        String lowerAction = action.toLowerCase();

        if (!lowerType.equals("ip") && !lowerType.equals("user")) { messageManager.send(sender, "exempt-command.usage"); return; }
        if (!lowerAction.equals("add") && !lowerAction.equals("remove")) { messageManager.send(sender, "exempt-command.usage"); return; }
        if (value == null || value.trim().isEmpty()) { messageManager.send(sender, "exempt-command.usage"); return; }

        if (lowerType.equals("ip")) {
            if (!IP_PATTERN.matcher(value).matches()) {
                // Pass ip placeholder
                messageManager.send(sender, "invalid-ip", MessageManager.ip(value));
                return;
            }
            if (lowerAction.equals("add")) {
                if (configManager.isIpExempt(value)) {
                    messageManager.send(sender, "exempt-command.ip-already-exempt", MessageManager.ip(value));
                } else {
                    configManager.addExemptIp(value);
                    messageManager.send(sender, "exempt-command.ip-added", MessageManager.ip(value));
                }
            } else { // remove
                if (!configManager.isIpExempt(value)) {
                    messageManager.send(sender, "exempt-command.ip-not-exempt", MessageManager.ip(value));
                } else {
                    configManager.removeExemptIp(value);
                    messageManager.send(sender, "exempt-command.ip-removed", MessageManager.ip(value));
                }
            }
        } else { // user
            // Make player names consistent (usually lowercase for comparisons/storage)
            String targetUser = value; //.toLowerCase(); // Decide if you want to force lowercase

            if (lowerAction.equals("add")) {
                // Check both exemption lists
                if (configManager.isAltUsernameExempt(targetUser) || configManager.isImpersonationUsernameExempt(targetUser)) {
                    messageManager.send(sender, "exempt-command.user-already-exempt", MessageManager.player(targetUser));
                } else {
                    configManager.addExemptUsername(targetUser); // Adds to both lists currently
                    messageManager.send(sender, "exempt-command.user-added", MessageManager.player(targetUser));
                }
            } else { // remove
                if (!configManager.isAltUsernameExempt(targetUser) && !configManager.isImpersonationUsernameExempt(targetUser)) {
                    messageManager.send(sender, "exempt-command.user-not-exempt", MessageManager.player(targetUser));
                } else {
                    configManager.removeExemptUsername(targetUser);
                    messageManager.send(sender, "exempt-command.user-removed", MessageManager.player(targetUser));
                }
            }
        }
    }


    // --- Flag Command ---
    @CommandPermission("altguardian.command.flag")
    @Subcommand("flag")
    @Description("Manages player flags.")
    @SuppressWarnings("unused")
    public class FlagCommands extends BaseCommand {

        @Subcommand("add")
        @Syntax("<player> <reason...>")
        @CommandCompletion("@altguardianplayers") // Use synchronous completion
        @Description("Adds a flag to a player.")
        @SuppressWarnings("unused")
        public void onFlagAdd(CommandSender sender, String playerName, @Single String reason) {
            String actor = flagManager.getActorName(sender);
            // No placeholders in temporary message
            messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" Adding flag '", NamedTextColor.GRAY).append(Component.text(reason, NamedTextColor.YELLOW)).append(Component.text("' to ", NamedTextColor.GRAY)).append(Component.text(playerName, NamedTextColor.WHITE)).append(Component.text("...", NamedTextColor.GRAY))));

            flagManager.addFlagAsync(playerName, reason, actor).whenCompleteAsync((added, error) -> {
                if (error != null) {
                    messageManager.logError("Error adding flag to " + playerName, error);
                    // Pass reason placeholder
                    messageManager.send(sender, "error", MessageManager.reason("Database error adding flag."));
                } else if (added) {
                    // Pass flag, player, actor placeholders
                    messageManager.send(sender, "flag-command.added", MessageManager.flag(reason), MessageManager.player(playerName), MessageManager.actor(actor));
                } else {
                    // Player exists, but flag wasn't added (likely already exists)
                    playerDataManager.getAccountIdAsync(playerName).thenAcceptAsync(idOpt -> { // Check if player exists for correct message
                        if(idOpt.isEmpty()) {
                            messageManager.send(sender, "player-not-found-db", MessageManager.player(playerName));
                        } else {
                            // Pass player, flag placeholders
                            messageManager.send(sender, "flag-command.already-flagged", MessageManager.player(playerName), MessageManager.flag(reason));
                        }
                    }, mainThreadExecutor);
                }
            }, mainThreadExecutor); // Ensure UI updates happen on main thread
        }

        @Subcommand("remove")
        @Syntax("<player> <reason...>")
        @CommandCompletion("@altguardianplayers @flags") // Use synchronous completions
        @Description("Removes a flag from a player.")
        @SuppressWarnings("unused")
        public void onFlagRemove(CommandSender sender, String playerName, @Single String reason) {
            String actor = flagManager.getActorName(sender);
            // No placeholders in temporary message
            messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" Removing flag '", NamedTextColor.GRAY).append(Component.text(reason, NamedTextColor.YELLOW)).append(Component.text("' from ", NamedTextColor.GRAY)).append(Component.text(playerName, NamedTextColor.WHITE)).append(Component.text("...", NamedTextColor.GRAY))));

            flagManager.removeFlagAsync(playerName, reason, actor).whenCompleteAsync((removed, error) -> {
                if (error != null) {
                    messageManager.logError("Error removing flag from " + playerName, error);
                    // Pass reason placeholder
                    messageManager.send(sender, "error", MessageManager.reason("Database error removing flag."));
                } else if (removed) {
                    // Pass flag, player, actor placeholders
                    messageManager.send(sender, "flag-command.removed", MessageManager.flag(reason), MessageManager.player(playerName), MessageManager.actor(actor));
                } else {
                    // Player exists, but flag wasn't removed (likely didn't exist)
                    playerDataManager.getAccountIdAsync(playerName).thenAcceptAsync(idOpt -> {
                        if(idOpt.isEmpty()) {
                            messageManager.send(sender, "player-not-found-db", MessageManager.player(playerName));
                        } else {
                            // Pass player, flag placeholders
                            messageManager.send(sender, "flag-command.not-flagged", MessageManager.player(playerName), MessageManager.flag(reason));
                        }
                    }, mainThreadExecutor);
                }
            }, mainThreadExecutor); // Ensure UI updates happen on main thread
        }

        @Subcommand("list")
        @Syntax("<player>")
        @CommandCompletion("@altguardianplayers") // Use synchronous completion
        @Description("Lists flags for a player.")
        @SuppressWarnings("unused")
        public void onFlagList(CommandSender sender, String playerName) {
            // Pass player placeholder for temporary message
            messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" Listing flags for ", NamedTextColor.GRAY).append(Component.text(playerName, NamedTextColor.WHITE)).append(Component.text("...", NamedTextColor.GRAY))));

            // NOTE: The flag list message has {flag} and {date}.
            // The current FlagManager.getFlagsAsync only returns Set<String> (reasons).
            // To show the date, DatabaseManager.getFlagsWithDataAsync would be needed.
            // For now, we only provide {flag}.
            flagManager.getFlagsAsync(playerName).whenCompleteAsync((flags, error) -> {
                if (error != null) {
                    messageManager.logError("Error listing flags for " + playerName, error);
                    // Pass reason placeholder
                    messageManager.send(sender, "error", MessageManager.reason("Database error listing flags."));
                } else if (flags.isEmpty()) {
                    playerDataManager.getAccountIdAsync(playerName).thenAcceptAsync(idOpt -> {
                        if(idOpt.isEmpty()) {
                            messageManager.send(sender, "player-not-found-db", MessageManager.player(playerName));
                        } else {
                            // Pass player placeholder
                            messageManager.send(sender, "flag-command.list-empty", MessageManager.player(playerName));
                        }
                    }, mainThreadExecutor);
                } else {
                    // Pass player placeholder
                    messageManager.send(sender, "flag-command.list-header", MessageManager.player(playerName));
                    List<String> sortedFlags = new ArrayList<>(flags);
                    sortedFlags.sort(String.CASE_INSENSITIVE_ORDER);
                    for (String flag : sortedFlags) {
                        // Pass flag placeholder (date placeholder in message ignored for now)
                        messageManager.send(sender, "flag-command.list-entry", MessageManager.flag(flag));
                    }
                }
            }, mainThreadExecutor); // Ensure UI updates happen on main thread
        }
    } // End of nested FlagCommands class

} // End of AltGuardianCommands class