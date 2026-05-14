package windsor.sevenzipbackup.util;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.TemporalAccessor;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import windsor.sevenzipbackup.plugin.Scheduler;

public class SchedulerUtil {
    private static final int TICKS_PER_SECOND = 20;

    private SchedulerUtil() {}

    /**
     * 取消指定的 Cancellable 任务列表（适配 Folia）
     * @param taskList 要取消的任务列表
     */
    public static void cancelTasks(@NotNull List<Scheduler.Cancellable> taskList) {
        for (Scheduler.Cancellable task : taskList) {
            task.cancel();
        }
        taskList.clear();
    }

    /**
     * Converts the specified number of seconds to game ticks
     * @param seconds the number of seconds
     * @return the number of game ticks
     */
    public static long sToTicks(long seconds) {
        return seconds * TICKS_PER_SECOND;
    }

    /**
     * Parses the time
     * @param time the time, as a String
     * @return the parsed time
     */
    @NotNull
    public static TemporalAccessor parseTime(String time) throws IllegalArgumentException {
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .appendOptional(DateTimeFormatter.ofPattern("kk:mm"))
                .appendOptional(DateTimeFormatter.ofPattern("k:mm"))
                .toFormatter();

        return formatter.parse(time);
    }
}