package windsor.sevenzipbackup.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.plugin.SevenZipBackup;

public class ServerUtil {
    /**
     * Turns the server auto save on/off
     * @param autoSave whether to save automatically
     */
    public static void setAutoSave(boolean autoSave) {
        if (!ConfigParser.getConfig().backupStorage.disableSavingDuringBackups) {
            return;
        }

        try {
            Bukkit.getScheduler().callSyncMethod(SevenZipBackup.getInstance(), () -> {
                for (World world : Bukkit.getWorlds()) {
                    world.setAutoSave(autoSave);
                }
                return Boolean.TRUE;
            }).get();
        } catch (Exception ignored) {
        
        }
    }
}
