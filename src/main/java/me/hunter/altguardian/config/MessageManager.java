package me.hunter.altguardian.config;

import me.hunter.altguardian.AltGuardianPlugin;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Handles loading, formatting (MiniMessage), and sending messages defined in config.yml.
 */
public class MessageManager {

    private final AltGuardianPlugin plugin;
    private final ConfigManager configManager;
    private final MiniMessage miniMessage;
    private final BukkitAudiences audiences;

    private String prefix = "";
    private DateTimeFormatter dateTimeFormatter;


    public MessageManager(AltGuardianPlugin plugin, ConfigManager configManager) {
        this.plugin = Objects.requireNonNull(plugin, "Plugin cannot be null");
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager cannot be null");
        this.audiences = Objects.requireNonNull(plugin.adventure(), "BukkitAudiences cannot be null");
        this.miniMessage = MiniMessage.miniMessage();
        loadMessages();
    }

    /**
     * Loads/reloads message settings, like the prefix, and sets up the date formatter.
     * Should be called after ConfigManager loads/reloads.
     */
    public void loadMessages() {
        FileConfiguration rawConfig = configManager.getRawConfig();
        if (rawConfig == null) { plugin.getLogger().severe("Cannot load messages: ConfigManager returned null config!"); this.prefix = "<gray>[<aqua>AltGuardian<gray>]<reset> "; initializeDateTimeFormatter("UTC"); return; }
        this.prefix = rawConfig.getString("messages.prefix", "<gray>[<aqua>AltGuardian<gray>]<reset> ");
        initializeDateTimeFormatter(configManager.getDisplayTimezone());
        logDebug("Messages prefix and date formatter reloaded/initialized.");
    }

