package windsor.sevenzipbackup;

import com.google.api.client.util.Strings;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.md_5.bungee.api.ChatColor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.config.ConfigParser.Config;
import windsor.sevenzipbackup.config.configSections.BackupList.BackupListEntry;
import windsor.sevenzipbackup.config.configSections.BackupList.BackupListEntry.PathBackupLocation;
import windsor.sevenzipbackup.config.configSections.ExternalBackups.ExternalBackupSource;
import windsor.sevenzipbackup.config.configSections.ExternalBackups.ExternalFTPSource;
import windsor.sevenzipbackup.config.configSections.ExternalBackups.ExternalFTPSource.ExternalBackupListEntry;
import windsor.sevenzipbackup.config.configSections.ExternalBackups.ExternalMySQLSource;
import windsor.sevenzipbackup.config.configSections.ExternalBackups.ExternalMySQLSource.MySQLDatabaseBackup;
import windsor.sevenzipbackup.constants.Permission;
import windsor.sevenzipbackup.handler.listeners.PlayerListener;
import windsor.sevenzipbackup.plugin.Scheduler;
import windsor.sevenzipbackup.uploaders.Authenticator;
import windsor.sevenzipbackup.uploaders.Authenticator.AuthenticationProvider;
import windsor.sevenzipbackup.uploaders.Uploader;
import windsor.sevenzipbackup.uploaders.ftp.FTPUploader;
import windsor.sevenzipbackup.uploaders.mysql.MySQLUploader;
import windsor.sevenzipbackup.util.BlacklistEntry;
import windsor.sevenzipbackup.util.FileUtil;
import windsor.sevenzipbackup.util.LocalDateTimeFormatter;
import windsor.sevenzipbackup.util.Logger;
import windsor.sevenzipbackup.util.MessageUtil;
import windsor.sevenzipbackup.util.ServerUtil;
import windsor.sevenzipbackup.util.Timer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import windsor.sevenzipbackup.util.FileUtil.BackupFileList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static windsor.sevenzipbackup.config.Localization.intl;

public class UploadThread implements Runnable {

    private static final String LINK_COMMAND = "/7zbackup linkaccount ";
    private CommandSender initiator;
    private final UploadLogger logger;
    private final FileUtil fileUtil;
    private final Timer totalTimer;

    private static BossBar backupBossBar;
    private static final AtomicInteger totalFilesToBackup = new AtomicInteger(0);
    private static final AtomicInteger totalFilesProcessed = new AtomicInteger(0);
    private static int totalBackupTasks = 0;
    private static final AtomicInteger completedBackupTasks = new AtomicInteger(0);
    private static final ConcurrentHashMap<String, TaskProgress> taskProgressMap = new ConcurrentHashMap<>();

    private static class TaskProgress {
        volatile int total;
        final AtomicInteger processed;
        TaskProgress(int total) {
            this.total = total;
            this.processed = new AtomicInteger(0);
        }
        void setTotal(int newTotal) {
            this.total = newTotal;
        }
    }

    private static void recalcGlobalTotal() {
        int sum = 0;
        for (TaskProgress tp : taskProgressMap.values()) {
            sum += tp.total;
        }
        totalFilesToBackup.set(sum);
    }

    enum BackupStatus {
        NOT_RUNNING,
        COMPRESSING,
        STARTING,
        PRUNING,
        UPLOADING
    }

    private ArrayList<Uploader> uploaders;
    private final ConcurrentHashMap<String, LocalDateTimeFormatter> locationsToBePruned = new ConcurrentHashMap<>(10);
    private List<BackupListEntry> backupList;
    private static BackupStatus backupStatus = BackupStatus.NOT_RUNNING;
    private static LocalDateTime nextIntervalBackupTime;
    private static boolean lastBackupSuccessful = true;
    private static volatile int backupBackingUp = 0;
    private static volatile String backupCurrentLocation = "";


