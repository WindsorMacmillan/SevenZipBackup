package windsor.sevenzipbackup.handler.listeners;

import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.jetbrains.annotations.NotNull;
import windsor.sevenzipbackup.UploadThread.UploadLogger;
import windsor.sevenzipbackup.plugin.SevenZipBackup;
import windsor.sevenzipbackup.plugin.Scheduler;
import windsor.sevenzipbackup.uploaders.googledrive.GoogleDriveUploader;
import windsor.sevenzipbackup.util.MessageUtil;

public class ChatInputListener implements Listener {

    @EventHandler
    public void onPlayerChat(@NotNull AsyncPlayerChatEvent event) {
        if (handleInput(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCommand(@NotNull ServerCommandEvent event) {
        if (handleInput(event.getSender(), event.getCommand())) {
            event.setCancelled(true);
        }
    }
    
    /**
     * Handles input from a player
     * <p>
     * {@link AsyncPlayerChatEvent} 在异步线程触发，而后续会写 config.yml、修改共享集合，
     * 这些都不是线程安全的。因此实际处理统一切回主线程执行（Folia 用全局区域线程）。
     *
     * @param sender the player who sent the input
     * @param input the input
     * @return whether the input was handled
     */
    private static boolean handleInput(CommandSender sender, String input) {
        if (!SevenZipBackup.chatInputPlayers.contains(sender)) {
            return false;
        }
        UploadLogger uploadLogger = new UploadLogger() {
            @Override
            public void log(String input, String... placeholders) {
                MessageUtil.Builder().mmText(input, placeholders).to(sender).send();
            }
        };
        Scheduler.runSyncTask(() -> {
            // 切线程期间该玩家可能已被其他流程移除，二次确认
            if (!SevenZipBackup.chatInputPlayers.remove(sender)) {
                return;
            }
            new GoogleDriveUploader(uploadLogger).finalizeSharedDrives(sender, input);
        });
        return true;
    }
}
