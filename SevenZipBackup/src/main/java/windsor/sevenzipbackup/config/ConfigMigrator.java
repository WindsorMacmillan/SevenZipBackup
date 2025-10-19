package windsor.sevenzipbackup.config;

import java.util.List;

import org.apache.commons.lang.ObjectUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import windsor.sevenzipbackup.config.ConfigParser.Config;
import windsor.sevenzipbackup.util.Logger;
import windsor.sevenzipbackup.plugin.SevenZipBackup;
import windsor.sevenzipbackup.util.MessageUtil;

public class ConfigMigrator {
    // ConfigMigrator is called before localization is parsed, since ConfigMigrator
    // may change the intl file. Therefore, we just hardcode any messages.
    private static final String MIGRATING_MESSAGE = "Automatically migrating config to version <version>";

    private final FileConfiguration config;
    private final FileConfiguration localizationConfig;
    private final List<CommandSender> initiators;

    public ConfigMigrator(FileConfiguration config, FileConfiguration localizationConfig,
            List<CommandSender> initiators) {
        this.config = config;
        this.localizationConfig = localizationConfig;
        this.initiators = initiators;
    }

    public void migrate() {
        Logger logger = (input, placeholders) -> MessageUtil.Builder().mmText(input, placeholders).to(initiators).send();
        //logger.log("Current version <version>", "version", String.valueOf(config.isSet("version")));
        if (config.get("version")!=null && config.getInt("version") >= Config.VERSION) {
            return;
        }
        logger.log(MIGRATING_MESSAGE, "version", String.valueOf(Config.VERSION));
        config.set("version", Config.VERSION);
        int backupThreadPriority = config.getInt("backup-thread-priority");
        if (backupThreadPriority < 1) config.set("backup-thread-priority", 1);
        else if(backupThreadPriority > 10)config.set("backup-thread-priority", 10);
        int zipCompression = config.getInt("7z-compression");
        if (zipCompression < 0)config.set("7z-compression-level", 0);
        else if (zipCompression > 9) config.set("7z-compression-level", 9);
        else config.set("7z-compression-level", zipCompression);
        config.set("7z-compression", null);
        SevenZipBackup.getInstance().saveConfig();
        SevenZipBackup.getInstance().saveIntlConfig();
    }

    /**
     * Migrates a setting from the specified old path in the config
     * to the new path.
     * @param oldPath the old path
     * @param newPath the new path
     */
    private void migrate(String oldPath, String newPath) {
        if(config.get(oldPath)==null)return;
        config.set(newPath, config.get(oldPath));
        config.set(oldPath, null);
    }

    /**
     * Migrates a setting from the specified old path in the config
     * to the new path in the localization config.
     * @param oldPath the old path
     * @param newPath the new path
     */
    private void migrateIntl(String oldPath, String newPath) {
        if(config.get(oldPath)==null)return;
        localizationConfig.set(newPath, config.get(oldPath));
        config.set(oldPath, null);
    }
}