    public abstract static class UploadLogger implements Logger {
        public void broadcast(String input, String... placeholders) {
            MessageUtil.Builder()
                    .mmText(input, placeholders)
                    .all()
                    .send();
        }
        public abstract void log(String input, String... placeholders);
        public void initiatorError(String input, String... placeholders) {}
        public void info(String input, String... placeholders) {
            MessageUtil.Builder()
                    .mmText(input, placeholders)
                    .send();
        }
    }

    public UploadThread() {
        logger = new UploadLogger() {
            @Override
            public void log(String input, String... placeholders) {
                MessageUtil.Builder()
                        .mmText(input, placeholders)
                        .toPerm(Permission.BACKUP)
                        .send();
            }
        };
        fileUtil = new FileUtil(logger);
        totalTimer = new Timer();
    }

    public UploadThread(CommandSender initiator) {
        this.initiator = initiator;
        logger = new UploadLogger() {
            @Override
            public void log(String input, String... placeholders) {
                MessageUtil.Builder()
                        .mmText(input, placeholders)
                        .to(initiator)
                        .toPerm(Permission.BACKUP)
                        .send();
            }
            @Override
            public void initiatorError(String input, String... placeholders) {
                MessageUtil.Builder()
                        .mmText(input, placeholders)
                        .to(initiator)
                        .toConsole(false)
                        .send();
            }
        };
        fileUtil = new FileUtil(logger);
        totalTimer = new Timer();
    }

    private void createBossBar() {
        Config config = ConfigParser.getConfig();
        if (!config.bossBarConfig.showBossBarProgress) return;

        Scheduler.runSyncTask(() -> {
            if (backupBossBar != null) backupBossBar.removeAll();

            String title = intl("bossbar-create");
            title = applyBossBarColor(title, config);

            backupBossBar = Bukkit.createBossBar(
                    ChatColor.translateAlternateColorCodes('&', title),
                    config.bossBarConfig.bossBarColor,
                    config.bossBarConfig.bossBarStyle
            );
            backupBossBar.setProgress(0.0);
            for (Player player : Bukkit.getOnlinePlayers()) {
                backupBossBar.addPlayer(player);
            }
        });
        logger.info("开始备份，共 " + totalBackupTasks + " 个文件夹需要备份");
    }

    public static void updateBossBarProgress() {
        Config config = ConfigParser.getConfig();
        if (!config.bossBarConfig.showBossBarProgress) return;

        Scheduler.runSyncTask(() -> {
            if (backupBossBar == null) return;
            int totalFiles = totalFilesToBackup.get();
            int processedFiles = totalFilesProcessed.get();
            double progress;
            String title;
            if (totalFiles <= 0) {
                int completedTasks = completedBackupTasks.get();
                progress = totalBackupTasks > 0 ? (double) completedTasks / totalBackupTasks : 0.0;
                title = intl("bossbar-preparing");
            } else {
                progress = (double) processedFiles / totalFiles;
                title = intl("bossbar-progress")
                        .replace("<progress>", String.format("%.2f", progress * 100))
                        .replace("<num>", String.valueOf(processedFiles))
                        .replace("<total>", String.valueOf(totalFiles));
            }
            // 应用 bossbar 颜色到标题
            title = applyBossBarColor(title, config);

            backupBossBar.setProgress(Math.min(progress, 1.0));
            backupBossBar.setTitle(ChatColor.translateAlternateColorCodes('&', title));
        });
    }

    /**
     * 根据配置中的 bossbar-color 转换为 ChatColor 并添加到文本开头
     */
    private static String applyBossBarColor(String text, Config config) {
        BarColor barColor = config.bossBarConfig.bossBarColor;
        ChatColor chatColor;
        try {
            chatColor = ChatColor.valueOf(barColor.name());
        } catch (IllegalArgumentException e) {
            chatColor = ChatColor.BLUE;
        }
        return chatColor + text;
    }

