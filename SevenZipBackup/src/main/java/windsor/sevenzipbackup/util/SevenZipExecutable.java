package windsor.sevenzipbackup.util;

import org.jetbrains.annotations.NotNull;
import windsor.sevenzipbackup.plugin.SevenZipBackup;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class SevenZipExecutable {
    private static final String RESOURCE_BASE = "/7z/";
    private static final String WIN_BINARY = "7zr.exe";
    private static final String LINUX_BINARY = "7zr_Linux";

    private static Path executablePath;

    public static void extract() throws Exception {
        SevenZipBackup plugin = SevenZipBackup.getInstance();
        Path dataFolder = plugin.getDataFolder().toPath();
        Path binDir = dataFolder.resolve("7zr");
        Files.createDirectories(binDir);

        String os = System.getProperty("os.name").toLowerCase();
        String resourceName;
        String binaryName;

        if (os.contains("win")) {
            resourceName = RESOURCE_BASE + WIN_BINARY;
            binaryName = WIN_BINARY;
        } else if (os.contains("linux") || os.contains("unix")) {
            resourceName = RESOURCE_BASE + LINUX_BINARY;
            binaryName = LINUX_BINARY;
        } else {
            throw new UnsupportedOperationException("Unsupported operating system: " + os);
        }

        Path destPath = binDir.resolve(binaryName);
        try (InputStream in = SevenZipExecutable.class.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new RuntimeException("Missing native 7zr binary in plugin resources: " + resourceName);
            }
            Files.copy(in, destPath, StandardCopyOption.REPLACE_EXISTING);
        }

        destPath.toFile().setExecutable(true, false);// 忽略 setExecutable 失败（Windows 下通常不需要）

        executablePath = destPath;
    }

    @NotNull
    public static Path getExecutablePath() {
        if (executablePath == null) {
            throw new IllegalStateException("SevenZip executable not extracted yet. Call extract() first.");
        }
        return executablePath;
    }
}