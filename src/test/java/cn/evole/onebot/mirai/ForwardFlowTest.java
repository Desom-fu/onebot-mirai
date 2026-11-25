package cn.evole.onebot.mirai;

import cn.evole.onebot.mirai.core.ApiMap;
import cn.evole.onebot.mirai.util.OnebotMsgUtils;
import cn.evole.onebot.mirai.util.PluginRuntime;
import cn.evole.onebot.sdk.action.ActionData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Friend;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.MessageReceipt;
import net.mamoe.mirai.message.data.*;
import net.mamoe.mirai.utils.MiraiLogger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

public class ForwardFlowTest {

    @SuppressWarnings("unchecked")
    private static MessageReceipt<Group> mockGroupReceipt(int msgId) {
        MessageReceipt<Group> receipt = Mockito.mock(MessageReceipt.class);
        OnlineMessageSource.Outgoing src = Mockito.mock(OnlineMessageSource.Outgoing.class);
        when(src.getIds()).thenReturn(new int[]{msgId});
        when(src.getInternalIds()).thenReturn(new int[]{msgId * 10});
        when(src.getTime()).thenReturn(1700000900);
        when(receipt.getSource()).thenReturn(src);
        return receipt;
    }

    @SuppressWarnings("unchecked")
    private static MessageReceipt<Friend> mockFriendReceipt(int msgId) {
        MessageReceipt<Friend> receipt = Mockito.mock(MessageReceipt.class);
        OnlineMessageSource.Outgoing src = Mockito.mock(OnlineMessageSource.Outgoing.class);
        when(src.getIds()).thenReturn(new int[]{msgId});
        when(src.getInternalIds()).thenReturn(new int[]{msgId * 10});
        when(src.getTime()).thenReturn(1700000901);
        when(receipt.getSource()).thenReturn(src);
        return receipt;
    }

    private static ForwardMessage simpleForward(long senderId, String senderName, String text) {
        MessageChain nodeChain = new MessageChainBuilder().append(text).build();
        ForwardMessage.Node node = new ForwardMessage.Node(senderId, 1700000000, senderName, nodeChain);
        return new ForwardMessage(
                List.of(senderName + ": " + text),
                "群聊的聊天记录",
                "[聊天记录]",
                "群聊的聊天记录",
                "查看1条转发消息",
                List.of(node)
        );
    }

    @Test
    void testMessageOriginForwardPlusForwardMessageConvertsWithoutWarningAndCachesResId() {
        MiraiLogger prevLogger = PluginRuntime.getLogger();
        MiraiLogger mockLogger = Mockito.mock(MiraiLogger.class);
        PluginRuntime.bind(mockLogger, "0.2.7", null, null, null, null);
        try {
            Bot bot = Mockito.mock(Bot.class);
            when(bot.getId()).thenReturn(10000L);
            ApiMap apiMap = new ApiMap(bot);

            ForwardMessage forward = simpleForward(12345L, "Alice", "forward body");
            MessageOrigin origin = new MessageOrigin(
                    new SimpleServiceMessage(35, "<msg serviceID=\"35\" m_resid=\"res_abc_123\"></msg>"),
                    "res_abc_123",
                    MessageOriginKind.FORWARD
            );
            MessageChain incoming = new MessageChainBuilder().append(origin).append(forward).build();

            apiMap.cacheForwardFromChain(9001, incoming);
            String cq = OnebotMsgUtils.toCQString(incoming, 9001, apiMap::cacheForwardMessage);

            assertEquals("[CQ:forward,id=9001]", cq);
            verify(mockLogger, never()).warning(contains("此处消息的转义尚未被插件支持"));
            assertSame(forward, apiMap.resolveForwardMessage("res_abc_123"));
            assertSame(forward, apiMap.resolveForwardMessage("9001"));

            JsonObject byResId = new JsonObject();
            byResId.addProperty("id", "res_abc_123");
            ActionData<?> resResp = apiMap.callMiraiApi("get_forward_msg", byResId);
            assertEquals("ok", resResp.getStatus());
            assertEquals(0, resResp.getRetCode());
            JsonObject resData = assertInstanceOf(JsonObject.class, resResp.getData());
            JsonArray messages = resData.getAsJsonArray("messages");
            assertEquals(1, messages.size());
            assertEquals("forward body", messages.get(0).getAsJsonObject().getAsJsonObject("data").get("content").getAsString());
        } finally {
            if (prevLogger != null) {
                PluginRuntime.bind(prevLogger, null, null, null, null, null);
            }
        }
    }

