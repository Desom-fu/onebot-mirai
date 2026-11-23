package cn.evole.onebot.mirai.util;

import cn.evole.onebot.mirai.database.NanoDb;
import net.mamoe.mirai.utils.MiraiLogger;

import java.io.File;
import java.util.function.Supplier;

/**
 * 插件运行时上下文持有器：在脱离 Mirai Console（如单元测试）时提供安全默认值，避免静态初始化异常。
 */
public final class PluginRuntime {
    private static volatile MiraiLogger logger = MiraiLogger.Factory.INSTANCE.create(PluginRuntime.class, "OneBotMirai");
    private static volatile String version = "0.2.3";
    private static volatile File dataFolder = new File("data" + File.separator + "cn.evole.mirai.onebot");
    private static volatile Supplier<Boolean> debugSupplier = () -> false;
    private static volatile Supplier<Boolean> dbEnabledSupplier = () -> false;
    private static volatile Supplier<NanoDb<Long, DBUtils.MessageNode>> dbSupplier = () -> null;

    private PluginRuntime() {
    }

    public static void bind(
            MiraiLogger pluginLogger,
            String pluginVersion,
            File pluginDataFolder,
            Supplier<Boolean> debug,
            Supplier<Boolean> dbEnabled,
            Supplier<NanoDb<Long, DBUtils.MessageNode>> db
    ) {
        if (pluginLogger != null) {
            logger = pluginLogger;
        }
        if (pluginVersion != null && !pluginVersion.isBlank()) {
            version = pluginVersion;
        }
        if (pluginDataFolder != null) {
            dataFolder = pluginDataFolder;
        }
        if (debug != null) {
            debugSupplier = debug;
        }
        if (dbEnabled != null) {
            dbEnabledSupplier = dbEnabled;
        }
        if (db != null) {
            dbSupplier = db;
        }
    }

    public static MiraiLogger getLogger() {
        return logger;
    }

    public static String getVersion() {
        return version;
    }

    public static File getDataFolder() {
        return dataFolder;
    }

    public static File getImageFolder() {
        return new File(dataFolder, "image");
    }

    public static File getRecordFolder() {
        return new File(dataFolder, "record");
    }

    public static boolean isDebug() {
        try {
            Boolean v = debugSupplier.get();
            return Boolean.TRUE.equals(v);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isDbEnabled() {
        try {
            Boolean v = dbEnabledSupplier.get();
            return Boolean.TRUE.equals(v);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static NanoDb<Long, DBUtils.MessageNode> getDb() {
        try {
            return dbSupplier.get();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
