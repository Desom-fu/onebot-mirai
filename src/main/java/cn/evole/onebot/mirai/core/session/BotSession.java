package cn.evole.onebot.mirai.core.session;

import cn.evole.onebot.mirai.config.PluginConfig;
import cn.evole.onebot.mirai.config.PluginConfig.BotConfig;
import cn.evole.onebot.mirai.core.ApiMap;
import cn.evole.onebot.mirai.core.EventMap;
import cn.evole.onebot.mirai.util.BaseUtils;
import cn.evole.onebot.mirai.util.HttpUtils;
import cn.evole.onebot.mirai.util.OnebotMsgUtils;
import cn.evole.onebot.mirai.util.PluginRuntime;
import cn.evole.onebot.mirai.web.http.OneBotHttpServer;
import cn.evole.onebot.mirai.web.websocket.OneBotWSClient;
import cn.evole.onebot.mirai.web.websocket.OneBotWSServer;
import cn.evole.onebot.sdk.event.Event;
import cn.evole.onebot.sdk.event.IgnoreEvent;
import cn.evole.onebot.sdk.event.meta.HeartbeatMetaEvent;
import cn.evole.onebot.sdk.event.meta.LifecycleMetaEvent;
import cn.evole.onebot.sdk.util.json.GsonUtils;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.event.events.BotEvent;
import net.mamoe.mirai.utils.MiraiLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 机器人会话单元：管理正向 HTTP、正向 WS、反向 WS、异步顺序 HTTP POST 上报与生命周期/心跳元事件。
 */
@Getter
public class BotSession {

    private final Bot bot;
    private final ApiMap apiImpl;
    private final BotConfig botConfig;
    private final OneBotHttpServer httpServer;
    private final OneBotWSServer websocketServer;
    private final List<OneBotWSClient> websocketClient = new ArrayList<>();
    private final MiraiLogger miraiLogger = PluginRuntime.getLogger();
    private final ExecutorService httpPostExecutor;
    private ScheduledExecutorService heartbeatScheduler;

    private final ThreadLocal<Gson> gsonTl = ThreadLocal.withInitial(GsonUtils::getGson);

    @Override
    public String toString() {
        return """
                Bot Id: %s,
                Bot Config: %s,
                OneBot HTTP Server: %s,
                OneBot WS Server: %s,
                OneBot WS Clients: %s
                """.formatted(
                this.bot.getId(),
                this.botConfig.toString(),
                this.httpServer.getAddress(),
                this.websocketServer.getAddress(),
                this.websocketClient.size()
        );
    }