    /**
     * 重新计算全局已处理文件数（遍历所有任务求和）
     */
    private static void recalcGlobalProcessed() {
        int sum = 0;
        for (TaskProgress tp : taskProgressMap.values()) {
            sum += tp.processed.get();
        }
        totalFilesProcessed.set(sum);
    }

    public static void addPlayerToBossBar(Player player) {
        Config config = ConfigParser.getConfig();
        if (!config.bossBarConfig.showBossBarProgress) return;

        Scheduler.runPlayerTask(player, () -> {
            if (backupBossBar != null && player.isOnline()) {
                backupBossBar.addPlayer(player);
            }
        });
    }

    private void removeBossBar() {
        Scheduler.runSyncTask(() -> {
            if (backupBossBar != null) {
                backupBossBar.removeAll();
                backupBossBar = null;
            }
            totalFilesToBackup.set(0);
            totalFilesProcessed.set(0);
            totalBackupTasks = 0;
            completedBackupTasks.set(0);
            taskProgressMap.clear();
        });
    }

    public static boolean isBackupInProgress() {
        return backupBossBar != null;
    }

    public static void addFilesToTotal(int fileCount) {
        totalFilesToBackup.addAndGet(fileCount);
    }

    public static void incrementCompletedTasks() {
        completedBackupTasks.incrementAndGet();
    }

    public static void cleanupBossBar() {
        Runnable cleanupTask = () -> {
            if (backupBossBar != null) {
                backupBossBar.removeAll();
                backupBossBar = null;
            }
            taskProgressMap.clear();
        };

        if (ConfigParser.getPluginInstance().isEnabled()) {
            Scheduler.runSyncTask(cleanupTask);
        } else {
            cleanupTask.run();
        }
    }

    @Override
    public void run() {
        if (initiator != null && backupStatus != BackupStatus.NOT_RUNNING) {
            logger.initiatorError(
                    intl("backup-already-running"),
                    "backup-status", getBackupStatus());
            return;
        }
        if(ConfigParser.getConfig().advanced.debugEnabled){
            logger.info("尝试运行定时备份任务");
        }
        try {
            run_internal();
        } catch (Exception e) {
            if(ConfigParser.getConfig().advanced.debugEnabled){
                logger.info("备份任务异常：");
                e.printStackTrace();
            }
            lastBackupSuccessful = false;
        } finally {
            backupStatus = BackupStatus.NOT_RUNNING;
            if (lastBackupSuccessful) {
                SevenZipBackupApi.backupDone();
            } else {
                SevenZipBackupApi.backupError();
            }
        }
    }

