package windsor.sevenzipbackup.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import windsor.sevenzipbackup.BackupProgressCallback;
import windsor.sevenzipbackup.UploadThread.UploadLogger;
import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.config.ConfigParser.Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.tukaani.xz.LZMA2Options;
import net.openhft.affinity.AffinityLock;
import windsor.sevenzipbackup.config.configSections.BackupStorage;

import static windsor.sevenzipbackup.config.Localization.intl;

public class FileUtil {
    private static final String NAME_KEYWORD = "%NAME";

    private final UploadLogger logger;

    private static ExecutorService compressionExecutor = createCompressionExecutor();
    private static ExecutorService fileListExecutor = createFileListExecutor();

    public FileUtil(UploadLogger logger) {
        this.logger = logger;
    }

    /**
     * 创建可配置的压缩线程池
     */
    private static ExecutorService createCompressionExecutor() {
        Config config = ConfigParser.getConfig();
        int threadCount = config.backupStorage.threadCounts;
        CompressionThreadFactory threadFactory = new CompressionThreadFactory(
                config.backupStorage.threadPriority,
                BackupStorage.CPUAffinity
        );

        return Executors.newFixedThreadPool(threadCount, threadFactory);
    }

    /**
     * 创建专门用于文件列表生成的线程池
     */
    private static ExecutorService createFileListExecutor() {
        Config config = ConfigParser.getConfig();
        int threadCount = config.backupStorage.threadCounts;
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(0);

            @Override
            public Thread newThread(@NotNull Runnable r) {
                String namePrefix = "filelist-worker-";
                Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            }
        };

