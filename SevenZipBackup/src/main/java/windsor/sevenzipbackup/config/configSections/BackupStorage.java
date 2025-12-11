package windsor.sevenzipbackup.config.configSections;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import windsor.sevenzipbackup.util.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static windsor.sevenzipbackup.config.Localization.intl;

public class BackupStorage {
    public final long delay;
    public final int threadPriority;
    public final int threadCounts;
    public static List<Integer> CPUAffinity;
    public final int keepCount;
    public final int localKeepCount;
    public final int zipCompression;
    public final boolean backupsRequirePlayers;
    public final boolean disableSavingDuringBackups;
    public final String localDirectory;
    public final String remoteDirectory;

    public BackupStorage(
            long delay,
            int threadPriority,
            int threadCounts,
            List<Integer> CPUAffinity,
            int keepCount,
            int localKeepCount,
            int zipCompression,
            boolean backupsRequirePlayers,
            boolean disableSavingDuringBackups,
            String localDirectory,
            String remoteDirectory
    ) {

        this.delay = delay;
        this.threadPriority = threadPriority;
        this.threadCounts = threadCounts;
        BackupStorage.CPUAffinity = CPUAffinity;
        this.keepCount = keepCount;
        this.localKeepCount = localKeepCount;
        this.zipCompression = zipCompression;
        this.backupsRequirePlayers = backupsRequirePlayers;
        this.disableSavingDuringBackups = disableSavingDuringBackups;
        this.localDirectory = localDirectory;
        this.remoteDirectory = remoteDirectory;
    }

    @NotNull
    @Contract ("_, _ -> new")
    public static BackupStorage parse(@NotNull FileConfiguration config, Logger logger) {
        Configuration defaultConfig = config.getDefaults();
        long delay = config.getLong("delay");
        if (delay < 5 && delay != -1) {
            logger.log(intl("invalid-backup-delay"));
            delay = Objects.requireNonNull(defaultConfig).getLong("delay");
        }
        int threadPriority = config.getInt("backup-thread-priority");
        if (threadPriority < Thread.MIN_PRIORITY) {
            logger.log(intl("thread-priority-too-low"));
            threadPriority = Thread.MIN_PRIORITY;
        } else if (threadPriority > Thread.MAX_PRIORITY) {
            logger.log(intl("thread-priority-too-high"));
            threadPriority = Thread.MAX_PRIORITY;
        }
        int threadCounts = config.getInt("backup-thread-counts");
        if (threadCounts < 1) {
            logger.log(intl("thread-counts-too-low"));
            threadCounts = Runtime.getRuntime().availableProcessors();
        } else if (threadCounts > Runtime.getRuntime().availableProcessors()) {
            logger.log(intl("thread-counts-too-high"));
            threadCounts = Runtime.getRuntime().availableProcessors();
        }
        if(config.getBoolean("enable-specify-cpu-cores")){
            String[] CPUAffinityString = Objects.requireNonNull(config.getString("cpu-cores-list")).split(",");
            List<Integer> CPUAffinity = Arrays.stream(CPUAffinityString).map(Integer::parseInt).collect(Collectors.toList());
            if(CPUAffinity.size()==1 && CPUAffinity.get(0) ==-1){
                CPUAffinity.remove(0);
                for(int i=0;i<threadCounts;i++)CPUAffinity.add(Runtime.getRuntime().availableProcessors()-threadCounts+i);
            }
            else for(int i:CPUAffinity){
                if (i < 1 || i >= Runtime.getRuntime().availableProcessors()) {
                    logger.log(intl("cpu-affinity-error"));
                    threadCounts = Runtime.getRuntime().availableProcessors();
                    break;
                }
            }
            logger.log("CPU："+CPUAffinity.stream().map(String::valueOf).collect(Collectors.joining(", ", "[", "]")));
        }
        int keepCount = config.getInt("keep-count");
        if (keepCount < 1 && keepCount != -1) {
            logger.log(intl("keep-count-invalid"));
            keepCount = Objects.requireNonNull(defaultConfig).getInt("keep-count");
        }
        int localKeepCount = config.getInt("local-keep-count");
        if (localKeepCount < -1) {
            logger.log(intl("local-keep-count-invalid"));
            localKeepCount = Objects.requireNonNull(defaultConfig).getInt("local-keep-count");
        }
        int zipCompression = config.getInt("7z-compression-level");
        if (zipCompression < 0) {
            logger.log(intl("7z-compression-too-low"));
            zipCompression = 0;
        } else if (zipCompression > 9) {
            logger.log(intl("7z-compression-too-high"));
            zipCompression = 9;
        }
        boolean backupsRequirePlayers = config.getBoolean("backups-require-players");
        boolean disableSavingDuringBackups = config.getBoolean("disable-saving-during-backups");
        String localDirectory = config.getString("local-save-directory");
        if (Objects.requireNonNull(localDirectory).startsWith("/")) {
            logger.log(intl("local-save-directory-not-relative"));
            localDirectory = localDirectory.substring(1);
        }
        String remoteDirectory = config.getString("remote-save-directory");
        return new BackupStorage(delay, threadPriority, threadCounts, CPUAffinity, keepCount, localKeepCount, zipCompression, backupsRequirePlayers, disableSavingDuringBackups, localDirectory, remoteDirectory);
    }
}