    void run_internal() throws ExecutionException, InterruptedException {
        Config config = ConfigParser.getConfig();
        totalTimer.start();
        backupStatus = BackupStatus.STARTING;

        // 清理之前的待清理数据
        if (!locationsToBePruned.isEmpty()) {
            locationsToBePruned.clear();
        }
        if (initiator == null) {
            updateNextIntervalBackupTime();
        }
        if (!SevenZipBackupApi.shouldStartBackup()) return;
        if (config.backupStorage.backupsRequirePlayers && !PlayerListener.isAutoBackupsActive() && initiator == null) return;

        List<ExternalBackupSource> externalBackupList = Arrays.asList(config.externalBackups.sources);
        backupList = new ArrayList<>(Arrays.asList(config.backupList.list));
        if (externalBackupList.isEmpty() && backupList.isEmpty()) {
            logger.info(intl("backup-empty-list"));
            return;
        }

        logger.broadcast(intl("backup-start"));

        // 处理外部备份
        for (ExternalBackupSource externalBackup : externalBackupList) {
            if (externalBackup instanceof ExternalFTPSource) {
                makeExternalFileBackup((ExternalFTPSource) externalBackup);
            } else {
                makeExternalDatabaseBackup((ExternalMySQLSource) externalBackup);
            }
        }

        logger.info(intl("backup-local-start"));
        backupStatus = BackupStatus.COMPRESSING;
        backupBackingUp = 0;
        backupCurrentLocation = "";

        // 暂停自动保存并强制写入
        ServerUtil.prepareForBackup();

        boolean errorOccurred = false;
        try {
            // 计算任务数，初始化 BossBar
            totalBackupTasks = 0;
            for (BackupListEntry set : backupList) {
                if (set.create) {
                    totalBackupTasks += set.location.getPaths().size();
                }
            }
            totalFilesToBackup.set(0);
            totalFilesProcessed.set(0);
            completedBackupTasks.set(0);

            if (totalBackupTasks > 0) {
                createBossBar();
                updateBossBarProgress();
            }

            // 异步压缩所有备份文件夹
            asyncCompressAllBackups();

        } catch (Exception e) {
            errorOccurred = true;
            logger.info(intl("backup-local-failed"));
            MessageUtil.sendConsoleException(e);
            if (ConfigParser.getConfig().advanced.debugEnabled) {
                logger.info("异步备份任务失败！");
                e.printStackTrace();
            }
            // 出现异常后立即移除 BossBar，避免卡进度条
            removeBossBar();
        } finally {
            // 确保自动保存被恢复，不论成功与否
            ServerUtil.restoreAfterBackup();
            if (ConfigParser.getConfig().advanced.debugEnabled) {
                logger.info("备份压缩任务结束");
            }
        }

        if (!errorOccurred) {
            logger.info(intl("backup-local-complete"));
            // 继续上传流程（其中会调用 removeBossBar）
            continueWithUploadProcess();
        }

        totalTimer.end();
        long totalBackupTime = totalTimer.getTime();
        long totalSeconds = Duration.of(totalBackupTime, ChronoUnit.MILLIS).getSeconds();
        logger.broadcast(intl("backup-total-time"), "time", String.valueOf(totalSeconds));
    }

    private void pruneLocalBackups() {
        logger.log(intl("backup-local-prune-start"));
        for (Map.Entry<String, LocalDateTimeFormatter> entry : locationsToBePruned.entrySet()) {
            String location = entry.getKey();
            LocalDateTimeFormatter formatter = entry.getValue();
            fileUtil.purgeLocalBackups(location, formatter);
        }
        logger.log(intl("backup-local-prune-complete"));
    }

