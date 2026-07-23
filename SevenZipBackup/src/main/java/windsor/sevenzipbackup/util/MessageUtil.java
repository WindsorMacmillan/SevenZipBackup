package windsor.sevenzipbackup.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.Builder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.config.ConfigParser.Config;
import windsor.sevenzipbackup.config.PermissionHandler;
import windsor.sevenzipbackup.constants.Permission;
import windsor.sevenzipbackup.plugin.SevenZipBackup;
import windsor.sevenzipbackup.plugin.Scheduler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MessageUtil {

    private boolean addPrefix = true;
    private final List<Component> message = new ArrayList<>();
    private final Set<CommandSender> recipients = new HashSet<>();
    private final Set<Permission> permissionRecipients = new HashSet<>();
    private boolean allPlayers = false;
    private Boolean sendToConsole = true;
    

    @NotNull
    @Contract (" -> new")
    public static MessageUtil Builder() {
        return new MessageUtil();
    }

    public MessageUtil() {
    }

    public MessageUtil addPrefix(boolean prefix) {
        addPrefix = prefix;
        return this;
    }
    
    public MessageUtil text(String text) {
        message.add(Component.text(text, getColor()));
        return this;
    }

    public MessageUtil emText(String text) {
        message.add(Component.text(text, NamedTextColor.GOLD));
        return this;
    }

    /**
     * Parses & add MiniMessage formatted text to the message
     * @param text the MiniMessage text
     * @return the calling MessageUtil's instance
     */
    public MessageUtil mmText(String text) {
        message.add(MiniMessage.miniMessage().deserialize("<color:" + getColor().asHexString() + ">" + text));
        return this;
    }

    /**
     * Parses & add MiniMessage formatted text to the message
     * @param text the MiniMessage text
     * @param placeholders optional MiniMessage placeholders
     * @return the calling MessageUtil's instance
     */
    public MessageUtil mmText(String text, @NotNull String... placeholders) {
        Builder builder = TagResolver.builder();
        for (int i = 0; i < placeholders.length; i += 2) {
            builder.resolver(Placeholder.parsed(placeholders[i], placeholders[i + 1]));
        }
        message.add(MiniMessage.miniMessage().deserialize("<color:" + getColor().asHexString() + ">" + text, builder.build()));
        return this;
    }

    /**
     * Parses & add MiniMessage formatted text to the message
     * @param text the MiniMessage text
     * @param title what to replace
     * @param content what to replace with
     * @return the calling MessageUtil's instance
     */
    public MessageUtil mmText(String text, String title, Component content) {
        message.add(MiniMessage.miniMessage().deserialize("<color:" + getColor().asHexString() + ">" + text, TagResolver.resolver(Placeholder.component(title, content))));
        return this;
    }

    public MessageUtil text(Component component) {
        message.add(component);
        return this;
    }

    /**
     * Adds a player to the list of recipients
     * @param player the player to be added to the recipients
     * @return the calling MessageUtil's instance
     */
    public MessageUtil to(CommandSender player) {
        recipients.add(player);
        return this;
    }

    /**
     * Adds a list of players to the list of recipients
     * @param players the list of players to be added to the recipients
     * @return the calling MessageUtil's instance
     */
    public MessageUtil to(@NotNull List<CommandSender> players) {
        recipients.addAll(players);
        return this;
    }

    /**
     * Adds all online players with the specified permissions to the recipients.
     * @param permission the specified permission to be added to the recipients
     * @return the calling MessageUtil's instance
     */
    public MessageUtil toPerm(Permission permission) {
        permissionRecipients.add(permission);
        return this;
    }

    /**
     * Set whether or not if the message should be sent to the console.
     * @param value boolean
     * @return the calling MessageUtil's instance
     */
    public MessageUtil toConsole(boolean value) {
        sendToConsole = value;
        return this;
    }

    /**
     * Adds all online players to the list of recipients
     * @return the calling MessageUtil's instance
     */
    public MessageUtil all() {
        allPlayers = true;
        return this;
    }

    /**
     * Sends the message to the recipients.
     * <p>
     * Console messages are always sent directly (safe from any thread), while player
     * messages on Folia/EtheriumMC are dispatched via the appropriate region scheduler.
     * This avoids bugs where scheduling a global region task from within a global region
     * task may be silently dropped on Folia 26.2+, and ensures console output always appears.
     */
    public void send() {
        JoinConfiguration separator = JoinConfiguration.separator(Component.text(" "));
        Component builtComponent = Component.join(separator, message);
        if (addPrefix) {
            builtComponent = prefixMessage(builtComponent);
        }
        final Component finalComponent = builtComponent;
        final Set<CommandSender> explicitRecipients = new HashSet<>(recipients);
        final Set<Permission> explicitPerms = new HashSet<>(permissionRecipients);
        final boolean sendConsole = sendToConsole;
        final boolean sendAllPlayers = allPlayers;

        // --- 1. Send console message directly ---
        // ConsoleSender is reachable from any thread on both Paper and Folia.
        // Send if requested via sendConsole flag OR if ConsoleSender was explicitly
        // added as a recipient via .to(sender) — even when toConsole(false) is set,
        // explicit .to(ConsoleSender) must still work.
        boolean needsConsole = sendConsole || explicitRecipients.contains(Bukkit.getConsoleSender());
        if (needsConsole) {
            sendToConsole(finalComponent);
        }

        // --- 2. Build player dispatch ---
        // Player messages may need region-scheduled delivery on Folia.
        Runnable playerDispatch = () -> {
            Set<CommandSender> playerTargets = new HashSet<>(explicitRecipients);
            if (sendAllPlayers) {
                playerTargets.addAll(Bukkit.getOnlinePlayers());
            }
            for (Permission permission : explicitPerms) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (PermissionHandler.hasPerm(player, permission)) {
                        playerTargets.add(player);
                    }
                }
            }
            // Remove console sender from player dispatch (already handled in Step 1,
            // either via sendConsole flag or as an explicit recipient).
            playerTargets.remove(Bukkit.getConsoleSender());

            for (CommandSender recipient : playerTargets) {
                if (recipient == null || (!getConfig().messages.sendInChat && recipient instanceof Player)) {
                    continue;
                }
                try {
                    if (Scheduler.isFolia() && recipient instanceof Player) {
                        // On Folia: player messages must be sent on the player's region thread
                        final Player player = (Player) recipient;
                        Scheduler.runPlayerTask(player, () ->
                                sendToPlayerDirect(player, finalComponent));
                    } else {
                        sendToPlayerDirect(recipient, finalComponent);
                    }
                } catch (Exception ignored) {
                    sendFallback(recipient, finalComponent);
                }
            }
        };

        // --- 3. Schedule player dispatch if needed ---
        if (Scheduler.isFolia()) {
            if (Bukkit.isPrimaryThread()) {
                playerDispatch.run();
            } else {
                Scheduler.runSyncTask(playerDispatch);
            }
        } else if (!Bukkit.isPrimaryThread()) {
            Scheduler.runSyncTask(playerDispatch);
        } else {
            playerDispatch.run();
        }
    }

    /**
     * Sends a message to the console. Uses legacy String-based sendMessage
     * to avoid silent failures of Adventure's sendMessage(Component) on EtheriumMC 26.2.
     */
    private static void sendToConsole(@NotNull Component component) {
        // Path 1: Legacy String via sendMessage(String) — reliable on all server implementations
        try {
            String legacy = LegacyComponentSerializer.legacySection().serialize(component);
            Bukkit.getConsoleSender().sendMessage(legacy);
            return;
        } catch (Exception ignored) {}

        // Path 2: Direct Component — Paper native Adventure (may silently fail on some forks)
        try {
            Bukkit.getConsoleSender().sendMessage(component);
            return;
        } catch (Exception ignored) {}

        // Path 3: Plugin logger — ultimate fallback
        try {
            String plain = LegacyComponentSerializer.legacySection().serialize(component);
            SevenZipBackup.getInstance().getLogger().info(
                    ChatColor.stripColor(plain));
        } catch (Exception ignored) {
            Bukkit.getLogger().info("[SevenZipBackup] " +
                    LegacyComponentSerializer.legacySection().serialize(component));
        }
    }

    /**
     * Sends a Component to a non-console recipient (player).
     * Uses Component-based sendMessage for rich formatting.
     */
    private static void sendToPlayerDirect(@NotNull CommandSender recipient, @NotNull Component component) {
        recipient.sendMessage(component);
    }

    /**
     * Fallback path when direct message sending fails.
     * Tries legacy String-based sendMessage, then the plugin logger as last resort.
     */
    private static void sendFallback(@NotNull CommandSender recipient, @NotNull Component component) {
        try {
            String legacy = LegacyComponentSerializer.legacySection().serialize(component);
            recipient.sendMessage(legacy);
        } catch (Exception ignored2) {
            try {
                SevenZipBackup.getInstance().getLogger().info(
                        ChatColor.stripColor(
                                LegacyComponentSerializer.legacySection().serialize(component)));
            } catch (Exception ignored3) {
                // Absolute last resort
                Bukkit.getLogger().info("[SevenZipBackup] " + LegacyComponentSerializer.legacySection().serialize(component));
            }
        }
    }

    /**
     * Sends the stack trace corresponding to the specified exception to the console,
     * only if suppress errors are disabled.
     * <p>
     * Whether suppress errors is enabled is specified by the user in the {@code config.yml}
     * @param exception Exception to send the stack trace of
     */
    public static void sendConsoleException(Exception exception) {
        if (!getConfig().advanced.suppressErrors) {
            exception.printStackTrace();
        }
    }
    
    /**
     * Prefixes the specified message with the plugin name
     * @param message the message to prefix
     * @return the prefixed message
     */
    @NotNull
    private static Component prefixMessage(Component message) {
        return Component.text(translateMessageColors(getConfig().messages.prefix)).append(message);
    }

    /**
     * Translates the color codes in the specified message to the type used internally.
     * @param message the message to translate
     * @return the translated message
     */
    @NotNull
    @Contract ("_ -> new")
    public static String translateMessageColors(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
    
    private static @NotNull Config getConfig() {
        Config config = ConfigParser.getConfig();
        if (config == null) {
            config = ConfigParser.defaultConfig();
        }
        return config;
    }
    
    private static @NotNull TextColor getColor() {
        TextColor color = LegacyComponentSerializer.legacyAmpersand().deserialize(getConfig().messages.defaultColor).color();
        if (color == null) {
            color = NamedTextColor.DARK_AQUA;
        }
        return color;
    }
}
