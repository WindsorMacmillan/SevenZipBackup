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
    private void downloadFile() throws IOException  {
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
        outputPath.renameTo(new File(file.getAbsolutePath()));
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
