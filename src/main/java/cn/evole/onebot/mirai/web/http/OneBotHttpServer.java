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
import io.github.kongweiguang.khttp.KHTTP;
import io.github.kongweiguang.khttp.core.Req;
import io.github.kongweiguang.khttp.core.Res;
import net.mamoe.mirai.utils.MiraiLogger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * OneBot v11 正向 HTTP API 服务器：基于 ActionRegistry 全量注册 POST/GET，支持标准平铺 JSON 与包装格式、Bearer/Query 鉴权。
 */
public class OneBotHttpServer {
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private final MiraiLogger miraiLogger = PluginRuntime.getLogger();
    private final BotSession botSession;
    private final PluginConfig.HTTPConfig config;
    private final KHTTP khttp;

    public OneBotHttpServer(BotSession botSession, PluginConfig.BotConfig botConfig) {
        this.botSession = botSession;
        this.config = botConfig.getHttp();
        this.khttp = KHTTP.of().executor(Executors.newCachedThreadPool());
    }

    public String getAddress() {
        if (khttp.server() == null) {
            return config.getHost() + ":" + config.getPort();
        }
        return khttp.server().getAddress().toString();
    }

    public void create() {
        // 拦截未注册路径，返回标准 404 JSON，避免 KHTTP 静默关闭连接或返回纯文本 404
        this.khttp.filter((req, res, chain) -> {
            String path = req.path();
            String pathAction = stripLeadingSlash(path);
            if (!ActionRegistry.contains(pathAction)) {
                if (!checkAuth(req, res)) {
                    return;
                }
                sendActionData(res, 404, DetailedFailure.notFound(pathAction), null);
                return;
            }
            chain.doFilter(req.httpExchange());
        });

        for (String action : ActionRegistry.all()) {
            String route = "/" + action;
            this.khttp
                    .post(route, (req, res) -> handlePost(action, req, res))
                    .get(route, (req, res) -> handleGet(action, req, res));
        }

        this.khttp.ok(new InetSocketAddress(config.getHost(), config.getPort()));
    }

    public void close() {
        if (khttp.server() != null) {
            khttp.stop(2);
        }
    }

    private void handlePost(String pathAction, Req req, Res res) {
        try {
            if (!checkAuth(req, res)) {
                return;
            }

            String contentType = req.contentType();
            byte[] rawBytes = req.httpExchange().getRequestBody().readAllBytes();
            String bodyStr = new String(rawBytes, StandardCharsets.UTF_8).trim();

            if (!bodyStr.isEmpty() && contentType != null && !contentType.isBlank()
                    && !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                sendActionData(res, 406, DetailedFailure.fail("Content-Type 不支持，请使用 application/json"), null);
                return;
            }

            JsonObject bodyObj = new JsonObject();
            if (!bodyStr.isEmpty()) {
                try {
                    JsonElement parsed = JsonParser.parseString(bodyStr);
                    if (parsed.isJsonObject()) {
                        bodyObj = parsed.getAsJsonObject();
                    } else {
                        sendActionData(res, 400, DetailedFailure.fail("请求体必须是 JSON 对象"), null);
                        return;
                    }
                } catch (Exception e) {
                    sendActionData(res, 400, DetailedFailure.fail("请求体 JSON 解析失败: " + e.getMessage()), null);
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
                sendActionData(res, 404, DetailedFailure.notFound(targetAction), echo);
                return;
            }

            ActionData<?> responseDTO = this.botSession.getApiImpl().callMiraiApi(targetAction, params);
            sendActionData(res, 200, responseDTO, echo);
        } catch (Throwable t) {
            miraiLogger.error("处理 HTTP POST API 请求异常 (" + pathAction + ")", t);
            sendActionData(res, 200, DetailedFailure.fail("服务端处理异常: " + t.getMessage()), null);
        }
    }

    private void handleGet(String pathAction, Req req, Res res) {
        try {
            if (!checkAuth(req, res)) {
                return;
            }

            Map<String, String> queryParams = parseQueryString(req.uri().getRawQuery());
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
                sendActionData(res, 404, DetailedFailure.notFound(pathAction), echo);
                return;
            }

            ActionData<?> responseDTO = this.botSession.getApiImpl().callMiraiApi(pathAction, params);
            sendActionData(res, 200, responseDTO, echo);
        } catch (Throwable t) {
            miraiLogger.error("处理 HTTP GET API 请求异常 (" + pathAction + ")", t);
            sendActionData(res, 200, DetailedFailure.fail("服务端处理异常: " + t.getMessage()), null);
        }
    }

    private boolean checkAuth(Req req, Res res) {
        String token = config.getAccessToken();
        if (token == null || token.isBlank()) {
            return true;
        }
        String authHeader = req.header("Authorization");
        if (authHeader != null && (authHeader.equals("Bearer " + token) || authHeader.equals("Token " + token))) {
            return true;
        }
        Map<String, String> queryParams = parseQueryString(req.uri().getRawQuery());
        String queryToken = queryParams.get("access_token");
        if (token.equals(queryToken)) {
            return true;
        }
        sendActionData(res, 401, DetailedFailure.unauthorized("鉴权失败：access_token 无效或缺失"), null);
        return false;
    }

    private String extractEcho(JsonObject bodyObj) {
        if (bodyObj == null || !bodyObj.has("echo") || bodyObj.get("echo").isJsonNull()) {
            return null;
        }
        JsonElement echoEl = bodyObj.get("echo");
        return echoEl.isJsonPrimitive() ? echoEl.getAsString() : echoEl.toString();
    }

    private void sendActionData(Res res, int statusCode, ActionData<?> data, String echo) {
        if (data == null) {
            data = DetailedFailure.fail("内部返回为空");
        }
        if (echo != null) {
            data.setEcho(echo);
        }
        String json = GsonUtils.getGson().toJson(data);
        sendJson(res, statusCode, json);
    }

    private void sendJson(Res res, int statusCode, String json) {
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            res.getHeaders().set("Content-Type", JSON_CONTENT_TYPE);
            res.httpExchange().sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = res.httpExchange().getResponseBody()) {
                os.write(bytes);
                os.flush();
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
