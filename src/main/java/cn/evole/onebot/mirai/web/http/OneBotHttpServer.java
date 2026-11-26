package cn.evole.onebot.mirai.web.http;

import cn.evole.onebot.mirai.config.PluginConfig;
import cn.evole.onebot.mirai.core.ActionRegistry;
import cn.evole.onebot.mirai.core.session.BotSession;
import cn.evole.onebot.mirai.model.DetailedFailure;
import cn.evole.onebot.mirai.util.PluginRuntime;
import cn.evole.onebot.sdk.action.ActionData;
import cn.evole.onebot.sdk.util.json.GsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.mamoe.mirai.utils.MiraiLogger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OneBot v11 正向 HTTP API 服务器：基于 ActionRegistry 全量注册 POST/GET，支持标准平铺 JSON 与包装格式、Bearer/Query 鉴权。
 * 每个 Bot 实例独立持有自己的 HttpServer 与路由分发器，避免第三方库静态路由表导致多账号串号。
 */
public class OneBotHttpServer {
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private final MiraiLogger miraiLogger = PluginRuntime.getLogger();
    private final BotSession botSession;
    private final PluginConfig.HTTPConfig config;
    private volatile HttpServer httpServer;
    private volatile ExecutorService executor;

    public OneBotHttpServer(BotSession botSession, PluginConfig.BotConfig botConfig) {
        this.botSession = botSession;
        this.config = botConfig.getHttp();
    }

    public String getAddress() {
        HttpServer server = this.httpServer;
        if (server == null) {
            return config.getHost() + ":" + config.getPort();
        }
        return server.getAddress().toString();
    }

    public void create() {
        long botId = botSession.getBot().getId();
        String host = config.getHost();
        InetSocketAddress bindAddr = (host == null || host.isBlank())
                ? new InetSocketAddress(config.getPort())
                : new InetSocketAddress(host.trim(), config.getPort());

        HttpServer server = null;
        ExecutorService pool = null;
        try {
            server = HttpServer.create(bindAddr, 0);
            AtomicInteger workerCounter = new AtomicInteger(0);
            pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "OneBot-Http-" + botId + "-" + workerCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(pool);
            server.createContext("/", this::handleExchange);
            server.start();

            this.executor = pool;
            this.httpServer = server;
            this.miraiLogger.info(String.format("OneBot HTTP 服务已绑定: %s (bot=%s)", getAddress(), botId));
        } catch (IOException e) {
            if (server != null) {
                server.stop(0);
            }
            if (pool != null) {
                pool.shutdownNow();
            }
            throw new RuntimeException(e);
        } catch (RuntimeException | Error e) {
            if (server != null) {
                server.stop(0);
            }
            if (pool != null) {
                pool.shutdownNow();
            }
            throw e;
        }
    }

    public void close() {
        HttpServer server = this.httpServer;
        this.httpServer = null;
        if (server != null) {
            server.stop(2);
        }
        ExecutorService pool = this.executor;
        this.executor = null;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void handleExchange(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : "";
            String pathAction = stripLeadingSlash(path);
            if (!ActionRegistry.contains(pathAction)) {
                if (!checkAuth(exchange)) {
                    return;
                }
                sendActionData(exchange, 404, DetailedFailure.notFound(pathAction), null);
                return;
            }

            String method = exchange.getRequestMethod();
            if ("POST".equalsIgnoreCase(method)) {
                handlePost(pathAction, exchange);
            } else if ("GET".equalsIgnoreCase(method)) {
                handleGet(pathAction, exchange);
            } else {
                if (!checkAuth(exchange)) {
                    return;
                }
                sendActionData(exchange, 405, DetailedFailure.fail("不支持的 HTTP 方法: " + method), null);
            }
        } catch (Throwable t) {
            miraiLogger.error("处理 HTTP API 请求异常", t);
            sendActionData(exchange, 200, DetailedFailure.fail("服务端处理异常: " + t.getMessage()), null);
        } finally {
            exchange.close();
        }
    }

