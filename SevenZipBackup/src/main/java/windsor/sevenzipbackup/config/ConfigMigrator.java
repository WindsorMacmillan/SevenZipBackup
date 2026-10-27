package windsor.sevenzipbackup.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import windsor.sevenzipbackup.config.ConfigParser.Config;
import windsor.sevenzipbackup.util.Logger;
import windsor.sevenzipbackup.plugin.SevenZipBackup;
import windsor.sevenzipbackup.util.MessageUtil;

public class ConfigMigrator {
    // ConfigMigrator is called before localization is parsed, since ConfigMigrator
    // may change the intl file. Therefore, we just hardcode any messages.
    private static final String MIGRATING_MESSAGE = "Automatically migrating config to version <version>";

    private final FileConfiguration config;
    private final FileConfiguration localizationConfig;
    private final List<CommandSender> initiators;

    public ConfigMigrator(FileConfiguration config, FileConfiguration localizationConfig,
                          List<CommandSender> initiators) {
        this.config = config;
        this.localizationConfig = localizationConfig;
        this.initiators = initiators;
    }

    public void migrate() {
        Logger logger = (input, placeholders) -> MessageUtil.Builder().mmText(input, placeholders).to(initiators).send();
        //logger.log("Current version <version>", "version", String.valueOf(config.isSet("version")));
        if (config.get("version")!=null && config.getInt("version") >= Config.VERSION) {
            return;
        }
        logger.log(MIGRATING_MESSAGE, "version", String.valueOf(Config.VERSION));

        // 此次引入黑名单正则开关，旧配置里的通配符按 glob 语义迁移成等价正则。
        // 只在首次补写 version 键时执行一次，避免后续重复改写用户自定义规则。
        boolean firstMigration = config.get("version") == null;
        if (firstMigration) {
            migrateBlacklistToRegex();
        }
        config.set("version", Config.VERSION);

        int backupThreadPriority = config.getInt("backup-thread-priority");
        if (backupThreadPriority < 1) config.set("backup-thread-priority", 1);
        else if(backupThreadPriority > 10)config.set("backup-thread-priority", 10);

        if (config.isSet("7z-compression")) {
            int zipCompression = config.getInt("7z-compression");
            if (zipCompression < 0) config.set("7z-compression-level", 0);
            else config.set("7z-compression-level", Math.min(zipCompression, 9));
            config.set("7z-compression", null);
        }

        // 添加BossBar配置的默认值
        if (!config.isSet("show-bossbar-progress")) {
            config.set("show-bossbar-progress", true);
        }

        if (!config.isSet("bossbar-color")) {
            config.set("bossbar-color", "BLUE");
        }

        if (!config.isSet("bossbar-style")) {
            config.set("bossbar-style", "SOLID");
        }

        if (!config.isSet("googledrive.oauth-device-client-id")) {
            config.set("googledrive.oauth-device-client-id", "");
        }

        // 黑名单正则开关：旧配置没有该键时默认启用正则
        if (!config.isSet("advanced.blacklist-regex")) {
            config.set("advanced.blacklist-regex", true);
        }

        SevenZipBackup.getInstance().saveConfig();
        SevenZipBackup.getInstance().saveIntlConfig();
    }