    private void initializeDateTimeFormatter(String timezoneId) {
        ZoneId zoneId;
        try { zoneId = ZoneId.of(timezoneId); }
        catch (DateTimeException e) { plugin.getLogger().warning("Invalid timezone ID '" + timezoneId + "' in config.yml. Falling back to UTC. Error: " + e.getMessage()); zoneId = ZoneId.of("UTC"); }
        try { this.dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).withZone(zoneId); }
        catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Failed to create DateTimeFormatter for zone " + zoneId, e); this.dateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("UTC")); }
    }

    /**
     * Gets a formatted message Component from the config.
     * (Method body remains the same)
     */
    public Component get(String key, TagResolver... resolvers) {
        FileConfiguration rawConfig = configManager.getRawConfig();
        if (rawConfig == null) { plugin.getLogger().severe("Cannot get message '" + key + "': Config is null!"); return Component.text("[!] Error: Config not loaded! [!]", NamedTextColor.RED); }
        String rawMessage = rawConfig.getString("messages." + key);
        if (rawMessage == null || rawMessage.trim().isEmpty()) { plugin.getLogger().warning("Missing or empty message key in config.yml: messages." + key); return miniMessage.deserialize("<red>Error: Missing message key '<yellow>messages." + key + "</yellow>'!</red>"); }
        TagResolver finalResolver = TagResolver.builder().resolver(Placeholder.component("prefix", miniMessage.deserialize(this.prefix))).resolvers(resolvers).build();
        try { if (rawMessage.startsWith("<prefix>")) { return miniMessage.deserialize(rawMessage, finalResolver); } else { return miniMessage.deserialize(this.prefix + rawMessage, finalResolver); } }
        catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Failed to parse MiniMessage for key 'messages." + key + "': " + rawMessage, e); return Component.text("[!] Message parse error for key: " + key + " [!]", NamedTextColor.RED); }
    }

    /**
     * Gets a simple, unformatted string from the config.
     * (Method body remains the same)
     */
    public String getRawString(String key, String defaultValue) {
        FileConfiguration rawConfig = configManager.getRawConfig();
        if (rawConfig == null) { plugin.getLogger().warning("Cannot get raw string '" + key + "': Config is null! Returning default."); return defaultValue; }
        return rawConfig.getString("messages." + key, defaultValue);
    }

    /**
     * Sends a formatted message to a CommandSender (Player or Console).
     * (Method body remains the same)
     */
    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        if (audiences == null) { logError("Cannot send message: BukkitAudiences is null!"); return; }
        Audience audience = audiences.sender(sender);
        audience.sendMessage(get(key, resolvers));
    }

    // <<< Removed unused send(Player, String, TagResolver...) method >>>
    /*
     * Sends a formatted message to a Player.
     *
     * @param player    The recipient player.
     * @param key       The message key from config.yml.
     * @param resolvers TagResolvers for placeholders.
    public void send(Player player, String key, TagResolver... resolvers) {
        if (audiences == null) {
            logError("Cannot send message: BukkitAudiences is null!");
            return;
        }
        Audience audience = audiences.player(player);
        audience.sendMessage(get(key, resolvers));
    }
    */

    /**
     * Sends a formatted message to the server console.
     * (Method body remains the same, keeping @SuppressWarnings)
     */
    @SuppressWarnings("unused")
    public void sendToConsole(String key, TagResolver... resolvers) {
        if (audiences == null) { logError("Cannot send message to console: BukkitAudiences is null!"); return; }
        Audience consoleAudience = audiences.console();
        consoleAudience.sendMessage(get(key, resolvers));
    }

    /**
     * Sends a notification message to all online players with the specified permission.
     * (Method body remains the same)
     */
    public void broadcastToPermission(String permission, String key, TagResolver... resolvers) {
        if (audiences == null) { logError("Cannot broadcast message: BukkitAudiences is null!"); return; }
        Component message = get(key, resolvers);
        Audience broadcastAudience = audiences.permission(permission);
        if (!Bukkit.isPrimaryThread()) { Bukkit.getScheduler().runTask(plugin, () -> broadcastAudience.sendMessage(message)); }
        else { broadcastAudience.sendMessage(message); }
    }


    // --- Logging Methods ---
    // (Methods remain the same)
    public void logInfo(String message) { plugin.getLogger().info(stripColor(message)); }
    public void logWarning(String message) { plugin.getLogger().warning(stripColor(message)); }
    public void logError(String message) { plugin.getLogger().severe(stripColor(message)); }
    public void logError(String message, Throwable throwable) { plugin.getLogger().log(Level.SEVERE, stripColor(message), throwable); }
    public void logSevere(String message) { plugin.getLogger().severe(stripColor(message)); }
    public void logSevere(String message, Throwable throwable) { plugin.getLogger().log(Level.SEVERE, stripColor(message), throwable); }
    public void logDebug(String message) { if (configManager != null && configManager.isDebugEnabled()) { plugin.getLogger().info("[DEBUG] " + stripColor(message)); } }

    // stripColor method... (remains same)
    private String stripColor(String message) { if (message == null) return null; try { Component component = miniMessage.deserialize(message); return PlainTextComponentSerializer.plainText().serialize(component); } catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Failed to strip color codes via MiniMessage parsing, using basic strip for: " + message, e); return message.replaceAll("<[^>]*>", ""); } }

    // --- Formatting Helpers ---
    // (Methods remain the same)
    public String formatTimestamp(long timestampMillis) { if (timestampMillis <= 0) { return "Unknown"; } if (dateTimeFormatter == null) { logError("Cannot format timestamp: DateTimeFormatter is null!"); return "Error"; } try { return dateTimeFormatter.format(Instant.ofEpochMilli(timestampMillis)); } catch (DateTimeParseException e) { logWarning("Could not format timestamp " + timestampMillis + " using the configured formatter: " + e.getMessage()); return Instant.ofEpochMilli(timestampMillis).toString(); } catch (Exception e) { logError("Failed to format timestamp " + timestampMillis, e); return "Invalid Date"; } }

    // --- Static Placeholder Helpers ---
    // (Methods remain the same, including unused suppression for 'reason')
    public static TagResolver player(String playerName) { return Placeholder.unparsed("player", playerName != null ? playerName : "Unknown"); }
    public static TagResolver ip(String ipAddress) { return Placeholder.unparsed("ip", ipAddress != null ? ipAddress : "?.?.?.?"); }
    public static TagResolver count(int count) { return Placeholder.unparsed("count", String.valueOf(count)); }
    public static TagResolver max(int max) { return Placeholder.unparsed("max", String.valueOf(max)); }
    public static TagResolver similarTo(String similarPlayer) { return Placeholder.unparsed("similar_to", similarPlayer != null ? similarPlayer : "another player"); }
    public static TagResolver username(String username) { return Placeholder.unparsed("username", username != null ? username : "Unknown"); }
    @SuppressWarnings("unused") public static TagResolver reason(String reason) { return Placeholder.unparsed("reason", reason != null ? reason : "No reason specified"); }
    public static TagResolver flag(String flag) { return Placeholder.unparsed("flag", flag != null ? flag : "Unnamed Flag"); }
    public static TagResolver actor(String actorName) { return Placeholder.unparsed("actor", actorName != null ? actorName : "System"); }
    public static TagResolver action(String actionTaken) { return Placeholder.unparsed("action", actionTaken != null ? actionTaken : "None"); }

}