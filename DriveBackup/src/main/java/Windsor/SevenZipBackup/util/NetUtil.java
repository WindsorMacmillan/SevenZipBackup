package Windsor.SevenZipBackup.util;

import java.net.UnknownHostException;

import static Windsor.SevenZipBackup.config.Localization.intl;

public class NetUtil {
    public static void catchException(Exception exception, String domain) {
        Logger logger = (input, placeholders) -> MessageUtil.Builder()
            .mmText(input, placeholders)
            .send();

        catchException(exception, domain, logger);
    }

    public static void catchException(Exception exception, String domain, Logger logger) {
        if (!(exception instanceof UnknownHostException)) {
            return;
        }

        logger.log(intl("connection-error"), "domain", domain);
    }
}
