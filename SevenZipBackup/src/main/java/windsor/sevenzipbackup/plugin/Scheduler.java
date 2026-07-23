package windsor.sevenzipbackup.plugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import windsor.sevenzipbackup.UploadThread;
import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.config.ConfigParser.Config;
import windsor.sevenzipbackup.config.configSections.BackupScheduling.BackupScheduleEntry;
import windsor.sevenzipbackup.util.MessageUtil;
import windsor.sevenzipbackup.util.SchedulerUtil;

import static windsor.sevenzipbackup.config.Localization.intl;

public class Scheduler {

    /**
     * 统一的取消接口，兼容 BukkitTask 与 Folia ScheduledTask
     */
    public interface Cancellable {
        void cancel();
    }

    private static final long SCHEDULE_DRIFT_CORRECTION_INTERVAL = 60 * 60 * 24;
    private static final List<Cancellable> backupTasks = new ArrayList<>();
    private static Cancellable scheduleDriftTask = null;

    /**
     * List of Dates representing each time a scheduled backup will occur.
     */
    private static final ArrayList<ZonedDateTime> backupDatesList = new ArrayList<>();

    // Folia / Paper 检测标记
    private static final boolean IS_FOLIA;
    private static final boolean HAS_ASYNC_SCHEDULER;

    static {
        boolean folia = false;
        boolean asyncScheduler = false;
        try {
            // 检测 Folia: io.papermc.paper.threadedregions.RegionizedServer
            // 注意: EtheriumMC 等兼容分支可能使用不同的内部类名，
            // 如果检测失败会退化为 Paper/Bukkit 调度模式。
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException ignored) {
            // Fallback: 尝试另一种 Folia 识别方式
            try {
                Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
                // 该类在 Paper 1.18+ 也存在（不做 Folia 判定），但可以辅助判断
            } catch (ClassNotFoundException ignored2) {}
        }
        try {
            // Paper 1.18+ 存在 AsyncScheduler，Folia 也有
            Bukkit.class.getMethod("getAsyncScheduler");
            asyncScheduler = true;
        } catch (NoSuchMethodException ignored) {}
        IS_FOLIA = folia;
        HAS_ASYNC_SCHEDULER = asyncScheduler;
    }

