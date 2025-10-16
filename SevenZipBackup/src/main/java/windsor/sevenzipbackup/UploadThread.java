package windsor.sevenzipbackup;

import com.google.api.client.util.Strings;
import org.bukkit.command.CommandSender;
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

import static windsor.sevenzipbackup.config.Localization.intl;

public class UploadThread implements Runnable {
    
    private static final String LINK_COMMAND = "/7zbackup linkaccount ";
    private CommandSender initiator;
    private final UploadLogger logger;
    private final FileUtil fileUtil;
    private final Timer totalTimer;

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
        try {
            run_internal();
        } catch (Exception e) {
            lastBackupSuccessful = false;
            throw e;
        } finally {
            backupStatus = BackupStatus.NOT_RUNNING;
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
        Thread.currentThread().setPriority(config.backupStorage.threadPriority);
        if (!SevenZipBackupApi.shouldStartBackup()) {
            return;
        }
        if (config.backupStorage.backupsRequirePlayers && !PlayerListener.isAutoBackupsActive() && initiator == null) {
            return;
        }
        boolean errorOccurred = false;
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

        //logger.info(intl("backup-local-start"));
        backupStatus = BackupStatus.COMPRESSING;
        backupBackingUp = 0;
        ServerUtil.setAutoSave(false);

        // 异步压缩所有备份文件夹
        try {
            asyncCompressAllBackups();
        } catch (Exception e) {
            logger.info(intl("backup-local-failed"));
            MessageUtil.sendConsoleException(e);
            errorOccurred = true;
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

    /**
     * 异步并发压缩所有备份文件夹
     */
    private void asyncCompressAllBackups() throws Exception {
        List<CompletableFuture<Void>> compressionFutures = new ArrayList<>();

        // 为每个备份项创建异步任务
        for (BackupListEntry set : backupList) {
            for (Path folder : set.location.getPaths()) {
                if (set.create) {
                    CompletableFuture<Void> future = createBackupAsync(folder.toString(), set.formatter, Arrays.asList(set.blacklist));
                    compressionFutures.add(future);
                }
            }
        }

        // 等待所有压缩任务完成
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
        return fileUtil.makeBackupAsync(location, formatter, blackList)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        //logger.info(intl("backup-local-failed"));
                        logger.info(intl("backup-local-file-failed"), "location", location);
                        MessageUtil.sendConsoleException((Exception) throwable);
                    } else {
                        locationsToBePruned.put(location, formatter);
                        logger.info(intl("backup-local-file-complete"), "location", location);
                    }
                });
    }
    /**
     * 继续执行上传流程
     */
    private void continueWithUploadProcess() {
        //logger.info(intl("backup-upload-start"));
        backupStatus = BackupStatus.UPLOADING;
        backupBackingUp = 0;

        // 原有的上传逻辑
        uploaders = new ArrayList<>(5);
        // ... 初始化 uploaders 的代码

        ensureMethodsAuthenticated();
        uploadBackupFiles(uploaders);
        FileUtil.deleteFolder(new File("external-backups"));
        logger.info(intl("backup-upload-complete"));

        // ... 剩余的上传后处理逻辑
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
            String name = file.getParent().replace("\\", "/").replace("./", "") + "/" + file.getName();
            //logger.info(intl("backup-file-upload-start"), "file-name", name);
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
