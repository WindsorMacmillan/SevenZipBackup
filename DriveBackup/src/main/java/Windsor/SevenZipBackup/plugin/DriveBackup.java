package Windsor.SevenZipBackup.plugin;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import okhttp3.OkHttpClient;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import Windsor.SevenZipBackup.config.ConfigMigrator;
import Windsor.SevenZipBackup.config.ConfigParser;
import Windsor.SevenZipBackup.config.Localization;
import Windsor.SevenZipBackup.config.PermissionHandler;
import Windsor.SevenZipBackup.constants.Permission;
import Windsor.SevenZipBackup.handler.CommandTabComplete;
import Windsor.SevenZipBackup.handler.commandHandler.CommandHandler;
import Windsor.SevenZipBackup.handler.listeners.ChatInputListener;
import Windsor.SevenZipBackup.handler.listeners.PlayerListener;
import Windsor.SevenZipBackup.plugin.updater.UpdateChecker;
import Windsor.SevenZipBackup.plugin.updater.Updater;
import Windsor.SevenZipBackup.util.CustomConfig;
import Windsor.SevenZipBackup.util.HttpLogger;
import Windsor.SevenZipBackup.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static Windsor.SevenZipBackup.config.Localization.intl;

public class DriveBackup extends JavaPlugin {

    private static DriveBackup plugin;

    private static ConfigParser config;

    private static CustomConfig localizationConfig;

    public static Updater updater;

    /**
     * Global instance of Adventure audience
     */
    public static BukkitAudiences adventure;

    /**
     * A list of players who are currently waiting to reply.
     */
    public static List<CommandSender> chatInputPlayers;

    /**
     * A global instance of OkHTTP client
     */
    public static OkHttpClient httpClient;

    /**
     * What to do when plugin is enabled (init)
     */
    @Override
    public void onEnable() {
        plugin = this;
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.MINUTES)
            .writeTimeout(3, TimeUnit.MINUTES)
            .readTimeout(3, TimeUnit.MINUTES)
            .addInterceptor(new HttpLogger())
            .build();
        adventure = BukkitAudiences.create(plugin);
        chatInputPlayers = new ArrayList<>(1);
        List<CommandSender> configPlayers = PermissionHandler.getPlayersWithPerm(Permission.RELOAD_CONFIG);
        saveDefaultConfig();
        localizationConfig = new CustomConfig("intl.yml");
        localizationConfig.saveDefaultConfig();
        Localization.set(localizationConfig.getConfig());
        ConfigMigrator configMigrator = new ConfigMigrator(getConfig(), localizationConfig.getConfig(), configPlayers);
        configMigrator.migrate();
        config = new ConfigParser(getConfig());
        config.reload(configPlayers);
        MessageUtil.Builder()
            .to(configPlayers)
            .mmText(intl("config-loaded"))
            .send();
        getCommand(CommandHandler.CHAT_KEYWORD).setTabCompleter(new CommandTabComplete());
        getCommand(CommandHandler.CHAT_KEYWORD).setExecutor(new CommandHandler());
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(), plugin);
        pm.registerEvents(new ChatInputListener(), plugin);
        Scheduler.startBackupThread();
        BstatsMetrics.initMetrics();
        updater = new Updater(getFile());
        UpdateChecker.updateCheck();
    }

    /**
     * What to do when plugin is disabled
     */
    @Override
    public void onDisable() {
        Scheduler.stopBackupThread();
        MessageUtil.Builder().mmText(intl("plugin-stop")).send();
    }

    public void saveIntlConfig() {
        localizationConfig.saveConfig();
    }

    /**
     * Gets an instance of the plugin
     *
     * @return DriveBackup plugin
     */
    public static DriveBackup getInstance() {
        return plugin;
    }

    /**
     * Reloads config
     */
    public static void reloadLocalConfig() {
        Scheduler.stopBackupThread();
        List<CommandSender> players = PermissionHandler.getPlayersWithPerm(Permission.RELOAD_CONFIG);
        getInstance().reloadConfig();
        FileConfiguration configFile = getInstance().getConfig();
        localizationConfig.reloadConfig();
        FileConfiguration localizationFile = localizationConfig.getConfig();
        config.reload(configFile, players);
        Localization.set(localizationFile);
        Scheduler.startBackupThread();
    }
}
