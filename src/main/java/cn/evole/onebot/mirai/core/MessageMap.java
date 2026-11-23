package cn.evole.onebot.mirai.core;

import cn.evole.onebot.mirai.core.session.BotSession;
import cn.evole.onebot.mirai.core.session.SessionManager;
import cn.evole.onebot.mirai.model.MiraiGroupSender;
import cn.evole.onebot.mirai.model.MiraiPrivateSender;
import cn.evole.onebot.mirai.util.OnebotMsgUtils;
import cn.evole.onebot.sdk.entity.Anonymous;
import cn.evole.onebot.sdk.event.Event;
import cn.evole.onebot.sdk.event.IgnoreEvent;
import cn.evole.onebot.sdk.event.message.PrivateMessageEvent;
import cn.evole.onebot.sdk.util.DataBaseUtils;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.AnonymousMember;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.event.events.*;
import net.mamoe.mirai.message.data.ForwardMessage;
import net.mamoe.mirai.message.data.MessageChain;
import net.mamoe.mirai.message.data.MessageSource;

import java.util.function.BiConsumer;

/**
 * Mirai 消息事件（含接收与自身发送）到 OneBot v11 事件 DTO 的转换器。
 */
public class MessageMap {

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static BiConsumer<Integer, ForwardMessage> forwardRecorderFor(long botId) {
        return (msgId, forwardMessage) -> {
            if (SessionManager.containsSession(botId)) {
                BotSession session = SessionManager.get(botId);
                if (session != null && session.getApiImpl() != null) {
                    session.getApiImpl().cacheForwardMessage(msgId, forwardMessage);
                }
            }
        };
    }

    public static String serializeMessageChain(long botId, int messageId, MessageChain chain) {
        return OnebotMsgUtils.toCQString(chain, messageId, forwardRecorderFor(botId));
    }

    public static Event toDTO(MessageEvent botEvent) {
        long botId = botEvent.getBot().getId();
        MessageSource source = botEvent.getSource();
        int messageId = DataBaseUtils.toMessageId(source.getInternalIds(), botId, source.getFromId());
        String rawMessage = serializeMessageChain(botId, messageId, botEvent.getMessage());
        long now = nowSeconds();

        if (botEvent instanceof GroupMessageEvent group) {
            var event = new cn.evole.onebot.sdk.event.message.GroupMessageEvent();
            event.setPostType("message");
            event.setMessageType("group");
            event.setSelfId(botId);
            if (group.getSender() instanceof AnonymousMember anonymous) {
                event.setSubType("anonymous");
                var anonymous1 = new Anonymous();
                anonymous1.setId(anonymous.getId());
                anonymous1.setFlag(anonymous.getAnonymousId() + "&" + anonymous.getNameCard());
                anonymous1.setName(anonymous.getNameCard());
                event.setAnonymous(anonymous1);
            } else {
                event.setSubType("normal");
                event.setAnonymous(null);
            }
            event.setMessageId(messageId);
            event.setGroupId(group.getGroup().getId());
            event.setUserId(group.getSender().getId());
            event.setMessage(rawMessage);
            event.setRawMessage(rawMessage);
            event.setFont(0);
            event.setSender(new MiraiGroupSender(group.getSender()));
            event.setTime(now);
            return event;
        } else if (botEvent instanceof FriendMessageEvent friend) {
            var event = new PrivateMessageEvent();
            event.setPostType("message");
            event.setMessageType("private");
            event.setSelfId(botId);
            event.setSubType("friend");
            event.setMessageId(messageId);
            event.setUserId(friend.getSender().getId());
            event.setMessage(rawMessage);
            event.setRawMessage(rawMessage);
            event.setFont(0);
            event.setPrivateSender(new MiraiPrivateSender(friend.getSender()));
            event.setTime(now);
            return event;
        } else if (botEvent instanceof GroupTempMessageEvent temp) {
            var event = new PrivateMessageEvent();
            event.setPostType("message");
            event.setMessageType("private");
            event.setSelfId(botId);
            event.setSubType("group");
            event.setMessageId(messageId);
            event.setUserId(temp.getSender().getId());
            event.setMessage(rawMessage);
            event.setRawMessage(rawMessage);
            event.setFont(0);
            event.setPrivateSender(new MiraiPrivateSender(temp.getSender()));
            event.setTime(now);
            return event;
        } else {
            return new IgnoreEvent(botId);
        }
    }