    @Test
    void testUnexpandedMessageOriginAndLongMessageOriginConversion() {
        MiraiLogger prevLogger = PluginRuntime.getLogger();
        MiraiLogger mockLogger = Mockito.mock(MiraiLogger.class);
        PluginRuntime.bind(mockLogger, "0.2.7", null, null, null, null);
        try {
            MessageOrigin unexpandedForwardOrigin = new MessageOrigin(
                    new SimpleServiceMessage(35, "<msg serviceID=\"35\" m_resid=\"res_unexpanded\"/>"),
                    "res_unexpanded",
                    MessageOriginKind.FORWARD
            );
            MessageChain forwardOriginOnly = new MessageChainBuilder().append(unexpandedForwardOrigin).build();
            assertEquals("[CQ:forward,id=res_unexpanded]", OnebotMsgUtils.toCQString(forwardOriginOnly, 0, null));
            assertEquals("[CQ:forward,id=8801]", OnebotMsgUtils.toCQString(forwardOriginOnly, 8801, null));

            MessageOrigin longOrigin = new MessageOrigin(
                    new SimpleServiceMessage(35, "<msg serviceID=\"35\" m_resid=\"res_long\"/>"),
                    "res_long",
                    MessageOriginKind.LONG
            );
            MessageChain longMsgChain = new MessageChainBuilder().append(longOrigin).append("long text body").build();
            assertEquals("long text body", OnebotMsgUtils.toCQString(longMsgChain, 8802, null));
            verify(mockLogger, never()).warning(contains("此处消息的转义尚未被插件支持"));
        } finally {
            if (prevLogger != null) {
                PluginRuntime.bind(prevLogger, null, null, null, null, null);
            }
        }
    }

    @Test
    void testGetForwardMsgWithNestedForwardAllocatesSyntheticIdAndSupportsChainedLookup() {
        Bot bot = Mockito.mock(Bot.class);
        when(bot.getId()).thenReturn(10000L);
        ApiMap apiMap = new ApiMap(bot);

        ForwardMessage inner = simpleForward(20001L, "InnerSender", "inner line");
        MessageOrigin innerOrigin = new MessageOrigin(
                new SimpleServiceMessage(35, "<msg serviceID=\"35\" m_resid=\"nested_res_1\"/>"),
                "nested_res_1",
                MessageOriginKind.FORWARD
        );
        MessageChain outerNodeChain = new MessageChainBuilder().append(innerOrigin).append(inner).build();
        ForwardMessage outer = new ForwardMessage(
                List.of("OuterSender: [聊天记录]"),
                "群聊的聊天记录",
                "[聊天记录]",
                "群聊的聊天记录",
                "查看1条转发消息",
                List.of(new ForwardMessage.Node(30001L, 1700000100, "OuterSender", outerNodeChain))
        );
        apiMap.cacheForwardMessage(7001, outer);

        JsonObject req = new JsonObject();
        req.addProperty("id", "7001");
        ActionData<?> outerResp = apiMap.callMiraiApi("get_forward_msg", req);
        assertEquals("ok", outerResp.getStatus());
        assertEquals(0, outerResp.getRetCode());

        JsonObject outerData = assertInstanceOf(JsonObject.class, outerResp.getData());
        JsonArray outerNodes = outerData.getAsJsonArray("messages");
        assertEquals(1, outerNodes.size());
        String content = outerNodes.get(0).getAsJsonObject().getAsJsonObject("data").get("content").getAsString();
        Matcher matcher = Pattern.compile("\\[CQ:forward,id=(-?\\d+)]").matcher(content);
        assertTrue(matcher.matches(), "Nested forward should allocate non-zero synthetic id, got: " + content);
        String syntheticId = matcher.group(1);
        assertNotEquals("0", syntheticId);

        JsonObject innerReq = new JsonObject();
        innerReq.addProperty("id", syntheticId);
        ActionData<?> innerResp = apiMap.callMiraiApi("get_forward_msg", innerReq);
        assertEquals("ok", innerResp.getStatus());
        assertEquals(0, innerResp.getRetCode());
        JsonObject innerData = assertInstanceOf(JsonObject.class, innerResp.getData());
        JsonArray innerNodes = innerData.getAsJsonArray("messages");
        assertEquals(1, innerNodes.size());
        assertEquals("inner line", innerNodes.get(0).getAsJsonObject().getAsJsonObject("data").get("content").getAsString());
    }

