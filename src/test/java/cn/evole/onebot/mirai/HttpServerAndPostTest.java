package cn.evole.onebot.mirai;

import cn.evole.onebot.mirai.config.PluginConfig;
import cn.evole.onebot.mirai.core.session.BotSession;
import cn.evole.onebot.mirai.util.BaseUtils;
import cn.evole.onebot.sdk.event.message.GroupMessageEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.mamoe.mirai.Bot;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP API 服务器（POST/GET/鉴权/404 JSON/echo）与 HTTP POST 异步上报（HMAC-SHA1/postMessageFormat）集成测试。
 */
public class HttpServerAndPostTest {

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void testHttpApiAndPostReportingEndToEnd() throws Exception {
        int apiPort = findFreePort();
        int receiverPort = findFreePort();

        List<String> receivedBodies = new CopyOnWriteArrayList<>();
        List<String> receivedSignatures = new CopyOnWriteArrayList<>();
        List<String> receivedSelfIds = new CopyOnWriteArrayList<>();
        CountDownLatch eventLatch = new CountDownLatch(2); // connect lifecycle + custom group msg

        HttpServer mockReceiver = HttpServer.create(new InetSocketAddress("127.0.0.1", receiverPort), 0);
        mockReceiver.createContext("/OlivOSMsgApi/qq/onebot/napcat_default", exchange -> {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            receivedBodies.add(body);
            receivedSignatures.add(exchange.getRequestHeaders().getFirst("X-Signature"));
            receivedSelfIds.add(exchange.getRequestHeaders().getFirst("X-Self-ID"));
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
            eventLatch.countDown();
        });
        mockReceiver.start();

        Bot bot = Mockito.mock(Bot.class);
        Mockito.when(bot.getId()).thenReturn(3520767439L);
        Mockito.when(bot.getNick()).thenReturn("MiraiTestBot");
        Mockito.when(bot.isOnline()).thenReturn(true);

        PluginConfig.BotConfig botCfg = new PluginConfig.BotConfig();
        botCfg.getHeartbeat().setEnable(false);
        botCfg.getWs().setEnable(false);
        botCfg.getWsReverse().clear();
        botCfg.getHttp().setEnable(true);
        botCfg.getHttp().setHost("127.0.0.1");
        botCfg.getHttp().setPort(apiPort);
        botCfg.getHttp().setAccessToken("1919810");
        botCfg.getHttp().setSecret("mysecret");
        botCfg.getHttp().setPostUrl("http://127.0.0.1:" + receiverPort + "/OlivOSMsgApi/qq/onebot/napcat_default");
        botCfg.getHttp().setPostMessageFormat("array");

        BotSession session = new BotSession(bot, botCfg);
        try {
            // 1. 无 token 返回 401 JSON
            HttpResp unauth = request("GET", "http://127.0.0.1:" + apiPort + "/get_login_info", null, null);
            assertEquals(401, unauth.status);
            assertEquals("failed", JsonParser.parseString(unauth.body).getAsJsonObject().get("status").getAsString());

            // 2. Query access_token + GET /get_login_info
            HttpResp loginGet = request("GET", "http://127.0.0.1:" + apiPort + "/get_login_info?access_token=1919810&echo=e1", null, null);
            assertEquals(200, loginGet.status);
            JsonObject loginJson = JsonParser.parseString(loginGet.body).getAsJsonObject();
            assertEquals("ok", loginJson.get("status").getAsString());
            assertEquals("e1", loginJson.get("echo").getAsString());
            assertEquals(3520767439L, loginJson.getAsJsonObject("data").get("user_id").getAsLong());

            // 3. Bearer token + POST 平铺参数（OlivOS 模式）调用 /get_word_slices
            HttpResp flatPost = request(
                    "POST",
                    "http://127.0.0.1:" + apiPort + "/get_word_slices",
                    "Bearer 1919810",
                    "{\"content\":\"你好world\"}"
            );
            assertEquals(200, flatPost.status);
            JsonObject slicesJson = JsonParser.parseString(flatPost.body).getAsJsonObject();
            assertEquals("ok", slicesJson.get("status").getAsString());
            JsonArray slices = slicesJson.getAsJsonObject("data").getAsJsonArray("slices");
            assertEquals(3, slices.size());

            // 4. POST 包装格式 {action, params, echo} + 数字 echo 转字符串回传
            HttpResp wrappedPost = request(
                    "POST",
                    "http://127.0.0.1:" + apiPort + "/get_status",
                    "Bearer 1919810",
                    "{\"action\":\"get_status\",\"params\":{},\"echo\":12345}"
            );
            assertEquals(200, wrappedPost.status);
            JsonObject statusJson = JsonParser.parseString(wrappedPost.body).getAsJsonObject();
            assertEquals("ok", statusJson.get("status").getAsString());
            assertEquals("12345", statusJson.get("echo").getAsString());
            assertTrue(statusJson.getAsJsonObject("data").get("online").getAsBoolean());

            // 5. 未知动作返回 404 JSON (retcode=1404)
            HttpResp notFound = request(
                    "POST",
                    "http://127.0.0.1:" + apiPort + "/non_existent_action_xyz",
                    "Bearer 1919810",
                    "{}"
            );
            assertEquals(404, notFound.status);
            JsonObject nfJson = JsonParser.parseString(notFound.body).getAsJsonObject();
            assertEquals("failed", nfJson.get("status").getAsString());
            assertEquals(1404, nfJson.get("retcode").getAsInt());

            // 6. 上报消息事件，验证 postMessageFormat=array 与 X-Signature HMAC-SHA1
            GroupMessageEvent msgEvent = new GroupMessageEvent();
            msgEvent.setPostType("message");
            msgEvent.setMessageType("group");
            msgEvent.setSubType("normal");
            msgEvent.setSelfId(3520767439L);
            msgEvent.setGroupId(10086L);
            msgEvent.setUserId(10001L);
            msgEvent.setMessageId(77);
            msgEvent.setMessage("看图[CQ:at,qq=123]");
            msgEvent.setRawMessage("看图[CQ:at,qq=123]");
            msgEvent.setTime(1700000000L);
            session.emitEvent(msgEvent);

            assertTrue(eventLatch.await(5, TimeUnit.SECONDS), "应在 5 秒内收到 HTTP POST 事件上报");
            assertTrue(receivedBodies.size() >= 2);

            // 校验 X-Self-ID 和 X-Signature (HMAC-SHA1)
            for (int i = 0; i < receivedBodies.size(); i++) {
                assertEquals("3520767439", receivedSelfIds.get(i));
                String expectedSig = "sha1=" + BaseUtils.hmacSha1Hex(receivedBodies.get(i), "mysecret");
                assertEquals(expectedSig, receivedSignatures.get(i));
            }

            // 校验 array 格式保留 raw_message 并将 message 转为数组
            JsonObject postedMsg = receivedBodies.stream()
                    .map(s -> JsonParser.parseString(s).getAsJsonObject())
                    .filter(o -> "message".equals(o.get("post_type").getAsString()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(postedMsg.get("message").isJsonArray());
            assertEquals(2, postedMsg.getAsJsonArray("message").size());
            assertEquals("看图[CQ:at,qq=123]", postedMsg.get("raw_message").getAsString());
        } finally {
            session.close();
            mockReceiver.stop(0);
        }
    }

    private record HttpResp(int status, String body) {
    }

    private static HttpResp request(String method, String urlStr, String authHeader, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        if (jsonBody != null) {
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        InputStream is = status >= 200 && status < 400 ? conn.getInputStream() : conn.getErrorStream();
        String body = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
        conn.disconnect();
        return new HttpResp(status, body);
    }
}
