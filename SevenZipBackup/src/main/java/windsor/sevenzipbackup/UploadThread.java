package windsor.sevenzipbackup;

import com.google.api.client.util.Strings;
import org.bukkit.Bukkit;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static windsor.sevenzipbackup.config.Localization.intl;

public class UploadThread implements Runnable {

    private static final String LINK_COMMAND = "/7zbackup linkaccount ";
    private CommandSender initiator;
    private final UploadLogger logger;
    private final FileUtil fileUtil;
    private final Timer totalTimer;

    // BossBar相关字段 - 修改为文件级别的进度跟踪
    private static BossBar backupBossBar;
    private static final AtomicInteger totalFilesToBackup = new AtomicInteger(0); // 总文件数（所有备份任务累加）
    private static final AtomicInteger totalFilesProcessed = new AtomicInteger(0); // 已处理的文件数

    // 备份任务计数
    private static int totalBackupTasks = 0; // 总备份任务数（文件夹数量）
    private static final AtomicInteger completedBackupTasks = new AtomicInteger(0); // 已完成备份任务数


    /**
     * The current status of the backup thread
     */
    enum BackupStatus {
        /**
         * The backup thread isn't running
         */
        NOT_RUNNING,

        /**
         * The backup thread is compressing the files to be backed up.
         */
        COMPRESSING,

        STARTING,
        PRUNING,
        /**
         * The backup thread is uploading the files
         */
        UPLOADING
    }

    /**
     * List of {@code Uploaders} to upload the backups to
     */
    private ArrayList<Uploader> uploaders;
    /**
     * List of locations to be pruned that were successfully backed up.
     */
    private final Map<String, LocalDateTimeFormatter> locationsToBePruned = new HashMap<>(10);

    /**
     * The list of items to be backed up by the backup thread.
     */
    private List<BackupListEntry> backupList;

    /**
     * The {@code BackupStatus} of the backup thread
     */
    private static BackupStatus backupStatus = BackupStatus.NOT_RUNNING;

    private static LocalDateTime nextIntervalBackupTime;
    private static boolean lastBackupSuccessful = true;

    /**
     * The backup currently being backed up by the
     */
    private static int backupBackingUp = 0;

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

    /**
     * Creates an instance of the {@code UploadThread} object
     */
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

    /**
     * Creates an instance of the {@code UploadThread} object
     * @param initiator the player who initiated the backup
     */
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

    /**
     * 创建BossBar
     */
    private void createBossBar() {
        Config config = ConfigParser.getConfig();

        // 检查是否启用BossBar
        if (!config.bossBarConfig.showBossBarProgress) {
            return;
        }

        Bukkit.getScheduler().runTask(ConfigParser.getPluginInstance(), () -> {
            if (backupBossBar != null) {
                backupBossBar.removeAll();
            }

            // 使用翻译文本
            String title = intl("bossbar-create");

            backupBossBar = Bukkit.createBossBar(
                    ChatColor.translateAlternateColorCodes('&', title),
                    config.bossBarConfig.bossBarColor,
                    config.bossBarConfig.bossBarStyle
            );
            backupBossBar.setProgress(0.0);

            // 为所有在线玩家显示BossBar
            for (Player player : Bukkit.getOnlinePlayers()) {
                backupBossBar.addPlayer(player);
            }

            logger.info("开始备份，共 " + totalBackupTasks + " 个文件夹需要备份");
        });
    }

