package windsor.sevenzipbackup.uploaders;

import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.handler.commandHandler.BasicCommands;
import windsor.sevenzipbackup.plugin.Scheduler;
import windsor.sevenzipbackup.plugin.SevenZipBackup;
import windsor.sevenzipbackup.util.Logger;
import windsor.sevenzipbackup.util.MessageUtil;
import windsor.sevenzipbackup.util.NetUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static windsor.sevenzipbackup.config.Localization.intl;

public class Authenticator {
    /**
     * Endpoints
     */
    private static final String GOOGLE_REQUEST_CODE_ENDPOINT = "https://oauth2.googleapis.com/device/code";
    private static final String GOOGLE_POLL_VERIFICATION_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_DRIVE_SCOPE = "https://www.googleapis.com/auth/drive";
    private static final String ONEDRIVE_REQUEST_CODE_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode";
    private static final String ONEDRIVE_POLL_VERIFICATION_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token";

    private static ScheduledTask pollTask = null;

    /**
     * OAuth 设备码流程中，这些错误只表示「还没处理完」，必须继续轮询而不能中止：
     * <ul>
     *   <li>{@code authorization_pending}：用户还没完成授权</li>
     *   <li>{@code slow_down}：轮询过快，服务端要求降低频率</li>
     * </ul>
     */
    private static boolean isRetryableOAuthError(String error) {
        return "authorization_pending".equals(error) || "slow_down".equals(error);
    }

    public enum AuthenticationProvider {
        GOOGLE_DRIVE("Google Drive", "googledrive", "/GoogleDriveCredential.json", "qWd2xXC/ORzdZvUotXoWhHC0POkMNuO/xuwcKWc9s1LLodayZXvkdKimmpOQqWYS6I+qGSrYNb8UCJWMhrgDXhIWEbDvytkQTwq+uNcnfw8=", "pasQz0KvtyC7o6CrlLPSMVV9Y0RMX76cXzsAbBoCBxI="),
        ONEDRIVE("OneDrive", "onedrive", "/OneDriveCredential.json", "Ktj7Jd1h0oYNVicuyTBk5fU+gHS+QYReZxZKNZNO9CDxxHaf8bXlw0SKO9jnwc81", ""),
        DROPBOX("Dropbox", "dropbox", "/DropboxCredential.json", "OSpqXymVUFSRnANAmj2DTA==", "4MrYNbN0I6J/fsAFeF00GQ==");

        private final String name;
        private final String id;
        private final String credStoreLocation;
        private final String clientId;
        private final String clientSecret;

        AuthenticationProvider(String name, String id, String credStoreLocation, String clientId, String clientSecret) {
            this.name = name;
            this.id = id;
            this.credStoreLocation = credStoreLocation;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        public String getName() {
            return name;
        }

        public String getId() {
            return id;
        }

        public @NotNull String getCredStoreLocation() {
            return SevenZipBackup.getInstance().getDataFolder().getAbsolutePath() + credStoreLocation;
        }

        public String getClientId() {
            return clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }
    }

    /**
     * Attempt to authenticate a user with the specified authentication provider 
     * using the OAuth 2.0-device authorization grant flow.
     * 
     * @param provider an {@code AuthenticationProvider}
     * @param initiator user who initiated the authentication
     */
    public static void authenticateUser(final AuthenticationProvider provider, final CommandSender initiator) {
        // 设备码申请是阻塞网络 IO，放在异步线程执行，避免卡住命令所在的主线程/区域线程。
        Scheduler.runAsyncTask(() -> authenticateUserAsync(provider, initiator));
    }

