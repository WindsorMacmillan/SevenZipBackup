package windsor.sevenzipbackup.util;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.regex.Pattern;

/**
 * 黑名单条目。
 * <p>
 * 支持两种匹配模式，由 {@code advanced.blacklist-regex} 决定：
 * <ul>
 *   <li><b>正则模式</b>（默认开启）：把配置串当作正则表达式，与归一化为 {@code /} 分隔符的
 *       相对路径做<b>整串匹配</b>（{@link java.util.regex.Matcher#matches()}）。</li>
 *   <li><b>glob 模式</b>：把配置串当作 glob，与相对路径做整串匹配。注意 glob 中的 {@code *}
 *       不跨越目录分隔符，需要跨层级时必须使用 {@code **}。</li>
 * </ul>
 * 两种模式都要求<b>完整匹配</b>，不做子串包含判断。
 */
public class BlacklistEntry {
    private final String globPattern;
    private final PathMatcher pathMatcher;
    private final Pattern regexPattern;
    private int blacklistedFiles;

    private BlacklistEntry(String globPattern, PathMatcher pathMatcher, Pattern regexPattern) {
        this.globPattern = globPattern;
        this.pathMatcher = pathMatcher;
        this.regexPattern = regexPattern;
        this.blacklistedFiles = 0;
    }

    /**
     * 按指定模式编译一条黑名单。
     *
     * @param pattern 配置中填写的匹配串
     * @param regex   {@code true} 表示按正则解释，{@code false} 表示按 glob 解释
     * @throws java.util.regex.PatternSyntaxException 正则语法错误时抛出
     */
    public static BlacklistEntry compile(String pattern, boolean regex) {
        if (regex) {
            return new BlacklistEntry(pattern, null, Pattern.compile(pattern));
        }
        return new BlacklistEntry(
                pattern,
                FileSystems.getDefault().getPathMatcher("glob:" + pattern),
                null
        );
    }

    /**
     * 判断给定的相对路径是否命中本条黑名单。
     *
     * @param relativePath 相对备份根目录的路径
     */
    public boolean matches(Path relativePath) {
        if (regexPattern != null) {
            return regexPattern.matcher(toUnixPath(relativePath)).matches();
        }
        return pathMatcher.matches(relativePath);
    }

    /**
     * 把路径统一成 {@code /} 分隔符，使正则配置在 Windows / Linux 下行为一致。
     */
    public static String toUnixPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    public void incBlacklistedFiles() {
        blacklistedFiles++;
    }

    public String getGlobPattern() {
        return globPattern;
    }

    public int getBlacklistedFiles() {
        return blacklistedFiles;
    }
}