    private void asyncCompressAllBackups() throws Exception {
        int maxParallel = ConfigParser.getConfig().backupStorage.threadCounts;
        if (maxParallel < 1) maxParallel = 1;

        // 预扫描，收集任务并获得初始文件数，同时建立进度条
        List<CompressTask> tasks = new ArrayList<>();
        int initialTotal = 0;
        taskProgressMap.clear();

        for (BackupListEntry set : backupList) {
            for (Path folder : set.location.getPaths()) {
                if (!set.create) continue;
                String location = folder.toString();
                LocalDateTimeFormatter formatter = set.formatter;
                List<String> blacklist = Arrays.asList(set.blacklist);
                String outputPath = fileUtil.generateOutputPath(location, formatter);

                // 预扫描，带回调以便显示日志，但不更新bossbar（避免闪烁）
                BackupFileList fileList = fileUtil.prepareFileList(location, blacklist,
                        new BackupProgressCallback() {
                            @Override public void onFileListPrepared(int fileCount) {
                                logger.info("备份位置 " + location + " 发现 " + fileCount + " 个文件需要备份");
                            }
                            @Override public void onFileProcessed() {}
                            @Override public void onBackupComplete() {}
                            @Override public void onError(Throwable throwable) {}
                        });
                int fileCount = fileList.getList().size();
                tasks.add(new CompressTask(tasks.size() + 1, location, outputPath, blacklist, formatter));
                taskProgressMap.put(location, new TaskProgress(fileCount));
                initialTotal += fileCount;
            }
        }

        if (tasks.isEmpty()) return;

        totalFilesToBackup.set(initialTotal);
        totalFilesProcessed.set(0);
        createBossBar();
        updateBossBarProgress();

        // 按并发度提交任务
        ExecutorService executor = Executors.newFixedThreadPool(maxParallel);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (CompressTask task : tasks) {
                Future<Void> future = executor.submit(() -> {
                    backupBackingUp = task.index;
                    backupCurrentLocation = task.location;

                    // 重新扫描实际文件列表，修正数量
                    BackupFileList actualFileList;
                    try {
                        actualFileList = fileUtil.prepareFileList(task.location, task.blacklist);
                    } catch (Exception e) {
                        // 如果扫描失败，记录错误并跳过
                        logger.info("重新扫描文件列表失败：" + task.location);
                        MessageUtil.sendConsoleException(e);
                        // 标记任务完成（文件数为0）
                        TaskProgress tp = taskProgressMap.get(task.location);
                        if (tp != null) {
                            tp.setTotal(0);
                            tp.processed.set(0);
                            recalcGlobalTotal();
                            recalcGlobalProcessed();
                        }
                        incrementCompletedTasks();
                        updateBossBarProgress();
                        return null;
                    }

                    // 更新总量
                    TaskProgress tp = taskProgressMap.get(task.location);
                    if (tp != null) {
                        int oldTotal = tp.total;
                        int newTotal = actualFileList.getList().size();
                        tp.setTotal(newTotal);
                        // 如果新总数更小，已处理数需要同步裁剪
                        if (tp.processed.get() > newTotal) {
                            tp.processed.set(newTotal);
                        }
                        recalcGlobalTotal();
                        recalcGlobalProcessed();
                        if (oldTotal != newTotal) {
                            logger.info("备份位置 " + task.location + " 文件数变更为 " + newTotal);
                        }
                    }

                    // 开始压缩
                    try {
                        fileUtil.compressBackup(task.location, task.outputPath, actualFileList,
                                new BackupProgressCallback() {
                                    @Override public void onFileListPrepared(int fileCount) {}
                                    @Override public void onFileProcessed() {}
                                    @Override public void onProgress(int processedFiles, int totalFiles) {
                                        TaskProgress t = taskProgressMap.get(task.location);
                                        if (t != null) {
                                            t.processed.set(processedFiles);
                                            recalcGlobalProcessed();
                                            updateBossBarProgress();
                                        }
                                    }
                                    @Override public void onBackupComplete() {
                                        TaskProgress t = taskProgressMap.get(task.location);
                                        if (t != null) {
                                            t.processed.set(t.total);
                                            recalcGlobalProcessed();
                                        }
                                        incrementCompletedTasks();
                                        updateBossBarProgress();
                                        locationsToBePruned.put(task.location, task.formatter);
                                        logger.info(intl("backup-local-file-complete"), "location", task.location);
                                    }
                                    @Override public void onError(Throwable throwable) {
                                        TaskProgress t = taskProgressMap.get(task.location);
                                        if (t != null) {
                                            t.processed.set(t.total);
                                            recalcGlobalProcessed();
                                        }
                                        incrementCompletedTasks();
                                        updateBossBarProgress();
                                        logger.info(intl("backup-local-file-failed"), "location", task.location);
                                        MessageUtil.sendConsoleException((Exception) throwable);
                                    }
                                });
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                    return null;
                });
                futures.add(future);
            }

            if (ConfigParser.getConfig().advanced.debugEnabled) {
                logger.info("等待所有压缩任务完成（最大并行 " + maxParallel + "）...");
            }
            for (Future<Void> f : futures) f.get();
        } finally {
            executor.shutdown();
        }
    }

    private static class CompressTask {
        final int index;
        final String location;
        final String outputPath;
        final List<String> blacklist;
        final LocalDateTimeFormatter formatter;