    /**
     * 启动备份线程 (适配 Folia 和 Paper)
     */
    public static void startBackupThread() {
        Config config = ConfigParser.getConfig();
        // 先取消所有旧任务
        cancelAll();

        if (config.backupScheduling.enabled) {
            for (BackupScheduleEntry entry : config.backupScheduling.schedule) {
                ZoneId timezone = config.advanced.dateTimezone;
                for (DayOfWeek day : entry.days) {
                    ZonedDateTime now = ZonedDateTime.now(timezone);
                    ZonedDateTime nextOccurrence = ZonedDateTime.now(timezone)
                            .with(TemporalAdjusters.nextOrSame(day))
                            .with(ChronoField.CLOCK_HOUR_OF_DAY, entry.time.get(ChronoField.CLOCK_HOUR_OF_DAY))
                            .withMinute(entry.time.get(ChronoField.MINUTE_OF_HOUR))
                            .withSecond(0);

                    ZonedDateTime startingOccurrence = nextOccurrence;
                    if (now.isAfter(startingOccurrence)) {
                        startingOccurrence = startingOccurrence.plusWeeks(1);
                    }

                    long initialDelayTicks = SchedulerUtil.sToTicks(ChronoUnit.SECONDS.between(now, startingOccurrence));
                    ZonedDateTime previousOccurrence = ZonedDateTime.now(timezone)
                            .with(TemporalAdjusters.previous(day))
                            .with(ChronoField.CLOCK_HOUR_OF_DAY, entry.time.get(ChronoField.CLOCK_HOUR_OF_DAY))
                            .withMinute(entry.time.get(ChronoField.MINUTE_OF_HOUR))
                            .withSecond(0);
                    long periodTicks = SchedulerUtil.sToTicks(ChronoUnit.SECONDS.between(previousOccurrence, nextOccurrence));

                    // 异步重复任务
                    Cancellable task = runAsyncTimer(new UploadThread(), initialDelayTicks, periodTicks);
                    backupTasks.add(task);
                }
                // 调度提醒消息
                ZonedDateTime scheduleMessageTime = ZonedDateTime.now(timezone)
                        .with(ChronoField.CLOCK_HOUR_OF_DAY, entry.time.get(ChronoField.CLOCK_HOUR_OF_DAY))
                        .withMinute(entry.time.get(ChronoField.MINUTE_OF_HOUR));
                StringBuilder scheduleDays = new StringBuilder();
                for (int i = 0; i < entry.days.length; i++) {
                    if (i == entry.days.length - 1) {
                        scheduleDays.append(intl("list-last-delimiter"));
                    } else if (i != 0) {
                        scheduleDays.append(intl("list-delimiter"));
                    }
                    scheduleDays.append(entry.days[i].getDisplayName(TextStyle.FULL, config.advanced.dateLanguage));
                }
                MessageUtil.Builder()
                        .mmText(
                                intl("backups-scheduled"),
                                "time", scheduleMessageTime.format(DateTimeFormatter.ofPattern("hh:mm a")),
                                "days", scheduleDays.toString())
                        .toConsole(true)
                        .send();
            }

            // 计划漂移修正任务 (同步)
            long driftTicks = SchedulerUtil.sToTicks(SCHEDULE_DRIFT_CORRECTION_INTERVAL);
            scheduleDriftTask = runSyncTimer(Scheduler::startBackupThread, driftTicks, driftTicks);

        } else if (config.backupStorage.delay != -1) {
            // 间隔备份模式
            MessageUtil.Builder()
                    .mmText(intl("backups-interval-scheduled"), "delay", String.valueOf(config.backupStorage.delay))
                    .toConsole(true)
                    .send();
            long intervalTicks = SchedulerUtil.sToTicks(config.backupStorage.delay * 60L);
            Cancellable task = runAsyncTimer(new UploadThread(), intervalTicks, intervalTicks);
            backupTasks.add(task);
            UploadThread.updateNextIntervalBackupTime();
        }
    }

    /**
     * 停止备份线程
     */
    public static void stopBackupThread() {
        cancelAll();
    }

    /**
     * 取消所有已注册任务
     */
    private static void cancelAll() {
        for (Cancellable task : backupTasks) {
            task.cancel();
        }
        backupTasks.clear();
        if (scheduleDriftTask != null) {
            scheduleDriftTask.cancel();
            scheduleDriftTask = null;
        }
    }

