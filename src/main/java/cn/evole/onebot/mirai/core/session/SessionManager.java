package cn.evole.onebot.mirai.core.session;

import cn.evole.onebot.mirai.config.PluginConfig;
import cn.evole.onebot.mirai.core.session.BotSession;
import com.google.gson.Gson;
import lombok.Getter;
import net.mamoe.mirai.Bot;

import java.util.LinkedHashMap;

/**
 * Description:客户端连接管理器
 * Author: cnlimiter
 * Date: 2022/10/8 19:34
 * Version: 1.0
 */
public class SessionManager {


    @Getter
    private static final LinkedHashMap<Long, BotSession> sessions = new LinkedHashMap<>();//一个机器人对应一个session

    private static final Gson GSON = new Gson();
    private static final net.mamoe.mirai.utils.MiraiLogger LOGGER =
            net.mamoe.mirai.utils.MiraiLogger.Factory.INSTANCE.create(SessionManager.class, "SessionManager");

    public static BotSession get(long botId) {
        return sessions.get(botId);
    }

    public static boolean containsSession(long botId) {
        return sessions.containsKey(botId);
    }

    public static void closeSession(long botId) {
        BotSession session = sessions.get(botId);
        if (session != null) {
            session.close();
        }
        sessions.remove(botId);

    }

    public static void closeAllSessions() {
        sessions.forEach((id, bs) -> bs.close());
        sessions.clear();
    }

    /**
     * 每个 BotSession 必须持有属于自己账号的独立配置副本。
     * 多账号同进程时，配置对象若被多个 session 共享，后登录的账号会把先登录账号的
     * http.port / accessToken 覆盖掉（表现为 9873 端口认了另一个号的 token）。
     * 这里始终按 bot 自身 id 从 PluginConfig 取一次，再深拷贝隔离。
     */
    public static BotSession createBotSession(Bot bot, PluginConfig.BotConfig botConfig) {
        PluginConfig.BotConfig resolved = null;
        var bots = PluginConfig.INSTANCE.getBots();
        if (bots != null) {
            resolved = bots.get(String.valueOf(bot.getId()));
        }
        if (resolved == null) {
            resolved = botConfig;
        }
        PluginConfig.BotConfig isolated = isolate(resolved);
        if (isolated != null && isolated.getHttp() != null) {
            String token = isolated.getHttp().getAccessToken();
            LOGGER.info(String.format("Session 配置解析: bot=%s -> %s:%s (token=%s)",
                    bot.getId(), isolated.getHttp().getHost(), isolated.getHttp().getPort(),
                    (token == null || token.isBlank()) ? "<empty>" : Integer.toHexString(token.hashCode())));
        }
        var session = new BotSession(bot, isolated);
        sessions.put(bot.getId(), session);
        return session;
    }

    private static PluginConfig.BotConfig isolate(PluginConfig.BotConfig source) {
        if (source == null) {
            return null;
        }
        try {
            String json = GSON.toJson(source);
            PluginConfig.BotConfig copy = GSON.fromJson(json, PluginConfig.BotConfig.class);
            return copy != null ? copy : source;
        } catch (Exception ignored) {
            return source;
        }
    }
}
