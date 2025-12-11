package windsor.sevenzipbackup.config.configSections;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import windsor.sevenzipbackup.util.Logger;

public class BossBarConfig {
    public final boolean showBossBarProgress;
    public final BarColor bossBarColor;
    public final BarStyle bossBarStyle;

    private BossBarConfig(boolean showBossBarProgress, BarColor bossBarColor, BarStyle bossBarStyle) {
        this.showBossBarProgress = showBossBarProgress;
        this.bossBarColor = bossBarColor;
        this.bossBarStyle = bossBarStyle;
    }

    public static BossBarConfig parse(FileConfiguration config, Logger logger) {
        // 读取是否显示BossBar进度条
        boolean showBossBarProgress = config.getBoolean("show-bossbar-progress", true);

        // 读取BossBar颜色，默认为BLUE
        String colorStr = config.getString("bossbar-color", "BLUE");
        BarColor bossBarColor;
        try {
            bossBarColor = BarColor.valueOf(colorStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            //logger.log("无效的bossbar-color配置: " + colorStr + "，使用默认值 BLUE");
            bossBarColor = BarColor.BLUE;
        }

        // 读取BossBar样式，默认为SOLID
        String styleStr = config.getString("bossbar-style", "SOLID");
        BarStyle bossBarStyle;
        try {
            bossBarStyle = BarStyle.valueOf(styleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            //logger.log("无效的bossbar-style配置: " + styleStr + "，使用默认值 SOLID");
            bossBarStyle = BarStyle.SOLID;
        }

        return new BossBarConfig(showBossBarProgress, bossBarColor, bossBarStyle);
    }
}