    /**
     * 异步重复任务适配器
     * @return Cancellable 或 null (失败时)
     */
    private static Cancellable runAsyncTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        SevenZipBackup plugin = SevenZipBackup.getInstance();
        if (HAS_ASYNC_SCHEDULER) {
            var future = Bukkit.getAsyncScheduler().runAtFixedRate(
                    plugin,
                    scheduledTask -> task.run(),
                    initialDelayTicks * 50,
                    periodTicks * 50,
                    TimeUnit.MILLISECONDS
            );
            return future::cancel;  // ← 修正点
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, task, initialDelayTicks, periodTicks);
            return new BukkitTaskCancellable(bukkitTask);
        }
    }

    /**
     * 同步重复任务适配器
     * @return Cancellable
     */
    private static Cancellable runSyncTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        SevenZipBackup plugin = SevenZipBackup.getInstance();
        if (IS_FOLIA) {
            ScheduledTask scheduledTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    plugin, scheduled -> task.run(), initialDelayTicks, periodTicks);
            return scheduledTask::cancel;  // ← 修正点
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelayTicks, periodTicks);
            return new BukkitTaskCancellable(bukkitTask);
        }
    }

    /**
     * 执行一次性同步任务（兼容全局区域调度器 / 旧版调度器）
     */
    public static void runSyncTask(Runnable task) {
        runSyncTask(SevenZipBackup.getInstance(), task);
    }

    /**
     * 执行一次性同步任务，指定插件实例
     */
    public static void runSyncTask(SevenZipBackup plugin, Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 在指定玩家所在的区域（Folia）或主线程（旧版）执行任务
     * 用于操作与特定玩家绑定的内容（如将玩家添加到 BossBar）
     */
    public static void runPlayerTask(Player player, Runnable task) {
        if (IS_FOLIA) {
            if (player == null || !player.isOnline()) return;
            try {
                player.getScheduler().run(SevenZipBackup.getInstance(), scheduled -> task.run(), null);
            } catch (Exception e) {
                // 如果 player.getScheduler() 不可用（如 EtheriumMC 兼容层），退化为同步任务
                runSyncTask(task);
            }
        } else {
            Bukkit.getScheduler().runTask(SevenZipBackup.getInstance(), task);
        }
    }

    public static void runSenderTask(CommandSender sender, Runnable task) {
        if (IS_FOLIA && sender instanceof Player) {
            runPlayerTask((Player) sender, task);
        } else {
            runSyncTask(task);
        }
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    /**
     * 将 BukkitTask 包装为 Cancellable (兼容旧版)
     */
    private static class BukkitTaskCancellable implements Cancellable {
        private final BukkitTask task;
        BukkitTaskCancellable(BukkitTask task) {
            this.task = task;
        }
        @Override
        public void cancel() {
            task.cancel();
        }
    }

    /**
     * 创建异步重复任务（兼容 Paper 和 Folia）
     * @param task 要执行的任务
     * @param initialDelayTicks 首次延迟（tick）
     * @param periodTicks 间隔（tick）
     * @return 可取消的句柄（可忽略）
     */
    public static Cancellable runAsyncRepeatingTask(Runnable task, long initialDelayTicks, long periodTicks) {
        return runAsyncTimer(task, initialDelayTicks, periodTicks);
    }

    /**
     * 在服务器主线程（Folia 的全局区域线程）执行任务，并阻塞当前线程直到任务完成。
     * 仅用于从异步线程调用同步 API 的场景，注意避免死锁。
     */
    public static void runSyncTaskAndWait(Runnable task) throws ExecutionException, InterruptedException {
        SevenZipBackup plugin = SevenZipBackup.getInstance();
        if (IS_FOLIA) {
            // 使用 CompletableFuture 进行等待
            CompletableFuture<Void> future = new CompletableFuture<>();
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> {
                try {
                    task.run();
                } finally {
                    future.complete(null);
                }
            });
            try {
                future.get();  // 阻塞异步线程
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        } else {
            // 旧版使用 callSyncMethod 或直接同步调用（如果已在主线程）
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    task.run();
                    return null;
                }).get();  // 旧版仍然会阻塞
            }
        }
    }

    /**
     * 在指定世界的一个区域线程（Folia）或主线程（Paper）中执行任务，
     * 并阻塞当前线程直到任务完成。
     *
     * @param world 目标世界
     * @param task  要执行的任务
     */
    public static void runWorldTaskAndWait(World world, Runnable task) throws ExecutionException, InterruptedException {
        SevenZipBackup plugin = SevenZipBackup.getInstance();

        if (IS_FOLIA) {
            // 使用出生点区块坐标，确保区域处于活跃状态
            Location spawn = world.getSpawnLocation();
            int chunkX = spawn.getBlockX() >> 4;
            int chunkZ = spawn.getBlockZ() >> 4;

            CompletableFuture<Void> future = new CompletableFuture<>();
            Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, scheduled -> {
                try {
                    task.run();
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            try {
                future.get(30, TimeUnit.SECONDS); // 等待最多30秒
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new RuntimeException("World task execution failed", e);
            }
        } else {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    task.run();
                    return null;
                }).get();
            }
        }
    }

    /**
     * Gets a list of Dates representing each time a scheduled backup will occur.
     * @return the ArrayList of {@code ZonedDateTime} objects
     */
    public static List<ZonedDateTime> getBackupDatesList() {
        return backupDatesList;
    }

}
