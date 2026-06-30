package windsor.sevenzipbackup.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import windsor.sevenzipbackup.plugin.Scheduler;

import java.util.concurrent.ExecutionException;

public class ServerUtil {

    /**
     * 备份前：在所有世界各自的区域线程中强制保存，暂停自动保存，并等待稳定
     */
    public static void prepareForBackup() throws ExecutionException, InterruptedException {
        // 暂停所有世界的自动保存
        for (World world : Bukkit.getWorlds()) {
            world.setAutoSave(false);
        }

        // 强制保存所有世界（在各自正确的线程上）
        for (World world : Bukkit.getWorlds()) {
            Scheduler.runWorldTaskAndWait(world, world::save);
        }

        // 等待5秒，确保操作系统缓冲区刷新到磁盘
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 备份后恢复自动保存
     */
    public static void restoreAfterBackup() {
        for (World world : Bukkit.getWorlds()) {
            world.setAutoSave(true);
        }
    }
}