    @Test
    void testSendForwardWithNestedNodeArrayAndByMessageIdReferenceAndCqNode() {
        Bot bot = Mockito.mock(Bot.class);
        when(bot.getId()).thenReturn(10000L);
        when(bot.getNick()).thenReturn("BotNick");
        Group group = Mockito.mock(Group.class);
        when(group.getId()).thenReturn(10001L);
        when(bot.getGroupOrFail(10001L)).thenReturn(group);
        when(bot.getGroup(10001L)).thenReturn(group);
        MessageReceipt<Group> receipt1 = mockGroupReceipt(9101);
        MessageReceipt<Group> receipt2 = mockGroupReceipt(9102);
        when(group.sendMessage(any(MessageChain.class)))
                .thenReturn(receipt1, receipt2);

        ApiMap apiMap = new ApiMap(bot);

        // Source-queue backed referenced message with metadata that should be stripped on forward
        MessageSource refSrc = Mockito.mock(MessageSource.class);
        when(refSrc.getFromId()).thenReturn(22222L);
        when(refSrc.getTargetId()).thenReturn(10001L);
        when(refSrc.getTime()).thenReturn(1700000111);
        when(refSrc.getIds()).thenReturn(new int[]{5001});
        when(refSrc.getInternalIds()).thenReturn(new int[]{50010});
        when(refSrc.getKind()).thenReturn(MessageSourceKind.GROUP);
        when(refSrc.isOriginalMessageInitialized()).thenReturn(true);
        MessageOrigin extraOrigin = new MessageOrigin(
                new SimpleServiceMessage(35, "<msg serviceID=\"35\"/>"),
                "res_dummy",
                MessageOriginKind.LONG
        );
        MessageChain refOrigChain = new MessageChainBuilder()
                .append(extraOrigin)
                .append("from source queue")
                .build();
        when(refSrc.getOriginalMessage()).thenReturn(refOrigChain);
        int queuedMsgId = apiMap.getCachedSourceQueue().add(refSrc);

        // Message-cache backed referenced message
        apiMap.cacheMessage(5002, "group", 1700000222, 44444L, "Carol", "", "", "from msg cache", 10001L);

        // Build nested node inside node[0]
        JsonObject nestedNode = new JsonObject();
        nestedNode.addProperty("type", "node");
        JsonObject nestedData = new JsonObject();
        nestedData.addProperty("user_id", 11111L);
        nestedData.addProperty("nickname", "NestedAuthor");
        nestedData.addProperty("content", "nested hello");
        nestedNode.add("data", nestedData);
        JsonArray nestedContent = new JsonArray();
        nestedContent.add(nestedNode);

        JsonObject outerNestedNode = new JsonObject();
        outerNestedNode.addProperty("type", "node");
        JsonObject outerNestedData = new JsonObject();
        outerNestedData.addProperty("user_id", 10002L);
        outerNestedData.addProperty("nickname", "OuterAuthor");
        outerNestedData.add("content", nestedContent);
        outerNestedNode.add("data", outerNestedData);

        JsonObject refByQueueNode = new JsonObject();
        refByQueueNode.addProperty("type", "node");
        JsonObject refByQueueData = new JsonObject();
        refByQueueData.addProperty("id", String.valueOf(queuedMsgId));
        refByQueueNode.add("data", refByQueueData);

        JsonObject refByCacheNode = new JsonObject();
        refByCacheNode.addProperty("type", "node");
        JsonObject refByCacheData = new JsonObject();
        refByCacheData.addProperty("id", 5002);
        refByCacheNode.add("data", refByCacheData);

        JsonArray messages = new JsonArray();
        messages.add(outerNestedNode);
        messages.add(refByQueueNode);
        messages.add(refByCacheNode);

        JsonObject params = new JsonObject();
        params.addProperty("group_id", 10001L);
        params.add("messages", messages);

        ActionData<?> resp = apiMap.callMiraiApi("send_group_forward_msg", params);
        assertEquals("ok", resp.getStatus());
        assertEquals(0, resp.getRetCode());

        // Also test [CQ:node,...] passed to send_group_msg
        JsonObject cqNodeParams = new JsonObject();
        cqNodeParams.addProperty("group_id", 10001L);
        cqNodeParams.addProperty("message", "[CQ:node,user_id=33333,nickname=CqUser,content=from cq node]");
        ActionData<?> cqResp = apiMap.callMiraiApi("send_group_msg", cqNodeParams);
        assertEquals("ok", cqResp.getStatus());
        assertEquals(0, cqResp.getRetCode());

        ArgumentCaptor<MessageChain> chainCaptor = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, times(2)).sendMessage(chainCaptor.capture());

