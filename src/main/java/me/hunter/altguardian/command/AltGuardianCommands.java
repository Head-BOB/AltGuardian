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
    @SuppressWarnings("unused") // Suppress warning - Used by ACF
    public void onHelp(CommandSender sender, CommandHelp help) {
        messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" AltGuardian Commands:", NamedTextColor.GOLD)));
        help.showHelp();
    }

    // --- Reload Command ---
    @Subcommand("reload")
    @CommandPermission("altguardian.command.reload")
    @Description("Reloads the AltGuardian configuration.")
    @SuppressWarnings("unused") // Suppress warning - Used by ACF
    public void onReload(CommandSender sender) {
        if (plugin.reloadPlugin()) {
            messageManager.send(sender, "reload-command.success");
        } else {
            messageManager.send(sender, "error", MessageManager.reason("Failed to reload configuration. Check console for details."));
        }
    }

    // --- Check Player Command ---
    @Subcommand("check|lookup|info")
    @CommandPermission("altguardian.command.check")
    @Description("Checks alt/impersonation info for a player.")
    @Syntax("<player_name>")
    @CommandCompletion("@players")
    @SuppressWarnings("unused") // Suppress warning - Used by ACF
    public void onCheckPlayer(CommandSender sender, String playerName) {
        messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" Checking player ", NamedTextColor.GRAY).append(Component.text(playerName, NamedTextColor.YELLOW)).append(Component.text("...", NamedTextColor.GRAY))));

        playerDataManager.getAccountDetailsAsync(playerName).whenCompleteAsync((detailsOpt, error) -> {
            if (error != null) { messageManager.logError("Error checking player " + playerName, error); messageManager.send(sender, "error"); return; }
            if (detailsOpt.isEmpty()) { messageManager.send(sender, "player-not-found-db", MessageManager.player(playerName)); return; }

            AccountDetails details = detailsOpt.get();
            messageManager.send(sender, "check-header", MessageManager.player(details.username()));
            messageManager.send(sender, "check-details", Placeholder.unparsed("reg_date", messageManager.formatTimestamp(details.registrationTimestamp())), Placeholder.unparsed("last_login", messageManager.formatTimestamp(details.lastLoginTimestamp())));
            if (details.offlineUuidAtRegistration() != null) { messageManager.send(sender, "check-uuid", Placeholder.unparsed("uuid", details.offlineUuidAtRegistration().toString())); }

            Set<String> flags = details.flags();
            if (flags.isEmpty()) { messageManager.send(sender, "check-no-flags"); }
            else { Component flagComponent = Component.text(String.join("</yellow>, <yellow>", flags), NamedTextColor.YELLOW); messageManager.send(sender, "check-flags", Placeholder.component("flags", flagComponent)); }

            playerDataManager.getIpHistoryAsync(details.username(), 10).thenAcceptAsync(ipHistory -> {
                if (ipHistory.isEmpty()) {
                    messageManager.send(sender, "check-ip-history-empty");
                    findPotentialAltsByIPs(sender, details.username(), Collections.emptySet());
                } else {
                    messageManager.send(sender, "check-ip-history-header", MessageManager.count(ipHistory.size()));
                    for (IpHistoryRecord record : ipHistory) { messageManager.send(sender, "check-ip-history-entry", MessageManager.ip(record.ipAddress()), Placeholder.unparsed("first_used", messageManager.formatTimestamp(record.firstUsedTimestamp())), Placeholder.unparsed("last_used", messageManager.formatTimestamp(record.lastUsedTimestamp()))); }
                    Set<String> recentIPs = ipHistory.stream().map(IpHistoryRecord::ipAddress).collect(Collectors.toSet());
                    findPotentialAltsByIPs(sender, details.username(), recentIPs);
                }
            }, mainThreadExecutor);
        }, mainThreadExecutor);
    }

    // Helper - Not directly a command handler, so no suppression needed unless IDE complains
    private void findPotentialAltsByIPs(CommandSender sender, String originalPlayer, Set<String> ips) {
        if (ips.isEmpty()) { mainThreadExecutor.execute(() -> messageManager.send(sender, "check-potential-alts-empty")); return; }
        List<CompletableFuture<List<String>>> futures = new ArrayList<>();
        long sinceMillis = Instant.now().toEpochMilli() - (configManager.getCheckTimeframeSeconds() * 1000);
        Executor dbExecutor = plugin.getDatabaseManager().getExecutor();
        for (String ip : ips) { futures.add(playerDataManager.getRecentAccountIdsByIp(ip, sinceMillis).thenComposeAsync(playerDataManager::getUsernamesByIdsAsync, dbExecutor)); }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenCompleteAsync((v, error) -> {
            if (error != null) { messageManager.logError("Error gathering potential alts for " + originalPlayer, error); messageManager.send(sender, "error", MessageManager.reason("Could not fetch alt data.")); return; }
            Set<String> potentialAlts = new HashSet<>();
            futures.stream().map(CompletableFuture::join).flatMap(List::stream).filter(name -> !name.equalsIgnoreCase(originalPlayer)).forEach(potentialAlts::add);
            if (potentialAlts.isEmpty()) { messageManager.send(sender, "check-potential-alts-empty"); }
            else {
                messageManager.send(sender, "check-potential-alts-header");
                List<String> sortedAlts = new ArrayList<>(potentialAlts);
                sortedAlts.sort(String.CASE_INSENSITIVE_ORDER); // Apply IDE suggestion for List.sort
                for (String alt : sortedAlts) { messageManager.send(sender, "check-potential-alts-entry", Placeholder.unparsed("alt_player", alt)); }
            }
        }, mainThreadExecutor);
    }


    // --- Check IP Command ---
    @Subcommand("checkip")
    @CommandPermission("altguardian.command.checkip")
    @Description("Lists accounts associated with a specific IP address recently.")
    @Syntax("<ip_address>")
    @SuppressWarnings("unused") // Suppress warning - Used by ACF
    public void onCheckIp(CommandSender sender, String ipAddress) {
        if (!IP_PATTERN.matcher(ipAddress).matches()) { messageManager.send(sender, "invalid-ip", MessageManager.ip(ipAddress)); return; }
        messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" Checking IP ", NamedTextColor.GRAY).append(Component.text(ipAddress, NamedTextColor.YELLOW)).append(Component.text("...", NamedTextColor.GRAY))));
        long sinceMillis = Instant.now().toEpochMilli() - (configManager.getCheckTimeframeSeconds() * 1000);
        Executor dbExecutor = plugin.getDatabaseManager().getExecutor();
        playerDataManager.getRecentAccountIdsByIp(ipAddress, sinceMillis)
                .thenComposeAsync(playerDataManager::getUsernamesByIdsAsync, dbExecutor)
                .whenCompleteAsync((usernames, error) -> {
                    if (error != null) { messageManager.logError("Error checking IP " + ipAddress, error); messageManager.send(sender, "error"); return; }
                    if (usernames.isEmpty()) { messageManager.send(sender, "ip-not-found-db", MessageManager.ip(ipAddress)); return; }
                    messageManager.send(sender, "check-ip-header", MessageManager.ip(ipAddress));
                    messageManager.send(sender, "check-ip-accounts-header", MessageManager.ip(ipAddress));
                    List<String> sortedUsers = new ArrayList<>(usernames);
                    sortedUsers.sort(String.CASE_INSENSITIVE_ORDER); // Apply IDE suggestion for List.sort
                    for (String user : sortedUsers) { messageManager.send(sender, "check-ip-accounts-entry", MessageManager.player(user)); }
                }, mainThreadExecutor);
    }


    // --- Migration Command ---
    @Subcommand("migrate nlogin")
    @CommandPermission("altguardian.command.migrate")
    @Description("Starts the one-time data migration from the nLogin database.")
    @SuppressWarnings("unused") // Suppress warning - Used by ACF
    public void onMigrate(CommandSender sender) {
        messageManager.send(sender, "migrate-command.start");
        nLoginMigrator.startMigrationAsync(sender);
    }


    // --- Exempt Command ---
    @Subcommand("exempt")
    @CommandPermission("altguardian.command.exempt")
    @Description("Manages IP and Username exemptions.")
    @Syntax("<ip|user> <add|remove> <value>")
    @CommandCompletion("ip|user add|remove @altguardianplayers|0.0.0.0")
    @SuppressWarnings("unused") // Suppress warning - Used by ACF
    public void onExempt(CommandSender sender, String type, String action, String value) {
        String lowerType = type.toLowerCase(); String lowerAction = action.toLowerCase();
        if (!lowerType.equals("ip") && !lowerType.equals("user")) { messageManager.send(sender, "exempt-command.usage"); return; }
        if (!lowerAction.equals("add") && !lowerAction.equals("remove")) { messageManager.send(sender, "exempt-command.usage"); return; }
        if (value == null || value.trim().isEmpty()) { messageManager.send(sender, "exempt-command.usage"); return; }

        if (lowerType.equals("ip")) {
            if (!IP_PATTERN.matcher(value).matches()) { messageManager.send(sender, "invalid-ip", MessageManager.ip(value)); return; }
            if (lowerAction.equals("add")) { if (configManager.isIpExempt(value)) { messageManager.send(sender, "exempt-command.ip-already-exempt", MessageManager.ip(value)); } else { configManager.addExemptIp(value); messageManager.send(sender, "exempt-command.ip-added", MessageManager.ip(value)); } }
            else { if (!configManager.isIpExempt(value)) { messageManager.send(sender, "exempt-command.ip-not-exempt", MessageManager.ip(value)); } else { configManager.removeExemptIp(value); messageManager.send(sender, "exempt-command.ip-removed", MessageManager.ip(value)); } }
        } else { // user
            if (lowerAction.equals("add")) { if (configManager.isAltUsernameExempt(value) || configManager.isImpersonationUsernameExempt(value)) { messageManager.send(sender, "exempt-command.user-already-exempt", MessageManager.player(value)); } else { configManager.addExemptUsername(value); messageManager.send(sender, "exempt-command.user-added", MessageManager.player(value)); } }
            else { if (!configManager.isAltUsernameExempt(value) && !configManager.isImpersonationUsernameExempt(value)) { messageManager.send(sender, "exempt-command.user-not-exempt", MessageManager.player(value)); } else { configManager.removeExemptUsername(value); messageManager.send(sender, "exempt-command.user-removed", MessageManager.player(value)); } }
        }
    }


    // --- Flag Command ---
    @CommandPermission("altguardian.command.flag")
    @Subcommand("flag")
    @Description("Manages player flags.")
    @SuppressWarnings("unused") // Suppress warning - Class IS used by ACF registration
    public class FlagCommands extends BaseCommand {

        @Subcommand("add")
        @Syntax("<player> <reason...>")
        @CommandCompletion("@altguardianplayers")
        @Description("Adds a flag to a player.")
        @SuppressWarnings("unused") // Suppress warning - Used by ACF
        public void onFlagAdd(CommandSender sender, String playerName, @Single String reason) {
            String actor = flagManager.getActorName(sender);
            messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" Adding flag '", NamedTextColor.GRAY).append(Component.text(reason, NamedTextColor.YELLOW)).append(Component.text("' to ", NamedTextColor.GRAY)).append(Component.text(playerName, NamedTextColor.WHITE)).append(Component.text("...", NamedTextColor.GRAY))));
            flagManager.addFlagAsync(playerName, reason, actor).whenCompleteAsync((added, error) -> {
                if (error != null) { messageManager.logError("Error adding flag to " + playerName, error); messageManager.send(sender, "error"); }
                else if (added) { messageManager.send(sender, "flag-command.added", MessageManager.flag(reason), MessageManager.player(playerName), MessageManager.actor(actor)); }
                else { playerDataManager.getAccountIdAsync(playerName).thenAcceptAsync(idOpt -> { if(idOpt.isEmpty()) { messageManager.send(sender, "player-not-found-db", MessageManager.player(playerName)); } else { messageManager.send(sender, "flag-command.already-flagged", MessageManager.player(playerName), MessageManager.flag(reason)); } }, mainThreadExecutor); }
            }, mainThreadExecutor);
        }

        @Subcommand("remove")
        @Syntax("<player> <reason...>")
        @CommandCompletion("@altguardianplayers @flags")
        @Description("Removes a flag from a player.")
        @SuppressWarnings("unused") // Suppress warning - Used by ACF
        public void onFlagRemove(CommandSender sender, String playerName, @Single String reason) {
            String actor = flagManager.getActorName(sender);
            messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" Removing flag '", NamedTextColor.GRAY).append(Component.text(reason, NamedTextColor.YELLOW)).append(Component.text("' from ", NamedTextColor.GRAY)).append(Component.text(playerName, NamedTextColor.WHITE)).append(Component.text("...", NamedTextColor.GRAY))));
            flagManager.removeFlagAsync(playerName, reason, actor).whenCompleteAsync((removed, error) -> {
                if (error != null) { messageManager.logError("Error removing flag from " + playerName, error); messageManager.send(sender, "error"); }
                else if (removed) { messageManager.send(sender, "flag-command.removed", MessageManager.flag(reason), MessageManager.player(playerName), MessageManager.actor(actor)); }
                else { playerDataManager.getAccountIdAsync(playerName).thenAcceptAsync(idOpt -> { if(idOpt.isEmpty()) { messageManager.send(sender, "player-not-found-db", MessageManager.player(playerName)); } else { messageManager.send(sender, "flag-command.not-flagged", MessageManager.player(playerName), MessageManager.flag(reason)); } }, mainThreadExecutor); }
            }, mainThreadExecutor);
        }

        @Subcommand("list")
        @Syntax("<player>")
        @CommandCompletion("@altguardianplayers")
        @Description("Lists flags for a player.")
        @SuppressWarnings("unused") // Suppress warning - Used by ACF
        public void onFlagList(CommandSender sender, String playerName) {
            messageManager.send(sender, "prefix", Placeholder.component("message", Component.text(" Listing flags for ", NamedTextColor.GRAY).append(Component.text(playerName, NamedTextColor.WHITE)).append(Component.text("...", NamedTextColor.GRAY))));
            flagManager.getFlagsAsync(playerName).whenCompleteAsync((flags, error) -> {
                if (error != null) { messageManager.logError("Error listing flags for " + playerName, error); messageManager.send(sender, "error"); }
                else if (flags.isEmpty()) { playerDataManager.getAccountIdAsync(playerName).thenAcceptAsync(idOpt -> { if(idOpt.isEmpty()) { messageManager.send(sender, "player-not-found-db", MessageManager.player(playerName)); } else { messageManager.send(sender, "flag-command.list-empty", MessageManager.player(playerName)); } }, mainThreadExecutor); }
                else {
                    messageManager.send(sender, "flag-command.list-header", MessageManager.player(playerName));
                    List<String> sortedFlags = new ArrayList<>(flags);
                    sortedFlags.sort(String.CASE_INSENSITIVE_ORDER); // Apply IDE suggestion for List.sort
                    for (String flag : sortedFlags) { messageManager.send(sender, "flag-command.list-entry", MessageManager.flag(flag)); }
                }
            }, mainThreadExecutor);
        }
    } // End of nested FlagCommands class

} // End of AltGuardianCommands class