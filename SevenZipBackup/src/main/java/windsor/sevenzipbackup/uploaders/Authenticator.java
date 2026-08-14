package windsor.sevenzipbackup.uploaders;

import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.command.CommandSender;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import windsor.sevenzipbackup.handler.commandHandler.BasicCommands;
import windsor.sevenzipbackup.plugin.SevenZipBackup;
import windsor.sevenzipbackup.util.Logger;
import windsor.sevenzipbackup.util.MessageUtil;
import windsor.sevenzipbackup.util.NetUtil;
import windsor.sevenzipbackup.util.SchedulerUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import static windsor.sevenzipbackup.config.Localization.intl;

public class Authenticator {
    /**
     * Endpoints
     */
    private static final String DRIVEBACKUP_AUTH_URL = "https://auth.drivebackup.com";
    private static final String REQUEST_CODE_ENDPOINT = DRIVEBACKUP_AUTH_URL + "/pin";
    private static final String POLL_VERIFICATION_ENDPOINT = DRIVEBACKUP_AUTH_URL + "/token";
    private static final String GOOGLE_REQUEST_CODE_ENDPOINT = "https://oauth2.googleapis.com/device/code";
    private static final String GOOGLE_POLL_VERIFICATION_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_DRIVE_SCOPE = "https://www.googleapis.com/auth/drive";
    private static final String ONEDRIVE_REQUEST_CODE_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode";
    private static final String ONEDRIVE_POLL_VERIFICATION_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token";

    /**
     * Authenticator client secret
     */
    private static final String CLIENT_SECRET = "fyKCRZRyJeHW5PzGJvQkL4dr2zRHRmwTaOutG7BBhQM=";

    private static ScheduledTask pollTask = null;

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
        SevenZipBackup plugin = SevenZipBackup.getInstance();
        Logger logger = (input, placeholders) -> MessageUtil.Builder().mmText(input, placeholders).to(initiator).toConsole(false).send();
        cancelPollTask();
        try {
            String configuredGoogleDeviceClientId = "";
            if (provider == AuthenticationProvider.GOOGLE_DRIVE) {
                configuredGoogleDeviceClientId = plugin.getConfig().getString("googledrive.oauth-device-client-id", "").trim();
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
            } else if (provider == AuthenticationProvider.ONEDRIVE) {
                requestBody.add("type", provider.getId());
                requestBody.add("client_id", Obfusticate.decrypt(provider.getClientId()));
                requestBody.add("scope", "offline_access Files.ReadWrite");
                requestEndpoint = ONEDRIVE_REQUEST_CODE_ENDPOINT;
            } else {
                requestBody.add("type", provider.getId());
                requestBody.add("client_secret", Obfusticate.decrypt(CLIENT_SECRET));
                requestEndpoint = REQUEST_CODE_ENDPOINT;
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
            long responseCheckDelay = SchedulerUtil.sToTicks(parsedResponse.optLong("interval", 5));
            logger.log(
                intl("link-account-code"),
                "link-url", verificationUri,
                "link-code", userCode,
                "provider", provider.getName());
            pollTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> {
                try {
                    FormBody.Builder requestBody1 = new FormBody.Builder()
                        .add("device_code", deviceCode);
                    String requestEndpoint1;
                    if (provider == AuthenticationProvider.GOOGLE_DRIVE) {
                        requestBody1.add("client_id", googleDeviceClientId);
                        requestBody1.add("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                        requestEndpoint1 = GOOGLE_POLL_VERIFICATION_ENDPOINT;
                    } else if (provider == AuthenticationProvider.ONEDRIVE) {
                        requestBody1.add("user_code", userCode);
                        requestBody1.add("client_id", Obfusticate.decrypt(provider.getClientId()));
                        requestBody1.add("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
                        requestEndpoint1 = ONEDRIVE_POLL_VERIFICATION_ENDPOINT;
                    } else {
                        requestBody1.add("user_code", userCode);
                        requestBody1.add("client_secret", Obfusticate.decrypt(CLIENT_SECRET));
                        requestEndpoint1 = POLL_VERIFICATION_ENDPOINT;
                    }
                    Request request1 = new Request.Builder()
                        .url(requestEndpoint1)
                        .post(requestBody1.build())
                        .build();
                    JSONObject parsedResponse1 = executeJsonRequest(request1, requestEndpoint1);
                    if (parsedResponse1.has("refresh_token")) {
                        saveRefreshToken(provider, (String) parsedResponse1.get("refresh_token"));
                        linkSuccess(initiator, provider, logger);
                        cancelPollTask();
                    } else if (
                        (provider == AuthenticationProvider.ONEDRIVE && !parsedResponse1.getString("error").equals("authorization_pending")) ||
                        (provider == AuthenticationProvider.GOOGLE_DRIVE
                                && !parsedResponse1.getString("error").equals("authorization_pending")
                                && !parsedResponse1.getString("error").equals("slow_down")) ||
                        (provider == AuthenticationProvider.DROPBOX && !parsedResponse1.get("msg").equals("code_not_authenticated"))
                        ) {
                        MessageUtil.Builder().text(parsedResponse1.toString()).send();
                        throw new UploadException();
                    }
                } catch (Exception exception) {
                    NetUtil.catchException(exception, getPollEndpoint(provider), logger);
                    logger.log(intl("link-provider-failed"), "provider", provider.getName());
                    MessageUtil.sendConsoleException(exception);
                    cancelPollTask();
                }
            }, responseCheckDelay, responseCheckDelay);
        } catch (Exception exception) {
            NetUtil.catchException(exception, provider == AuthenticationProvider.GOOGLE_DRIVE
                    ? GOOGLE_REQUEST_CODE_ENDPOINT : DRIVEBACKUP_AUTH_URL, logger);
            logger.log(intl("link-provider-failed"), "provider", provider.getName());
            MessageUtil.sendConsoleException(exception);
        }
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
        if (provider == AuthenticationProvider.ONEDRIVE) {
            return ONEDRIVE_POLL_VERIFICATION_ENDPOINT;
        }
        return POLL_VERIFICATION_ENDPOINT;
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

    public static void linkSuccess(CommandSender initiator, @NotNull AuthenticationProvider provider, @NotNull Logger logger) {
        logger.log(intl("link-provider-complete"), "provider", provider.getName());
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