        CompressTask(int index, String location, String outputPath, List<String> blacklist, LocalDateTimeFormatter formatter) {
            this.index = index;
            this.location = location;
            this.outputPath = outputPath;
            this.blacklist = blacklist;
            this.formatter = formatter;
        }
    }

    private void continueWithUploadProcess() {
        logger.info(intl("backup-upload-start"));
        backupStatus = BackupStatus.UPLOADING;
        backupBackingUp = 0;
        backupCurrentLocation = "";

        uploaders = new ArrayList<>(5);
        ensureMethodsAuthenticated();
        uploadBackupFiles(uploaders);
        FileUtil.deleteFolder(new File("external-backups"));
        logger.info(intl("backup-upload-complete"));
        removeBossBar();
        pruneLocalBackups();
    }

    private void ensureMethodsAuthenticated() {
        Iterator<Uploader> iterator = uploaders.iterator();
        while (iterator.hasNext()) {
            Uploader uploader = iterator.next();
            AuthenticationProvider provider = uploader.getAuthProvider();
            if (provider != null && !Authenticator.hasRefreshToken(provider)) {
                logger.info(
                        intl("backup-method-not-linked"),
                        "link-command", LINK_COMMAND + provider.getId(),
                        "upload-method", provider.getName());
                iterator.remove();
                continue;
            }
            if (!uploader.isAuthenticated()) {
                if (provider == null) {
                    logger.info(
                            intl("backup-method-not-auth"),
                            "upload-method", uploader.getName());
                } else {
                    logger.info(
                            intl("backup-method-not-auth-authenticator"),
                            "link-command", LINK_COMMAND + provider.getId(),
                            "upload-method", uploader.getName());
                }
                iterator.remove();
            }
        }
    }

    private void uploadBackupFiles(List<Uploader> uploaders) {
        for (BackupListEntry set : backupList) {
            backupBackingUp++;
            backupCurrentLocation = set.location.toString();
            for (Path folder : set.location.getPaths()) {
                uploadFile(folder.toString(), set.formatter, uploaders);
            }
        }
    }

    private void uploadFile(String location, LocalDateTimeFormatter formatter, List<Uploader> uploaders) {
        try {
            if (FileUtil.isBaseFolder(location)) {
                location = "root";
            }
            TreeMap<Long, File> localBackups = fileUtil.getLocalBackups(location, formatter);
            if (localBackups.isEmpty()) {
                logger.info(intl("location-empty"), "location", location);
                return;
            }
            File file = localBackups.descendingMap().firstEntry().getValue();
            Timer timer = new Timer();
            for (Uploader uploader : uploaders) {
                logger.info(
                        intl("backup-method-uploading"),
                        "upload-method",
                        uploader.getName());
                timer.start();
                uploader.uploadFile(file, location);
                timer.end();
                if (!uploader.isErrorWhileUploading()) {
                    logger.info(timer.getUploadTimeMessage(file));
                } else {
                    logger.info(intl("backup-method-upload-failed"));
                }
            }
            logger.info(intl("backup-file-upload-complete"), "file-name", file.getName());
        } catch (Exception e) {
            logger.info(intl("backup-method-upload-failed"));
            MessageUtil.sendConsoleException(e);
        }
    }