    private static void authenticateUserAsync(final AuthenticationProvider provider, final CommandSender initiator) {
        SevenZipBackup plugin = SevenZipBackup.getInstance();
        Logger logger = (input, placeholders) -> MessageUtil.Builder().mmText(input, placeholders).to(initiator).toConsole(false).send();

        // Dropbox 不支持设备码流程，且原中转服务（drivebackup.com）域名已过期失效，
        // 无法再走在线授权。改为引导用户手动填写 refresh_token。
        if (provider == AuthenticationProvider.DROPBOX) {
            logger.log(intl("dropbox-manual-auth-required"));
            return;
        }

        cancelPollTask();
        try {
            String configuredGoogleDeviceClientId = "";
            if (provider == AuthenticationProvider.GOOGLE_DRIVE) {
                // 此处已在异步线程，不能调用 JavaPlugin.getConfig()（非线程安全）；
                // 改读插件启动时解析好的内存快照。
                String rawId = ConfigParser.getConfig().backupMethods.googleDrive.oauthDeviceClientId;
                configuredGoogleDeviceClientId = rawId == null ? "" : rawId.trim();
                if (configuredGoogleDeviceClientId.isEmpty()) {
                    logger.log(intl("google-drive-oauth-client-id-required"));
                    return;
                }
            }
            final String googleDeviceClientId = configuredGoogleDeviceClientId;

            FormBody.Builder requestBody = new FormBody.Builder();
            String requestEndpoint;
            if (provider == AuthenticationProvider.GOOGLE_DRIVE) {
                requestBody.add("client_id", googleDeviceClientId);
                requestBody.add("scope", GOOGLE_DRIVE_SCOPE);
                requestEndpoint = GOOGLE_REQUEST_CODE_ENDPOINT;
            } else {
                // 走到这里只剩 OneDrive：Dropbox 已在方法开头改为手动配置
                requestBody.add("type", provider.getId());
                requestBody.add("client_id", Obfusticate.decrypt(provider.getClientId()));
                requestBody.add("scope", "offline_access Files.ReadWrite");
                requestEndpoint = ONEDRIVE_REQUEST_CODE_ENDPOINT;
            }
            Request request = new Request.Builder()
                .url(requestEndpoint)
                .post(requestBody.build())
                .build();
            JSONObject parsedResponse = executeJsonRequest(request, requestEndpoint);
            throwIfOAuthError(parsedResponse);
            String userCode = parsedResponse.getString("user_code");
            String deviceCode = parsedResponse.getString("device_code");
            String verificationUri = parsedResponse.optString("verification_uri", parsedResponse.optString("verification_url"));
            if (verificationUri.isEmpty()) {
                throw new IOException("OAuth response did not contain a verification URL");
            }
            long pollIntervalSeconds = parsedResponse.optLong("interval", 5);
            if (pollIntervalSeconds <= 0) {
                pollIntervalSeconds = 5;
            }
            logger.log(
                intl("link-account-code"),
                "link-url", verificationUri,
                "link-code", userCode,
                "provider", provider.getName());
            // 轮询里是阻塞式 HTTP 请求，直接挂在异步调度器上，不占用全局区域线程。
            // 只有写配置、刷新调度这类必须主线程的操作才切回全局区域线程。
            pollTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, asyncTask -> {
                try {
                    FormBody.Builder requestBody1 = new FormBody.Builder()
                        .add("device_code", deviceCode);
                    String requestEndpoint1;
                    if (provider == AuthenticationProvider.GOOGLE_DRIVE) {
                        requestBody1.add("client_id", googleDeviceClientId);
                        requestBody1.add("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                        requestEndpoint1 = GOOGLE_POLL_VERIFICATION_ENDPOINT;
                    } else {
                        // 只剩 OneDrive：Dropbox 已改为手动配置，不再进入轮询
                        requestBody1.add("user_code", userCode);
                        requestBody1.add("client_id", Obfusticate.decrypt(provider.getClientId()));
                        requestBody1.add("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                        requestEndpoint1 = ONEDRIVE_POLL_VERIFICATION_ENDPOINT;
                    }
                    Request request1 = new Request.Builder()
                        .url(requestEndpoint1)
                        .post(requestBody1.build())
                        .build();
                    JSONObject parsedResponse1 = executeJsonRequest(request1, requestEndpoint1);
                    if (parsedResponse1.has("refresh_token")) {
                        saveRefreshToken(provider, (String) parsedResponse1.get("refresh_token"));
                        Scheduler.runSyncTask(() -> {
                            linkSuccess(initiator, provider, logger);
                            cancelPollTask();
                        });
                    } else if (!isRetryableOAuthError(parsedResponse1.optString("error"))) {
                        // OneDrive / Google：authorization_pending 与 slow_down 都继续等待，
                        // 其余错误（access_denied、expired_token、invalid_grant 等）才是真的失败
                        failPolling(provider, parsedResponse1, logger);
                    }
                } catch (Exception exception) {
                    NetUtil.catchException(exception, getPollEndpoint(provider), logger);
                    logger.log(intl("link-provider-failed"), "provider", provider.getName());
                    MessageUtil.sendConsoleException(exception);
                    cancelPollTask();
                }
            }, pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS);
        } catch (Exception exception) {
            NetUtil.catchException(exception, provider == AuthenticationProvider.GOOGLE_DRIVE
                    ? GOOGLE_REQUEST_CODE_ENDPOINT : ONEDRIVE_REQUEST_CODE_ENDPOINT, logger);
            logger.log(intl("link-provider-failed"), "provider", provider.getName());
            MessageUtil.sendConsoleException(exception);
        }
    }

    /**
     * 轮询遇到不可恢复的错误时统一处理：告知用户、必要时打印服务端返回的详情、停止轮询。
     * <p>
     * 服务端返回的错误信息（{@code error}/{@code error_description}）对排查授权问题至关重要，
     * 例如 {@code expired_token}（码已过期）、{@code access_denied}（用户拒绝了授权）、
     * {@code invalid_grant}（客户端配置与授权不匹配）。
     */
    private static void failPolling(@NotNull AuthenticationProvider provider,
                                    @NotNull JSONObject response,
                                    @NotNull Logger logger) {
        String error = response.optString("error", response.optString("msg", "unknown_error"));
        String description = response.optString("error_description", response.optString("error", ""));
        MessageUtil.Builder()
                .text(intl("link-provider-failed-detail")
                        .replace("<provider>", provider.getName())
                        .replace("<error>", error)
                        .replace("<error-description>", abbreviateResponse(description)))
                .send();
        logger.log(intl("link-provider-failed"), "provider", provider.getName());
        if (SevenZipBackup.getInstance().getConfig().getBoolean("advanced.debug", false)) {
            MessageUtil.sendConsoleException(new IOException("OAuth polling failed: " + response));
        }
        cancelPollTask();
    }

    @NotNull
    private static JSONObject executeJsonRequest(@NotNull Request request, @NotNull String endpoint) throws IOException {
        try (Response response = SevenZipBackup.httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            try {
                return new JSONObject(body);
            } catch (Exception exception) {
                throw new IOException("OAuth endpoint " + endpoint + " returned a non-JSON response: "
                        + abbreviateResponse(body) + " (HTTP " + response.code() + ")", exception);
            }
        }
    }

    private static void throwIfOAuthError(@NotNull JSONObject response) throws IOException {
        if (!response.has("error")) {
            return;
        }
        String error = response.optString("error", "unknown_error");
        String description = response.optString("error_description", error);
        throw new IOException("OAuth request failed: " + error + " (" + description + ")");
    }

    @NotNull
    private static String getPollEndpoint(@NotNull AuthenticationProvider provider) {
        if (provider == AuthenticationProvider.GOOGLE_DRIVE) {
            return GOOGLE_POLL_VERIFICATION_ENDPOINT;
        }
        return ONEDRIVE_POLL_VERIFICATION_ENDPOINT;
    }

    @NotNull
    private static String abbreviateResponse(@NotNull String response) {
        String compact = response.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "...";
    }

    public static void unauthenticateUser(final AuthenticationProvider provider, final CommandSender initiator) {
        Logger logger = (input, placeholders) -> MessageUtil.Builder().mmText(input, placeholders).to(initiator).send();
        disableBackupMethod(provider, logger);
        try {
            File credStoreFile = new File(provider.getCredStoreLocation());
            if (credStoreFile.exists()) {
                credStoreFile.delete();
            }
        } catch (Exception exception) {
            logger.log(intl("unlink-provider-failed"), "provider", provider.getName());
            MessageUtil.sendConsoleException(exception);
        }
        logger.log(intl("unlink-provider-complete"), "provider", provider.getName());
    }

    private static void cancelPollTask() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
    }

