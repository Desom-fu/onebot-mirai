package cn.evole.onebot.mirai.util;

import cn.evole.onebot.mirai.core.ApiMap;
import cn.evole.onebot.mirai.model.DetailedFailure;
import cn.evole.onebot.sdk.action.ActionData;
import cn.evole.onebot.sdk.util.json.GsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;

/**
 * WebSocket API 请求分发工具。
 */
public class ActionUtils {
    public static void handleWebSocketActions(WebSocket session, ApiMap api, JsonObject json) {
        try {
            boolean debug = PluginRuntime.isDebug();
            if (debug) {
                PluginRuntime.getLogger().info(String.format("WebSocket收到操作请求: %s", GsonUtils.getGson().toJson(json)));
            }

            String echo = null;
            if (json.has("echo") && !json.get("echo").isJsonNull()) {
                JsonElement echoEl = json.get("echo");
                echo = echoEl.isJsonPrimitive() ? echoEl.getAsString() : echoEl.toString();
            }
            String action = (json.has("action") && !json.get("action").isJsonNull())
                    ? json.get("action").getAsString() : "";
            JsonObject params = (json.has("params") && json.get("params").isJsonObject())
                    ? json.getAsJsonObject("params") : new JsonObject();

            ActionData<?> responseDTO = (api != null)
                    ? api.callMiraiApi(action, params)
                    : DetailedFailure.fail("Bot 会话尚未初始化");

            if (echo != null) {
                responseDTO.setEcho(echo);
            }
            String jsonToSend = GsonUtils.getGson().toJson(responseDTO);
            session.send(jsonToSend);
            if (debug) {
                PluginRuntime.getLogger().info(String.format("WebSocket将返回结果: %s", jsonToSend));
            }
        } catch (Exception e) {
            PluginRuntime.getLogger().error("处理 WebSocket API 请求异常: " + e.getMessage());
        }
    }
}
