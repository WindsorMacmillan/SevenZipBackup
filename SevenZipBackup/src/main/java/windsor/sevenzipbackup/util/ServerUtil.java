package windsor.sevenzipbackup.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import windsor.sevenzipbackup.plugin.Scheduler;

import java.util.concurrent.ExecutionException;

public class ServerUtil {

    /**
     * 备份前：在所有世界各自的区域线程中强制保存，暂停自动保存，并等待稳定。
     * <p>
     * Folia 下 {@link World#setAutoSave(boolean)} 属于世界状态操作，必须在世界所在区域线程执行，
     * 因此这里把它和 {@link World#save()} 一起交给 {@link Scheduler#runWorldTaskAndWait}。
     * 本方法通常由异步备份线程调用，不能直接触碰世界状态。
     */
    public static void prepareForBackup() throws ExecutionException, InterruptedException {
        for (World world : Bukkit.getWorlds()) {
            // 暂停自动保存 + 强制保存，都在该世界所属区域线程内完成。
            // 单个世界保存失败（例如该区域长时间不 tick 导致超时）不应让整次备份失败：
            // 记录后继续处理其余世界，宁可备份到稍旧的数据也不要完全不备份。
            try {
                Scheduler.runWorldTaskAndWait(world, () -> {
                    world.setAutoSave(false);
                    world.save();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                MessageUtil.sendConsoleException(
                        new java.io.IOException("保存世界 \"" + world.getName() + "\" 失败，跳过该世界", e));
            }
        }

        // 等待5秒，确保操作系统缓冲区刷新到磁盘
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 备份后恢复自动保存。
     * <p>
     * 同样必须在各世界自己的区域线程上恢复，否则 Folia 会抛出跨线程访问异常。
     */
    public static void restoreAfterBackup() {
        for (World world : Bukkit.getWorlds()) {
            try {
                Scheduler.runWorldTaskAndWait(world, () -> world.setAutoSave(true));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                // 世界可能已卸载或区域线程不可用，记录下来但不中断后续世界的恢复
                MessageUtil.sendConsoleException(e);
            }
        }
    }
}
