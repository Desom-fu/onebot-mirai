package cn.evole.onebot.mirai;

import cn.evole.onebot.mirai.cmd.OneBotMiraiCmd;
import cn.evole.onebot.mirai.config.PluginConfig;
import cn.evole.onebot.mirai.core.MessageMap;
import cn.evole.onebot.mirai.core.session.BotSession;
import cn.evole.onebot.mirai.core.session.SessionManager;
import cn.evole.onebot.mirai.database.NanoDb;
import cn.evole.onebot.mirai.util.BaseUtils;
import cn.evole.onebot.mirai.util.DBUtils;
import cn.evole.onebot.mirai.util.HttpUtils;
import cn.evole.onebot.mirai.util.PluginRuntime;
import cn.evole.onebot.sdk.util.DataBaseUtils;
import cn.evole.onebot.sdk.util.FileUtils;
import lombok.val;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.Mirai;
import net.mamoe.mirai.console.command.CommandManager;
import net.mamoe.mirai.console.plugin.jvm.JavaPlugin;
import net.mamoe.mirai.contact.Friend;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.contact.Member;
import net.mamoe.mirai.event.Event;
import net.mamoe.mirai.event.GlobalEventChannel;
import net.mamoe.mirai.event.Listener;
import net.mamoe.mirai.event.events.*;
import net.mamoe.mirai.message.data.Image;
import net.mamoe.mirai.message.data.MessageSource;
import net.mamoe.mirai.message.data.OnlineAudio;
import net.mamoe.mirai.utils.MiraiLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

import static cn.evole.onebot.mirai.util.ImgUtils.constructCacheImageMeta;
import static cn.evole.onebot.mirai.util.ImgUtils.getImageType;

public final class OneBotMirai extends JavaPlugin {
    public static String VERSION;
    public static final OneBotMirai INSTANCE = new OneBotMirai();

    public static MiraiLogger logger = INSTANCE.getLogger();

    private Listener<? extends Event> initialSubscription = null;

    private OneBotMirai() {
        VERSION = getDescription().getVersion().toString();
    }

    public final File imageFolder = new File(getDataFolder(), "image");
    public final File recordFolder = new File(getDataFolder(), "record");
    public final File dbFolder = new File(getDataFolder(), "db");
    public NanoDb<Long, DBUtils.MessageNode> db = null;

    @Override
    public void onEnable() {
        bindRuntime();
        CommandManager.INSTANCE.registerCommand(OneBotMiraiCmd.INSTANCE, false);

        logger.info("OneBot Loaded!");
        logger.info("插件当前版本: " + VERSION);
        logger.info("交流请提 Issue: https://github.com/Desom-fu/onebot-mirai/issues");

        init();
    }

    @Override
    public void onDisable() {
        close();
        logger.info("OneBot Stopped!");
    }

    private void bindRuntime() {
        PluginRuntime.bind(
                this.getLogger(),
                VERSION,
                getDataFolder(),
                () -> PluginConfig.INSTANCE.getDebug(),
                () -> PluginConfig.INSTANCE.getDb().getEnable(),
                () -> this.db
        );
    }

