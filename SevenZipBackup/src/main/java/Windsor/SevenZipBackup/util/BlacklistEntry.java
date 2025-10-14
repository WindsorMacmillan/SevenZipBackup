package Windsor.SevenZipBackup.util;

import java.nio.file.PathMatcher;

public class BlacklistEntry {
    private final String globPattern;
    private final PathMatcher pathMatcher;
    private int blacklistedFiles;

    public BlacklistEntry(String globPattern, PathMatcher pathMatcher) {
        this.globPattern = globPattern;
        this.pathMatcher = pathMatcher;
        this.blacklistedFiles = 0;
    }

    public void incBlacklistedFiles() {
        blacklistedFiles++;
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
}
