package cn.evole.onebot.mirai.util;

import cn.evole.onebot.mirai.database.NanoDb;
import cn.evole.onebot.mirai.database.csv.Localizer;
import cn.evole.onebot.sdk.util.DataBaseUtils;
import net.mamoe.mirai.event.events.GroupMessageEvent;
import net.mamoe.mirai.event.events.MessageEvent;
import net.mamoe.mirai.message.data.MessageChain;
import net.mamoe.mirai.message.data.MessageSource;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;
import java.io.Serializable;

/**
 * 消息持久化工具：以 messageId 为主键存入 NanoDb，保留发送者昵称与消息类型供 get_msg / delete_msg / reply 兜底。
 */
public class DBUtils {
    public static class MessageNode implements Serializable {
        @Serial
        private static final long serialVersionUID = 114514114514L;
        public long contactId;
        public int messageId;
        public String content;
        public long senderId;
        public String senderNickname;
        public String messageType;
        public int time;

        public MessageNode(long contactId, int messageId, String content) {
            this(contactId, messageId, content, 0L, "", "private", (int) (System.currentTimeMillis() / 1000L));
        }

        public MessageNode(long contactId, int messageId, String content,
                           long senderId, String senderNickname, String messageType, int time) {
            this.contactId = contactId;
            this.messageId = messageId;
            this.content = content;
            this.senderId = senderId;
            this.senderNickname = senderNickname != null ? senderNickname : "";
            this.messageType = messageType != null ? messageType : "private";
            this.time = time;
        }
    }

    public static class Locates {
        private static Localizer l = null;

        public static Localizer getLocalizer() {
            if (l == null) {
                l = new Localizer();
                l.add("contactId", "群或者陌生人或者好友id");
                l.add("messageId", "消息id");
                l.add("content", "内容");
                l.add("senderId", "发送者id");
                l.add("senderNickname", "发送者昵称");
                l.add("messageType", "消息类型");
                l.add("time", "时间戳");
            }
            return l;
        }
    }

    public static void saveMessageToDB(MessageEvent event) {
        if (!PluginRuntime.isDbEnabled()) {
            return;
        }
        NanoDb<Long, MessageNode> db = PluginRuntime.getDb();
        if (db == null) {
            return;
        }
        try {
            MessageSource source = event.getSource();
            int messageId = DataBaseUtils.toMessageId(source.getInternalIds(), event.getBot().getId(), source.getFromId());
            String msgType = (event instanceof GroupMessageEvent) ? "group" : "private";
            String nick = event.getSenderName() != null ? event.getSenderName() : "";
            String serialized = MessageChain.serializeToJsonString(event.getMessage());
            db.set((long) messageId, new MessageNode(
                    event.getSubject().getId(),
                    messageId,
                    serialized,
                    event.getSender().getId(),
                    nick,
                    msgType,
                    source.getTime()
            ));
        } catch (Exception e) {
            PluginRuntime.getLogger().warning("保存消息到数据库失败: " + e.getMessage());
        }
    }

    public static void saveSentMessageToDB(
            long contactId,
            int messageId,
            MessageChain chain,
            long senderId,
            String senderNickname,
            String messageType,
            int time
    ) {
        if (!PluginRuntime.isDbEnabled()) {
            return;
        }
        NanoDb<Long, MessageNode> db = PluginRuntime.getDb();
        if (db == null || chain == null) {
            return;
        }
        try {
            String serialized = MessageChain.serializeToJsonString(chain);
            db.set((long) messageId, new MessageNode(
                    contactId,
                    messageId,
                    serialized,
                    senderId,
                    senderNickname,
                    messageType,
                    time
            ));
        } catch (Exception e) {
            PluginRuntime.getLogger().warning("保存已发送消息到数据库失败: " + e.getMessage());
        }
    }

    @Nullable
    public static MessageNode queryMessageNode(int messageId) {
        if (!PluginRuntime.isDbEnabled()) {
            return null;
        }
        NanoDb<Long, MessageNode> db = PluginRuntime.getDb();
        if (db == null) {
            return null;
        }
        try {
            return db.query((long) messageId);
        } catch (Exception e) {
            PluginRuntime.getLogger().warning("从数据库读取消息失败: " + e.getMessage());
            return null;
        }
    }

    @Nullable
    public static MessageChain deserializeChain(@Nullable MessageNode node) {
        if (node == null || node.content == null || node.content.isBlank()) {
            return null;
        }
        try {
            return MessageChain.deserializeFromJsonString(node.content);
        } catch (Exception e) {
            PluginRuntime.getLogger().warning("反序列化数据库消息链失败: " + e.getMessage());
            return null;
        }
    }

    @Nullable
    public static MessageSource loadMessageSourceFromDB(int messageId) {
        MessageChain chain = deserializeChain(queryMessageNode(messageId));
        return chain != null ? chain.get(MessageSource.Key) : null;
    }
}
