package cn.evole.onebot.mirai;

import cn.evole.onebot.mirai.util.OnebotMsgUtils;
import cn.evole.onebot.mirai.util.OnebotMsgUtils.MessageSegment;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Friend;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.At;
import net.mamoe.mirai.message.data.AtAll;
import net.mamoe.mirai.message.data.MessageChain;
import net.mamoe.mirai.message.data.PlainText;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CQ 码分词器、反转义、非法 CQ 码容错、JSON 数组解析及 At 方向修正测试。
 */
public class MessageParserTest {

    @Test
    void testMixedTextImageAndAtSegments() {
        String raw = "看这个[CQ:image,file=base64://aGVsbG8=]还有[CQ:at,qq=123]";
        List<MessageSegment> segments = OnebotMsgUtils.parseCQString(raw, false);

        assertEquals(4, segments.size());
        assertEquals("text", segments.get(0).type());
        assertEquals("看这个", segments.get(0).get("text"));

        assertEquals("image", segments.get(1).type());
        assertEquals("base64://aGVsbG8=", segments.get(1).get("file"));

        assertEquals("text", segments.get(2).type());
        assertEquals("还有", segments.get(2).get("text"));

        assertEquals("at", segments.get(3).type());
        assertEquals("123", segments.get(3).get("qq"));

        // 相邻 CQ 码无中间文字 -> 3 段
        String adjacent = "看这个[CQ:image,file=base64://aGVsbG8=][CQ:at,qq=123]";
        List<MessageSegment> adjSegs = OnebotMsgUtils.parseCQString(adjacent, false);
        assertEquals(3, adjSegs.size());
        assertEquals("text", adjSegs.get(0).type());
        assertEquals("image", adjSegs.get(1).type());
        assertEquals("at", adjSegs.get(2).type());
    }

    @Test
    void testAutoEscapeTrueKeepsPlainText() {
        String raw = "看这个[CQ:image,file=base64://aGVsbG8=]还有[CQ:at,qq=123]";
        List<MessageSegment> segments = OnebotMsgUtils.parseCQString(raw, true);

        assertEquals(1, segments.size());
        assertEquals("text", segments.get(0).type());
        assertEquals(raw, segments.get(0).get("text"));

        Bot bot = Mockito.mock(Bot.class);
        Group group = Mockito.mock(Group.class);
        MessageChain chain = OnebotMsgUtils.messageToChains(bot, group, JsonParser.parseString("\"" + raw + "\""), true);
        assertEquals(1, chain.size());
        assertInstanceOf(PlainText.class, chain.get(0));
        assertEquals(raw, ((PlainText) chain.get(0)).getContent());
    }

    @Test
    void testCQEscapesAndNoDoubleUnescape() {
        // &amp;#91; 反转义后应得到字面 "&#91;" 而不是 "["
        String raw = "文本&#91;a&#93;&amp;#91;[CQ:text,text=x&#44;y&#91;z&#93;&amp;#44;&amp;end]";
        List<MessageSegment> segments = OnebotMsgUtils.parseCQString(raw, false);

        assertEquals(2, segments.size());
        assertEquals("text", segments.get(0).type());
        assertEquals("文本[a]&#91;", segments.get(0).get("text"));

        assertEquals("text", segments.get(1).type());
        assertEquals("x,y[z]&#44;&end", segments.get(1).get("text"));
    }

    @Test
    void testInvalidCQCodeDoesNotAffectOtherSegments() {
        // 未闭合的 CQ 码紧接合法 CQ 码
        String raw1 = "前缀[CQ:image,file=broken[CQ:at,qq=12345]后缀";
        List<MessageSegment> segs1 = OnebotMsgUtils.parseCQString(raw1, false);
        assertTrue(segs1.stream().anyMatch(s -> "at".equals(s.type()) && "12345".equals(s.get("qq"))));
        assertTrue(segs1.stream().anyMatch(s -> "text".equals(s.type()) && "后缀".equals(s.get("text"))));

        // 非法类型名不影响后续合法 CQ 码
        String raw2 = "你好[CQ:bad type!][CQ:face,id=14]世界";
        List<MessageSegment> segs2 = OnebotMsgUtils.parseCQString(raw2, false);
        assertTrue(segs2.stream().anyMatch(s -> "face".equals(s.type()) && "14".equals(s.get("id"))));
        assertTrue(segs2.stream().anyMatch(s -> "text".equals(s.type()) && "世界".equals(s.get("text"))));
    }

    @Test
    void testJsonArrayWithTextImageAndAt() {
        String json = """
                [
                  {"type": "text", "data": {"text": "看这个"}},
                  {"type": "image", "data": {"file": "base64://aGVsbG8="}},
                  {"type": "at", "data": {"qq": "123456"}}
                ]
                """;
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        // auto_escape 对 JSON 数组不生效
        List<MessageSegment> segments = OnebotMsgUtils.parseMessageSegments(arr, true);

        assertEquals(3, segments.size());
        assertEquals("text", segments.get(0).type());
        assertEquals("看这个", segments.get(0).get("text"));
        assertEquals("image", segments.get(1).type());
        assertEquals("base64://aGVsbG8=", segments.get(1).get("file"));
        assertEquals("at", segments.get(2).type());
        assertEquals("123456", segments.get(2).get("qq"));
    }

    @Test
    void testAtAllowedInGroupAndSkippedInPrivate() {
        Bot bot = Mockito.mock(Bot.class);
        Group group = Mockito.mock(Group.class);
        Friend friend = Mockito.mock(Friend.class);

        JsonArray arr = JsonParser.parseString("""
                [
                  {"type": "text", "data": {"text": "hi"}},
                  {"type": "at", "data": {"qq": "98765"}},
                  {"type": "at", "data": {"qq": "all"}}
                ]
                """).getAsJsonArray();

        // 群聊应包含 PlainText + At(98765) + AtAll
        MessageChain groupChain = OnebotMsgUtils.messageToChains(bot, group, arr, false);
        assertEquals(3, groupChain.size());
        assertInstanceOf(PlainText.class, groupChain.get(0));
        assertInstanceOf(At.class, groupChain.get(1));
        assertEquals(98765L, ((At) groupChain.get(1)).getTarget());
        assertSame(AtAll.INSTANCE, groupChain.get(2));

        // 私聊应跳过 At，仅保留 PlainText
        MessageChain privateChain = OnebotMsgUtils.messageToChains(bot, friend, arr, false);
        assertEquals(1, privateChain.size());
        assertInstanceOf(PlainText.class, privateChain.get(0));
        assertEquals("hi", ((PlainText) privateChain.get(0)).getContent());
    }

    @Test
    void testCqStringToSegmentArrayRoundTrip() {
        JsonArray arr = OnebotMsgUtils.cqStringToSegmentArray("你好[CQ:at,qq=123]世界");
        assertEquals(3, arr.size());
        JsonObject seg0 = arr.get(0).getAsJsonObject();
        assertEquals("text", seg0.get("type").getAsString());
        assertEquals("你好", seg0.getAsJsonObject("data").get("text").getAsString());
        JsonObject seg1 = arr.get(1).getAsJsonObject();
        assertEquals("at", seg1.get("type").getAsString());
        assertEquals("123", seg1.getAsJsonObject("data").get("qq").getAsString());
    }
}
