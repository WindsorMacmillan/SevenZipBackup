package windsor.sevenzipbackup.handler;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.config.configSections.BackupMethods;
import windsor.sevenzipbackup.constants.Permission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommandTabComplete implements TabCompleter {
    
    public static boolean hasPerm(CommandSender player, Permission permission) {
        return player.hasPermission(permission.getPermission());
    }

    /**
     * Command tab completer
     *
     * @param player Player, who sent command
     * @param cmd    Command that was sent
     * @param label  Command alias that was used
     * @param args   Arguments that followed command
     * @return String list of possible tab completions
     */
    @Override
    public List<String> onTabComplete(@NotNull CommandSender player, @NotNull Command cmd, @NotNull String label, String[] args) {
        if ("7zbackup".equalsIgnoreCase(cmd.getName())) {
            if (args.length == 1) {
                List<String> commandList = new ArrayList<>(10);
                commandList.add("v");
                commandList.add("help");
                commandList.add("commands");
                if (hasPerm(player, Permission.LINK_ACCOUNTS)) {
                    commandList.add("linkaccount");
                    commandList.add("unlinkaccount");
                }
                if (hasPerm(player, Permission.RELOAD_CONFIG)) {
                    commandList.add("reloadconfig");
                    commandList.add("debug");
                }
                if (hasPerm(player, Permission.GET_BACKUP_STATUS)) {
                    commandList.add("status");
                }
                if (hasPerm(player, Permission.GET_NEXT_BACKUP)) {
                    commandList.add("nextbackup");
                }
                if (hasPerm(player, Permission.BACKUP)) {
                    commandList.add("backup");
                    commandList.add("test");
                    commandList.add("update");
                }
                return commandList;
            } else if (args[0].equalsIgnoreCase("linkaccount") && args.length == 2) {
                if (!hasPerm(player, Permission.LINK_ACCOUNTS)) {
                    return Collections.emptyList();
                }
                List<String> commandList = new ArrayList<>(3);
                commandList.add("googledrive");
                commandList.add("onedrive");
                commandList.add("dropbox");
                return commandList;
            } else if (args[0].equalsIgnoreCase("test") && args.length == 2) {
                if (!hasPerm(player, Permission.BACKUP)) {
                    return Collections.emptyList();
                }
                return getStrings();
            }
        }
        return Collections.emptyList();
    }

    private static @NotNull List<String> getStrings() {
        List<String> commandList = new ArrayList<>(6);
        BackupMethods methods = ConfigParser.getConfig().backupMethods;
        if (methods.googleDrive.enabled) {
            commandList.add("googledrive");
        }
        if (methods.oneDrive.enabled) {
            commandList.add("onedrive");
        }
        if (methods.dropbox.enabled) {
            commandList.add("dropbox");
        }
        if (methods.webdav.enabled) {
            commandList.add("webdav");
        }
        if (methods.nextcloud.enabled) {
            commandList.add("nextcloud");
        }
        if (methods.s3.enabled) {
            commandList.add("s3");
        }
        if (methods.ftp.enabled) {
            commandList.add("ftp");
        }
        return commandList;
    }
}
