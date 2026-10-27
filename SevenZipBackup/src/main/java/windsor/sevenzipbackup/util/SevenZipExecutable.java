package windsor.sevenzipbackup.util;

import org.jetbrains.annotations.NotNull;
import windsor.sevenzipbackup.plugin.SevenZipBackup;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class SevenZipExecutable {
    private static final String RESOURCE_BASE = "/7z/";
    private static final String WIN_BINARY = "7za.exe";
    private static final String LINUX_BINARY = "7zzs";
    /**
     * 旧版本曾解压到数据目录的二进制名。启动时一并删除，避免残留文件继续占用磁盘。
     * 注意：这些名字在 resources 中已不存在，此处仅用于清理用户的旧部署。
     */
    private static final String[] LEGACY_BINARY_NAMES = {"7zr.exe", "7zr_Linux"};

    private static Path executablePath;

    public static void extract() throws Exception {
        SevenZipBackup plugin = SevenZipBackup.getInstance();
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
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

        Path destPath = binDir.resolve(binaryName).toAbsolutePath().normalize();
        try (InputStream in = SevenZipExecutable.class.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new RuntimeException("Missing native 7zr binary in plugin resources: " + resourceName);
            }
            Files.copy(in, destPath, StandardCopyOption.REPLACE_EXISTING);
        }

        if (!os.contains("win")) {
            validateLinuxBinary(destPath);
            if (!destPath.toFile().setExecutable(true, false) && !Files.isExecutable(destPath)) {
                throw new IllegalStateException("Cannot make bundled 7-Zip executable: " + destPath);
            }
        }

        for (String legacyBinaryName : LEGACY_BINARY_NAMES) {
            Files.deleteIfExists(binDir.resolve(legacyBinaryName));
        }

        executablePath = destPath;
    }

    private static void validateLinuxBinary(Path binary) throws Exception {
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!architecture.equals("amd64") && !architecture.equals("x86_64")) {
            throw new UnsupportedOperationException(
                    "Bundled Linux 7-Zip requires x86_64, but the server architecture is " + architecture);
        }

        byte[] header = new byte[20];
        try (InputStream input = Files.newInputStream(binary)) {
            if (input.readNBytes(header, 0, header.length) != header.length) {
                throw new IllegalStateException("Bundled Linux 7-Zip executable is truncated: " + binary);
            }
        }

        boolean elf = header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';
        int elfClass = Byte.toUnsignedInt(header[4]);
        int machine = Short.toUnsignedInt(ByteBuffer.wrap(header, 18, 2)
                .order(ByteOrder.LITTLE_ENDIAN).getShort());
        if (!elf || elfClass != 2 || machine != 0x3e) {
            throw new IllegalStateException(
                    "Bundled Linux 7-Zip must be a 64-bit x86_64 ELF executable: " + binary);
        }
    }

    @NotNull
    public static Path getExecutablePath() {
        if (executablePath == null) {
            throw new IllegalStateException("SevenZip executable not extracted yet. Call extract() first.");
        }
        return executablePath;
    }
}