        return Executors.newFixedThreadPool(threadCount, threadFactory);
    }

    /**
     * 自定义线程工厂，用于设置线程优先级和亲和性
     */
    private static class CompressionThreadFactory implements ThreadFactory {
        private final int threadPriority;
        private final List<Integer> threadAffinity;  // 改为 List<Integer>
        private final AtomicInteger threadNumber = new AtomicInteger(0);

        public CompressionThreadFactory(int threadPriority, List<Integer> threadAffinity) {
            this.threadPriority = threadPriority;
            this.threadAffinity = threadAffinity;
        }

        @Override
        public Thread newThread(@NotNull Runnable r) {
            String namePrefix = "compression-worker-";
            Thread t = new Thread(() -> {
                // 在线程开始时设置CPU亲和性
                if (threadAffinity != null && !threadAffinity.isEmpty()) {
                    setThreadAffinity(threadAffinity);
                }
                // 执行原始任务
                r.run();
            }, namePrefix + threadNumber.getAndIncrement());

            // 设置线程优先级
            if (threadPriority >= Thread.MIN_PRIORITY && threadPriority <= Thread.MAX_PRIORITY) {
                t.setPriority(threadPriority);
            } else {
                t.setPriority(Thread.NORM_PRIORITY);
            }

            t.setDaemon(false);
            return t;
        }

        /**
         * 使用OpenHFT Affinity库设置线程CPU亲和性（跨平台）
         */
        private void setThreadAffinity(List<Integer> affinityCores) {
            try {
                int threadIndex = threadNumber.get() - 1;
                int coreIndex = threadIndex % affinityCores.size();
                int targetCore = affinityCores.get(coreIndex);

                AffinityLock lock = AffinityLock.acquireLock(targetCore);

                if (lock != null && lock.isAllocated()) {
                    if(ConfigParser.getConfig().advanced.debugEnabled) {
                        System.out.println("Successfully set thread " + Thread.currentThread().getName() +
                                " to CPU core " + targetCore + " using OpenHFT Affinity");
                    }

                    Runtime.getRuntime().addShutdownHook(new Thread(lock::release));
                } else {
                    System.err.println("Failed to acquire affinity lock for CPU core " + targetCore +
                            " for thread " + Thread.currentThread().getName());
                }

            } catch (Exception e) {
                System.err.println("Error setting thread affinity using OpenHFT: " + e.getMessage());
                if(ConfigParser.getConfig().advanced.debugEnabled) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 重新初始化线程池（当配置改变时调用）
     */
    public static void reinitializeExecutor() {
        if (compressionExecutor != null && !compressionExecutor.isShutdown()) {
            compressionExecutor.shutdown();
            try {
                // 等待现有任务完成
                if (!compressionExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    compressionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                compressionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        compressionExecutor = createCompressionExecutor();

        // 同时重新初始化文件列表线程池
        if (fileListExecutor != null && !fileListExecutor.isShutdown()) {
            fileListExecutor.shutdown();
            try {
                if (!fileListExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    fileListExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                fileListExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        fileListExecutor = createFileListExecutor();
    }

    /**
     * Gets the local backups in the specified folder as a {@code TreeMap} with their creation date and a reference to them.
     *
     * @param location  the location of the folder containing the backups
     * @param formatter the format of the file name
     * @return The list of backups
     */
    public TreeMap<Long, File> getLocalBackups(String location, LocalDateTimeFormatter formatter) {
        location = escapeBackupLocation(location);
        TreeMap<Long, File> backupList = new TreeMap<>();
        String path = new File(ConfigParser.getConfig().backupStorage.localDirectory).getAbsolutePath() + "/" + location;
        File[] files = new File(path).listFiles();
        if (files == null) {
            return backupList;
        }
        for (File file : files) {
            if (file.getName().endsWith(".7z")) {
                backupList.put((file.lastModified() / 1000), file);
            }
        }
        return backupList;
    }

    /**
     * 异步创建备份压缩文件（包含异步文件列表生成）
     */
    public CompletableFuture<Void> makeBackupAsync(@NotNull String location, LocalDateTimeFormatter formatter, List<String> blacklistGlobs, BackupProgressCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            // 第一阶段：准备工作和文件列表生成
            try {
                Config config = ConfigParser.getConfig();
                if (location.charAt(0) == '/') {
                    throw new IllegalArgumentException("Location cannot start with a slash");
                }
                if(ConfigParser.getConfig().advanced.debugEnabled){
                    logger.info("处理文件夹路径中："+location);
                }
                ZonedDateTime now = ZonedDateTime.now(config.advanced.dateTimezone);
                String fileName = formatter.format(now);
                String subFolderName = location;
                if (isBaseFolder(subFolderName)) {
                    subFolderName = "root";
                }
                File path = new File(escapeBackupLocation(config.backupStorage.localDirectory + "/" + subFolderName));
                if (!path.exists()) {
                    path.mkdirs();
                }

                // 准备黑名单
                if(ConfigParser.getConfig().advanced.debugEnabled){
                    logger.info("处理黑名单文件排除中");
                }
                List<BlacklistEntry> blacklist = new ArrayList<>();
                for (String blacklistGlob : blacklistGlobs) {
                    BlacklistEntry blacklistEntry = new BlacklistEntry(
                            blacklistGlob,
                            FileSystems.getDefault().getPathMatcher("glob:" + blacklistGlob)
                    );
                    blacklist.add(blacklistEntry);
                }

                // 处理文件名
                if (fileName.contains(NAME_KEYWORD)) {
                    int lastSeparatorIndex = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
                    String lastFolderName = location.substring(lastSeparatorIndex + 1);
                    fileName = fileName.replace(NAME_KEYWORD, lastFolderName);
                }

                final String finalFileName = fileName.replace(".zip", ".7z");
                final String outputPath = path.getPath() + "/" + finalFileName;

                // 返回准备结果，用于下一阶段
                return new BackupPreparation(location, outputPath, blacklist, finalFileName);
            } catch (Exception e) {
                if(ConfigParser.getConfig().advanced.debugEnabled){
                    logger.info("无法处理备份文件夹："+location);
                    e.printStackTrace();
                }
                throw new CompletionException(e);
            }
        }, fileListExecutor).thenComposeAsync(preparation -> {
            // 第二阶段：异步生成文件列表
            return generateFileListAsync(preparation.location, preparation.blacklist)
                    .thenApplyAsync(fileList -> {
                        // 记录黑名单和备份文件夹统计信息
                        for (BlacklistEntry blacklistEntry : fileList.getBlacklist()) {
                            String globPattern = blacklistEntry.getGlobPattern();
                            int blacklistedFiles = blacklistEntry.getBlacklistedFiles();
                            if (blacklistedFiles > 0) {
                                logger.info(
                                        intl("local-backup-backlisted"),
                                        "blacklisted-files-count", String.valueOf(blacklistedFiles),
                                        "glob-pattern", globPattern);
                            }
                        }
                        int filesInBackupFolder = fileList.getFilesInBackupFolder();
                        if (filesInBackupFolder > 0) {
                            logger.info(
                                    intl("local-backup-in-backup-folder"),
                                    "files-in-backup-folder-count", String.valueOf(filesInBackupFolder));
                        }

                        // 通知文件列表准备完成，传递文件数量
                        if (callback != null) {
                            callback.onFileListPrepared(fileList.getList().size());
                        }

                        return new BackupData(preparation, fileList);
                    }, compressionExecutor);
        }, fileListExecutor).thenComposeAsync(backupData -> {
            // 第三阶段：异步压缩
            if(ConfigParser.getConfig().advanced.debugEnabled){
                logger.info("进入异步压缩环节：");
            }
            return ZipItAsync(backupData.preparation.location,
                    backupData.preparation.outputPath,
                    backupData.fileList,
                    callback)
                    .whenComplete((result, throwable) -> {
                        if (throwable == null) {
                            if (callback != null) {
                                callback.onBackupComplete();
                            }
                        } else {
                            if (callback != null) {
                                callback.onError(throwable);
                            }
                        }
                    });
        }, compressionExecutor);
    }

    /**
     * 异步生成文件列表
     */
    private CompletableFuture<BackupFileList> generateFileListAsync(String inputFolderPath, List<BlacklistEntry> blacklist) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BackupFileList fileList = new BackupFileList(blacklist);
                generateFileList(new File(inputFolderPath), inputFolderPath, fileList);
                return fileList;
            } catch (Exception e) {
                if(ConfigParser.getConfig().advanced.debugEnabled){
                    logger.info("生成待备份文件列表错误："+inputFolderPath);
                    e.printStackTrace();
                }
                throw new CompletionException(e);
            }
        }, fileListExecutor);
    }

    /**
     * 关闭线程池
     */
    public static void shutdown() {
        if (compressionExecutor != null) {
            compressionExecutor.shutdown();
            try {
                if (!compressionExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    compressionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                compressionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (fileListExecutor != null) {
            fileListExecutor.shutdown();
            try {
                if (!fileListExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    fileListExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                fileListExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Deletes the oldest files in the specified folder past the number to retain locally.
     * <p>
     * The number of files to retain locally is specified by the user in the {@code config.yml}
     * @param location the location of the folder containing the backups
     * @param formatter the format of the file name
     */
    public void purgeLocalBackups(String location, LocalDateTimeFormatter formatter) {
        location = escapeBackupLocation(location);
        if (isBaseFolder(location)) {
            location = "root";
        }
        logger.log(intl("local-backup-pruning-start"), "location", location);
        int localKeepCount = ConfigParser.getConfig().backupStorage.localKeepCount;
        if (localKeepCount == -1) {
            logger.info(intl("local-backup-no-limit"));
        } else {
            try {
                TreeMap<Long, File> backupList = getLocalBackups(location, formatter);
                String size = String.valueOf(backupList.size());
                String keepCount = String.valueOf(localKeepCount);
                if (backupList.size() > localKeepCount) {
                    logger.info(intl("local-backup-limit-reached"),
                            "backup-count", size,
                            "backup-limit", keepCount);
                } else {
                    logger.info(intl("local-backup-limit-not-reached"),
                            "backup-count", size,
                            "backup-limit", keepCount);
                    return;
                }
                while (backupList.size() > localKeepCount) {
                    File fileToDelete = backupList.descendingMap().lastEntry().getValue();
                    long dateOfFile = backupList.descendingMap().lastKey();
                    if (!fileToDelete.delete()) {
                        logger.log(intl("local-backup-file-failed-to-delete"),
                                "local-backup-name", fileToDelete.getName());
                    } else {
                        logger.info(intl("local-backup-file-deleted"),
                                "local-backup-name", fileToDelete.getName());
                    }
                    backupList.remove(dateOfFile);
                }
                logger.log(intl("local-backup-pruning-complete"), "location", location);
            } catch (Exception e) {
                logger.log(intl("local-backup-failed-to-delete"));
                MessageUtil.sendConsoleException(e);
            }
        }
    }

    /**
     * Creates 7z files in the specified folder into the specified file location using LZMA2 algorithm and solid compression.
     *
     * @param inputFolderPath the path of the zip file to create
     * @param outputFilePath  the path of the folder to put it in
     * @param fileList        file to include in the zip
     * @param callback        进度回调
     */
    private void ZipIt(String inputFolderPath, String outputFilePath, BackupFileList fileList, BackupProgressCallback callback) throws Exception {
        if(ConfigParser.getConfig().advanced.debugEnabled)
            logger.info("正在为"+inputFolderPath+"创建压缩文件");
        byte[] buffer = new byte[65536]; // 64KB 缓冲区
        try (SevenZOutputFile sevenZOutput = new SevenZOutputFile(new File(outputFilePath))) {
            SevenZMethodConfiguration methodConfig = new SevenZMethodConfiguration(
                    SevenZMethod.LZMA2,
                    new LZMA2Options(ConfigParser.getConfig().backupStorage.zipCompression)
            );
            sevenZOutput.setContentMethods(Collections.singletonList(methodConfig));
            if(ConfigParser.getConfig().advanced.debugEnabled){
                logger.info("设定压缩级别成功");
            }

            List<String> files = fileList.getList();
            for (int i = 0; i < files.size(); i++) {
                String file = files.get(i);
                String entryName = file.replace(File.separator, "/");
                String filePath = new File(inputFolderPath, file).getPath();

                File sourceFile = new File(filePath);
                if (!sourceFile.exists()) {
                    continue;
                }

                SevenZArchiveEntry entry = new SevenZArchiveEntry();
                entry.setName(entryName);
                entry.setSize(sourceFile.length());

                try {
                    BasicFileAttributes attrs = Files.readAttributes(sourceFile.toPath(), BasicFileAttributes.class);
                    entry.setCreationTime(attrs.creationTime());
                    entry.setAccessTime(attrs.lastAccessTime());
                    entry.setLastModifiedTime(attrs.lastModifiedTime());
                } catch (Exception e) {
                    // 忽略属性错误
                }

                sevenZOutput.putArchiveEntry(entry);

                try (FileInputStream fileInputStream = new FileInputStream(sourceFile)) {
                    int len;
                    while ((len = fileInputStream.read(buffer)) > 0) {
                        sevenZOutput.write(buffer, 0, len);
                    }
                } catch (Exception e) {
                    if (!filePath.endsWith(".lock")) {
                        logger.info(
                                intl("local-backup-failed-to-include"),
                                "file-path", filePath);
                        if(ConfigParser.getConfig().advanced.debugEnabled){
                            e.printStackTrace();
                        }
                    }
                }
                sevenZOutput.closeArchiveEntry();

                // 每个文件处理完成后调用回调
                if (callback != null) {
                    callback.onFileProcessed();
                }
            }
        }
    }

    /**
     * 异步创建7z压缩文件
     */
    private CompletableFuture<Void> ZipItAsync(String inputFolderPath, String outputFilePath, BackupFileList fileList, BackupProgressCallback callback) {
        return CompletableFuture.runAsync(() -> {
            try {
                ZipIt(inputFolderPath, outputFilePath, fileList, callback);
            } catch (Exception e) {
                if(ConfigParser.getConfig().advanced.debugEnabled){
                    logger.info("异步创建压缩文件错误："+inputFolderPath);
                    e.printStackTrace();
                }
                throw new CompletionException(e);
            }
        }, compressionExecutor);
    }

    /**
     * A list of files to put in a zip file
     * Mutable.
     */
    private static class BackupFileList {
        private int filesInBackupFolder;
        private final List<String> fileList;
        private final List<BlacklistEntry> blacklist;

        @Contract(pure = true)
        private BackupFileList(List<BlacklistEntry> blacklist) {
            this.filesInBackupFolder = 0;
            this.fileList = new ArrayList<>();
            this.blacklist = blacklist;
        }

        void incFilesInBackupFolder() {
            filesInBackupFolder++;
        }

        int getFilesInBackupFolder() {
            return filesInBackupFolder;
        }

        void appendToList(String file) {
            fileList.add(file);
        }

        List<String> getList() {
            return fileList;
        }

        List<BlacklistEntry> getBlacklist() {
            return blacklist;
        }
    }

    /**
     * 用于在异步阶段间传递备份准备数据的内部类
     */
    private static class BackupPreparation {
        final String location;
        final String outputPath;
        final List<BlacklistEntry> blacklist;
        final String fileName;

        BackupPreparation(String location, String outputPath, List<BlacklistEntry> blacklist, String fileName) {
            this.location = location;
            this.outputPath = outputPath;
            this.blacklist = blacklist;
            this.fileName = fileName;
        }
    }

    /**
     * 用于在异步阶段间传递备份数据的内部类
     */
    private static class BackupData {
        final BackupPreparation preparation;
        final BackupFileList fileList;

        BackupData(BackupPreparation preparation, BackupFileList fileList) {
            this.preparation = preparation;
            this.fileList = fileList;
        }
    }

    /**
     * Adds the specified file or folder to the list of files to put in the zip created from the specified folder.
     *
     * @param file            the file or folder to add
     * @param inputFolderPath the path of the folder to create the zip
     * @param fileList        the list of files to add the specified file or folder to.
     */
    private void generateFileList(@NotNull File file, String inputFolderPath, BackupFileList fileList) throws Exception {
        BasicFileAttributes fileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        if (fileAttributes.isRegularFile()) {
            // Verify not backing up previous backups
            if (file.getCanonicalPath().startsWith(new File(ConfigParser.getConfig().backupStorage.localDirectory).getCanonicalPath())) {
                fileList.incFilesInBackupFolder();
                return;
            }
            Path relativePath = Paths.get(inputFolderPath).relativize(file.toPath());
            for (BlacklistEntry blacklistEntry : fileList.getBlacklist()) {
                if (blacklistEntry.getPathMatcher().matches(relativePath)) {
                    blacklistEntry.incBlacklistedFiles();
                    return;
                }
            }
            fileList.appendToList(relativePath.toString());
        } else if (fileAttributes.isDirectory()) {
            for (String filename : Objects.requireNonNull(file.list())) {
                generateFileList(new File(file, filename), inputFolderPath, fileList);
            }
        } else {
            logger.info(intl("local-backup-failed-to-include"),
                    "file-path", file.getAbsolutePath()
            );
        }
    }

    /**
     * Removes ".." from the location string to keep the location's backup folder within the local-save-directory.
     *
     * @param location the unescaped location
     * @return the escaped location
     */
    @NotNull
    @Contract(pure = true)
    private static String escapeBackupLocation(@NotNull String location) {
        return location.replace("../", "");
    }

    /**
     * Finds all folders that match a glob
     *
     * @param glob     the glob to search
     * @param rootPath the path to start searching from
     * @return List of all folders that match this glob under rootPath
     */
    public static List<Path> generateGlobFolderList(String glob, String rootPath) {
        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:./" + glob);
        List<Path> list = new ArrayList<>();

        try {
            // 使用 walkFileTree 而不是 walk，因为它提供了更好的异常控制
            Files.walkFileTree(Paths.get(rootPath), new SimpleFileVisitor<Path>() {
                @Override
                public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                    // 我们只关心目录，所以跳过文件
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                    try {
                        if (pathMatcher.matches(dir)) {
                            list.add(dir);
                        }
                        return FileVisitResult.CONTINUE;
                    } catch (Exception e) {
                        // 如果访问目录时出现异常，跳过并继续
                        System.err.println("Warning: Skipping directory due to access issue: " + dir + " - " + e.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                }

                @Override
                public @NotNull FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException exc) {
                    // 当访问文件失败时（如权限不足），跳过并继续
                    System.err.println("Warning: Failed to visit path: " + file + " - " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            // 记录错误但不抛出异常
            System.err.println("Error generating glob folder list for glob: " + glob + " at root: " + rootPath);
            System.err.println("Error message: " + exception.getMessage());
            // 返回已找到的列表（可能为空）
        } catch (Exception exception) {
            // 捕获其他可能的异常
            System.err.println("Unexpected error generating glob folder list for glob: " + glob + " at root: " + rootPath);
            System.err.println("Error message: " + exception.getMessage());
            // 返回已找到的列表（可能为空）
        }

        return list;
    }

    /**
     * Whether the specified folder is the base folder of the Minecraft server.
     * <p>
     * In other words, whether the folder is the folder containing the server jar.
     *
     * @param folderPath the path of the folder
     * @return whether the folder is the base folder
     */
    public static boolean isBaseFolder(String folderPath) {
        return new File(folderPath).getPath().equals(".");
    }

    /**
     * Deletes the specified folder
     *
     * @param folder the folder to be deleted
     */
    public static void deleteFolder(@NotNull File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFolder(file);
            }
        }
        folder.delete();
    }

    /**
     * Inner class for blacklist entries
     */
    private static class BlacklistEntry {
        private final String globPattern;
        private final PathMatcher pathMatcher;
        private int blacklistedFiles;

        public BlacklistEntry(String globPattern, PathMatcher pathMatcher) {
            this.globPattern = globPattern;
            this.pathMatcher = pathMatcher;
            this.blacklistedFiles = 0;
        }

        public String getGlobPattern() {
            return globPattern;
        }

        public PathMatcher getPathMatcher() {
            return pathMatcher;
        }

        public int getBlacklistedFiles() {
            return blacklistedFiles;
        }

        public void incBlacklistedFiles() {
            blacklistedFiles++;
        }
    }
}