package windsor.sevenzipbackup.handler;

import java.net.UnknownHostException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.config.ConfigParser.Config;
import windsor.sevenzipbackup.config.configSections.BackupList;
import windsor.sevenzipbackup.config.configSections.BackupScheduling;
import windsor.sevenzipbackup.plugin.SevenZipBackup;

public class DebugCollector {
    private static final String PASTEBIN_UPLOAD_URL = "https://api.mclo.gs/1/log";

    public DebugCollector(@NotNull SevenZipBackup plugin) {
        String serverType = plugin.getServer().getName();
        String serverVersion = plugin.getServer().getVersion();
        boolean onlineMode = plugin.getServer().getOnlineMode();
        ConfigInfo configInfo = new ConfigInfo();
        List<PluginInfo> plugins = new ArrayList<>();
        RamInfo ramInfo = new RamInfo();
        for (Plugin pinfo : plugin.getServer().getPluginManager().getPlugins()) {
            plugins.add(new PluginInfo(pinfo.getDescription().getName(), pinfo.getDescription().getVersion(), pinfo.getDescription().getMain(), pinfo.getDescription().getAuthors()));
        }
    }

    public String publish(SevenZipBackup plugin) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String jsonInString = gson.toJson(this);
        RequestBody formBody = new FormBody.Builder()
            .add("content", jsonInString)
            .build();
        Request request = new Request.Builder()
            .url(PASTEBIN_UPLOAD_URL)
            .post(formBody)
            .build();
        try (Response response = SevenZipBackup.httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Unexpected code " + response);
            }
            JSONObject responseJson = new JSONObject(Objects.requireNonNull(response.body()).string());
            return responseJson.getString("url");
        } catch (UnknownHostException e) {
            return "Network error, check your connection";
        } catch (Exception e) {
            e.printStackTrace();
            return "Failed";
        }
    }

    private static class PluginInfo {

        private PluginInfo(String name2, String version2, String main2, List<String> authors2) {
        }
    }

    private static class ConfigInfo {

        private ConfigInfo() {
            Config config = ConfigParser.getConfig();
            boolean backupsRequirePlayers = config.backupStorage.backupsRequirePlayers;
            boolean disableSavingDuringBackups = config.backupStorage.disableSavingDuringBackups;
            BackupScheduling scheduleBackups = config.backupScheduling;
            BackupList backupList = config.backupList;
            boolean googleDriveEnabled = config.backupMethods.googleDrive.enabled;
            boolean oneDriveEnabled = config.backupMethods.oneDrive.enabled;
            boolean dropboxEnabled = config.backupMethods.dropbox.enabled;
            boolean ftpEnabled = config.backupMethods.ftp.enabled;
            String ftpType;
            if (ftpEnabled) {
                if (config.backupMethods.ftp.sftp) {
                    ftpType = "SFTP";
                } else if (config.backupMethods.ftp.ftps) {
                    ftpType = "FTPS";
                } else {
                    ftpType = "FTP";
                }
            } else {
                ftpType = "none";
            }
            ZoneId timezone = config.advanced.dateTimezone;
        }
    }

    private static class RamInfo {
        private static final long MEGABYTE = 1024L * 1024L;

        private RamInfo() {
            long free = Runtime.getRuntime().freeMemory() / MEGABYTE;
            long total = Runtime.getRuntime().totalMemory() / MEGABYTE;
            long max = Runtime.getRuntime().maxMemory() / MEGABYTE;
        }
    }
}