    private void makeExternalFileBackup(ExternalFTPSource externalBackup) {
        logger.info(
                intl("external-ftp-backup-start"),
                "socket-addr", getSocketAddress(externalBackup));
        FTPUploader ftpUploader = new FTPUploader(
                logger,
                externalBackup.hostname,
                externalBackup.port,
                externalBackup.username,
                externalBackup.password,
                externalBackup.ftps,
                externalBackup.sftp,
                externalBackup.publicKey,
                externalBackup.passphrase,
                "external-backups",
                ".");
        String tempFolderName = getTempFolderName(externalBackup);
        if (tempFolderName == null) {
            logger.info(intl("external-backup-failed"));
            return;
        }
        for (ExternalBackupListEntry backup : externalBackup.backupList) {
            ArrayList<BlacklistEntry> blacklist = new ArrayList<>();
            for (String blacklistGlob : backup.blacklist) {
                BlacklistEntry blacklistEntry = new BlacklistEntry(
                        blacklistGlob,
                        FileSystems.getDefault().getPathMatcher("glob:" + blacklistGlob)
                );
                blacklist.add(blacklistEntry);
            }
            String baseDirectory;
            if (Strings.isNullOrEmpty(externalBackup.baseDirectory)) {
                baseDirectory = backup.path;
            } else {
                baseDirectory = externalBackup.baseDirectory + "/" + backup.path;
            }
            for (String relativeFilePath : ftpUploader.getFiles(baseDirectory)) {
                String filePath = baseDirectory + "/" + relativeFilePath;

                for (BlacklistEntry blacklistEntry : blacklist) {
                    if (blacklistEntry.getPathMatcher().matches(Paths.get(relativeFilePath))) {
                        blacklistEntry.incBlacklistedFiles();
                    }
                }
                String parentFolder = new File(relativeFilePath).getParent();
                String parentFolderPath;
                if (parentFolder != null) {
                    parentFolderPath = "/" + parentFolder;
                } else {
                    parentFolderPath = "";
                }
                ftpUploader.downloadFile(filePath, tempFolderName + "/" + backup.path + parentFolderPath);
            }
            for (BlacklistEntry blacklistEntry : blacklist) {
                String globPattern = blacklistEntry.getGlobPattern();
                int blacklistedFiles = blacklistEntry.getBlacklistedFiles();
                if (blacklistedFiles > 0) {
                    logger.info(
                            intl("external-ftp-backup-blacklisted"),
                            "blacklisted-files", String.valueOf(blacklistedFiles),
                            "glob-pattern", globPattern);
                }
            }
        }
        ftpUploader.close();
        BackupListEntry backup = new BackupListEntry(
                new PathBackupLocation("external-backups" + "/" + tempFolderName),
                externalBackup.format,
                true,
                new String[0]
        );
        backupList.add(backup);
        if (ftpUploader.isErrorWhileUploading()) {
            logger.info(
                    intl("external-ftp-backup-failed"),
                    "socket-addr", getSocketAddress(externalBackup));
        } else {
            logger.info(
                    intl("external-ftp-backup-complete"),
                    "socket-addr", getSocketAddress(externalBackup));
        }
    }

    private void makeExternalDatabaseBackup(ExternalMySQLSource externalBackup) {
        logger.info(
                intl("external-mysql-backup-start"),
                "socket-addr", getSocketAddress(externalBackup));
        MySQLUploader mysqlUploader = new MySQLUploader(
                externalBackup.hostname,
                externalBackup.port,
                externalBackup.username,
                externalBackup.password,
                externalBackup.ssl);
        String tempFolderName = getTempFolderName(externalBackup);
        if (tempFolderName == null) {
            logger.info(intl("external-backup-failed"));
            return;
        }
        for (MySQLDatabaseBackup database : externalBackup.databaseList) {
            for (String blacklistEntry : database.blacklist) {
                logger.info(
                        intl("external-mysql-backup-blacklisted"),
                        "blacklist-entry", blacklistEntry);
            }
            mysqlUploader.downloadDatabase(database.name, tempFolderName, Arrays.asList(database.blacklist));
        }
        BackupListEntry backup = new BackupListEntry(
                new PathBackupLocation("external-backups" + "/" + tempFolderName),
                externalBackup.format,
                true,
                new String[0]
        );
        backupList.add(backup);
        if (mysqlUploader.isErrorWhileUploading()) {
            logger.info(
                    intl("external-mysql-backup-failed"),
                    "socket-addr", getSocketAddress(externalBackup));
        } else {
            logger.info(
                    intl("external-mysql-backup-complete"),
                    "socket-addr", getSocketAddress(externalBackup));
        }
    }

