package cn.evole.onebot.mirai;

import cn.evole.onebot.mirai.config.PluginConfig;
import cn.evole.onebot.mirai.core.MessageMap;
import cn.evole.onebot.mirai.core.session.BotSession;
import cn.evole.onebot.mirai.core.session.SessionManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.IMirai;
import net.mamoe.mirai.Mirai;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.MessageReceipt;
import net.mamoe.mirai.message.data.Image;
import net.mamoe.mirai.message.data.MessageChain;
import net.mamoe.mirai.message.data.MessageChainBuilder;
import net.mamoe.mirai.message.data.OnlineMessageSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 多账号同时在线时的正向 HTTP 服务端口/token/路由隔离及会话重建测试。
 * 回归验证：后登录账号不得覆盖先登录账号的 HTTP 路由处理器或 access_token。
 */
public class MultiBotHttpIsolationTest {

    private static final long BOT1_ID = 3520767439L;
    private static final long BOT2_ID = 3948837959L;

    @AfterEach
    void cleanup() {
        SessionManager.closeAllSessions();
        if (PluginConfig.INSTANCE.getBots() != null) {
            PluginConfig.INSTANCE.getBots().remove(String.valueOf(BOT1_ID));
            PluginConfig.INSTANCE.getBots().remove(String.valueOf(BOT2_ID));
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @SuppressWarnings("unchecked")
    private static Bot mockBot(long id, String nick, long groupId) {
        Bot bot = Mockito.mock(Bot.class);
        Group group = Mockito.mock(Group.class);
        MessageReceipt<Group> receipt = Mockito.mock(MessageReceipt.class);
        OnlineMessageSource.Outgoing src = Mockito.mock(OnlineMessageSource.Outgoing.class);
        when(src.getInternalIds()).thenReturn(new int[]{(int) (id % 10000)});
        when(src.getBotId()).thenReturn(id);
        when(src.getFromId()).thenReturn(id);
        when(src.getTime()).thenReturn(1700000000);
        when(receipt.getSource()).thenReturn(src);

        when(group.getId()).thenReturn(groupId);
        when(group.sendMessage(any(MessageChain.class))).thenReturn(receipt);

        when(bot.getId()).thenReturn(id);
        when(bot.getNick()).thenReturn(nick);
        when(bot.isOnline()).thenReturn(true);
        when(bot.getGroupOrFail(groupId)).thenReturn(group);
        return bot;
    }

    private static PluginConfig.BotConfig httpConfig(int port, String token) {
        PluginConfig.BotConfig cfg = new PluginConfig.BotConfig();
        cfg.getHeartbeat().setEnable(false);
        cfg.getWs().setEnable(false);
        cfg.getWsReverse().clear();
        cfg.getHttp().setEnable(true);
        cfg.getHttp().setHost("127.0.0.1");
        cfg.getHttp().setPort(port);
        cfg.getHttp().setAccessToken(token);
        cfg.getHttp().setPostUrl("");
        return cfg;
    }

    @Test
    void testMultiBotHttpPortsTokensAndHandlersRemainIsolated() throws Exception {
        int port1 = findFreePort();
        int port2 = findFreePort();
        long testGroupId = 1067393447L;

        Bot bot1 = mockBot(BOT1_ID, "Fronia2", testGroupId);
        Bot bot2 = mockBot(BOT2_ID, "Bobobo", testGroupId);

        PluginConfig.BotConfig cfg1 = httpConfig(port1, "114514");
        PluginConfig.BotConfig cfg2 = httpConfig(port2, "1919810");
        PluginConfig.INSTANCE.getBots().put(String.valueOf(BOT1_ID), cfg1);
        PluginConfig.INSTANCE.getBots().put(String.valueOf(BOT2_ID), cfg2);

        // 1. 先登录 Bot 1，此时应正常响应
        BotSession session1 = SessionManager.createBotSession(bot1, cfg1);
        assertNotNull(session1);
        assertBotLoginInfo(port1, "114514", BOT1_ID, "Fronia2");

        // 2. 再登录 Bot 2，绝不能覆盖 Bot 1 的 HTTP 端口路由或 token
        BotSession session2 = SessionManager.createBotSession(bot2, cfg2);
        assertNotNull(session2);

        // 3. 再次验证 Bot 1：自己的 token 仍返回 Bot 1，用 Bot 2 的 token 必须被 401 拒绝
        assertBotLoginInfo(port1, "114514", BOT1_ID, "Fronia2");
        HttpResp wrongTokenOnPort1 = request("GET", "http://127.0.0.1:" + port1 + "/get_login_info", "Bearer 1919810", null);
        assertEquals(401, wrongTokenOnPort1.status);

        // 4. 验证 Bot 2：自己的 token 返回 Bot 2，用 Bot 1 的 token 必须被 401 拒绝
        assertBotLoginInfo(port2, "1919810", BOT2_ID, "Bobobo");
        HttpResp wrongTokenOnPort2 = request("GET", "http://127.0.0.1:" + port2 + "/get_login_info", "Bearer 114514", null);
        assertEquals(401, wrongTokenOnPort2.status);

        // 5. 并发向两个端口发 send_group_msg，确认分别路由到各自 Bot 的 Group
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                final int idx = i;
                tasks.add(() -> {
                    HttpResp r1 = request(
                            "POST",
                            "http://127.0.0.1:" + port1 + "/send_group_msg",
                            "Bearer 114514",
                            "{\"group_id\":" + testGroupId + ",\"message\":\"bot1-" + idx + "\"}"
                    );
                    assertEquals(200, r1.status);
                    assertEquals("ok", JsonParser.parseString(r1.body).getAsJsonObject().get("status").getAsString());

                    HttpResp r2 = request(
                            "POST",
                            "http://127.0.0.1:" + port2 + "/send_group_msg",
                            "Bearer 1919810",
                            "{\"group_id\":" + testGroupId + ",\"message\":\"bot2-" + idx + "\"}"
                    );
                    assertEquals(200, r2.status);
                    assertEquals("ok", JsonParser.parseString(r2.body).getAsJsonObject().get("status").getAsString());
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        verify(bot1.getGroupOrFail(testGroupId), times(6)).sendMessage(any(MessageChain.class));
        verify(bot2.getGroupOrFail(testGroupId), times(6)).sendMessage(any(MessageChain.class));

        // 6. 关闭 Bot 2 不应影响 Bot 1；同号重建 Session 应释放旧端口并重新绑定成功
        SessionManager.closeSession(BOT2_ID);
        assertBotLoginInfo(port1, "114514", BOT1_ID, "Fronia2");

        Bot bot1Reconnect = mockBot(BOT1_ID, "Fronia2-Reconnected", testGroupId);
        SessionManager.createBotSession(bot1Reconnect, cfg1);
        assertBotLoginInfo(port1, "114514", BOT1_ID, "Fronia2-Reconnected");
    }

    @Test
    void testMultiBotImageUrlUsesOwningBotInsteadOfFirstInstance() throws Exception {
        int port1 = findFreePort();
        int port2 = findFreePort();
        Bot bot1 = mockBot(BOT1_ID, "Fronia2", 10001L);
        Bot bot2 = mockBot(BOT2_ID, "Bobobo", 10002L);
        PluginConfig.BotConfig cfg1 = httpConfig(port1, "114514");
        PluginConfig.BotConfig cfg2 = httpConfig(port2, "1919810");
        PluginConfig.INSTANCE.getBots().put(String.valueOf(BOT1_ID), cfg1);
        PluginConfig.INSTANCE.getBots().put(String.valueOf(BOT2_ID), cfg2);

        SessionManager.createBotSession(bot1, cfg1);
        SessionManager.createBotSession(bot2, cfg2);

        Image img = Mockito.mock(Image.class);
        when(img.getImageId()).thenReturn("{9EEC3B2F-F27C-5899-32CF-989E9B107DFA}.jpg");
        MessageChain chain = new MessageChainBuilder().append(img).build();

        IMirai mockMirai = Mockito.mock(IMirai.class);
        when(mockMirai.queryImageUrl(bot1, img)).thenReturn("https://example.test/" + BOT1_ID + "/img.jpg");
        when(mockMirai.queryImageUrl(bot2, img)).thenReturn("https://example.test/" + BOT2_ID + "/img.jpg");

        try (MockedStatic<Mirai> miraiStatic = Mockito.mockStatic(Mirai.class)) {
            miraiStatic.when(Mirai::getInstance).thenReturn(mockMirai);

            String cq1 = MessageMap.serializeMessageChain(BOT1_ID, 1, chain);
            String cq2 = MessageMap.serializeMessageChain(BOT2_ID, 2, chain);

            assertTrue(cq1.contains("https://example.test/" + BOT1_ID + "/img.jpg"));
            assertTrue(cq2.contains("https://example.test/" + BOT2_ID + "/img.jpg"));
            verify(mockMirai, times(1)).queryImageUrl(bot1, img);
            verify(mockMirai, times(1)).queryImageUrl(bot2, img);
        }
    }

    private static void assertBotLoginInfo(int port, String token, long expectedId, String expectedNick) throws IOException {
        HttpResp resp = request("GET", "http://127.0.0.1:" + port + "/get_login_info", "Bearer " + token, null);
        assertEquals(200, resp.status);
        JsonObject root = JsonParser.parseString(resp.body).getAsJsonObject();
        assertEquals("ok", root.get("status").getAsString());
        JsonObject data = root.getAsJsonObject("data");
        assertEquals(expectedId, data.get("user_id").getAsLong());
        assertEquals(expectedNick, data.get("nickname").getAsString());
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
