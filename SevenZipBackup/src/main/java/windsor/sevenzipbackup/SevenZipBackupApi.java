package windsor.sevenzipbackup;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import windsor.sevenzipbackup.config.ConfigParser;
import windsor.sevenzipbackup.config.ConfigParser.Config;
import windsor.sevenzipbackup.plugin.SevenZipBackup;
import windsor.sevenzipbackup.util.MessageUtil;

public class SevenZipBackupApi {
    private static final ArrayList<Callable<Boolean>> beforeBackupStartCallables = new ArrayList<>();
    private static final ArrayList<Runnable> onBackupDoneRunnables = new ArrayList<>();
    private static final ArrayList<Runnable> onBackupErrorRunnables = new ArrayList<>();

    /**
     * Gets whether to proceed with the backup by executing the {@code Callable}s specified by API users.
     * @return whether to proceed
     */
    static boolean shouldStartBackup() {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        ArrayList<Future<Boolean>> futures = new ArrayList<>();

        for (Callable<Boolean> callable : beforeBackupStartCallables) {
            futures.add(executor.submit(callable));
        }

        boolean shouldStartBackup = true;

        for (Future<Boolean> future : futures){
            try {

                if (Boolean.FALSE.equals(future.get(10, TimeUnit.SECONDS))) {
                    shouldStartBackup = false;
                    MessageUtil.Builder().text("Not starting a backup due to a beforeBackupStart() Callable returning false").toConsole(true).send();

                    break;
                }
            } catch (Exception exception) {
                MessageUtil.Builder().text("Failed to execute a beforeBackupStart() Callable, ignoring").toConsole(true).send();
                MessageUtil.sendConsoleException(exception);
            }
        }

        return shouldStartBackup;
    }

    /**
     * Runs the {@code Callable}s specified by API users to be run after a backup is successfully completed.
     */
    static void backupDone() {
        for (Runnable runnable : onBackupDoneRunnables) {
            new Thread(runnable).start();
        }
    }

    /**
     * Runs the {@code Callable}s specified by API users to be run after an error occurs during a backup.
     */
    static void backupError() {
        for (Runnable runnable : onBackupErrorRunnables) {
            new Thread(runnable).start();
        }
    }

    /**
     * Gets the plugin's parsed config as an object
     * @return the config
     */
    public static Config getConfig() {
        return ConfigParser.getConfig();
    }

    /**
     * Runs the specified {@code Callable} after a backup has been initiated (either manually using {@code /7zbackup backup} or the API, or automatically with scheduled or interval-based backups), but before the backup process has been started.
     * <p>
     * Multiple {@code Callable}s can be specified by calling this method multiple times.
     * <p>
     * If any {@code Callable} returns {@code false}, the backup will be canceled
     * <p>
     * If the {@code Callable} doesn't return in 10 seconds, the {@code Callable} will be ignored.
     * @param callable the {@code Callable}
     */
    public static void beforeBackupStart(Callable<Boolean> callable) {
        beforeBackupStartCallables.add(callable);
    }

    /**
     * Runs the specified {@code Runnable} after a backup is successfully completed
     * @param runnable the {@code Runnable}
     */
    public static void onBackupDone(Runnable runnable) {
        onBackupDoneRunnables.add(runnable);
    }

    /**
     * Runs the specified {@code Runnable} after an error occurs during a backup
     * @param runnable the {@code Runnable}
     */
    public static void onBackupError(Runnable runnable) {
        onBackupErrorRunnables.add(runnable);
    }

    /**
     * Starts a backup
     * <p>
     * Behaves identically to running {@code /7zbackup backup}
     */
    public static void startBackup() {
        new Thread(new UploadThread()).start();
    }

    /**
     * Reloads the SevenZipBackup plugin's {@code config.yml}
     * <p>
     * Behaves identically to running {@code /7zbackup reloadconfig}
     */
    public static void reloadConfig() {
        SevenZipBackup.reloadLocalConfig();
    }

    /**
     * Returns the message sent to chat when {@code /7zbackup nextbackup} is run
     * <p>
     * For more information about the {@code /7zbackup nextbackup} command, see <a href="https://github.com/MaxMaeder/SevenZipBackup/wiki/Commands#7zbackup-nextbackup">this</a>
     * @return the message
     */
    public static String getNextAutoBackup() {
        return UploadThread.getNextAutoBackup();
    }
}