    /**
     * 关闭插件时清理轮询任务。静态字段持有 {@link ScheduledTask}，
     * 若不显式取消，PlugmanX 热重载后旧任务仍会继续轮询并引用旧插件实例，造成内存泄漏。
     */
    public static void shutdown() {
        cancelPollTask();
    }

    public static void linkSuccess(CommandSender initiator, @NotNull AuthenticationProvider provider, @NotNull Logger logger) {
        logger.log(intl("link-provider-complete"), "provider", provider.getName());
        // enableBackupMethod 会写配置并 saveConfig，必须先把 enabled=true 落盘，
        // 否则随后的 reloadLocalConfig 会从磁盘重新读取，把刚改的值覆盖回 false。
        enableBackupMethod(provider, logger);
        SevenZipBackup.reloadLocalConfig();
        BasicCommands.sendBriefBackupList(initiator);
    }

    public static void saveRefreshToken(@NotNull AuthenticationProvider provider, String token) throws IOException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("refresh_token", token);
        try (FileWriter file = new FileWriter(provider.getCredStoreLocation())) {
            file.write(jsonObject.toString());
        }
    }

    private static void enableBackupMethod(@NotNull AuthenticationProvider provider, Logger logger) {
        SevenZipBackup plugin = SevenZipBackup.getInstance();
        if (!plugin.getConfig().getBoolean(provider.getId() + ".enabled")) {
            logger.log("Automatically enabled " + provider.getName() + " backups");
            plugin.getConfig().set(provider.getId() + ".enabled", true);
            plugin.saveConfig();
        }
    }

    private static void disableBackupMethod(@NotNull AuthenticationProvider provider, Logger logger) {
        SevenZipBackup plugin = SevenZipBackup.getInstance();
        if (plugin.getConfig().getBoolean(provider.getId() + ".enabled")) {
            logger.log("Disabled " + provider.getName() + " backups");
            plugin.getConfig().set(provider.getId() + ".enabled", false);
            plugin.saveConfig();
        }
    }

    @NotNull
    public static String getRefreshToken(AuthenticationProvider provider) {
        try {
            String clientJSON = processCredentialJsonFile(provider);
            JSONObject clientJsonObject = new JSONObject(clientJSON);
            String readRefreshToken = (String) clientJsonObject.get("refresh_token");
            if (readRefreshToken == null || readRefreshToken.isEmpty()) {
                throw new Exception();
            }
            return readRefreshToken;
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean hasRefreshToken(AuthenticationProvider provider) {
        // what am I doing with my life?
        return !getRefreshToken(provider).isEmpty();
    }

    @NotNull
    private static String processCredentialJsonFile(@NotNull AuthenticationProvider provider) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(provider.getCredStoreLocation()))) {
            StringBuilder sb = new StringBuilder();
            String line = br.readLine();
            while (line != null) {
                sb.append(line);
                line = br.readLine();
            }
            return sb.toString();
        }
    }
}