    /**
     * 把旧的 glob 风格黑名单/备份位置迁移成正则写法。
     * <p>
     * 旧版本按 glob 解释这些串，而 glob 中 {@code *} 不跨越目录分隔符；新版本默认按正则解释，
     * 因此必须做等价换算，否则 {@code *hprof} 这类写法会静默失效。
     * <ul>
     *   <li>{@code *} → {@code [^/]*}（与 glob 一致，不跨目录）</li>
     *   <li>{@code **} → {@code .*}（跨目录）</li>
     *   <li>{@code ?} → {@code [^/]}</li>
     *   <li>其余正则元字符原样转义</li>
     * </ul>
     * 已被 {@link #looksLikeRegex(String)} 判定为正则写法的串会原样保留，不做换算。
     */
    private void migrateBlacklistToRegex() {
        int index = 0;
        for (Map<?, ?> entry : config.getMapList("backup-list")) {
            if (entry.containsKey("glob")) {
                Object rawGlob = entry.get("glob");
                if (rawGlob instanceof String) {
                    String converted = migratePattern((String) rawGlob);
                    config.set("backup-list[" + index + "].glob", converted);
                }
            }
            if (entry.containsKey("blacklist")) {
                Object rawBlacklist = entry.get("blacklist");
                if (rawBlacklist instanceof List) {
                    List<String> converted = new ArrayList<>();
                    for (Object item : (List<?>) rawBlacklist) {
                        if (item instanceof String) {
                            converted.add(migratePattern((String) item));
                        } else {
                            converted.add(String.valueOf(item));
                        }
                    }
                    config.set("backup-list[" + index + "].blacklist", converted);
                }
            }
            index++;
        }
        // 外部备份列表里的黑名单同样是 glob 语义
        for (String key : new String[]{"external-backup-list"}) {
            List<Map<?, ?>> externalList = config.getMapList(key);
            int outer = 0;
            for (Map<?, ?> outerEntry : externalList) {
                Object rawBackupList = outerEntry.get("backup-list");
                if (rawBackupList instanceof List) {
                    int inner = 0;
                    for (Object rawInner : (List<?>) rawBackupList) {
                        if (rawInner instanceof Map) {
                            Map<?, ?> innerEntry = (Map<?, ?>) rawInner;
                            Object rawBlacklist = innerEntry.get("blacklist");
                            if (rawBlacklist instanceof List) {
                                List<String> converted = new ArrayList<>();
                                for (Object item : (List<?>) rawBlacklist) {
                                    converted.add(item instanceof String
                                            ? migratePattern((String) item)
                                            : String.valueOf(item));
                                }
                                config.set(key + "[" + outer + "].backup-list[" + inner + "].blacklist", converted);
                            }
                        }
                        inner++;
                    }
                }
                outer++;
            }
        }
    }

    /**
     * 判断一条模式是否已经是正则写法。
     * <p>
     * 用户可能早已按正则填写（例如 {@code .*hprof}），这种串在旧版本里被当成 glob 反而失效。
     * 迁移时若再按 glob 换算，会把它变成 {@code \.[^/]*hprof}，语义被进一步破坏。
     * 因此凡是带正则特征的串一律原样保留，让它在正则模式下立即生效。
     */
    static boolean looksLikeRegex(String pattern) {
        if (pattern.contains("\\") || pattern.contains("^") || pattern.contains("$")
                || pattern.contains("|") || pattern.contains("(") || pattern.contains(")")
                || pattern.contains("[") || pattern.contains("]") || pattern.contains("{")
                || pattern.contains("}") || pattern.contains("+") || pattern.contains("?")) {
            return true;
        }
        return pattern.contains(".*") || pattern.contains(".+");
    }

    /**
     * 把一条 glob 风格的模式换算成等价的正则表达式；已是正则写法时原样返回。
     * <p>
     * 换算保持 glob 原语义：{@code *} 不跨越目录分隔符，{@code **} 跨越。
     */
    static String migratePattern(String pattern) {
        if (looksLikeRegex(pattern)) {
            return pattern;
        }
        return globToRegex(pattern);
    }

    /**
     * 把一条 glob 风格的模式换算成等价的正则表达式。
     */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = glob.length();
        while (i < len) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < len && glob.charAt(i + 1) == '*') {
                sb.append(".*");
                i += 2;
            } else if (c == '*') {
                sb.append("[^/]*");
                i++;
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else if (c == '/' || c == '\\') {
                // 统一成 / 分隔符，与 BlacklistEntry 的归一化保持一致
                sb.append('/');
                i++;
            } else if ("\\^$.|+()[]{}".indexOf(c) >= 0) {
                sb.append('\\').append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
