package Windsor.SevenZipBackup.handler.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import Windsor.SevenZipBackup.UploadThread;
import Windsor.SevenZipBackup.config.Localization;
import Windsor.SevenZipBackup.config.PermissionHandler;
import Windsor.SevenZipBackup.constants.Permission;
import Windsor.SevenZipBackup.plugin.updater.UpdateChecker;
import Windsor.SevenZipBackup.util.MessageUtil;

public class PlayerListener implements Listener {
    private static boolean autoBackupsActive;

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!autoBackupsActive) {
            MessageUtil.Builder().mmText(Localization.intl("player-join-backup-enable")).send();
            autoBackupsActive = true;
        }
        Player player = event.getPlayer();
        if (UpdateChecker.isUpdateAvailable() && PermissionHandler.hasPerm(player, Permission.LINK_ACCOUNTS)) {
            MessageUtil.Builder().mmText(Localization.intl("player-join-update-available")).to(player).toConsole(false).send();
        }
        if (!UploadThread.wasLastBackupSuccessful() && PermissionHandler.hasPerm(player, Permission.BACKUP)) {
            MessageUtil.Builder().mmText(Localization.intl("player-join-backup-failed")).to(player).toConsole(false).send();
        }
    }

    public static boolean isAutoBackupsActive() {
        return autoBackupsActive;
    }

    public static void setAutoBackupsActive(boolean autoBackupsActiveValue) {
        autoBackupsActive = autoBackupsActiveValue;
    }
}
