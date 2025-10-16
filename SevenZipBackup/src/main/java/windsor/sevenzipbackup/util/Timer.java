package windsor.sevenzipbackup.util;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Date;
import java.util.Locale;

import static windsor.sevenzipbackup.config.Localization.intl;

public class Timer {
    private Date start;
    private Date end;

    public Timer() {

    }

    /**
     * Starts the timer
     */
    public void start() {
        start = new Date();
        end = null;
    }

    /**
     * Ends the timer
     */
    public void end() {
        if (start == null) {
            return;
        }
        end = new Date();
    }

    /**
     * Construct an upload message
     * @param file file that was uploaded
     * @return message
     */
    public String getUploadTimeMessage(@NotNull File file) {
        DecimalFormat df = new DecimalFormat("#.##");
        df.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.ENGLISH));

        double difference = getTime();
        double length = difference / 1000;
        double speed = ( ((double) file.length()) / 1024) / length;
        
        return intl("file-upload-message")
            .replace("<length>", df.format(length))
            .replace("<speed>", df.format(speed));
    }

    /**
     * Calculates the time
     * @return Calculated time
     */
    public long getTime() {
        return end.getTime() - start.getTime();
    }

}