    public void init() {
        bindRuntime();
        this.reloadPluginConfig(PluginConfig.INSTANCE);
        FileUtils.checkFolder(imageFolder.toPath());
        FileUtils.checkFolder(recordFolder.toPath());
        FileUtils.checkFolder(dbFolder.toPath());
        if (PluginConfig.INSTANCE.getDb().getEnable()) {
            try {
                db = new NanoDb<>(dbFolder + "/db.obm");
            } catch (Exception e) {
                logger.warning("初始化本地消息数据库失败: " + e.getMessage());
            }
        }
        List<Bot> instances = Bot.getInstances();
        logger.info(String.format("当前存在的机器人数量: %d", instances.size()));
        instances.forEach(bot -> {
            logger.info(String.format("Bot : %s 开始创建", bot.getId()));
            if (SessionManager.containsSession(bot.getId())
                    && SessionManager.get(bot.getId()).getBot() != bot) {
                logger.info(String.format("Bot : %s 实例已更新，关闭旧 Session", bot.getId()));
                SessionManager.closeSession(bot.getId());
            }
            if (!SessionManager.getSessions().containsKey(bot.getId())) {
                val botId = String.valueOf(bot.getId());
                if (Objects.requireNonNull(PluginConfig.INSTANCE.getBots()).containsKey(botId)) {
                    val mapConfig = PluginConfig.INSTANCE.getBots().get(botId);
                    SessionManager.createBotSession(bot, mapConfig);
                    logger.info(String.format("Bot: %s 创建Session", bot.getId()));
                } else {
                    logger.debug(String.format("Bot: %s 未进行OneBot配置,请在setting.yml中进行配置", bot.getId()));
                }
            } else {
                logger.debug(String.format("Bot: %s 已经存在", bot.getId()));
            }
        });

        initialSubscription = GlobalEventChannel.INSTANCE.subscribeAlways(BotEvent.class, event -> {
            if (event instanceof BotOnlineEvent onlineEvent) {
                long onlineBotId = onlineEvent.getBot().getId();
                // 断线重连时 mirai 会给出新的 Bot 实例，旧 Session 仍占着 HTTP 端口：
                // 必须先关掉旧的，否则新 Session 绑同一端口会 BindException，且旧服务仍对外应答。
                if (SessionManager.containsSession(onlineBotId)
                        && SessionManager.get(onlineBotId).getBot() != onlineEvent.getBot()) {
                    logger.info(String.format("Bot: %s 重新上线，关闭旧 Session", onlineBotId));
                    SessionManager.closeSession(onlineBotId);
                }
                if (!SessionManager.containsSession(onlineBotId)) {
                    val botId = String.valueOf(onlineBotId);
                    if (Objects.requireNonNull(PluginConfig.INSTANCE.getBots()).containsKey(botId)) {
                        val mapConfig = PluginConfig.INSTANCE.getBots().get(botId);
                        val session = SessionManager.createBotSession(onlineEvent.getBot(), mapConfig);
                        logger.info(String.format("Bot: %s 创建Session", event.getBot().getId()));
                        if (PluginConfig.INSTANCE.getDebug()) {
                            logger.info("OneBot Session: " + session);
                        }
                    } else {
                        logger.warning(String.format("Bot: %s 未进行OneBot配置,请在setting.yml中进行配置", event.getBot().getId()));
                    }
                }
            }

            long botId = event.getBot().getId();
            if (!SessionManager.containsSession(botId)) {
                return;
            }
            BotSession session = SessionManager.get(botId);

            // 先完成本地缓存/DB入库，再触发上报，避免上游收到事件立即回调 get_msg/reply/approve 时竞态未命中
            if (event instanceof MessageEvent messageEvent) {
                handleIncomingMessageCache(session, messageEvent);
            } else if (event instanceof MessagePostSendEvent<?> postSendEvent) {
                handleSentMessageCache(session, postSendEvent);
            } else if (event instanceof NewFriendRequestEvent requestEvent) {
                session.getApiImpl().getCacheRequestQueue().add(requestEvent);
            } else if (event instanceof MemberJoinRequestEvent requestEvent) {
                session.getApiImpl().getCacheRequestQueue().add(requestEvent);
            } else if (event instanceof BotInvitedJoinGroupRequestEvent requestEvent) {
                session.getApiImpl().getCacheRequestQueue().add(requestEvent);
            }

            session.triggerEvent(event);
        });
    }

    private void handleIncomingMessageCache(BotSession session, MessageEvent messageEvent) {
        Bot bot = messageEvent.getBot();
        DBUtils.saveMessageToDB(messageEvent);

        // 群聊、好友私聊、群临时会话的 MessageSource 全部进入 CacheSourceQueue
        MessageSource source = messageEvent.getSource();
        int messageId = session.getApiImpl().getCachedSourceQueue().add(source);
        String rawMessage = MessageMap.serializeMessageChain(bot.getId(), messageId, messageEvent.getMessage());

        if (messageEvent instanceof GroupMessageEvent groupMsg) {
            var sender = groupMsg.getSender();
            String role = sender.getPermission().name().equals("ADMINISTRATOR")
                    ? "admin" : sender.getPermission().name().toLowerCase();
            session.getApiImpl().cacheMessage(
                    messageId, "group", source.getTime(),
                    sender.getId(), sender.getNick(), sender.getNameCard(), role,
                    rawMessage, groupMsg.getGroup().getId()
            );
            session.getApiImpl().recordRecentContact(
                    true, groupMsg.getGroup().getId(), groupMsg.getGroup().getName(), "",
                    source.getTime(), messageId, sender.getNick(), sender.getNameCard(), rawMessage
            );
        } else if (messageEvent instanceof FriendMessageEvent friendMsg) {
            Friend sender = friendMsg.getSender();
            session.getApiImpl().cacheMessage(
                    messageId, "private", source.getTime(),
                    sender.getId(), sender.getNick(), "", "",
                    rawMessage, 0L
            );
            session.getApiImpl().recordRecentContact(
                    false, sender.getId(), sender.getNick(), sender.getRemark(),
                    source.getTime(), messageId, sender.getNick(), "", rawMessage
            );
        } else if (messageEvent instanceof GroupTempMessageEvent tempMsg) {
            session.getApiImpl().getCachedTempContact()
                    .put(tempMsg.getSender().getId(), tempMsg.getGroup().getId());
            session.getApiImpl().cacheMessage(
                    messageId, "private", source.getTime(),
                    tempMsg.getSender().getId(), tempMsg.getSender().getNick(), tempMsg.getSender().getNameCard(), "",
                    rawMessage, tempMsg.getGroup().getId()
            );
            session.getApiImpl().recordRecentContact(
                    false, tempMsg.getSender().getId(), tempMsg.getSender().getNick(), "",
                    source.getTime(), messageId, tempMsg.getSender().getNick(), tempMsg.getSender().getNameCard(), rawMessage
            );
        }

        if (session.getBotConfig().getCacheImage()) {
            messageEvent.getMessage().stream()
                    .filter(singleMessage -> singleMessage instanceof Image)
                    .forEach(singleMessage -> {
                        val img = (Image) singleMessage;
                        long imageSize;
                        val imageMD5 = BaseUtils.bytesToHexString(img.getMd5());
                        val subject = messageEvent.getSubject();
                        if (subject instanceof Member || subject instanceof Friend) {
                            imageSize = (long) img.getHeight() * img.getWidth();
                        } else if (subject instanceof Group) {
                            imageSize = img.getSize();
                        } else {
                            imageSize = 0;
                        }
                        try {
                            val imgMetaContent = constructCacheImageMeta(
                                    imageMD5,
                                    imageSize,
                                    Mirai.getInstance().queryImageUrl(bot, img),
                                    getImageType(img)
                            );
                            saveImageOrRecord(img.getImageId() + ".cqimg", imgMetaContent, true);
                        } catch (Exception e) {
                            logger.debug("缓存图片元数据失败: " + e.getMessage());
                        }
                    });
        }
        if (session.getBotConfig().getCacheRecord()) {
            messageEvent.getMessage().stream()
                    .filter(singleMessage -> singleMessage instanceof OnlineAudio)
                    .forEach(singleMessage -> {
                        val audio = (OnlineAudio) singleMessage;
                        val voiceUrl = audio.getUrlForDownload();
                        val voiceBytes = HttpUtils.getBytesFromHttpUrl(voiceUrl);
                        if (voiceBytes != null) {
                            saveImageOrRecord(
                                    BaseUtils.bytesToHexString(audio.getFileMd5()) + ".cqrecord",
                                    voiceBytes,
                                    false
                            );
                        }
                    });
        }
    }

