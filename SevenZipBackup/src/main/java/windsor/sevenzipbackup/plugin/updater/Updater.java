package windsor.sevenzipbackup.plugin.updater;

import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.command.CommandSender;
import windsor.sevenzipbackup.plugin.SevenZipBackup;
import windsor.sevenzipbackup.util.Logger;
import windsor.sevenzipbackup.util.MessageUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import static windsor.sevenzipbackup.config.Localization.intl;

public class Updater {
    // The plugin file (jar)
    private final File file;
    // The folder that downloads will be placed in
    private final File updateFolder;

    /**
     * Initialize the updater.
     *
     * @param file The plugin jar file
     */
    public Updater(File file) {
        // Plugin running Updater
        SevenZipBackup plugin = SevenZipBackup.getInstance();
        this.file = file;
        updateFolder = plugin.getDataFolder().getParentFile();
    }

    /**
     * Download the latest plugin jar and save it to the plugins' folder.
     */
    private void downloadFile() throws IOException {
        File outputPath = new File(updateFolder, "SevenZipBackup.jar.temp");
        Request request = new Request.Builder().url(UpdateChecker.getLatestDownloadUrl()).addHeader("Accept", "application/octet-stream").build();
        try (Response response = SevenZipBackup.httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to download file: " + response);
            }
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                fos.write(Objects.requireNonNull(response.body()).bytes());
            }
        }
        replacePluginJar(outputPath);
    }

    /**
     * 用下载好的 jar 替换正在使用的插件文件。
     * <p>
     * Windows 上无法重命名覆盖一个已被 JVM 打开的文件，而插件 jar 在运行时始终被占用，
     * 因此原地替换通常失败。这里不再静默忽略失败（此前 renameTo 的返回值被丢弃，
     * 失败时仍提示"更新成功"）：先尝试原子移动；失败则退回「删除旧文件后移动」，
     * 若删除也失败（文件被占用），明确抛错并清理临时文件。
     */
    private void replacePluginJar(File downloaded) throws IOException {
        Path target = file.toPath();

        // 1) 优先尝试原子替换（Java 支持时可在部分平台覆盖成功）
        try {
            Files.move(downloaded.toPath(), target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException atomicFailure) {
            // 继续尝试下面的降级方案
        }

        // 2) 退回：先删旧文件再移动。删除失败说明文件仍被占用，明确报错而不是假装成功。
        try {
            Files.deleteIfExists(target);
        } catch (IOException deleteFailure) {
            // 清理下载的临时文件，避免在 plugins 目录留下垃圾
            Files.deleteIfExists(downloaded.toPath());
            throw new IOException(
                    "无法替换插件文件（可能仍被服务端占用）：" + target
                            + "。请关闭服务器后手动替换，或使用服务端自带的插件更新目录机制。",
                    deleteFailure);
        }

        try {
            Files.move(downloaded.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailure) {
            Files.deleteIfExists(downloaded.toPath());
            throw new IOException("插件文件替换失败，原文件已删除，请手动重新下载：" + target, moveFailure);
        }
    }

    public void runUpdater(CommandSender initiator) {
        Logger logger = (input, placeholders) -> MessageUtil.Builder().mmText(input, placeholders).to(initiator).send();
        if (UpdateChecker.isUpdateAvailable()) {
            if (UpdateChecker.getLatestDownloadUrl() != null) {
                try {
                    logger.log(intl("updater-start"));
                    downloadFile();
                    logger.log(intl("updater-successful"));
                } catch (Exception exception) {
                    logger.log(intl("updater-update-failed"));
                    MessageUtil.sendConsoleException(exception);
                }
            } else {
                logger.log(intl("updater-fetch-failed"));
            }
        } else {
            logger.log(intl("updater-no-updates"));
        }
    }
}