    private void handlePost(String pathAction, HttpExchange exchange) {
        try {
            if (!checkAuth(exchange)) {
                return;
            }

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] rawBytes = exchange.getRequestBody().readAllBytes();
            String bodyStr = new String(rawBytes, StandardCharsets.UTF_8).trim();

            if (!bodyStr.isEmpty() && contentType != null && !contentType.isBlank()
                    && !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                sendActionData(exchange, 406, DetailedFailure.fail("Content-Type 不支持，请使用 application/json"), null);
                return;
            }

            JsonObject bodyObj = new JsonObject();
            if (!bodyStr.isEmpty()) {
                try {
                    JsonElement parsed = JsonParser.parseString(bodyStr);
                    if (parsed.isJsonObject()) {
                        bodyObj = parsed.getAsJsonObject();
                    } else {
                        sendActionData(exchange, 400, DetailedFailure.fail("请求体必须是 JSON 对象"), null);
                        return;
                    }
                } catch (Exception e) {
                    sendActionData(exchange, 400, DetailedFailure.fail("请求体 JSON 解析失败: " + e.getMessage()), null);
                    return;
                }
            }

            String echo = extractEcho(bodyObj);
            String targetAction = pathAction;
            JsonObject params;

            // 同时兼容包装格式 {action, params, echo} 与标准 OneBot v11 / OlivOS 平铺参数格式
            if (bodyObj.has("params") && bodyObj.get("params").isJsonObject()) {
                if (bodyObj.has("action") && !bodyObj.get("action").isJsonNull()) {
                    String bodyAction = bodyObj.get("action").getAsString().trim();
                    if (!bodyAction.isEmpty()) {
                        targetAction = bodyAction;
                    }
                }
                params = bodyObj.getAsJsonObject("params");
            } else if (bodyObj.has("action") && bodyObj.get("action").isJsonPrimitive()
                    && bodyObj.size() <= 2
                    && (!bodyObj.has("params") || bodyObj.get("params").isJsonNull())) {
                String bodyAction = bodyObj.get("action").getAsString().trim();
                if (!bodyAction.isEmpty()) {
                    targetAction = bodyAction;
                }
                params = new JsonObject();
            } else {
                params = bodyObj;
            }

            if (!ActionRegistry.contains(targetAction)) {
                sendActionData(exchange, 404, DetailedFailure.notFound(targetAction), echo);
                return;
            }

            ActionData<?> responseDTO = this.botSession.getApiImpl().callMiraiApi(targetAction, params);
            sendActionData(exchange, 200, responseDTO, echo);
        } catch (Throwable t) {
            miraiLogger.error("处理 HTTP POST API 请求异常 (" + pathAction + ")", t);
            sendActionData(exchange, 200, DetailedFailure.fail("服务端处理异常: " + t.getMessage()), null);
        }
    }

    private void handleGet(String pathAction, HttpExchange exchange) {
        try {
            if (!checkAuth(exchange)) {
                return;
            }

            String rawQuery = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawQuery() : null;
            Map<String, String> queryParams = parseQueryString(rawQuery);
            String echo = queryParams.get("echo");
            JsonObject params = new JsonObject();
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                String key = entry.getKey();
                if ("access_token".equals(key) || "echo".equals(key)) {
                    continue;
                }
                params.addProperty(key, entry.getValue());
            }

            if (!ActionRegistry.contains(pathAction)) {
                sendActionData(exchange, 404, DetailedFailure.notFound(pathAction), echo);
                return;
            }

            ActionData<?> responseDTO = this.botSession.getApiImpl().callMiraiApi(pathAction, params);
            sendActionData(exchange, 200, responseDTO, echo);
        } catch (Throwable t) {
            miraiLogger.error("处理 HTTP GET API 请求异常 (" + pathAction + ")", t);
            sendActionData(exchange, 200, DetailedFailure.fail("服务端处理异常: " + t.getMessage()), null);
        }
    }

    private boolean checkAuth(HttpExchange exchange) {
        String token = config.getAccessToken();
        if (token == null || token.isBlank()) {
            return true;
        }
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null) {
            if (authHeader.equals("Bearer " + token) || authHeader.equals("Token " + token)) {
                return true;
            }
            if (authHeader.regionMatches(true, 0, "Bearer ", 0, 7)
                    || authHeader.regionMatches(true, 0, "Token ", 0, 6)) {
                String provided = authHeader.substring(authHeader.indexOf(' ') + 1).trim();
                if (token.equals(provided)) {
                    return true;
                }
            }
        }
        String rawQuery = exchange.getRequestURI() != null ? exchange.getRequestURI().getRawQuery() : null;
        Map<String, String> queryParams = parseQueryString(rawQuery);
        String queryToken = queryParams.get("access_token");
        if (token.equals(queryToken)) {
            return true;
        }
        sendActionData(exchange, 401, DetailedFailure.unauthorized("鉴权失败：access_token 无效或缺失"), null);
        return false;
    }

    private String extractEcho(JsonObject bodyObj) {
        if (bodyObj == null || !bodyObj.has("echo") || bodyObj.get("echo").isJsonNull()) {
            return null;
        }
        JsonElement echoEl = bodyObj.get("echo");
        return echoEl.isJsonPrimitive() ? echoEl.getAsString() : echoEl.toString();
    }

    private void sendActionData(HttpExchange exchange, int statusCode, ActionData<?> data, String echo) {
        if (data == null) {
            data = DetailedFailure.fail("内部返回为空");
        }
        if (echo != null) {
            data.setEcho(echo);
        }
        String json = GsonUtils.getGson().toJson(data);
        sendJson(exchange, statusCode, json);
    }

    private void sendJson(HttpExchange exchange, int statusCode, String json) {
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
            exchange.sendResponseHeaders(statusCode, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                    os.flush();
                }
            }
        } catch (IOException e) {
            miraiLogger.warning("写入 HTTP JSON 响应失败: " + e.getMessage());
        }
    }

    public static Map<String, String> parseQueryString(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key;
            String val;
            if (idx >= 0) {
                key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            } else {
                key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                val = "";
            }
            if (!key.isEmpty()) {
                map.put(key, val);
            }
        }
        return map;
    }

    private static String stripLeadingSlash(String path) {
        if (path == null) {
            return "";
        }
        String s = path.trim();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }
}