    public BotSession(Bot bot, BotConfig botConfig) {
        this.bot = bot;
        this.apiImpl = new ApiMap(bot);
        this.botConfig = botConfig;
        this.httpServer = new OneBotHttpServer(this, botConfig);
        this.websocketServer = new OneBotWSServer(this, botConfig);
        this.httpPostExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OneBot-HttpPost-" + bot.getId());
            t.setDaemon(true);
            return t;
        });

        if (this.botConfig.getHttp().getEnable()) {
            this.miraiLogger.info(String.format("创建正向HTTP服务器: %s:%s (bot=%s, token=%s)",
                    botConfig.getHttp().getHost(), botConfig.getHttp().getPort(),
                    bot.getId(), tokenFingerprint(botConfig.getHttp().getAccessToken())));
            this.httpServer.create();
        }
        if (this.botConfig.getWs().getEnable()) {
            this.miraiLogger.info(String.format("创建正向WS服务器: %s:%s", botConfig.getWs().getWsHost(), botConfig.getWs().getWsPort()));
            this.websocketServer.create();
        }

        for (PluginConfig.WSReverseConfig wsRe : botConfig.getWsReverse()) {
            if (wsRe.getEnable()) {
                OneBotWSClient client = new OneBotWSClient(this, wsRe);
                client.create();
                this.miraiLogger.info(String.format("创建反向WS客户端: %s:%s", wsRe.getReverseHost(), wsRe.getReversePort()));
                this.websocketClient.add(client);
            }
        }

        // 启动时上报 lifecycle connect 元事件
        emitLifecycleEvent("connect");

        // 按配置定时上报 heartbeat 元事件（毫秒，最小 1000ms）
        if (this.botConfig.getHeartbeat().getEnable()) {
            long intervalMs = Math.max(1000L, this.botConfig.getHeartbeat().getInterval());
            this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "OneBot-Heartbeat-" + bot.getId());
                t.setDaemon(true);
                return t;
            });
            this.heartbeatScheduler.scheduleAtFixedRate(
                    () -> emitHeartbeatEvent(intervalMs),
                    intervalMs,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    public void close() {
        if (this.heartbeatScheduler != null) {
            this.heartbeatScheduler.shutdownNow();
        }

        // 关闭前上报 lifecycle disable 元事件
        emitLifecycleEvent("disable");

        this.httpPostExecutor.shutdown();
        try {
            this.httpPostExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (this.botConfig.getHttp().getEnable()) {
            this.httpServer.close();
        }
        if (this.botConfig.getWs().getEnable()) {
            this.websocketServer.close();
        }
        this.websocketClient.forEach(OneBotWSClient::close);
    }

    private static String tokenFingerprint(String token) {
        if (token == null || token.isBlank()) {
            return "<empty>";
        }
        return Integer.toHexString(token.hashCode()) + "(len=" + token.length() + ")";
    }

    private void emitLifecycleEvent(String subType) {
        LifecycleMetaEvent lifecycle = new LifecycleMetaEvent();
        lifecycle.setPostType("meta_event");
        lifecycle.setMetaEventType("lifecycle");
        lifecycle.setSubType(subType);
        lifecycle.setSelfId(bot.getId());
        lifecycle.setTime(System.currentTimeMillis() / 1000L);
        emitEvent(lifecycle);
    }

    private void emitHeartbeatEvent(long intervalMs) {
        try {
            HeartbeatMetaEvent hb = new HeartbeatMetaEvent();
            hb.setPostType("meta_event");
            hb.setMetaEventType("heartbeat");
            hb.setSelfId(bot.getId());
            hb.setTime(System.currentTimeMillis() / 1000L);
            hb.setInterval(intervalMs);
            JsonObject status = new JsonObject();
            status.addProperty("online", bot.isOnline());
            status.addProperty("good", bot.isOnline());
            hb.setStatus(status);
            emitEvent(hb);
        } catch (Throwable t) {
            this.miraiLogger.warning("定时心跳事件生成失败: " + t.getMessage());
        }
    }

    public void triggerEvent(BotEvent event) {
        Event dto = EventMap.toDTO(event);
        emitEvent(dto);
    }

    public void emitEvent(Event dto) {
        if (dto == null || dto instanceof IgnoreEvent) {
            return;
        }
        boolean debug = PluginRuntime.isDebug();

        // 1. HTTP POST 异步顺序上报
        PluginConfig.HTTPConfig httpCfg = this.botConfig.getHttp();
        if (httpCfg.getEnable() && httpCfg.getPostUrl() != null && !httpCfg.getPostUrl().isBlank()) {
            String payload = formatEventJson(dto, httpCfg.getPostMessageFormat());
            if (!this.httpPostExecutor.isShutdown()) {
                this.httpPostExecutor.execute(() -> postHttpEventWithRetry(httpCfg, payload, debug));
            }
        }

        // 2. 正向 WS 广播
        if (this.botConfig.getWs().getEnable()) {
            String wsPayload = formatEventJson(dto, this.botConfig.getWs().getPostMessageFormat());
            if (debug) {
                this.miraiLogger.info("广播正向websocket事件");
            }
            try {
                this.websocketServer.broadcast(wsPayload);
            } catch (Exception ex) {
                this.miraiLogger.warning("广播正向websocket事件失败", ex);
            }
        }

        // 3. 反向 WS 广播
        for (int i = 0; i < this.botConfig.getWsReverse().size(); i++) {
            PluginConfig.WSReverseConfig wsRe = this.botConfig.getWsReverse().get(i);
            if (!wsRe.getEnable()) {
                continue;
            }
            String wsRePayload = formatEventJson(dto, wsRe.getPostMessageFormat());
            long sendCount = this.websocketClient.stream().filter(client -> {
                try {
                    if (client.isOpen()) {
                        client.send(wsRePayload);
                        return true;
                    }
                } catch (Exception ex) {
                    this.miraiLogger.warning("广播反向websocket事件失败:", ex);
                }
                return false;
            }).count();
            if (debug) {
                this.miraiLogger.info(String.format("广播反向websocket事件, 共发送 :%d", sendCount));
            }
            break; // websocketClient 已包含全部已启用客户端，避免重复广播
        }
    }

    /**
     * 按 postMessageFormat (string / array) 序列化事件 JSON。
     */
    public String formatEventJson(Event event, String postMessageFormat) {
        Gson gson = gsonTl.get();
        if (!"array".equalsIgnoreCase(postMessageFormat)) {
            return gson.toJson(event);
        }
        JsonElement tree = gson.toJsonTree(event);
        if (tree.isJsonObject()) {
            JsonObject obj = tree.getAsJsonObject();
            if (obj.has("message") && obj.get("message").isJsonPrimitive()) {
                String cqMsg = obj.get("message").getAsString();
                if (!obj.has("raw_message") || obj.get("raw_message").isJsonNull()) {
                    obj.addProperty("raw_message", cqMsg);
                }
                obj.add("message", OnebotMsgUtils.cqStringToSegmentArray(cqMsg));
            }
        }
        return gson.toJson(tree);
    }

    private void postHttpEventWithRetry(PluginConfig.HTTPConfig httpCfg, String json, boolean debug) {
        String postUrl = httpCfg.getPostUrl();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "OneBot-Mirai/" + PluginRuntime.getVersion());
        headers.put("X-Self-ID", String.valueOf(bot.getId()));
        if (httpCfg.getSecret() != null && !httpCfg.getSecret().isEmpty()) {
            headers.put("X-Signature", "sha1=" + BaseUtils.hmacSha1Hex(json, httpCfg.getSecret()));
        }
        int timeoutMs = httpCfg.getTimeout() > 0 ? (int) Math.min(httpCfg.getTimeout(), Integer.MAX_VALUE) : 5000;

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                if (debug) {
                    this.miraiLogger.info("上报HTTP事件 (attempt=" + attempt + "): " + postUrl);
                }
                HttpUtils.HttpResponseResult res = HttpUtils.jsonPostWithStatus(postUrl, json, headers, timeoutMs);
                if (res.isSuccess()) {
                    if (debug && res.body() != null && !res.body().isEmpty()) {
                        this.miraiLogger.info("收到HTTP上报响应: " + res.body());
                    }
                    return;
                }
                if (attempt == 1) {
                    Thread.sleep(500L);
                } else {
                    this.miraiLogger.warning("HTTP事件上报失败 (status=" + res.statusCode() + ", url=" + postUrl + ")");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (attempt == 1) {
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    this.miraiLogger.warning("HTTP事件上报重试仍失败 (url=" + postUrl + "): " + e.getMessage());
                }
            }
        }
    }
}