    /**
     * 更新BossBar进度 - 基于文件级别
     */
    private void updateBossBarProgress() {
        Config config = ConfigParser.getConfig();

        // 检查是否启用BossBar
        if (!config.bossBarConfig.showBossBarProgress) {
            return;
        }

        Bukkit.getScheduler().runTask(ConfigParser.getPluginInstance(), () -> {
            if (backupBossBar == null) return;

            int totalFiles = totalFilesToBackup.get();
            int processedFiles = totalFilesProcessed.get();

            // 计算进度
            double progress;
            String title;

            if (totalFiles <= 0) {
                int completedTasks = completedBackupTasks.get();
                progress = totalBackupTasks > 0 ? (double) completedTasks / totalBackupTasks : 0.0;
                title = intl("bossbar-preparing");

            } else {
                // 有文件统计，显示文件进度
                progress = (double) processedFiles / totalFiles;

                // 使用格式化字符串替换占位符
                title = intl("bossbar-progress")
                        .replace("<progress>", String.format("%.2f", progress * 100))
                        .replace("<num>", String.valueOf(processedFiles))
                        .replace("<total>", String.valueOf(totalFiles));
            }

            backupBossBar.setProgress(Math.min(progress, 1.0));
            backupBossBar.setTitle(ChatColor.translateAlternateColorCodes('&', title));
        });
    }

    /**
     * 将玩家添加到BossBar（如果当前正在备份）
     * @param player 要添加的玩家
     */
    public static void addPlayerToBossBar(Player player) {
        Config config = ConfigParser.getConfig();

        // 检查是否启用BossBar
        if (!config.bossBarConfig.showBossBarProgress) {
            return;
        }

        Bukkit.getScheduler().runTask(ConfigParser.getPluginInstance(), () -> {
            if (backupBossBar != null && player != null && player.isOnline()) {
                backupBossBar.addPlayer(player);
            }
        });
    }

    /**
     * 移除BossBar
     */
    private void removeBossBar() {
        Bukkit.getScheduler().runTask(ConfigParser.getPluginInstance(), () -> {
            if (backupBossBar != null) {
                backupBossBar.removeAll();
                backupBossBar = null;
            }

            // 重置计数器
            totalFilesToBackup.set(0);
            totalFilesProcessed.set(0);
            totalBackupTasks = 0;
            completedBackupTasks.set(0);
        });
    }

    /**
     * 检查是否正在备份
     * @return 如果正在备份返回true
     */
    public static boolean isBackupInProgress() {
        return backupBossBar != null;
    }

    /**
     * 增加总文件数（当一个新的备份任务完成文件列表准备时调用）
     */
    public static void addFilesToTotal(int fileCount) {
        totalFilesToBackup.addAndGet(fileCount);
    }

    /**
     * 增加已处理文件数（每处理完一个文件时调用）
     */
    public static void incrementProcessedFiles() {
        totalFilesProcessed.incrementAndGet();
    }

    /**
     * 增加已完成备份任务数
     */
    public static void incrementCompletedTasks() {
        completedBackupTasks.incrementAndGet();
    }

    /**
     * 清理BossBar（插件禁用时调用）
     */
    public static void cleanupBossBar() {
        Bukkit.getScheduler().runTask(ConfigParser.getPluginInstance(), () -> {
            if (backupBossBar != null) {
                backupBossBar.removeAll();
                backupBossBar = null;
            }
        });
    }

