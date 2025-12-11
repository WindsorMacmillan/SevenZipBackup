package windsor.sevenzipbackup.plugin;

import windsor.sevenzipbackup.UploadThread;
import windsor.sevenzipbackup.util.FileUtil;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import okhttp3.OkHttpClient;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import windsor.sevenzipbackup.config.ConfigMigrator;
import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.config.Localization;
import windsor.sevenzipbackup.config.PermissionHandler;
import windsor.sevenzipbackup.constants.Permission;
import windsor.sevenzipbackup.handler.CommandTabComplete;
import windsor.sevenzipbackup.handler.commandHandler.CommandHandler;
import windsor.sevenzipbackup.handler.listeners.ChatInputListener;
import windsor.sevenzipbackup.handler.listeners.PlayerListener;
import windsor.sevenzipbackup.plugin.updater.UpdateChecker;
import windsor.sevenzipbackup.plugin.updater.Updater;
import windsor.sevenzipbackup.util.CustomConfig;
import windsor.sevenzipbackup.util.HttpLogger;
import windsor.sevenzipbackup.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static windsor.sevenzipbackup.config.Localization.intl;

public class SevenZipBackup extends JavaPlugin {

    private static SevenZipBackup plugin;

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
        try {
            reloadConfig();
            FileConfiguration mainConfig = getConfig();
            localizationConfig.reloadConfig();
            FileConfiguration localizationFile = localizationConfig.getConfig();
            ConfigMigrator configMigrator = new ConfigMigrator(mainConfig, localizationFile, configPlayers);
            configMigrator.migrate();
            reloadConfig();
            localizationConfig.reloadConfig();
        } catch (Exception e) {
            getLogger().severe("Load config.yml error: " + e.getMessage());
            reloadConfig();
            localizationConfig.reloadConfig();
        }
        config = new ConfigParser(getConfig());
        ConfigParser.setPluginInstance(this); // 设置插件实例到ConfigParser
        config.reload(configPlayers);
        MessageUtil.Builder()
                .to(configPlayers)
                .mmText(intl("config-loaded"))
                .send();
        Objects.requireNonNull(getCommand(CommandHandler.CHAT_KEYWORD)).setTabCompleter(new CommandTabComplete());
        Objects.requireNonNull(getCommand(CommandHandler.CHAT_KEYWORD)).setExecutor(new CommandHandler());
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
        FileUtil.shutdown();
        UploadThread.cleanupBossBar(); // 清理BossBar
        MessageUtil.Builder().mmText(intl("plugin-stop")).send();
    }

    public void saveIntlConfig() {
        localizationConfig.saveConfig();
    }

    /**
     * Gets an instance of the plugin
     *
     * @return SevenZipBackup plugin
     */
    public static SevenZipBackup getInstance() {
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
        // 在重新加载时也验证配置
        try {
            config.reload(configFile, players);
            Localization.set(localizationFile);
        } catch (Exception e) {
            MessageUtil.Builder().mmText(intl("config-load-error")).send();
        }

        Scheduler.startBackupThread();
    }
}