        List<MessageChain> sentChains = chainCaptor.getAllValues();
        ForwardMessage sentForward = sentChains.get(0).get(ForwardMessage.Key);
        assertNotNull(sentForward);
        assertEquals(3, sentForward.getNodeList().size());

        ForwardMessage innerForward = sentForward.getNodeList().get(0).getMessageChain().get(ForwardMessage.Key);
        assertNotNull(innerForward);
        assertEquals(1, innerForward.getNodeList().size());
        assertEquals("nested hello", innerForward.getNodeList().get(0).getMessageChain().contentToString());

        ForwardMessage.Node refNode1 = sentForward.getNodeList().get(1);
        assertEquals(22222L, refNode1.getSenderId());
        assertEquals("from source queue", refNode1.getMessageChain().contentToString());
        for (SingleMessage sm : refNode1.getMessageChain()) {
            assertFalse(sm instanceof MessageMetadata, "Metadata should be stripped when forwarding by id");
        }

        ForwardMessage.Node refNode2 = sentForward.getNodeList().get(2);
        assertEquals(44444L, refNode2.getSenderId());
        assertEquals("Carol", refNode2.getSenderName());
        assertEquals("from msg cache", refNode2.getMessageChain().contentToString());

        ForwardMessage cqForward = sentChains.get(1).get(ForwardMessage.Key);
        assertNotNull(cqForward);
        assertEquals(1, cqForward.getNodeList().size());
        assertEquals(33333L, cqForward.getNodeList().get(0).getSenderId());
        assertEquals("CqUser", cqForward.getNodeList().get(0).getSenderName());
        assertEquals("from cq node", cqForward.getNodeList().get(0).getMessageChain().contentToString());
    }

    @Test
    void testSendGroupMsgWithCqForwardIdAndSingleMsgForwarding() {
        Bot bot = Mockito.mock(Bot.class);
        when(bot.getId()).thenReturn(10000L);
        Group group = Mockito.mock(Group.class);
        when(group.getId()).thenReturn(10001L);
        when(bot.getGroupOrFail(10001L)).thenReturn(group);
        Friend friend = Mockito.mock(Friend.class);
        when(friend.getId()).thenReturn(20002L);
        when(bot.getFriend(20002L)).thenReturn(friend);

        MessageReceipt<Group> receipt1 = mockGroupReceipt(9201);
        MessageReceipt<Group> receipt2 = mockGroupReceipt(9202);
        MessageReceipt<Friend> friendReceipt = mockFriendReceipt(9203);
        when(group.sendMessage(any(MessageChain.class)))
                .thenReturn(receipt1, receipt2);
        when(friend.sendMessage(any(MessageChain.class)))
                .thenReturn(friendReceipt);

        ApiMap apiMap = new ApiMap(bot);
        ForwardMessage existingForward = simpleForward(55555L, "OrigSender", "re-forwarded payload");
        apiMap.cacheForwardMessage(6001, existingForward, "res_cached_1");

        // Send [CQ:forward,id=res_cached_1] via send_group_msg (without SessionManager registration)
        JsonObject cqForwardReq = new JsonObject();
        cqForwardReq.addProperty("group_id", 10001L);
        cqForwardReq.addProperty("message", "[CQ:forward,id=res_cached_1]");
        ActionData<?> fwdSendResp = apiMap.callMiraiApi("send_group_msg", cqForwardReq);
        assertEquals("ok", fwdSendResp.getStatus());
        assertEquals(0, fwdSendResp.getRetCode());

        // Prepare single message source with MessageMetadata to test forward_group_single_msg & forward_friend_single_msg
        MessageSource singleSrc = Mockito.mock(MessageSource.class);
        when(singleSrc.getFromId()).thenReturn(66666L);
        when(singleSrc.getTargetId()).thenReturn(10001L);
        when(singleSrc.getTime()).thenReturn(1700000333);
        when(singleSrc.getIds()).thenReturn(new int[]{6002});
        when(singleSrc.getInternalIds()).thenReturn(new int[]{60020});
        when(singleSrc.getKind()).thenReturn(MessageSourceKind.GROUP);
        when(singleSrc.isOriginalMessageInitialized()).thenReturn(true);
        MessageOrigin longOrigin = new MessageOrigin(
                new SimpleServiceMessage(35, "<msg/>"),
                "res_single",
                MessageOriginKind.LONG
        );
        MessageChain singleOrigChain = new MessageChainBuilder()
                .append(longOrigin)
                .append("single msg text")
                .build();
        when(singleSrc.getOriginalMessage()).thenReturn(singleOrigChain);
        int singleMsgId = apiMap.getCachedSourceQueue().add(singleSrc);

        JsonObject fwdGroupSingle = new JsonObject();
        fwdGroupSingle.addProperty("group_id", 10001L);
        fwdGroupSingle.addProperty("message_id", singleMsgId);
        ActionData<?> grpSingleResp = apiMap.callMiraiApi("forward_group_single_msg", fwdGroupSingle);
        assertEquals("ok", grpSingleResp.getStatus());
        assertEquals(0, grpSingleResp.getRetCode());

        JsonObject fwdFriendSingle = new JsonObject();
        fwdFriendSingle.addProperty("user_id", 20002L);
        fwdFriendSingle.addProperty("message_id", singleMsgId);
        ActionData<?> frSingleResp = apiMap.callMiraiApi("forward_friend_single_msg", fwdFriendSingle);
        assertEquals("ok", frSingleResp.getStatus());
        assertEquals(0, frSingleResp.getRetCode());

        ArgumentCaptor<MessageChain> groupCaptor = ArgumentCaptor.forClass(MessageChain.class);
        verify(group, times(2)).sendMessage(groupCaptor.capture());
        assertEquals(existingForward, groupCaptor.getAllValues().get(0).get(ForwardMessage.Key));

        MessageChain grpSingleChain = groupCaptor.getAllValues().get(1);
        assertEquals("single msg text", grpSingleChain.contentToString());
        for (SingleMessage sm : grpSingleChain) {
            assertFalse(sm instanceof MessageMetadata, "forward_group_single_msg must strip MessageMetadata");
        }

        ArgumentCaptor<MessageChain> friendCaptor = ArgumentCaptor.forClass(MessageChain.class);
        verify(friend, times(1)).sendMessage(friendCaptor.capture());
        MessageChain frSingleChain = friendCaptor.getValue();
        assertEquals("single msg text", frSingleChain.contentToString());
        for (SingleMessage sm : frSingleChain) {
            assertFalse(sm instanceof MessageMetadata, "forward_friend_single_msg must strip MessageMetadata");
        }
    }
}