    /**
     * Starts a backup
     */
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
                logger.info("无法运行备份任务：");
                e.printStackTrace();
            }
            lastBackupSuccessful = false;
            throw e;
        } finally {
            backupStatus = BackupStatus.NOT_RUNNING;
            // 无论成功与否，都移除BossBar
            removeBossBar();
            if (lastBackupSuccessful) {
                SevenZipBackupApi.backupDone();
            } else {
                SevenZipBackupApi.backupError();
            }
        }
    }

    /**
     * actual backup logic
     */
    void run_internal() {
        Config config = ConfigParser.getConfig();
        totalTimer.start();
        backupStatus = BackupStatus.STARTING;
        if (!locationsToBePruned.isEmpty()) {
            locationsToBePruned.clear();
        }
        if (initiator == null) {
            updateNextIntervalBackupTime();
        }
        if(ConfigParser.getConfig().advanced.debugEnabled){
            logger.info("为备份任务设定线程优先级");
        }
        Thread.currentThread().setPriority(config.backupStorage.threadPriority);
        if (!SevenZipBackupApi.shouldStartBackup()) {
            return;
        }
        if (config.backupStorage.backupsRequirePlayers && !PlayerListener.isAutoBackupsActive() && initiator == null) {
            return;
        }
        boolean errorOccurred = false;
        if(ConfigParser.getConfig().advanced.debugEnabled){
            logger.info("备份条件已通过检查");
        }
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
        ServerUtil.setAutoSave(false);

        // 计算总备份任务数（需要备份的文件夹数量）
        totalBackupTasks = 0;
        for (BackupListEntry set : backupList) {
            if (set.create) {
                totalBackupTasks += set.location.getPaths().size();
            }
        }

        // 重置计数器
        totalFilesToBackup.set(0);
        totalFilesProcessed.set(0);
        completedBackupTasks.set(0);

        // 在开始异步压缩前创建BossBar
        if (totalBackupTasks > 0) {
            createBossBar();
            // 初始更新一次进度条
            updateBossBarProgress();
        }

        // 异步压缩所有备份文件夹
        try {
            asyncCompressAllBackups();
        } catch (Exception e) {
            logger.info(intl("backup-local-failed"));
            MessageUtil.sendConsoleException(e);
            if(ConfigParser.getConfig().advanced.debugEnabled){
                logger.info("异步备份任务失败！");
                e.printStackTrace();
            }
            errorOccurred = true;
        }
        if(ConfigParser.getConfig().advanced.debugEnabled){
            logger.info("备份压缩任务完成");
        }
        ServerUtil.setAutoSave(true);

        if (!errorOccurred) {
            logger.info(intl("backup-local-complete"));
            // 继续后续的上传流程...
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

    /**
     * 异步并发压缩所有备份文件夹
     */
    private void asyncCompressAllBackups() throws Exception {
        List<CompletableFuture<Void>> compressionFutures = new ArrayList<>();
        // 为每个备份项创建异步任务
        for (BackupListEntry set : backupList) {
            for (Path folder : set.location.getPaths()) {
                if(ConfigParser.getConfig().advanced.debugEnabled){
                    logger.info("为备份项创建异步任务："+ folder.toString());
                }
                if (set.create) {
                    CompletableFuture<Void> future = createBackupAsync(folder.toString(), set.formatter, Arrays.asList(set.blacklist));
                    compressionFutures.add(future);
                }
            }
        }

        if (compressionFutures.isEmpty()) {
            return;
        }

        if(ConfigParser.getConfig().advanced.debugEnabled){
            logger.info("等待所有压缩任务完成...");
        }

        // 创建所有任务完成后的回调
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                compressionFutures.toArray(new CompletableFuture[0])
        );

        // 设置超时时间（例如2小时）
        allFutures.get(2, java.util.concurrent.TimeUnit.HOURS);
    }

    /**
     * 异步创建备份文件
     */
    private CompletableFuture<Void> createBackupAsync(String location, LocalDateTimeFormatter formatter, List<String> blackList) {
        return fileUtil.makeBackupAsync(location, formatter, blackList, new BackupProgressCallback() {
            @Override
            public void onFileListPrepared(int fileCount) {
                // 累加文件总数
                addFilesToTotal(fileCount);
                updateBossBarProgress();
                logger.info("备份位置 " + location + " 发现 " + fileCount + " 个文件需要备份");
            }

            @Override
            public void onFileProcessed() {
                // 增加已处理文件数并更新进度
                incrementProcessedFiles();
                updateBossBarProgress();
            }

            @Override
            public void onBackupComplete() {
                // 增加已完成任务数
                incrementCompletedTasks();
                updateBossBarProgress();
                locationsToBePruned.put(location, formatter);
                logger.info(intl("backup-local-file-complete"), "location", location);
            }

            @Override
            public void onError(Throwable throwable) {
                // 即使出错，也计入已完成任务
                incrementCompletedTasks();
                updateBossBarProgress();
                logger.info(intl("backup-local-file-failed"), "location", location);
                MessageUtil.sendConsoleException((Exception) throwable);
            }
        });
    }
    /**
     * 继续执行上传流程
     */
    private void continueWithUploadProcess() {
        logger.info(intl("backup-upload-start"));
        backupStatus = BackupStatus.UPLOADING;
        backupBackingUp = 0;

        // 原有的上传逻辑
        uploaders = new ArrayList<>(5);
        // ... 初始化 uploaders 的代码

        ensureMethodsAuthenticated();
        uploadBackupFiles(uploaders);
        FileUtil.deleteFolder(new File("external-backups"));
        logger.info(intl("backup-upload-complete"));
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
            for (Path folder : set.location.getPaths()) {
                uploadFile(folder.toString(), set.formatter, uploaders);
            }
        }
    }

    /**
     * Uploads the most recent backup file to the specified uploaders
     * @param location path to the folder
     * @param formatter save format configuration
     * @param uploaders services to upload to
     */
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

    /**
     * Downloads files from an FTP server and stores them within the external-backups temporary folder, using the specified external backup settings.
     * @param externalBackup the external backup settings
     */
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

    /**
     * Downloads databases from a MySQL server and stores them within the external-backups temporary folder, using the specified external backup settings.
     * @param externalBackup the external backup settings
     */
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

    /**
     * Gets the current status of the backup thread
     * @return the status of the backup thread as a {@code String}
     */
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
        int backupIndex = Math.min(backupNumber, backupList.length - 1);

        String backupSetName = backupList[backupIndex].location.toString();

        return message
                .replace("<set-name>", backupSetName)
                .replace("<set-num>", String.valueOf(backupNumber+1))
                .replace("<set-count>", String.valueOf(backupList.length));
    }

    /**
     * Gets the date/time of the next automatic backup, if enabled.
     * @return the time and/or date of the next automatic backup formatted using the messages in the {@code config.yml}
     */
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

    /**
     * Sets the time of the next interval-based backup to the current time + the configured interval.
     */
    public static void updateNextIntervalBackupTime() {
        nextIntervalBackupTime = LocalDateTime.now().plusMinutes(ConfigParser.getConfig().backupStorage.delay);
    }

    public static boolean wasLastBackupSuccessful() {
        return lastBackupSuccessful;
    }

    /**
     * Gets the socket address (ipaddress/hostname:port) of an external backup server based on the specified settings.
     * @param externalBackup the external backup settings
     * @return the socket address
     */
    @NotNull
    @Contract (pure = true)
    private static String getSocketAddress(@NotNull ExternalBackupSource externalBackup) {
        return externalBackup.hostname + "-" + externalBackup.port;
    }

    /**
     * Generates the name for a folder based on the specified external backup settings to be stored within the external-backups temporary folder.
     * @param externalBackup the external backup settings
     * @return the folder name
     */
    @Nullable
    private static String getTempFolderName(ExternalBackupSource externalBackup) {
        // There is probably a better way to do this without modifying the config to have unique identifiers for each external backup.
        StringBuilder base = new StringBuilder(getSocketAddress(externalBackup));
        base.append(externalBackup.username);
        base.append(externalBackup.password);
        if (externalBackup instanceof ExternalFTPSource) {
            ExternalFTPSource ftpSource = (ExternalFTPSource) externalBackup;
            base.append(ftpSource.baseDirectory);
            String hash2 = hash(base.toString());
            if (hash2 == null) {
                return null;
            }
            return "ftp-" + hash2;
        } else if (externalBackup instanceof ExternalMySQLSource) {
            ExternalMySQLSource mysqlSource = (ExternalMySQLSource) externalBackup;
            for (MySQLDatabaseBackup database : mysqlSource.databaseList) {
                base.append(database.name);
            }
            String hash3 = hash(base.toString());
            if (hash3 == null) {
                return null;
            }
            return "mysql-" + hash3;
        } else {
            return null;
        }
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
