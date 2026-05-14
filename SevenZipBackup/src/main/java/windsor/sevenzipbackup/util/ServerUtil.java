package windsor.sevenzipbackup.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.plugin.Scheduler;

import java.util.concurrent.ExecutionException;

public class ServerUtil {
    public static void setAutoSave(boolean autoSave) throws ExecutionException, InterruptedException {
        if (!ConfigParser.getConfig().backupStorage.disableSavingDuringBackups) {
            return;
        }
        Scheduler.runSyncTaskAndWait(() -> {
            for (World world : Bukkit.getWorlds()) {
                world.setAutoSave(autoSave);
            }
        });
    }
}