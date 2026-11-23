package cn.evole.onebot.mirai;

import cn.evole.onebot.mirai.core.ApiMap;
import cn.evole.onebot.mirai.util.OnebotMsgUtils;
import cn.evole.onebot.mirai.util.OnebotMsgUtils.ParsedForwardNode;
import cn.evole.onebot.sdk.action.ActionData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.ForwardMessage;
import net.mamoe.mirai.message.data.MessageChainBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合并转发节点兼容性解析（新旧两套字段、默认值、空数组失败）与 get_forward_msg 测试。
 */
public class ForwardNodeTest {

    @Test
    void testLegacyFieldsUinNameContent() {
        JsonArray arr = JsonParser.parseString("""
                [
                  {
                    "type": "node",
                    "data": {
                      "uin": 10001,
                      "name": "旧版昵称",
                      "time": 1700000100,
                      "content": "第一条消息"
                    }
                  }
                ]
                """).getAsJsonArray();

        List<ParsedForwardNode> nodes = OnebotMsgUtils.parseForwardNodes(arr, 99999L, 1700000000);
        assertEquals(1, nodes.size());
        assertEquals(10001L, nodes.get(0).userId());
        assertEquals("旧版昵称", nodes.get(0).nickname());
        assertEquals(1700000100, nodes.get(0).time());
        assertEquals("第一条消息", nodes.get(0).content().getAsString());
    }

    @Test
    void testNewFieldsUserIdNicknameMessage() {
        JsonArray arr = JsonParser.parseString("""
                [
                  {
                    "type": "node",
                    "data": {
                      "user_id": "20002",
                      "nickname": "NapCat用户",
                      "message": [{"type": "text", "data": {"text": "数组内容"}}]
                    }
                  }
                ]
                """).getAsJsonArray();

        List<ParsedForwardNode> nodes = OnebotMsgUtils.parseForwardNodes(arr, 99999L, 1700000000);
        assertEquals(1, nodes.size());
        assertEquals(20002L, nodes.get(0).userId());
        assertEquals("NapCat用户", nodes.get(0).nickname());
        assertEquals(1700000000, nodes.get(0).time());
        assertTrue(nodes.get(0).content().isJsonArray());
    }

    @Test
    void testDirectNodeObjectAndDefaults() {
        JsonArray arr = JsonParser.parseString("""
                [
                  {
                    "content": "缺省字段测试"
                  }
                ]
                """).getAsJsonArray();

        List<ParsedForwardNode> nodes = OnebotMsgUtils.parseForwardNodes(arr, 88888L, 1700000555);
        assertEquals(1, nodes.size());
        assertEquals(88888L, nodes.get(0).userId());
        assertEquals("", nodes.get(0).nickname());
        assertEquals(1700000555, nodes.get(0).time());
        assertEquals("缺省字段测试", nodes.get(0).content().getAsString());
    }

    @Test
    void testEmptyNodesArrayReturnsFailureNotMessageSuccessMinusOne() {
        Bot bot = Mockito.mock(Bot.class);
        Group group = Mockito.mock(Group.class);
        Mockito.when(bot.getId()).thenReturn(3520767439L);

        ApiMap apiMap = new ApiMap(bot);
        JsonObject params = JsonParser.parseString("""
                {
                  "group_id": 123456,
                  "messages": []
                }
                """).getAsJsonObject();

        ActionData<?> resp = apiMap.sendForwardMessage(group, params, "群聊的聊天记录");
        assertEquals("failed", resp.getStatus());
        assertNotEquals(0, resp.getRetCode());
        assertNotNull(resp.getData());
    }

    @Test
    void testGetForwardMsgReturnsBothSchemasAndFailsWhenMissing() {
        Bot bot = Mockito.mock(Bot.class);
        ApiMap apiMap = new ApiMap(bot);

        // 未命中返回失败
        JsonObject missParams = new JsonObject();
        missParams.addProperty("id", "999999");
        ActionData<?> missResp = apiMap.callMiraiApi("get_forward_msg", missParams);
        assertEquals("failed", missResp.getStatus());

        // 命中缓存时返回同时包含两套字段（user_id/nickname + uin/name + content + message）
        ForwardMessage.Node node = new ForwardMessage.Node(
                12345L,
                1700000000,
                "测试者",
                new MessageChainBuilder().append("hello").build()
        );
        ForwardMessage fm = new ForwardMessage(
                List.of("查看 1 条转发消息"),
                "群聊的聊天记录",
                "查看 1 条转发消息",
                "",
                "查看 1 条转发消息",
                List.of(node)
        );
        apiMap.cacheForwardMessage(42, fm);

        JsonObject hitParams = new JsonObject();
        hitParams.addProperty("message_id", 42);
        ActionData<?> hitResp = apiMap.callMiraiApi("get_forward_msg", hitParams);
        assertEquals("ok", hitResp.getStatus());
        assertEquals(0, hitResp.getRetCode());
        JsonObject data = assertInstanceOf(JsonObject.class, hitResp.getData());
        JsonArray messages = data.getAsJsonArray("messages");
        assertEquals(1, messages.size());

        JsonObject nodeData = messages.get(0).getAsJsonObject().getAsJsonObject("data");
        assertEquals(12345L, nodeData.get("user_id").getAsLong());
        assertEquals("测试者", nodeData.get("nickname").getAsString());
        assertEquals(12345L, nodeData.get("uin").getAsLong());
        assertEquals("测试者", nodeData.get("name").getAsString());
        assertEquals(1700000000, nodeData.get("time").getAsInt());
        assertEquals("hello", nodeData.get("content").getAsString());
        assertEquals("text", nodeData.getAsJsonArray("message").get(0).getAsJsonObject().get("type").getAsString());
    }
}
