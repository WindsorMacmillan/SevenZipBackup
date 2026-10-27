package windsor.sevenzipbackup.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import windsor.sevenzipbackup.BackupProgressCallback;
import windsor.sevenzipbackup.UploadThread.UploadLogger;
import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.config.ConfigParser.Config;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static windsor.sevenzipbackup.config.Localization.intl;

public class FileUtil {
    private static final String NAME_KEYWORD = "%NAME";
    private static final Pattern PROGRESS_PERCENT = Pattern.compile("(?<!\\d)(\\d{1,3})%");

    private final UploadLogger logger;

    public FileUtil(UploadLogger logger) {
        this.logger = logger;
    }

    /**
     * Gets the local backups in the specified folder as a {@code TreeMap} with their creation date and a reference to them.
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
     * Deletes the oldest files in the specified folder past the number to retain locally.
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
     * 创建 7z 压缩文件
     */
    private void ZipIt(String inputFolderPath, String outputFilePath,
                       BackupFileList fileList, BackupProgressCallback callback) throws Exception {
        if (ConfigParser.getConfig().advanced.debugEnabled)
            logger.info("正在为" + inputFolderPath + "创建压缩文件 (使用外部 7zr)");

        File outputFile = new File(outputFilePath).getAbsoluteFile();
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        List<String> files = fileList.getList();
        files.removeIf(file -> {
            File f = new File(inputFolderPath, file);
            return !isFileReadable(f);
        });
        Path listFile = Files.createTempFile("7zlist_", ".txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(listFile.toFile()))) {
            for (String file : files) {
                writer.write(file);
                writer.newLine();
            }
        }

        Path exePath = SevenZipExecutable.getExecutablePath();
        String level = "-mx" + ConfigParser.getConfig().backupStorage.zipCompression;
        List<String> command = new ArrayList<>(List.of(
                exePath.toString(),
                "a", "-t7z", level, "-ms=on", "-ssw", "-sccUTF-8", "-bsp1"
        ));
        // CPU 亲和性：如果启用，附加 -stm{HexMask} 参数以绑定到指定 CPU 核心
        String affinityArg = buildCpuAffinityArg();
        if (affinityArg != null) {
            command.add(affinityArg);
        }
        command.add(outputFile.getAbsolutePath());
        command.add("@" + listFile.toAbsolutePath());

        if (ConfigParser.getConfig().advanced.debugEnabled) {
            logger.info("执行命令: " + String.join(" ", command));
            logger.info("工作目录: " + inputFolderPath);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(inputFolderPath));
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 用于速率限制（每秒最多5次 ≈ 200ms 间隔）
        long lastProgressUpdate = 0;
        int totalFiles = files.size();
        int lastReportedPercent = -1;

        StringBuilder outputBuilder = new StringBuilder();
        StringBuilder progressBuffer = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] chars = new char[1024];
            int read;
            while ((read = reader.read(chars)) != -1) {
                String output = new String(chars, 0, read);
                outputBuilder.append(output);
                progressBuffer.append(output);

                Matcher matcher = PROGRESS_PERCENT.matcher(progressBuffer);
                int consumed = 0;
                while (matcher.find()) {
                    int percent = Integer.parseInt(matcher.group(1));
                    consumed = matcher.end();
                    if (percent > 100 || percent == lastReportedPercent) {
                        continue;
                    }

                    lastReportedPercent = percent;
                    long now = System.currentTimeMillis();
                    // Rate-limit updates to five per second, except for completion.
                    if (percent == 100 || (now - lastProgressUpdate) >= 200) {
                        if (callback != null) {
                            int processed = (int) (totalFiles * (percent / 100.0));
                            callback.onProgress(processed, totalFiles);
                        }
                        lastProgressUpdate = now;
                    }
                }

                if (consumed > 0) {
                    progressBuffer.delete(0, consumed);
                }
                // Keep enough trailing characters to match a percentage split across reads.
                if (progressBuffer.length() > 32) {
                    progressBuffer.delete(0, progressBuffer.length() - 32);
                }
            }
        }

        int exitCode = process.waitFor();
        try { Files.deleteIfExists(listFile); } catch (Exception ignored) {}

        if (exitCode >= 2) {
            String errorMsg = "7zr failed with exit code " + exitCode + ". Output: " + outputBuilder;
            logger.info(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        if (exitCode == 1) {
            logger.info("备份完成，但部分文件无法读取（已跳过）。");
        }
        if (ConfigParser.getConfig().advanced.debugEnabled) {
            logger.info("7zr output: " + outputBuilder);
        }

        // 确保最终进度为 100%
        if (callback != null) {
            callback.onProgress(totalFiles, totalFiles);
            callback.onBackupComplete();
        }
    }

    /**
     * 根据配置构建 7zr 的 CPU 亲和性参数 {@code -stm{HexMask}}。
     * <p>
     * 规则：
     * <ul>
     *   <li>未启用（{@code enable-specify-cpu-cores: false}）→ 返回 {@code null}，不附加参数，使用全部核心</li>
     *   <li>列表为 {@code "-1"}（自动）→ 返回 {@code null}，使用全部核心</li>
     *   <li>列表包含逗号分隔的核心编号（0 到 N-1）→ 将每个核心对应的位（1 &lt;&lt; core）置入掩码，
     *       格式化为十六进制附加到命令</li>
     *   <li>任一编号无法解析或超出有效范围（含混合出现的 {@code -1}）→ 回退至默认，返回 {@code null}</li>
     * </ul>
     *
     * @return {@code -stm...} 亲和性参数，或 {@code null}（使用全部核心 / 回退默认）
     */
    private static String buildCpuAffinityArg() {
        Config config = ConfigParser.getConfig();
        if (config == null || !config.backupStorage.enableSpecifyCpuCores) {
            return null;
        }
        String rawList = config.backupStorage.cpuCoresList;
        if (rawList == null || rawList.trim().isEmpty()) {
            return null;
        }
        // "-1" 表示自动 → 使用全部核心
        if (rawList.trim().equals("-1")) {
            return null;
        }

        int availableProcessors = Runtime.getRuntime().availableProcessors();
        long mask = 0L;
        for (String part : rawList.split(",")) {
            String trimmed = part.trim();
            // 混合出现 "-1"（如 "0,-1,3"）按无效处理 → 回退默认
            if (trimmed.equals("-1")) {
                return null;
            }
            int core;
            try {
                core = Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                return null; // 无法解析 → 回退默认
            }
            if (core < 0 || core >= availableProcessors) {
                return null; // 无效核心编号 → 回退默认
            }
            mask |= (1L << core);
        }
        return "-stm" + Long.toHexString(mask);
    }

    public static class BackupFileList {
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

        public List<String> getList() {
            return fileList;
        }

        List<BlacklistEntry> getBlacklist() {
            return blacklist;
        }
    }

    // 新增：生成输出文件路径
    public String generateOutputPath(String location, LocalDateTimeFormatter formatter) {
        Config config = ConfigParser.getConfig();
        ZonedDateTime now = ZonedDateTime.now(config.advanced.dateTimezone);
        String fileName = formatter.format(now);
        if (isBaseFolder(location)) location = "root";
        if (fileName.contains(NAME_KEYWORD)) {
            int idx = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
            String lastFolderName = location.substring(idx + 1);
            fileName = fileName.replace(NAME_KEYWORD, lastFolderName);
        }
        fileName = fileName.replace(".zip", ".7z");
        String escaped = escapeBackupLocation(config.backupStorage.localDirectory + "/" + location);
        File path = new File(escaped);
        if (!path.exists()) path.mkdirs();
        return path.getPath() + "/" + fileName;
    }

    // 仅生成文件列表，不压缩，并通过回调通知文件数量
    public BackupFileList prepareFileList(String inputFolderPath, List<String> blacklistGlobs,
                                          BackupProgressCallback callback) throws Exception {
        List<BlacklistEntry> blacklist = buildBlacklist(blacklistGlobs);
        BackupFileList fileList = new BackupFileList(blacklist);
        generateFileList(new File(inputFolderPath), inputFolderPath, fileList);

        // 统计信息
        for (BlacklistEntry be : blacklist) {
            int count = be.getBlacklistedFiles();
            if (count > 0) {
                logger.info(intl("local-backup-backlisted"),
                        "blacklisted-files-count", String.valueOf(count),
                        "glob-pattern", be.getGlobPattern());
            }
        }
        int folderFiles = fileList.getFilesInBackupFolder();
        if (folderFiles > 0) {
            logger.info(intl("local-backup-in-backup-folder"),
                    "files-in-backup-folder-count", String.valueOf(folderFiles));
        }
        if (callback != null) {
            callback.onFileListPrepared(fileList.getList().size());
        }
        return fileList;
    }

    /**
     * 仅生成文件列表，不做日志统计，用于重新扫描
     */
    public BackupFileList prepareFileList(String inputFolderPath, List<String> blacklistGlobs) throws Exception {
        List<BlacklistEntry> blacklist = buildBlacklist(blacklistGlobs);
        BackupFileList fileList = new BackupFileList(blacklist);
        generateFileList(new File(inputFolderPath), inputFolderPath, fileList);
        return fileList;
    }

    /**
     * 按当前配置的正则开关把配置串编译成黑名单条目。
     * <p>
     * 关闭正则时按 glob 解释；无论哪种模式都要求完整匹配，不做子串包含判断。
     * 单条规则语法非法时只跳过该条并记录日志，不影响其余规则。
     */
    private List<BlacklistEntry> buildBlacklist(List<String> patterns) {
        Config config = ConfigParser.getConfig();
        boolean regex = config == null || config.advanced.blacklistRegex;
        List<BlacklistEntry> blacklist = new ArrayList<>();
        for (String pattern : patterns) {
            try {
                blacklist.add(BlacklistEntry.compile(pattern, regex));
            } catch (PatternSyntaxException e) {
                logger.log(intl("local-backup-blacklist-invalid"),
                        "glob-pattern", pattern,
                        "error", e.getDescription());
            }
        }
        return blacklist;
    }

    // 直接压缩给定的文件列表
    public void compressBackup(String inputFolderPath, String outputFilePath,
                               BackupFileList fileList, BackupProgressCallback callback) throws Exception {
        ZipIt(inputFolderPath, outputFilePath, fileList, callback);
    }

    /**
     * Adds the specified file or folder to the list of files to put in the zip created from the specified folder.
     */
    private void generateFileList(@NotNull File file, String inputFolderPath, BackupFileList fileList) throws Exception {
        if (!file.exists()) return;
        BasicFileAttributes fileAttributes;
        try {
            fileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        } catch (java.nio.file.NoSuchFileException e) {
            return;
        }

        if (fileAttributes.isRegularFile()) {
            String fileName = file.getName();
            String lowerName = fileName.toLowerCase();
            // 跳过所有 SQLite / H2 等嵌入式数据库文件，它们常被插件以独占模式打开
            if (lowerName.endsWith(".db")
                    || lowerName.endsWith(".mv.db")
                    || lowerName.endsWith(".sqlite")
                    || lowerName.endsWith(".db-shm")
                    || lowerName.endsWith(".db-wal")) {
                return;
            }
            // 跳过 session.lock
            if (lowerName.equals("session.lock")) return;

            // 验证是否备份了本地备份目录
            if (file.getCanonicalPath().startsWith(new File(ConfigParser.getConfig().backupStorage.localDirectory).getCanonicalPath())) {
                fileList.incFilesInBackupFolder();
                return;
            }

            Path relativePath = Paths.get(inputFolderPath).relativize(file.toPath());

            // 黑名单检查
            for (BlacklistEntry blacklistEntry : fileList.getBlacklist()) {
                if (blacklistEntry.matches(relativePath)) {
                    blacklistEntry.incBlacklistedFiles();
                    return;
                }
            }

            // 可读性检查：尝试打开文件，确保后续压缩能顺利读取
            if (!isFileReadable(file)) {
                // 记录无法读取的文件，但不会中断整个列表生成
                logger.info("跳过无法读取的文件: " + file.getAbsolutePath());
                return;
            }

            fileList.appendToList(relativePath.toString());
        } else if (fileAttributes.isDirectory()) {
            String[] children = file.list();
            if (children != null) {
                for (String filename : children) {
                    generateFileList(new File(file, filename), inputFolderPath, fileList);
                }
            }
        } else {
            logger.info(intl("local-backup-failed-to-include"),
                    "file-path", file.getAbsolutePath());
        }
    }

    /**
     * 快速检查文件是否可读（通过尝试打开输入流）
     */
    private boolean isFileReadable(File file) {
        try (FileInputStream ignored = new FileInputStream(file)) {
            return true; // 成功打开即说明可读
        } catch (IOException e) {
            return false;
        }
    }

    @NotNull
    @Contract(pure = true)
    private static String escapeBackupLocation(@NotNull String location) {
        return location.replace("../", "");
    }

    /**
     * 找出顶层目录中与给定模式匹配的文件夹。
     * <p>
     * 只扫描 {@code rootPath} 的直接子目录，不递归，因此不会误收
     * {@code plugins/WorldEdit}、{@code backups/world} 这类深层目录。
     * <p>
     * {@code advanced.blacklist-regex} 开启时（默认）{@code glob} 按正则解释，
     * 与目录名做整串匹配；关闭时按 glob 解释（{@code *} 不跨目录分隔符）。
     */
    public static List<Path> generateGlobFolderList(String glob, String rootPath) {
        List<Path> list = new ArrayList<>();
        try {
            Config config = ConfigParser.getConfig();
            boolean regex = config == null || config.advanced.blacklistRegex;
            Pattern regexPattern = regex ? Pattern.compile(glob) : null;
            // glob 分支匹配的是 relativize 之后的相对路径（如 "world"），不能再加 "./" 前缀，
            // 否则永远匹配不上。
            PathMatcher pathMatcher = regex
                    ? null
                    : FileSystems.getDefault().getPathMatcher("glob:" + glob);
            Path root = Paths.get(rootPath);
            File[] dirs = new File(rootPath).listFiles(File::isDirectory);
            if (dirs != null) {
                for (File dir : dirs) {
                    Path path = dir.toPath();
                    boolean matched;
                    if (regexPattern != null) {
                        // 只取文件夹名参与匹配，避免把父路径带进正则
                        matched = regexPattern.matcher(path.getFileName().toString()).matches();
                    } else {
                        matched = pathMatcher.matches(root.relativize(path));
                    }
                    if (matched) {
                        list.add(path);
                    }
                }
            }
        } catch (Exception exception) {
            System.err.println("Error generating glob folder list for glob: " + glob + " at root: " + rootPath);
            System.err.println("Error message: " + exception.getMessage());
        }
        return list;
    }

    public static boolean isBaseFolder(String folderPath) {
        return new File(folderPath).getPath().equals(".");
    }

    public static void deleteFolder(@NotNull File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFolder(file);
            }
        }
        folder.delete();
    }
}