    private void handleSentMessageCache(BotSession session, MessagePostSendEvent<?> postSendEvent) {
        if (postSendEvent.getException() != null || postSendEvent.getReceipt() == null) {
            return;
        }
        Bot bot = postSendEvent.getBot();
        MessageSource source = postSendEvent.getReceipt().getSource();
        int messageId = session.getApiImpl().getCachedSourceQueue().add(source);
        String rawMessage = MessageMap.serializeMessageChain(bot.getId(), messageId, postSendEvent.getMessage());

        if (postSendEvent instanceof GroupMessagePostSendEvent groupSend) {
            Group group = groupSend.getTarget();
            session.getApiImpl().cacheMessage(
                    messageId, "group", source.getTime(),
                    bot.getId(), bot.getNick(), "", "",
                    rawMessage, group.getId()
            );
            DBUtils.saveSentMessageToDB(group.getId(), messageId, postSendEvent.getMessage(),
                    bot.getId(), bot.getNick(), "group", source.getTime());
            session.getApiImpl().recordRecentContact(
                    true, group.getId(), group.getName(), "",
                    source.getTime(), messageId, bot.getNick(), "", rawMessage
            );
        } else if (postSendEvent instanceof FriendMessagePostSendEvent friendSend) {
            Friend friend = friendSend.getTarget();
            session.getApiImpl().cacheMessage(
                    messageId, "private", source.getTime(),
                    bot.getId(), bot.getNick(), "", "",
                    rawMessage, 0L
            );
            DBUtils.saveSentMessageToDB(friend.getId(), messageId, postSendEvent.getMessage(),
                    bot.getId(), bot.getNick(), "private", source.getTime());
            session.getApiImpl().recordRecentContact(
                    false, friend.getId(), friend.getNick(), friend.getRemark(),
                    source.getTime(), messageId, bot.getNick(), "", rawMessage
            );
        } else if (postSendEvent instanceof GroupTempMessagePostSendEvent tempSend) {
            session.getApiImpl().cacheMessage(
                    messageId, "private", source.getTime(),
                    bot.getId(), bot.getNick(), "", "",
                    rawMessage, tempSend.getGroup().getId()
            );
            DBUtils.saveSentMessageToDB(tempSend.getTarget().getId(), messageId, postSendEvent.getMessage(),
                    bot.getId(), bot.getNick(), "private", source.getTime());
        }
    }

    public void close() {
        if (db != null) {
            db.save();
        }
        if (initialSubscription != null) {
            initialSubscription.complete();
        }
        SessionManager.closeAllSessions();
    }

    private File image(String imageName) {
        return new File(this.imageFolder, imageName);
    }

    private File record(String recordName) {
        return new File(this.recordFolder, recordName);
    }

    public void saveImageOrRecord(String name, String data, boolean img) {
        BaseUtils.safeRun(() -> {
            if (data != null) {
                try {
                    Files.writeString(img ? image(name).toPath() : record(name).toPath(), data, StandardOpenOption.CREATE_NEW);
                } catch (IOException ignored) {
                }
            }
        });
    }

    public void saveImageOrRecord(String name, byte[] data, boolean img) {
        BaseUtils.safeRun(() -> {
            if (data != null) {
                try {
                    Files.write(img ? image(name).toPath() : record(name).toPath(), data, StandardOpenOption.CREATE_NEW);
                } catch (IOException ignored) {
                }
            }
        });
    }
}
