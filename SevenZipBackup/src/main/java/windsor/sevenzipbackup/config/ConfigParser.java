package windsor.sevenzipbackup.config;

import java.nio.file.InvalidPathException;
import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import windsor.sevenzipbackup.config.configSections.Advanced;
import windsor.sevenzipbackup.config.configSections.BackupList;
import windsor.sevenzipbackup.config.configSections.BackupMethods;
import windsor.sevenzipbackup.config.configSections.BackupScheduling;
import windsor.sevenzipbackup.config.configSections.BackupStorage;
import windsor.sevenzipbackup.config.configSections.BossBarConfig;
import windsor.sevenzipbackup.config.configSections.ExternalBackups;
import windsor.sevenzipbackup.config.configSections.Messages;
import windsor.sevenzipbackup.plugin.SevenZipBackup;
import windsor.sevenzipbackup.util.Logger;
import windsor.sevenzipbackup.util.MessageUtil;

public class ConfigParser {
    public static class Config {
        public static final int VERSION = 4;
        public final BackupStorage backupStorage;
        public final BackupScheduling backupScheduling;
        public final BackupList backupList;
        public final ExternalBackups externalBackups;
        public final BackupMethods backupMethods;
        public final Messages messages;
        public final Advanced advanced;
        public final BossBarConfig bossBarConfig; // 添加BossBar配置

        private Config(
                BackupStorage backupStorage,
                BackupScheduling backupScheduling,
                BackupList backupList,
                ExternalBackups externalBackups,
                BackupMethods backupMethods,
                Messages messages,
                Advanced advanced,
                BossBarConfig bossBarConfig // 添加BossBar配置参数
        ) {
            this.backupStorage = backupStorage;
            this.backupScheduling = backupScheduling;
            this.backupList = backupList;
            this.externalBackups = externalBackups;
            this.backupMethods = backupMethods;
            this.messages = messages;
            this.advanced = advanced;
            this.bossBarConfig = bossBarConfig;
        }
    }

    private FileConfiguration config;
    private static Config parsedConfig;
    private static JavaPlugin pluginInstance; // 添加插件实例引用

    /**
     * Creates an instance of the {@code Config} object
     * @param config A reference to the plugin's {@code config.yml}
     */
    public ConfigParser(FileConfiguration config) {
        this.config = config;
    }

    /**
     * 设置插件实例（需要在插件启动时调用）
     * @param plugin 插件实例
     */
    public static void setPluginInstance(JavaPlugin plugin) {
        pluginInstance = plugin;
    }

    /**
     * 获取插件实例
     * @return 插件实例
     */
    public static JavaPlugin getPluginInstance() {
        return pluginInstance;
    }

    /**
     * Reloads the plugin's {@code config.yml}
     * @param config A reference to the plugin's {@code config.yml}
     */
    public void reload(FileConfiguration config, List<CommandSender> initiator) {
        this.config = config;
        reload(initiator);
    }

    /**
     * Gets the plugin's parsed config
     * @return the config
     */
    public static Config getConfig() {
        return parsedConfig;
    }

    /**
     * Reloads the plugin's {@code config.yml}
     */
    public void reload(List<CommandSender> initiators) {
        Logger logger = (input, placeholders) -> MessageUtil.Builder().mmText(input, placeholders).to(initiators).send();

        parsedConfig = new Config(
                BackupStorage.parse(config, logger),
                BackupScheduling.parse(config, logger),
                BackupList.parse(config, logger),
                ExternalBackups.parse(config, logger),
                BackupMethods.parse(config, logger),
                Messages.parse(config, logger),
                Advanced.parse(config, logger),
                BossBarConfig.parse(config, logger)
        );
    }

    @NotNull
    public static Config defaultConfig() {
        FileConfiguration config = SevenZipBackup.getInstance().getConfig();
        Logger logger = (input, placeholders) -> {};

        return new Config(
                BackupStorage.parse(config, logger),
                BackupScheduling.parse(config, logger),
                BackupList.parse(config, logger),
                ExternalBackups.parse(config, logger),
                BackupMethods.parse(config, logger),
                Messages.parse(config, logger),
                Advanced.parse(config, logger),
                BossBarConfig.parse(config, logger)
        );
    }

    @NotNull
    @Contract ("_ -> param1")
    public static String verifyPath(@NotNull String path) throws InvalidPathException {
        if (
                path.contains("\\")
        ) {
            throw new InvalidPathException(path, "Path must use the unix file separator, \"/\"");
        }
        return path;
    }
}