    /**
     * 将 Mirai MessagePostSendEvent 转为 OneBot v11 message_sent 事件。
     */
    public static Event toSentDTO(MessagePostSendEvent<?> postSendEvent) {
        Bot bot = postSendEvent.getBot();
        long botId = bot.getId();
        if (postSendEvent.getException() != null || postSendEvent.getReceipt() == null) {
            return new IgnoreEvent(botId);
        }
        MessageSource source = postSendEvent.getReceipt().getSource();
        int messageId = DataBaseUtils.toMessageId(source.getInternalIds(), botId, source.getFromId());
        String rawMessage = serializeMessageChain(botId, messageId, postSendEvent.getMessage());
        long now = nowSeconds();

        if (postSendEvent instanceof GroupMessagePostSendEvent groupSend) {
            Group group = groupSend.getTarget();
            var event = new cn.evole.onebot.sdk.event.message.GroupMessageEvent();
            event.setPostType("message_sent");
            event.setMessageType("group");
            event.setSubType("normal");
            event.setSelfId(botId);
            event.setMessageId(messageId);
            event.setGroupId(group.getId());
            event.setUserId(botId);
            event.setAnonymous(null);
            event.setMessage(rawMessage);
            event.setRawMessage(rawMessage);
            event.setFont(0);
            var sender = new cn.evole.onebot.sdk.event.message.GroupMessageEvent.GroupSender();
            sender.setUserId(String.valueOf(botId));
            sender.setNickname(bot.getNick());
            try {
                var botMember = group.getBotAsMember();
                sender.setCard(botMember.getNameCard());
                sender.setTitle(botMember.getSpecialTitle());
                sender.setRole(botMember.getPermission().name().equals("ADMINISTRATOR")
                        ? "admin" : botMember.getPermission().name().toLowerCase());
            } catch (Exception ignored) {
                sender.setCard("");
                sender.setRole("member");
            }
            event.setSender(sender);
            event.setTime(now);
            return event;
        } else if (postSendEvent instanceof FriendMessagePostSendEvent friendSend) {
            var event = new PrivateMessageEvent();
            event.setPostType("message_sent");
            event.setMessageType("private");
            event.setSubType("friend");
            event.setSelfId(botId);
            event.setMessageId(messageId);
            event.setUserId(friendSend.getTarget().getId());
            event.setMessage(rawMessage);
            event.setRawMessage(rawMessage);
            event.setFont(0);
            var sender = new PrivateMessageEvent.PrivateSender();
            sender.setUserId(botId);
            sender.setNickname(bot.getNick());
            event.setPrivateSender(sender);
            event.setTime(now);
            return event;
        } else if (postSendEvent instanceof GroupTempMessagePostSendEvent tempSend) {
            var event = new PrivateMessageEvent();
            event.setPostType("message_sent");
            event.setMessageType("private");
            event.setSubType("group");
            event.setSelfId(botId);
            event.setMessageId(messageId);
            event.setUserId(tempSend.getTarget().getId());
            event.setMessage(rawMessage);
            event.setRawMessage(rawMessage);
            event.setFont(0);
            var sender = new PrivateMessageEvent.PrivateSender();
            sender.setUserId(botId);
            sender.setNickname(bot.getNick());
            event.setPrivateSender(sender);
            event.setTime(now);
            return event;
        }
        return new IgnoreEvent(botId);
    }
}