    public static String getBackupStatus() {
        Config config = ConfigParser.getConfig();
        String message;
        switch (backupStatus) {
            case COMPRESSING:
                message = intl("backup-status-compressing");
                break;
            case UPLOADING:
                message = intl("backup-status-uploading");
                break;
            case STARTING:
                return intl("backup-status-starting");
            case PRUNING:
                return intl("backup-status-purging");
            default:
                return intl("backup-status-not-running");
        }
        BackupListEntry[] backupList = config.backupList.list;

        int backupNumber = Math.max(0, backupBackingUp - 1);
        int backupCount = backupStatus == BackupStatus.COMPRESSING && totalBackupTasks > 0
                ? totalBackupTasks
                : backupList.length;
        int backupIndex = Math.min(backupNumber, backupList.length - 1);

        String backupSetName = backupCurrentLocation;
        if (backupSetName.isEmpty() && backupList.length > 0) {
            backupSetName = backupList[backupIndex].location.toString();
        }

        return message
                .replace("<set-name>", backupSetName)
                .replace("<set-num>", String.valueOf(backupNumber+1))
                .replace("<set-count>", String.valueOf(backupCount));
    }

    public static String getNextAutoBackup() {
        Config config = ConfigParser.getConfig();
        if (config.backupScheduling.enabled) {
            ZonedDateTime now = ZonedDateTime.now(config.advanced.dateTimezone);
            ZonedDateTime nextBackupDate = Scheduler.getBackupDatesList().stream()
                    .filter(zdt -> zdt.isAfter(now))
                    .min(Comparator.naturalOrder())
                    .orElseThrow(NoSuchElementException::new);
            DateTimeFormatter backupDateFormatter = DateTimeFormatter.ofPattern(intl("next-schedule-backup-format"), config.advanced.dateLanguage);
            return intl("next-schedule-backup").replaceAll("%DATE", nextBackupDate.format(backupDateFormatter));
        } else if (config.backupStorage.delay != -1) {
            return intl("next-backup").replaceAll("%TIME", String.valueOf(LocalDateTime.now().until(nextIntervalBackupTime, ChronoUnit.MINUTES)));
        } else {
            return intl("auto-backups-disabled");
        }
    }

    public static void updateNextIntervalBackupTime() {
        nextIntervalBackupTime = LocalDateTime.now().plusMinutes(ConfigParser.getConfig().backupStorage.delay);
    }

    public static boolean wasLastBackupSuccessful() {
        return lastBackupSuccessful;
    }

    @NotNull
    @Contract(pure = true)
    private static String getSocketAddress(@NotNull ExternalBackupSource externalBackup) {
        return externalBackup.hostname + "-" + externalBackup.port;
    }

    @Nullable
    private static String getTempFolderName(ExternalBackupSource externalBackup) {
        StringBuilder base = new StringBuilder(getSocketAddress(externalBackup));
        base.append(externalBackup.username);
        base.append(externalBackup.password);
        if (externalBackup instanceof ExternalFTPSource) {
            ExternalFTPSource ftpSource = (ExternalFTPSource) externalBackup;
            base.append(ftpSource.baseDirectory);
            String hash2 = hash(base.toString());
            if (hash2 == null) return null;
            return "ftp-" + hash2;
        } else if (externalBackup instanceof ExternalMySQLSource) {
            ExternalMySQLSource mysqlSource = (ExternalMySQLSource) externalBackup;
            for (MySQLDatabaseBackup database : mysqlSource.databaseList) {
                base.append(database.name);
            }
            String hash3 = hash(base.toString());
            if (hash3 == null) return null;
            return "mysql-" + hash3;
        }
        return null;
    }

    @Nullable
    private static String hash(String input) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            MessageUtil.sendConsoleException(e);
            return null;
        }
        byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
