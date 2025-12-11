package windsor.sevenzipbackup.handler.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import windsor.sevenzipbackup.UploadThread;
import windsor.sevenzipbackup.config.Localization;
import windsor.sevenzipbackup.config.PermissionHandler;
import windsor.sevenzipbackup.constants.Permission;
import windsor.sevenzipbackup.plugin.updater.UpdateChecker;
import windsor.sevenzipbackup.util.MessageUtil;

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
        // 如果当前正在备份，将玩家添加到BossBar
        if (UploadThread.isBackupInProgress()) {
            UploadThread.addPlayerToBossBar(player);
        }
    }

    public static boolean isAutoBackupsActive() {
        return autoBackupsActive;
    }

    public static void setAutoBackupsActive(boolean autoBackupsActiveValue) {
        autoBackupsActive = autoBackupsActiveValue;
    }
}
