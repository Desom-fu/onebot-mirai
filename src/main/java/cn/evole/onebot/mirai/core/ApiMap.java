package cn.evole.onebot.mirai.core;

import cn.evole.onebot.mirai.model.DetailedFailure;
import cn.evole.onebot.mirai.model.MiraiGroupHonorInfoResp;
import cn.evole.onebot.mirai.model.MiraiGroupMemberInfoResp;
import cn.evole.onebot.mirai.util.*;
import cn.evole.onebot.mirai.web.queue.CacheRequestQueue;
import cn.evole.onebot.mirai.web.queue.CacheSourceQueue;
import cn.evole.onebot.sdk.action.ActionData;
import cn.evole.onebot.sdk.response.contact.FriendInfoResp;
import cn.evole.onebot.sdk.response.contact.LoginInfoResp;
import cn.evole.onebot.sdk.response.contact.StrangerInfoResp;
import cn.evole.onebot.sdk.response.group.GroupAtAllRemainResp;
import cn.evole.onebot.sdk.response.group.GroupFilesResp;
import cn.evole.onebot.sdk.response.group.GroupInfoResp;
import cn.evole.onebot.sdk.response.group.GroupMemberInfoResp;
import cn.evole.onebot.sdk.response.misc.*;
import cn.evole.onebot.sdk.response.misc.MiraiResp.MessageSuccess;
import cn.evole.onebot.sdk.response.misc.MiraiResp.Success;
import cn.evole.onebot.sdk.util.DataBaseUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lombok.Getter;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.LowLevelApi;
import net.mamoe.mirai.Mirai;
import net.mamoe.mirai.contact.*;
import net.mamoe.mirai.contact.announcement.AnnouncementImage;
import net.mamoe.mirai.contact.announcement.AnnouncementParameters;
import net.mamoe.mirai.contact.announcement.AnnouncementParametersBuilder;
import net.mamoe.mirai.contact.announcement.OfflineAnnouncement;
import net.mamoe.mirai.contact.announcement.OnlineAnnouncement;
import net.mamoe.mirai.contact.essence.EssenceMessageRecord;
import net.mamoe.mirai.contact.file.AbsoluteFile;
import net.mamoe.mirai.contact.file.AbsoluteFolder;
import net.mamoe.mirai.event.events.BotEvent;
import net.mamoe.mirai.event.events.BotInvitedJoinGroupRequestEvent;
import net.mamoe.mirai.event.events.MemberJoinRequestEvent;
import net.mamoe.mirai.event.events.NewFriendRequestEvent;
import net.mamoe.mirai.message.MessageReceipt;
import net.mamoe.mirai.message.data.*;
import net.mamoe.mirai.utils.ExternalResource;
import net.mamoe.mirai.utils.MiraiExperimentalApi;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static cn.evole.onebot.mirai.util.ImgUtils.getCachedImageFile;
import static cn.evole.onebot.mirai.util.ImgUtils.getImageType;
import static cn.evole.onebot.mirai.util.VoiceUtils.getCachedRecordFile;

/**
 * OneBot v11 及扩展动作全量分发与实现。
 */
public class ApiMap {
    private static final int FORWARD_CACHE_CAPACITY = 256;
    private static final int MESSAGE_CACHE_CAPACITY = 2048;
    private static final int RECENT_CONTACT_CAPACITY = 100;
    private static final int IGNORED_REQUEST_CAPACITY = 256;

    final Bot bot;

    @Getter
    private final Map<Long, Long> cachedTempContact = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > 512;
        }
    });

    @Getter
    private final CacheRequestQueue cacheRequestQueue = new CacheRequestQueue();

    @Getter
    private final CacheSourceQueue cachedSourceQueue = new CacheSourceQueue();

    private final Map<Integer, ForwardMessage> forwardCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, ForwardMessage> eldest) {
            return size() > FORWARD_CACHE_CAPACITY;
        }
    });

    private final Map<String, ForwardMessage> resIdForwardCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ForwardMessage> eldest) {
            return size() > FORWARD_CACHE_CAPACITY;
        }
    });

    private final Map<Integer, String> resIdByMsgIdCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
            return size() > FORWARD_CACHE_CAPACITY * 2;
        }
    });

    private final AtomicInteger syntheticForwardIdCounter = new AtomicInteger(-100000);

    private final Map<Integer, CachedMessageRecord> messageCache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, CachedMessageRecord> eldest) {
            return size() > MESSAGE_CACHE_CAPACITY;
        }
    });

    private final Map<String, RecentContactRecord> recentContacts = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RecentContactRecord> eldest) {
            return size() > RECENT_CONTACT_CAPACITY;
        }
    });

    private final List<JsonObject> ignoredGroupRequests = Collections.synchronizedList(new ArrayList<>());

    public record CachedMessageRecord(
            int messageId,
            String messageType,
            int time,
            long senderId,
            String senderNickname,
            String senderCard,
            String senderRole,
            String rawMessage,
            long groupId
    ) {
    }

    public record RecentContactRecord(
            boolean group,
            long peerId,
            String peerName,
            String remark,
            int msgTime,
            int msgId,
            String sendNickName,
            String sendMemberName,
            String lastMessage
    ) {
    }

    public ApiMap(Bot bot) {
        this.bot = bot;
    }

    public void cacheForwardMessage(int messageId, ForwardMessage forwardMessage) {
        cacheForwardMessage(messageId, forwardMessage, null);
    }

    public void cacheForwardMessage(int messageId, ForwardMessage forwardMessage, @Nullable String resId) {
        if (forwardMessage != null) {
            if (messageId != 0) {
                forwardCache.put(messageId, forwardMessage);
            }
            if (resId != null && !resId.isBlank()) {
                resIdForwardCache.put(resId, forwardMessage);
                if (messageId != 0) {
                    resIdByMsgIdCache.put(messageId, resId);
                }
            }
        }
    }

    public void cacheForwardFromChain(int messageId, @Nullable MessageChain chain) {
        if (chain == null) {
            return;
        }
        ForwardMessage forward = chain.get(ForwardMessage.Key);
        String resId = extractForwardResIdFromChain(chain);
        if (forward != null) {
            cacheForwardMessage(messageId, forward, resId);
        } else if (resId != null && !resId.isBlank() && messageId != 0) {
            resIdByMsgIdCache.put(messageId, resId);
        }
    }

    @Nullable
    private static String extractForwardResIdFromChain(MessageChain chain) {
        MessageOrigin origin = chain.get(MessageOrigin.Key);
        if (origin != null && origin.getKind() == MessageOriginKind.FORWARD
                && origin.getResourceId() != null && !origin.getResourceId().isBlank()) {
            return origin.getResourceId();
        }
        for (SingleMessage sm : chain) {
            if (sm instanceof ServiceMessage svc && svc.getServiceId() == 35) {
                String extracted = OnebotMsgUtils.extractForwardResId(svc.getContent());
                if (extracted != null) {
                    return extracted;
                }
            }
        }
        return null;
    }

    private int registerSyntheticForwardMessage(ForwardMessage forwardMessage, @Nullable MessageChain sourceChain) {
        int syntheticId;
        do {
            syntheticId = syntheticForwardIdCounter.getAndDecrement();
            if (syntheticId == 0) {
                syntheticId = syntheticForwardIdCounter.getAndDecrement();
            }
        } while (forwardCache.containsKey(syntheticId) || messageCache.containsKey(syntheticId));
        String resId = sourceChain != null ? extractForwardResIdFromChain(sourceChain) : null;
        cacheForwardMessage(syntheticId, forwardMessage, resId);
        return syntheticId;
    }

    public ForwardMessage getForwardMessage(int messageId) {
        return forwardCache.get(messageId);
    }

    @Nullable
    public ForwardMessage resolveForwardMessage(@Nullable String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String key = rawId.trim();
        ForwardMessage byResId = resIdForwardCache.get(key);
        if (byResId != null) {
            return byResId;
        }

        try {
            int msgId = Integer.parseInt(key);
            ForwardMessage cached = forwardCache.get(msgId);
            if (cached != null) {
                return cached;
            }
            String mappedResId = resIdByMsgIdCache.get(msgId);
            if (mappedResId != null) {
                ForwardMessage downloaded = downloadForwardAsMessage(mappedResId);
                if (downloaded != null) {
                    cacheForwardMessage(msgId, downloaded, mappedResId);
                    return downloaded;
                }
            }
            DBUtils.MessageNode dbNode = queryDbNodeSafely(msgId);
            MessageChain dbChain = dbNode != null ? DBUtils.deserializeChain(dbNode) : null;
            ForwardMessage fromDb = extractAndCacheForwardFromChain(msgId, dbChain);
            if (fromDb != null) {
                return fromDb;
            }
            MessageSource source = cachedSourceQueue.get(msgId);
            if (source != null && source.isOriginalMessageInitialized()) {
                ForwardMessage fromSource = extractAndCacheForwardFromChain(msgId, source.getOriginalMessage());
                if (fromSource != null) {
                    return fromSource;
                }
            }
            return null;
        } catch (NumberFormatException ignored) {
            return downloadForwardAsMessage(key);
        }
    }

    @Nullable
    private ForwardMessage extractAndCacheForwardFromChain(int msgId, @Nullable MessageChain chain) {
        if (chain == null) {
            return null;
        }
        ForwardMessage fm = chain.get(ForwardMessage.Key);
        String resId = extractForwardResIdFromChain(chain);
        if (fm != null) {
            cacheForwardMessage(msgId, fm, resId);
            return fm;
        }
        if (resId != null) {
            ForwardMessage downloaded = downloadForwardAsMessage(resId);
            if (downloaded != null) {
                cacheForwardMessage(msgId, downloaded, resId);
                return downloaded;
            }
        }
        return null;
    }

    @Nullable
    private ForwardMessage downloadForwardAsMessage(String resId) {
        if (bot == null || resId == null || resId.isBlank()) {
            return null;
        }
        ForwardMessage cached = resIdForwardCache.get(resId);
        if (cached != null) {
            return cached;
        }
        try {
            List<ForwardMessage.Node> nodes = Mirai.getInstance().downloadForwardMessage(bot, resId);
            if (nodes != null) {
                ForwardMessage fm = new RawForwardMessage(nodes).render(ForwardMessage.DisplayStrategy.Default);
                resIdForwardCache.put(resId, fm);
                return fm;
            }
        } catch (Exception e) {
            PluginRuntime.getLogger().warning("通过 resId 下载合并转发失败: " + e.getMessage());
        }
        return null;
    }

    public void cacheMessage(
            int messageId,
            String messageType,
            int time,
            long senderId,
            String senderNickname,
            String senderCard,
            String senderRole,
            String rawMessage,
            long groupId
    ) {
        messageCache.put(messageId, new CachedMessageRecord(
                messageId,
                messageType,
                time,
                senderId,
                senderNickname != null ? senderNickname : "",
                senderCard != null ? senderCard : "",
                senderRole != null ? senderRole : "",
                rawMessage != null ? rawMessage : "",
                groupId
        ));
    }

    public void recordRecentContact(
            boolean isGroup,
            long peerId,
            String peerName,
            String remark,
            int msgTime,
            int msgId,
            String sendNickName,
            String sendMemberName,
            String lastMessage
    ) {
        String key = (isGroup ? "g:" : "p:") + peerId;
        recentContacts.put(key, new RecentContactRecord(
                isGroup,
                peerId,
                peerName != null ? peerName : "",
                remark != null ? remark : "",
                msgTime,
                msgId,
                sendNickName != null ? sendNickName : "",
                sendMemberName != null ? sendMemberName : "",
                lastMessage != null ? lastMessage : ""
        ));
    }

    public ActionData<?> callMiraiApi(String action, JsonObject params) {
        if (action == null || action.isBlank()) {
            return DetailedFailure.notFound("");
        }
        JsonObject safeParams = params != null ? params : new JsonObject();

        try {
            return switch (action) {
                // ===== 消息类 =====
                case "send_msg" -> sendMessage(safeParams);
                case "send_private_msg" -> sendPrivateMessage(safeParams);
                case "send_group_msg" -> sendGroupMessage(safeParams);
                case "send_group_forward_msg" -> sendGroupForwardMessage(safeParams);
                case "send_private_forward_msg" -> sendPrivateForwardMessage(safeParams);
                case "send_forward_msg" -> sendForwardMessageDispatch(safeParams);
                case "forward_friend_single_msg" -> forwardFriendSingleMsg(safeParams);
                case "forward_group_single_msg" -> forwardGroupSingleMsg(safeParams);
                case "delete_msg" -> deleteMessage(safeParams);
                case "get_msg" -> getMessage(safeParams);
                case "get_forward_msg" -> getForwardMessageAction(safeParams);
                case "send_like" -> DetailedFailure.unsupported("mirai 2.16 未提供公开的好友点赞 API");
                case "set_msg_emoji_like", "unset_msg_emoji_like", "set_group_reaction" ->
                        DetailedFailure.unsupported("mirai 2.16 未提供公开的消息表情回应 (reaction) API");
                case "group_poke" -> groupPoke(safeParams);
                case "friend_poke" -> friendPoke(safeParams);
                case "send_poke" -> sendPoke(safeParams);

                // ===== 群管理与请求类 =====
                case "set_group_kick" -> setGroupKick(safeParams);
                case "set_group_ban" -> setGroupBan(safeParams);
                case "set_group_anonymous_ban" -> setGroupAnonymousBan(safeParams);
                case "set_group_whole_ban" -> setWholeGroupBan(safeParams);
                case "set_group_admin" -> setGroupAdmin(safeParams);
                case "set_group_anonymous" -> setGroupAnonymous(safeParams);
                case "set_group_card" -> setGroupCard(safeParams);
                case "set_group_name" -> setGroupName(safeParams);
                case "set_group_leave" -> setGroupLeave(safeParams);
                case "set_group_special_title" -> setGroupSpecialTitle(safeParams);
                case "set_group_portrait" -> DetailedFailure.unsupported("mirai 2.16 未提供公开的修改群头像 API");
                case "get_group_honor_info" -> getGroupHonorInfo(safeParams);
                case "get_group_at_all_remain" -> getGroupAtAllRemain(safeParams);
                case "get_group_system_msg" -> getGroupSystemMsg(safeParams);
                case "get_group_ignore_add_request" -> getGroupIgnoreAddRequest(safeParams);
                case "get_doubt_friends_add_request", "set_doubt_friends_add_request" ->
                        DetailedFailure.unsupported("mirai 2.16 不存在被过滤好友请求 (doubt_friends) 概念");
                case "send_group_sign" -> DetailedFailure.unsupported("mirai 2.16 未提供公开的群打卡/签到 API");
                case "set_friend_add_request" -> setFriendAddRequest(safeParams);
                case "set_group_add_request" -> setGroupAddRequest(safeParams);

                // ===== 群文件与下载类 =====
                case "upload_group_file" -> uploadGroupFile(safeParams);
                case "delete_group_file" -> deleteGroupFile(safeParams);
                case "create_group_file_folder" -> createGroupFileFolder(safeParams);
                case "delete_group_folder" -> deleteGroupFolder(safeParams);
                case "get_group_file_system_info" -> getGroupFileSystemInfo(safeParams);
                case "get_group_root_files" -> getGroupRootFiles(safeParams);
                case "get_group_files_by_folder" -> getGroupFilesByFolder(safeParams);
                case "get_group_file_url" -> getGroupFileUrl(safeParams);
                case "rename_group_file" -> renameGroupFile(safeParams);
                case "rename_group_file_folder" -> renameGroupFileFolder(safeParams);
                case "move_group_file" -> moveGroupFile(safeParams);
                case "trans_group_file" -> transGroupFile(safeParams);
                case "set_group_file_forever" -> DetailedFailure.unsupported("mirai 2.16 的 AbsoluteFile 未提供群文件转永久/修改过期时间 API");
                case "upload_private_file" -> DetailedFailure.unsupported("mirai 2.16 的 Friend 未实现 FileSupported，不支持上传私聊文件");
                case "get_private_file_url" -> DetailedFailure.unsupported("mirai 2.16 的 Friend 未实现 FileSupported，不支持获取私聊文件链接");
                case "download_file" -> downloadFile(safeParams);

                // ===== 群公告与精华类 =====
                case "get_group_notice", "_get_group_notice" -> getGroupNotice(safeParams);
                case "send_group_notice", "_send_group_notice", "_set_group_notice" -> sendGroupNotice(safeParams);
                case "del_group_notice", "delete_group_notice", "_delete_group_notice" -> deleteGroupNotice(safeParams);
                case "set_essence_msg" -> setEssenceMsg(safeParams);
                case "delete_essence_msg" -> deleteEssenceMsg(safeParams);
                case "get_essence_msg_list" -> getEssenceMsgList(safeParams);

                // ===== 查询 / 好友 / 系统类 =====
                case "get_login_info" -> getLoginInfo(safeParams);
                case "get_stranger_info" -> getStrangerInfo(safeParams);
                case "get_friend_list" -> getFriendList(safeParams);
                case "get_group_info" -> getGroupInfo(safeParams);
                case "get_group_list" -> getGroupList(safeParams);
                case "get_group_member_info" -> getGroupMemberInfo(safeParams);
                case "get_group_member_list" -> getGroupMemberList(safeParams);
                case "get_online_clients" -> getOnlineClients(safeParams);
                case "get_status" -> getStatus(safeParams);
                case "get_version_info" -> getVersionInfo(safeParams);
                case "get_cookies" -> DetailedFailure.unsupported("mirai 2.16 未提供 Web Cookie 获取 API");
                case "get_csrf_token" -> DetailedFailure.unsupported("mirai 2.16 未提供 CSRF Token 获取 API");
                case "get_credentials", "get_credentails" ->
                        DetailedFailure.unsupported("mirai 2.16 未提供 Web 凭证 (cookies/csrf_token) 获取 API");
                case "get_group_msg_history" -> getGroupMsgHistory(safeParams);
                case "get_friend_msg_history" -> getFriendMsgHistory(safeParams);
                case "get_recent_contact" -> getRecentContact(safeParams);
                case "mark_msg_as_read", "_mark_all_as_read", "mark_private_msg_as_read", "mark_group_msg_as_read" ->
                        markMsgAsRead(action);
                case "set_input_status" -> DetailedFailure.unsupported("mirai 2.16 未提供输入状态设置 API");
                case "delete_friend" -> deleteFriend(safeParams);
                case "set_friend_remark" -> setFriendRemark(safeParams);
                case "set_qq_profile" -> DetailedFailure.unsupported("mirai 2.16 未提供修改个人资料 API");
                case "set_qq_avatar" -> DetailedFailure.unsupported("mirai 2.16 未提供修改机器人头像 API");
                case "set_self_longnick" -> DetailedFailure.unsupported("mirai 2.16 未提供修改个性签名 API");
                case "set_online_status" -> DetailedFailure.unsupported("mirai 2.16 未提供修改在线状态 API");
                case "set_restart", "set_restart_plugin" -> setRestartPlugin(safeParams);
                case "clean_cache", "clean_data_dir", "clean_plugin_log" -> cleanCache();
                case "can_send_image" -> canSendImage(safeParams);
                case "can_send_record" -> canSendRecord(safeParams);
                case "get_image" -> getImage(safeParams);
                case "get_record" -> getRecord(safeParams);
                case "_check_url_safely", "check_url_safely" -> checkUrlSafely(safeParams);
                case "ocr_image" -> DetailedFailure.unsupported("未集成 OCR 识别依赖，不支持 ocr_image");
                case "get_word_slices", ".get_word_slices" -> getWordSlice(safeParams);
                case "send_discuss_msg", "set_discuss_leave" ->
                        DetailedFailure.unsupported("mirai 2.16 不支持讨论组功能");

                // ===== 频道与其他不支持扩展 =====
                case "send_guild_channel_msg",
                     "get_guild_member_profile",
                     "get_guild_service_profile",
                     "get_guild_list",
                     "get_guild_msg",
                     "get_guild_meta_by_guest",
                     "get_guild_channel_list",
                     "get_guild_member_list",
                     "get_topic_channel_feeds" ->
                        DetailedFailure.unsupported("mirai 2.16 不支持 QQ 频道功能 (" + action + ")");
                case "_get_model_show", "_set_model_show" ->
                        DetailedFailure.unsupported("mirai 2.16 未提供在线机型显示设置 API");
                case "get_unidirectional_friend_list", "delete_unidirectional_friend" ->
                        DetailedFailure.unsupported("mirai 2.16 未提供单向好友管理 API");

                default -> {
                    PluginRuntime.getLogger().warning("未知 OneBot API: " + action);
                    yield DetailedFailure.notFound(action);
                }
            };
        } catch (OnebotMsgUtils.MessageSegmentException e) {
            PluginRuntime.getLogger().warning("消息段构造失败 (" + action + "): " + e.getMessage());
            return DetailedFailure.fail(e.getMessage());
        } catch (IllegalArgumentException e) {
            PluginRuntime.getLogger().info("参数校验失败 (" + action + "): " + e.getMessage());
            return DetailedFailure.fail("参数无效: " + e.getMessage());
        } catch (NoSuchElementException e) {
            PluginRuntime.getLogger().info("目标不存在 (" + action + "): " + e.getMessage());
            return DetailedFailure.fail("目标不存在: " + e.getMessage());
        } catch (PermissionDeniedException e) {
            PluginRuntime.getLogger().info("机器人无操作权限, 调用的 API: /" + action);
            return DetailedFailure.fail("机器人无操作权限: " + action);
        } catch (UnsupportedOperationException e) {
            PluginRuntime.getLogger().warning("当前协议不支持该操作 (" + action + "): " + e.getMessage());
            return DetailedFailure.unsupported("当前协议或 mirai 2.16 不支持操作 " + action + ": " + e.getMessage());
        } catch (Exception e) {
            PluginRuntime.getLogger().error("执行 OneBot API 异常: /" + action, e);
            return DetailedFailure.fail("执行 " + action + " 失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    // ==================== 参数解析辅助方法 ====================

    private static boolean hasValue(JsonObject params, String key) {
        return params != null && params.has(key) && !params.get(key).isJsonNull();
    }

    private static String getString(JsonObject params, String key, String defaultVal) {
        if (!hasValue(params, key)) {
            return defaultVal;
        }
        JsonElement el = params.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    private static String requireString(JsonObject params, String key) {
        String val = getString(params, key, null);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException("缺少必要参数: " + key);
        }
        return val;
    }

    private static long getLong(JsonObject params, String key, long defaultVal) {
        if (!hasValue(params, key)) {
            return defaultVal;
        }
        String raw = getString(params, key, "").trim();
        if (raw.isEmpty()) {
            return defaultVal;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + key + " 不是合法整数: " + raw, e);
        }
    }

    private static long requireLong(JsonObject params, String key) {
        if (!hasValue(params, key)) {
            throw new IllegalArgumentException("缺少必要参数: " + key);
        }
        return getLong(params, key, 0L);
    }

    private static int getInt(JsonObject params, String key, int defaultVal) {
        return (int) getLong(params, key, defaultVal);
    }

    private static int requireInt(JsonObject params, String key) {
        return (int) requireLong(params, key);
    }

    private static boolean getBoolean(JsonObject params, String key, boolean defaultVal) {
        if (!hasValue(params, key)) {
            return defaultVal;
        }
        JsonElement el = params.get(key);
        if (el.isJsonPrimitive()) {
            var p = el.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                return p.getAsInt() != 0;
            }
            String s = p.getAsString().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) {
                return true;
            }
            if ("false".equals(s) || "0".equals(s) || "no".equals(s)) {
                return false;
            }
        }
        return defaultVal;
    }

    private static boolean hasPositiveLong(JsonObject params, String key) {
        return hasValue(params, key) && getLong(params, key, -1L) > 0;
    }

    private Contact resolvePrivateContact(long userId) {
        Friend friend = bot.getFriend(userId);
        if (friend != null) {
            return friend;
        }
        Long fromGroupId = cachedTempContact.get(userId);
        if (fromGroupId != null) {
            Group group = bot.getGroup(fromGroupId);
            if (group != null) {
                NormalMember member = group.get(userId);
                if (member != null) {
                    return member;
                }
            }
        }
        throw new NoSuchElementException("未找到好友或临时会话联系人: " + userId);
    }

    // ==================== 消息发送与查询 ====================

    public ActionData<?> sendMessage(JsonObject params) {
        String messageType = getString(params, "message_type", "").trim().toLowerCase(Locale.ROOT);
        if ("private".equals(messageType)) {
            return sendPrivateMessage(params);
        }
        if ("group".equals(messageType)) {
            return sendGroupMessage(params);
        }
        if ("discuss".equals(messageType)) {
            return DetailedFailure.unsupported("mirai 2.16 不支持讨论组功能");
        }
        if (hasPositiveLong(params, "group_id")) {
            return sendGroupMessage(params);
        }
        if (hasPositiveLong(params, "user_id")) {
            return sendPrivateMessage(params);
        }
        return DetailedFailure.fail("send_msg 缺少有效的 message_type / group_id / user_id");
    }

    public ActionData<?> sendGroupMessage(JsonObject params) {
        long targetGroupId = requireLong(params, "group_id");
        boolean autoEscape = getBoolean(params, "auto_escape", false);
        JsonElement messages = params.get("message");
        if (messages == null || messages.isJsonNull()) {
            return DetailedFailure.fail("缺少必要参数: message");
        }

        Group group = bot.getGroupOrFail(targetGroupId);
        JsonArray forwardNodes = OnebotMsgUtils.extractForwardNodesFromMessage(messages, autoEscape);
        if (forwardNodes != null) {
            JsonObject forwardParams = params.deepCopy();
            forwardParams.add("messages", forwardNodes);
            return sendForwardMessage(group, forwardParams, "群聊的聊天记录");
        }

        boolean sideEffectOnly = OnebotMsgUtils.hasOnlySideEffectSegments(messages, autoEscape);
        MessageChain messageChain = OnebotMsgUtils.messageToChains(bot, group, this, messages, autoEscape, 0);
        if (messageChain != null && !messageChain.isEmpty()) {
            MessageReceipt<Group> receipt = group.sendMessage(messageChain);
            int msgId = cachedSourceQueue.add(receipt.getSource());
            cacheForwardFromChain(msgId, messageChain);
            return new MessageSuccess(msgId);
        }
        if (sideEffectOnly) {
            return new MessageSuccess(0);
        }
        return DetailedFailure.fail("消息内容解析后为空，未发送");
    }

    public ActionData<?> sendPrivateMessage(JsonObject params) {
        long targetQQId = requireLong(params, "user_id");
        boolean autoEscape = getBoolean(params, "auto_escape", false);
        JsonElement messages = params.get("message");
        if (messages == null || messages.isJsonNull()) {
            return DetailedFailure.fail("缺少必要参数: message");
        }

        Contact contact = resolvePrivateContact(targetQQId);
        JsonArray forwardNodes = OnebotMsgUtils.extractForwardNodesFromMessage(messages, autoEscape);
        if (forwardNodes != null) {
            JsonObject forwardParams = params.deepCopy();
            forwardParams.add("messages", forwardNodes);
            return sendForwardMessage(contact, forwardParams, "聊天记录");
        }

        boolean sideEffectOnly = OnebotMsgUtils.hasOnlySideEffectSegments(messages, autoEscape);
        MessageChain messageChain = OnebotMsgUtils.messageToChains(bot, contact, this, messages, autoEscape, 0);
        if (messageChain != null && !messageChain.isEmpty()) {
            MessageReceipt<? extends Contact> receipt = contact.sendMessage(messageChain);
            int msgId = cachedSourceQueue.add(receipt.getSource());
            cacheForwardFromChain(msgId, messageChain);
            return new MessageSuccess(msgId);
        }
        if (sideEffectOnly) {
            return new MessageSuccess(0);
        }
        return DetailedFailure.fail("消息内容解析后为空，未发送");
    }

    public ActionData<?> sendGroupForwardMessage(JsonObject params) {
        long targetGroupId = requireLong(params, "group_id");
        Group group = bot.getGroupOrFail(targetGroupId);
        return sendForwardMessage(group, params, "群聊的聊天记录");
    }

    public ActionData<?> sendPrivateForwardMessage(JsonObject params) {
        long targetUserId = requireLong(params, "user_id");
        Contact contact = resolvePrivateContact(targetUserId);
        return sendForwardMessage(contact, params, "聊天记录");
    }

    public ActionData<?> sendForwardMessageDispatch(JsonObject params) {
        String messageType = getString(params, "message_type", "").trim().toLowerCase(Locale.ROOT);
        boolean isGroup = "group".equals(messageType) || (messageType.isEmpty() && hasPositiveLong(params, "group_id"));
        boolean isPrivate = "private".equals(messageType) || (!isGroup && hasPositiveLong(params, "user_id"));

        if (!isGroup && !isPrivate) {
            return DetailedFailure.fail("send_forward_msg 缺少有效的 group_id 或 user_id");
        }
        if (hasValue(params, "messages")) {
            return isGroup ? sendGroupForwardMessage(params) : sendPrivateForwardMessage(params);
        }
        if (hasValue(params, "message")) {
            boolean autoEscape = getBoolean(params, "auto_escape", false);
            JsonArray forwardNodes = OnebotMsgUtils.extractForwardNodesFromMessage(params.get("message"), autoEscape);
            if (forwardNodes != null) {
                JsonObject forwardParams = params.deepCopy();
                forwardParams.add("messages", forwardNodes);
                return isGroup ? sendGroupForwardMessage(forwardParams) : sendPrivateForwardMessage(forwardParams);
            }
            return isGroup ? sendGroupMessage(params) : sendPrivateMessage(params);
        }
        return DetailedFailure.fail("send_forward_msg 缺少 messages 或 message 参数");
    }

    public ActionData<?> sendForwardMessage(Contact contact, JsonObject params) {
        String defaultTitle = (contact instanceof Group) ? "群聊的聊天记录" : "聊天记录";
        return sendForwardMessage(contact, params, defaultTitle);
    }

    public ActionData<?> sendForwardMessage(Contact contact, JsonObject params, String title) {
        boolean autoEscape = getBoolean(params, "auto_escape", false);
        JsonElement rawMessages = params.has("messages") && !params.get("messages").isJsonNull()
                ? params.get("messages")
                : (params.has("message") && !params.get("message").isJsonNull() ? params.get("message") : null);
        JsonArray nodesArr = OnebotMsgUtils.normalizeForwardMessagesParam(rawMessages);
        if (nodesArr == null) {
            return DetailedFailure.fail("合并转发参数 messages 必须是 JSON 数组");
        }

        ForwardMessage forward;
        try {
            forward = OnebotMsgUtils.buildForwardMessage(bot, contact, this, nodesArr, params, title, autoEscape, 0);
        } catch (OnebotMsgUtils.MessageSegmentException | IllegalArgumentException e) {
            return DetailedFailure.fail(e.getMessage());
        }

        MessageChain chain = new MessageChainBuilder().append(forward).build();
        MessageReceipt<? extends Contact> receipt = contact.sendMessage(chain);
        int messageId = cachedSourceQueue.add(receipt.getSource());
        cacheForwardMessage(messageId, forward);
        return new MessageSuccess(messageId);
    }

    public ActionData<?> forwardFriendSingleMsg(JsonObject params) {
        long userId = requireLong(params, "user_id");
        int messageId = requireInt(params, "message_id");
        Contact contact = resolvePrivateContact(userId);
        return forwardSingleMsg(contact, messageId);
    }

    public ActionData<?> forwardGroupSingleMsg(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        int messageId = requireInt(params, "message_id");
        Group group = bot.getGroupOrFail(groupId);
        return forwardSingleMsg(group, messageId);
    }

    private ActionData<?> forwardSingleMsg(Contact target, int messageId) {
        MessageChain sourceChain = resolveChainForMessageId(messageId, target);
        if (sourceChain == null || sourceChain.isEmpty()) {
            return DetailedFailure.fail("未找到可转发的源消息 (message_id=" + messageId + ")");
        }
        MessageChain cleanChain = OnebotMsgUtils.stripMetadata(sourceChain);
        if (cleanChain.isEmpty()) {
            return DetailedFailure.fail("源消息内容为空，无法转发 (message_id=" + messageId + ")");
        }
        MessageReceipt<? extends Contact> receipt = target.sendMessage(cleanChain);
        int sentMsgId = cachedSourceQueue.add(receipt.getSource());
        cacheForwardFromChain(sentMsgId, cleanChain);
        return new MessageSuccess(sentMsgId);
    }

    @Nullable
    private MessageChain resolveChainForMessageId(int messageId, @Nullable Contact contact) {
        ForwardMessage cachedForward = forwardCache.get(messageId);

        DBUtils.MessageNode dbNode = queryDbNodeSafely(messageId);
        if (dbNode != null) {
            MessageChain dbChain = DBUtils.deserializeChain(dbNode);
            if (dbChain != null && !dbChain.isEmpty()) {
                if (dbChain.get(ForwardMessage.Key) != null) {
                    return dbChain;
                }
                if (cachedForward != null) {
                    return new MessageChainBuilder().append(cachedForward).build();
                }
                ForwardMessage resolvedFm = extractAndCacheForwardFromChain(messageId, dbChain);
                if (resolvedFm != null) {
                    return new MessageChainBuilder().append(resolvedFm).build();
                }
                return dbChain;
            }
        }

        MessageSource source = cachedSourceQueue.get(messageId);
        if (source != null && source.isOriginalMessageInitialized()) {
            MessageChain origChain = source.getOriginalMessage();
            if (!origChain.isEmpty()) {
                if (origChain.get(ForwardMessage.Key) != null) {
                    return origChain;
                }
                if (cachedForward != null) {
                    return new MessageChainBuilder().append(cachedForward).build();
                }
                ForwardMessage resolvedFm = extractAndCacheForwardFromChain(messageId, origChain);
                if (resolvedFm != null) {
                    return new MessageChainBuilder().append(resolvedFm).build();
                }
                return origChain;
            }
        }

        ForwardMessage byId = cachedForward != null ? cachedForward : resolveForwardMessage(String.valueOf(messageId));
        if (byId != null) {
            return new MessageChainBuilder().append(byId).build();
        }

        CachedMessageRecord rec = messageCache.get(messageId);
        if (rec != null && rec.rawMessage() != null && !rec.rawMessage().isBlank() && contact != null) {
            try {
                return OnebotMsgUtils.messageToChains(
                        bot,
                        contact,
                        this,
                        OnebotMsgUtils.cqStringToSegmentArray(rec.rawMessage()),
                        false,
                        0
                );
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    @Nullable
    public ForwardMessage.Node resolveForwardNodeById(
            OnebotMsgUtils.ParsedForwardNode parsed,
            Contact contact,
            int defaultTime
    ) {
        if (parsed == null || parsed.referencedMessageId() == null || parsed.referencedMessageId().isBlank()) {
            return null;
        }
        String refId = parsed.referencedMessageId().trim();
        Integer parsedMsgId = null;
        try {
            parsedMsgId = Integer.parseInt(refId);
        } catch (NumberFormatException ignored) {
        }

        MessageChain chain = parsedMsgId != null ? resolveChainForMessageId(parsedMsgId, contact) : null;
        if (chain == null || chain.isEmpty()) {
            ForwardMessage resolvedForward = resolveForwardMessage(refId);
            if (resolvedForward != null) {
                chain = new MessageChainBuilder().append(resolvedForward).build();
            }
        }
        if (chain == null || chain.isEmpty()) {
            return null;
        }

        MessageChain cleanChain = OnebotMsgUtils.stripMetadata(chain);
        if (cleanChain.isEmpty()) {
            return null;
        }

        long defaultUid = bot != null ? bot.getId() : 0L;
        CachedMessageRecord cachedRec = parsedMsgId != null ? messageCache.get(parsedMsgId) : null;
        DBUtils.MessageNode dbNode = parsedMsgId != null ? queryDbNodeSafely(parsedMsgId) : null;
        MessageSource queueSource = parsedMsgId != null ? cachedSourceQueue.get(parsedMsgId) : null;
        MessageSource chainSource = chain.get(MessageSource.Key);

        long uid = parsed.userId() > 0 ? parsed.userId() : -1L;
        if (uid <= 0 && cachedRec != null && cachedRec.senderId() > 0) {
            uid = cachedRec.senderId();
        }
        if (uid <= 0 && dbNode != null && dbNode.senderId > 0) {
            uid = dbNode.senderId;
        }
        if (uid <= 0 && queueSource != null && queueSource.getFromId() > 0) {
            uid = queueSource.getFromId();
        }
        if (uid <= 0 && chainSource != null && chainSource.getFromId() > 0) {
            uid = chainSource.getFromId();
        }
        if (uid <= 0) {
            uid = defaultUid;
        }

        int time = parsed.time() > 0 ? parsed.time() : -1;
        if (time <= 0 && cachedRec != null && cachedRec.time() > 0) {
            time = cachedRec.time();
        }
        if (time <= 0 && dbNode != null && dbNode.time > 0) {
            time = dbNode.time;
        }
        if (time <= 0 && queueSource != null && queueSource.getTime() > 0) {
            time = queueSource.getTime();
        }
        if (time <= 0 && chainSource != null && chainSource.getTime() > 0) {
            time = chainSource.getTime();
        }
        if (time <= 0) {
            time = defaultTime;
        }

        String nick = parsed.nickname();
        if (nick == null || nick.isBlank()) {
            if (cachedRec != null && cachedRec.senderNickname() != null && !cachedRec.senderNickname().isBlank()) {
                nick = cachedRec.senderNickname();
            } else if (dbNode != null && dbNode.senderNickname != null && !dbNode.senderNickname.isBlank()) {
                nick = dbNode.senderNickname;
            } else {
                nick = resolveNicknameForForward(uid, contact);
            }
        }
        if (nick == null || nick.isBlank()) {
            nick = uid > 0 ? String.valueOf(uid) : "QQ用户";
        }

        return new ForwardMessage.Node(uid, time, nick, cleanChain);
    }

    public String resolveNicknameForForward(long userId, @Nullable Contact contact) {
        if (userId <= 0) {
            return "";
        }
        boolean isGroup = contact instanceof Group;
        long groupId = isGroup ? contact.getId() : 0L;
        return resolveNicknameBestEffort(userId, groupId, isGroup);
    }

    @Nullable
    private DBUtils.MessageNode queryDbNodeSafely(int messageId) {
        try {
            return DBUtils.queryMessageNode(messageId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public ActionData<?> getForwardMessageAction(JsonObject params) {
        String rawId = getString(params, "message_id", getString(params, "id", "")).trim();
        if (rawId.isEmpty()) {
            return DetailedFailure.fail("get_forward_msg 缺少 message_id / id 参数");
        }

        ForwardMessage resolved = resolveForwardMessage(rawId);
        if (resolved == null) {
            return DetailedFailure.fail("转发消息不存在或已过期");
        }

        JsonArray msgArray = new JsonArray();
        for (ForwardMessage.Node node : resolved.getNodeList()) {
            JsonObject nodeObj = new JsonObject();
            nodeObj.addProperty("type", "node");

            JsonObject dataObj = new JsonObject();
            String senderName = node.getSenderName() != null ? node.getSenderName() : "";
            dataObj.addProperty("user_id", node.getSenderId());
            dataObj.addProperty("nickname", senderName);
            dataObj.addProperty("time", node.getTime());
            dataObj.addProperty("uin", node.getSenderId());
            dataObj.addProperty("name", senderName);

            MessageChain nodeChain = node.getMessageChain();
            String cqContent = OnebotMsgUtils.toCQString(
                    nodeChain,
                    0,
                    null,
                    nested -> registerSyntheticForwardMessage(nested, nodeChain)
            );
            dataObj.addProperty("content", cqContent);
            dataObj.add("message", OnebotMsgUtils.cqStringToSegmentArray(cqContent));

            nodeObj.add("data", dataObj);
            msgArray.add(nodeObj);
        }

        JsonObject resp = new JsonObject();
        resp.add("messages", msgArray);
        return DetailedFailure.ok(resp);
    }

    public ActionData<?> deleteMessage(JsonObject params) {
        int messageId = requireInt(params, "message_id");
        MessageSource source = cachedSourceQueue.get(messageId);
        if (source == null) {
            source = DBUtils.loadMessageSourceFromDB(messageId);
        }
        if (source == null) {
            return DetailedFailure.fail("消息不存在或已过期，无法撤回 (message_id=" + messageId + ")");
        }
        MessageSource.recall(source);
        return new Success();
    }

    public ActionData<?> getMessage(JsonObject params) {
        int messageId = requireInt(params, "message_id");

        // 1. 优先查内存缓存
        CachedMessageRecord cached = messageCache.get(messageId);
        if (cached != null) {
            return DetailedFailure.ok(buildGetMsgJson(
                    cached.messageId(),
                    cached.messageType(),
                    cached.time(),
                    cached.senderId(),
                    cached.senderNickname(),
                    cached.senderCard(),
                    cached.senderRole(),
                    cached.rawMessage(),
                    cached.groupId()
            ));
        }

        // 2. 兜底查本地 NanoDb
        DBUtils.MessageNode dbNode = queryDbNodeSafely(messageId);
        if (dbNode != null) {
            MessageChain chain = DBUtils.deserializeChain(dbNode);
            if (chain != null) {
                cacheForwardFromChain(messageId, chain);
                String rawMessage = OnebotMsgUtils.toCQString(chain, messageId, this::cacheForwardMessage);
                MessageSource source = chain.get(MessageSource.Key);
                long senderId = dbNode.senderId != 0 ? dbNode.senderId : (source != null ? source.getFromId() : 0L);
                int time = dbNode.time != 0 ? dbNode.time : (source != null ? source.getTime() : 0);
                String msgType = dbNode.messageType;
                if ((msgType == null || msgType.isBlank()) && source != null) {
                    msgType = (source.getKind() == MessageSourceKind.GROUP) ? "group" : "private";
                }
                String nickname = dbNode.senderNickname;
                if (nickname == null || nickname.isBlank()) {
                    nickname = resolveNicknameBestEffort(senderId, dbNode.contactId, "group".equals(msgType));
                }
                return DetailedFailure.ok(buildGetMsgJson(
                        messageId,
                        msgType != null ? msgType : "private",
                        time,
                        senderId,
                        nickname,
                        "",
                        "",
                        rawMessage,
                        "group".equals(msgType) ? dbNode.contactId : 0L
                ));
            }
        }

        // 3. 兜底查 CacheSourceQueue
        MessageSource source = cachedSourceQueue.get(messageId);
        if (source != null) {
            String msgType = (source.getKind() == MessageSourceKind.GROUP) ? "group" : "private";
            String rawMessage = "";
            if (source.isOriginalMessageInitialized()) {
                MessageChain original = source.getOriginalMessage();
                cacheForwardFromChain(messageId, original);
                rawMessage = OnebotMsgUtils.toCQString(original, messageId, this::cacheForwardMessage);
            }
            long groupId = (source.getKind() == MessageSourceKind.GROUP) ? source.getTargetId() : 0L;
            String nickname = resolveNicknameBestEffort(source.getFromId(), groupId, "group".equals(msgType));
            return DetailedFailure.ok(buildGetMsgJson(
                    messageId,
                    msgType,
                    source.getTime(),
                    source.getFromId(),
                    nickname,
                    "",
                    "",
                    rawMessage,
                    groupId
            ));
        }

        return DetailedFailure.fail("未找到指定消息 (message_id=" + messageId + ")");
    }

    private JsonObject buildGetMsgJson(
            int messageId,
            String messageType,
            int time,
            long senderId,
            String nickname,
            String card,
            String role,
            String rawMessage,
            long groupId
    ) {
        JsonObject resp = new JsonObject();
        resp.addProperty("message_id", messageId);
        resp.addProperty("real_id", messageId);
        resp.addProperty("message_type", messageType);
        resp.addProperty("msg_type", messageType);
        resp.addProperty("time", time);
        resp.addProperty("message", rawMessage);
        resp.addProperty("raw_message", rawMessage);
        if (groupId > 0) {
            resp.addProperty("group_id", groupId);
        }
        JsonObject sender = new JsonObject();
        sender.addProperty("user_id", senderId);
        sender.addProperty("nickname", nickname != null ? nickname : "");
        if (card != null && !card.isEmpty()) {
            sender.addProperty("card", card);
        }
        if (role != null && !role.isEmpty()) {
            sender.addProperty("role", role);
        }
        resp.add("sender", sender);
        return resp;
    }

    private String resolveNicknameBestEffort(long userId, long groupId, boolean isGroup) {
        if (bot == null || userId <= 0) {
            return "";
        }
        if (userId == bot.getId()) {
            return bot.getNick();
        }
        if (isGroup && groupId > 0) {
            Group group = bot.getGroup(groupId);
            if (group != null) {
                NormalMember m = group.get(userId);
                if (m != null) {
                    return m.getNick();
                }
            }
        }
        Friend f = bot.getFriend(userId);
        if (f != null) {
            return f.getNick();
        }
        return "";
    }

    public ActionData<?> groupPoke(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        long userId = requireLong(params, "user_id");
        Group group = bot.getGroupOrFail(groupId);
        NormalMember member = group.getOrFail(userId);
        boolean ok = member.nudge().sendTo(group);
        return ok ? new Success() : DetailedFailure.fail("群戳一戳发送失败");
    }

    public ActionData<?> friendPoke(JsonObject params) {
        long userId = requireLong(params, "user_id");
        Friend friend = bot.getFriendOrFail(userId);
        boolean ok = friend.nudge().sendTo(friend);
        return ok ? new Success() : DetailedFailure.fail("好友戳一戳发送失败");
    }

    public ActionData<?> sendPoke(JsonObject params) {
        if (hasPositiveLong(params, "group_id")) {
            return groupPoke(params);
        }
        return friendPoke(params);
    }

    // ==================== 群管理与请求 ====================

    public ActionData<?> setGroupKick(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        long memberId = requireLong(params, "user_id");
        boolean rejectAddRequest = getBoolean(params, "reject_add_request", false);
        bot.getGroupOrFail(groupId).getOrFail(memberId).kick("", rejectAddRequest);
        return new Success();
    }

    public ActionData<?> setGroupBan(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        long memberId = requireLong(params, "user_id");
        int duration = getInt(params, "duration", 30 * 60);
        if (duration <= 0) {
            bot.getGroupOrFail(groupId).getOrFail(memberId).unmute();
        } else {
            bot.getGroupOrFail(groupId).getOrFail(memberId).mute(duration);
        }
        return new Success();
    }

    @MiraiExperimentalApi
    @LowLevelApi
    public ActionData<?> setGroupAnonymousBan(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String flag = "";
        if (params.has("anonymous") && params.get("anonymous").isJsonObject()) {
            flag = getString(params.getAsJsonObject("anonymous"), "flag", "");
        }
        if (flag.isBlank()) {
            flag = getString(params, "anonymous_flag", "");
        }
        if (flag.isBlank()) {
            flag = getString(params, "flag", "");
        }
        if (flag.isBlank() || !flag.contains("&")) {
            return DetailedFailure.fail("set_group_anonymous_ban 缺少有效的匿名 flag");
        }
        int duration = getInt(params, "duration", 30 * 60);
        String[] splits = flag.split("&", 2);
        Mirai.getInstance().muteAnonymousMember(bot, splits[0], splits[1], groupId, duration);
        return new Success();
    }

    public ActionData<?> setWholeGroupBan(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        boolean enable = getBoolean(params, "enable", true);
        bot.getGroupOrFail(groupId).getSettings().setMuteAll(enable);
        return new Success();
    }

    public ActionData<?> setGroupAdmin(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        long memberId = requireLong(params, "user_id");
        boolean enable = getBoolean(params, "enable", true);
        bot.getGroupOrFail(groupId).getOrFail(memberId).modifyAdmin(enable);
        return new Success();
    }

    public ActionData<?> setGroupAnonymous(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        boolean enable = getBoolean(params, "enable", true);
        bot.getGroupOrFail(groupId).getSettings().setAnonymousChatEnabled(enable);
        return new Success();
    }

    public ActionData<?> setGroupCard(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        long memberId = requireLong(params, "user_id");
        String card = getString(params, "card", "");
        bot.getGroupOrFail(groupId).getOrFail(memberId).setNameCard(card);
        return new Success();
    }

    public ActionData<?> setGroupName(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String name = requireString(params, "group_name");
        bot.getGroupOrFail(groupId).setName(name);
        return new Success();
    }

    public ActionData<?> setGroupLeave(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        boolean dismiss = getBoolean(params, "is_dismiss", false);
        if (dismiss) {
            return DetailedFailure.unsupported("mirai 2.16 不支持解散群聊 (is_dismiss=true)");
        }
        bot.getGroupOrFail(groupId).quit();
        return new Success();
    }

    public ActionData<?> setGroupSpecialTitle(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        long memberId = requireLong(params, "user_id");
        String specialTitle = getString(params, "special_title", "");
        bot.getGroupOrFail(groupId).getOrFail(memberId).setSpecialTitle(specialTitle);
        return new Success();
    }

    public ActionData<?> getGroupHonorInfo(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String type = getString(params, "type", "all").trim();
        Group group = bot.getGroupOrFail(groupId);
        MiraiGroupHonorInfoResp resp = new MiraiGroupHonorInfoResp(group, type);
        return DetailedFailure.ok(resp);
    }

    public ActionData<?> getGroupAtAllRemain(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        Group group = bot.getGroupOrFail(groupId);
        MemberPermission perm = group.getBotPermission();
        boolean canAtAll = (perm == MemberPermission.OWNER || perm == MemberPermission.ADMINISTRATOR);
        GroupAtAllRemainResp resp = new GroupAtAllRemainResp();
        resp.setCanAtAll(canAtAll);
        resp.setRemainAtAllCountForGroup(-1);
        resp.setRemainAtAllCountForUin(-1);
        return DetailedFailure.ok(resp, "mirai 2.16 未提供 @全体成员 剩余次数查询 API，剩余次数返回 -1");
    }

    public ActionData<?> getGroupSystemMsg(JsonObject params) {
        int count = getInt(params, "count", 50);
        if (count <= 0) {
            count = 50;
        }
        JsonArray invitedRequests = new JsonArray();
        JsonArray joinRequests = new JsonArray();

        List<BotEvent> events = cacheRequestQueue.snapshotValues();
        for (int i = events.size() - 1; i >= 0; i--) {
            BotEvent ev = events.get(i);
            if (ev instanceof BotInvitedJoinGroupRequestEvent inviteEv && invitedRequests.size() < count) {
                JsonObject item = new JsonObject();
                item.addProperty("request_id", inviteEv.getEventId());
                item.addProperty("invitor_uin", inviteEv.getInvitorId());
                item.addProperty("invitor_nick", inviteEv.getInvitorNick());
                item.addProperty("group_id", inviteEv.getGroupId());
                item.addProperty("group_name", inviteEv.getGroupName());
                item.addProperty("checked", false);
                item.addProperty("actor", 0);
                item.addProperty("requester_nick", inviteEv.getInvitorNick());
                item.addProperty("message", "");
                invitedRequests.add(item);
            } else if (ev instanceof MemberJoinRequestEvent joinEv && joinRequests.size() < count) {
                JsonObject item = new JsonObject();
                item.addProperty("request_id", joinEv.getEventId());
                item.addProperty("invitor_uin", joinEv.getInvitorId() != null ? joinEv.getInvitorId() : 0L);
                item.addProperty("invitor_nick", "");
                item.addProperty("group_id", joinEv.getGroupId());
                item.addProperty("group_name", joinEv.getGroupName());
                item.addProperty("checked", false);
                item.addProperty("actor", 0);
                item.addProperty("requester_nick", joinEv.getFromNick());
                item.addProperty("requester_uin", joinEv.getFromId());
                item.addProperty("message", joinEv.getMessage());
                joinRequests.add(item);
            }
        }

        JsonObject resp = new JsonObject();
        resp.add("invited_requests", invitedRequests);
        resp.add("InvitedRequest", invitedRequests.deepCopy());
        resp.add("join_requests", joinRequests);
        return DetailedFailure.ok(resp);
    }

    public ActionData<?> getGroupIgnoreAddRequest(JsonObject params) {
        long filterGroupId = getLong(params, "group_id", -1L);
        JsonArray list = new JsonArray();
        synchronized (ignoredGroupRequests) {
            for (JsonObject item : ignoredGroupRequests) {
                if (filterGroupId > 0 && item.has("group_id") && item.get("group_id").getAsLong() != filterGroupId) {
                    continue;
                }
                list.add(item.deepCopy());
            }
        }
        return DetailedFailure.ok(list);
    }

    public ActionData<?> setFriendAddRequest(JsonObject params) {
        String flag = requireString(params, "flag");
        boolean approve = getBoolean(params, "approve", true);
        String remark = getString(params, "remark", "");

        long eventId = Long.parseLong(flag.trim());
        BotEvent event = cacheRequestQueue.get(eventId);
        if (!(event instanceof NewFriendRequestEvent requestEvent)) {
            return DetailedFailure.fail("未找到对应的加好友请求 (flag=" + flag + ")");
        }
        if (approve) {
            requestEvent.accept();
            if (!remark.isBlank()) {
                try {
                    Friend friend = bot.getFriend(requestEvent.getFromId());
                    if (friend != null) {
                        friend.setRemark(remark);
                    }
                } catch (Exception ignored) {
                }
            }
        } else {
            requestEvent.reject(false);
        }
        cacheRequestQueue.remove(eventId);
        return new Success();
    }

    public ActionData<?> setGroupAddRequest(JsonObject params) {
        String flag = requireString(params, "flag");
        boolean approve = getBoolean(params, "approve", true);
        String reason = getString(params, "reason", "");

        long eventId = Long.parseLong(flag.trim());
        BotEvent event = cacheRequestQueue.get(eventId);
        if (event instanceof MemberJoinRequestEvent requestEvent) {
            if (approve) {
                requestEvent.accept();
            } else {
                requestEvent.reject(false, reason);
                recordIgnoredGroupRequest(requestEvent);
            }
            cacheRequestQueue.remove(eventId);
            return new Success();
        } else if (event instanceof BotInvitedJoinGroupRequestEvent requestEvent) {
            if (approve) {
                requestEvent.accept();
            } else {
                requestEvent.ignore();
                recordIgnoredGroupInvite(requestEvent);
            }
            cacheRequestQueue.remove(eventId);
            return new Success();
        }
        return DetailedFailure.fail("未找到对应的加群请求/邀请 (flag=" + flag + ")");
    }

    private void recordIgnoredGroupRequest(MemberJoinRequestEvent req) {
        JsonObject item = new JsonObject();
        item.addProperty("request_id", req.getEventId());
        item.addProperty("invitor_uin", req.getInvitorId() != null ? req.getInvitorId() : req.getFromId());
        item.addProperty("invitor_nick", req.getFromNick());
        item.addProperty("group_id", req.getGroupId());
        item.addProperty("group_name", req.getGroupName());
        item.addProperty("checked", true);
        item.addProperty("actor", bot.getId());
        item.addProperty("requester_nick", req.getFromNick());
        item.addProperty("message", req.getMessage());
        appendIgnoredRequest(item);
    }

    private void recordIgnoredGroupInvite(BotInvitedJoinGroupRequestEvent req) {
        JsonObject item = new JsonObject();
        item.addProperty("request_id", req.getEventId());
        item.addProperty("invitor_uin", req.getInvitorId());
        item.addProperty("invitor_nick", req.getInvitorNick());
        item.addProperty("group_id", req.getGroupId());
        item.addProperty("group_name", req.getGroupName());
        item.addProperty("checked", true);
        item.addProperty("actor", bot.getId());
        item.addProperty("requester_nick", req.getInvitorNick());
        item.addProperty("message", "");
        appendIgnoredRequest(item);
    }

    private void appendIgnoredRequest(JsonObject item) {
        synchronized (ignoredGroupRequests) {
            if (ignoredGroupRequests.size() >= IGNORED_REQUEST_CAPACITY) {
                ignoredGroupRequests.remove(0);
            }
            ignoredGroupRequests.add(item);
        }
    }

    // ==================== 群文件与下载 ====================

    public ActionData<?> uploadGroupFile(JsonObject params) throws IOException {
        long groupId = requireLong(params, "group_id");
        String fileStr = requireString(params, "file");
        String name = getString(params, "name", "");
        String folderId = getString(params, "folder_id", getString(params, "folder", "")).trim();

        Group group = bot.getGroupOrFail(groupId);
        AbsoluteFolder targetFolder = resolveFolder(group, folderId);
        if (targetFolder == null) {
            return DetailedFailure.fail("未找到目标群文件夹: " + folderId);
        }

        File localFile = null;
        File tempFile = null;
        try {
            ImgUtils.ParsedImageSource parsed = ImgUtils.parseImageSource(fileStr);
            if (parsed.kind() == ImgUtils.ImageSourceKind.FILE_URI || parsed.kind() == ImgUtils.ImageSourceKind.LOCAL_PATH) {
                File f = new File(parsed.value());
                if (!f.isFile() || !f.canRead()) {
                    return DetailedFailure.fail("本地文件不存在或不可读: " + ImgUtils.truncateFileForLog(parsed.value()));
                }
                localFile = f;
                if (name.isBlank()) {
                    name = f.getName();
                }
            } else {
                byte[] bytes = ImgUtils.resolveBinaryBytes(fileStr);
                if (name.isBlank()) {
                    name = inferFileNameFromSource(fileStr, "upload.bin");
                }
                tempFile = Files.createTempFile("obm-upload-", ".tmp").toFile();
                Files.write(tempFile.toPath(), bytes);
                localFile = tempFile;
            }

            try (ExternalResource resource = ExternalResource.create(localFile)) {
                AbsoluteFile uploaded = targetFolder.uploadNewFile(name, resource);
                JsonObject resp = new JsonObject();
                resp.addProperty("file_id", uploaded.getId());
                resp.addProperty("file_name", uploaded.getName());
                return DetailedFailure.ok(resp);
            }
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    public ActionData<?> deleteGroupFile(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String fileId = requireString(params, "file_id");
        Group group = bot.getGroupOrFail(groupId);
        AbsoluteFile file = group.getFiles().getRoot().resolveFileById(fileId, true);
        if (file == null) {
            return DetailedFailure.fail("未找到指定群文件 (file_id=" + fileId + ")");
        }
        boolean deleted = file.delete();
        return deleted ? new Success() : DetailedFailure.fail("删除群文件失败 (file_id=" + fileId + ")");
    }

    public ActionData<?> createGroupFileFolder(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String name = requireString(params, "name");
        String parentId = getString(params, "parent_id", "/").trim();
        Group group = bot.getGroupOrFail(groupId);
        AbsoluteFolder parent = resolveFolder(group, parentId);
        if (parent == null) {
            return DetailedFailure.fail("未找到父文件夹: " + parentId);
        }
        AbsoluteFolder created = parent.createFolder(name);
        JsonObject resp = new JsonObject();
        resp.addProperty("folder_id", created.getId());
        resp.addProperty("folder_name", created.getName());
        return DetailedFailure.ok(resp);
    }

    public ActionData<?> deleteGroupFolder(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String folderId = requireString(params, "folder_id");
        Group group = bot.getGroupOrFail(groupId);
        AbsoluteFolder folder = group.getFiles().getRoot().resolveFolderById(folderId);
        if (folder == null) {
            return DetailedFailure.fail("未找到指定群文件夹 (folder_id=" + folderId + ")");
        }
        boolean deleted = folder.delete();
        return deleted ? new Success() : DetailedFailure.fail("删除群文件夹失败 (folder_id=" + folderId + ")");
    }

    public ActionData<?> getGroupFileSystemInfo(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        Group group = bot.getGroupOrFail(groupId);
        long fileCount;
        try (Stream<AbsoluteFile> stream = group.getFiles().getRoot().filesStream()) {
            fileCount = stream.count();
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("file_count", fileCount);
        resp.addProperty("limit_count", 0);
        resp.addProperty("used_space", 0);
        resp.addProperty("total_space", 0);
        return DetailedFailure.ok(resp, "mirai 2.16 未提供群文件配额查询 API，仅返回根目录文件数");
    }

    public ActionData<?> getGroupRootFiles(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        int fileCount = getInt(params, "file_count", -1);
        Group group = bot.getGroupOrFail(groupId);
        return DetailedFailure.ok(listFolderContents(group, group.getFiles().getRoot(), fileCount));
    }

    public ActionData<?> getGroupFilesByFolder(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String folderId = requireString(params, "folder_id");
        int fileCount = getInt(params, "file_count", -1);
        Group group = bot.getGroupOrFail(groupId);
        AbsoluteFolder folder = resolveFolder(group, folderId);
        if (folder == null) {
            return DetailedFailure.fail("未找到指定群文件夹 (folder_id=" + folderId + ")");
        }
        return DetailedFailure.ok(listFolderContents(group, folder, fileCount));
    }

    private GroupFilesResp listFolderContents(Group group, AbsoluteFolder folder, int limit) {
        List<GroupFilesResp.Files> filesList = new ArrayList<>();
        try (Stream<AbsoluteFile> fileStream = folder.filesStream()) {
            Stream<AbsoluteFile> limited = limit > 0 ? fileStream.limit(limit) : fileStream;
            limited.forEach(f -> {
                GroupFilesResp.Files item = new GroupFilesResp.Files();
                item.setFileId(f.getId());
                item.setFileName(f.getName());
                item.setFileSize(f.getSize());
                item.setBusid(0);
                item.setUploadTime(f.getUploadTime());
                item.setDeadTime(f.getExpiryTime());
                item.setModifyTime(f.getLastModifiedTime());
                item.setDownloadTimes(0);
                item.setUploader(f.getUploaderId());
                NormalMember uploader = group.get(f.getUploaderId());
                item.setUploaderName(uploader != null ? uploader.getNick() : "");
                filesList.add(item);
            });
        }

        List<GroupFilesResp.Folders> foldersList = new ArrayList<>();
        try (Stream<AbsoluteFolder> folderStream = folder.foldersStream()) {
            folderStream.forEach(d -> {
                GroupFilesResp.Folders item = new GroupFilesResp.Folders();
                item.setFolderId(d.getId());
                item.setFolderName(d.getName());
                item.setCreateTime(d.getUploadTime());
                item.setCreator(d.getUploaderId());
                NormalMember creator = group.get(d.getUploaderId());
                item.setCreatorName(creator != null ? creator.getNick() : "");
                item.setTotalFileCount(d.getContentsCount());
                foldersList.add(item);
            });
        }

        GroupFilesResp resp = new GroupFilesResp();
        resp.setFiles(filesList);
        resp.setFolders(foldersList);
        return resp;
    }

    public ActionData<?> getGroupFileUrl(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String fileId = requireString(params, "file_id");
        int busid = getInt(params, "busid", 0);
        Group group = bot.getGroupOrFail(groupId);
        AbsoluteFile file = group.getFiles().getRoot().resolveFileById(fileId, true);
        if (file == null) {
            return DetailedFailure.fail("未找到指定群文件 (file_id=" + fileId + ")");
        }
        String url = file.getUrl();
        if (url == null || url.isBlank()) {
            return DetailedFailure.fail("获取群文件下载链接失败 (file_id=" + fileId + ")");
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("url", url);
        resp.addProperty("file_id", fileId);
        resp.addProperty("busid", busid);
        return DetailedFailure.ok(resp);
    }

    public ActionData<?> renameGroupFile(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String fileId = requireString(params, "file_id");
        String newName = requireString(params, "new_name");
        String currentParent = getString(params, "current_parent_directory", "/").trim();

        Group group = bot.getGroupOrFail(groupId);
        AbsoluteFile file = resolveFileInParentOrRoot(group, fileId, currentParent);
        if (file == null) {
            return DetailedFailure.fail("未找到指定群文件 (file_id=" + fileId + ")");
        }
        boolean ok = file.renameTo(newName);
        return ok ? new Success() : DetailedFailure.fail("重命名群文件失败 (file_id=" + fileId + ")");
    }

    public ActionData<?> renameGroupFileFolder(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String folderId = requireString(params, "folder_id");
        String newFolderName = requireString(params, "new_folder_name");
        Group group = bot.getGroupOrFail(groupId);
        AbsoluteFolder folder = group.getFiles().getRoot().resolveFolderById(folderId);
        if (folder == null) {
            return DetailedFailure.fail("未找到指定群文件夹 (folder_id=" + folderId + ")");
        }
        boolean ok = folder.renameTo(newFolderName);
        return ok ? new Success() : DetailedFailure.fail("重命名群文件夹失败 (folder_id=" + folderId + ")");
    }

    public ActionData<?> moveGroupFile(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String fileId = requireString(params, "file_id");
        String parentDir = getString(params, "parent_directory", "/").trim();
        String targetDir = getString(params, "target_parent_directory", "/").trim();

        Group group = bot.getGroupOrFail(groupId);
        AbsoluteFile file = resolveFileInParentOrRoot(group, fileId, parentDir);
        if (file == null) {
            return DetailedFailure.fail("未找到指定群文件 (file_id=" + fileId + ")");
        }
        AbsoluteFolder targetFolder = resolveFolder(group, targetDir);
        if (targetFolder == null) {
            return DetailedFailure.fail("未找到目标群文件夹 (target_parent_directory=" + targetDir + ")");
        }
        boolean ok = file.moveTo(targetFolder);
        return ok ? new Success() : DetailedFailure.fail("移动群文件失败 (file_id=" + fileId + ")");
    }

    public ActionData<?> transGroupFile(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String fileId = requireString(params, "file_id");
        if (!hasPositiveLong(params, "target_group_id")) {
            return DetailedFailure.unsupported("mirai 2.16 不支持群文件转永久，且未提供 target_group_id 用于跨群转存");
        }
        long targetGroupId = requireLong(params, "target_group_id");
        Group sourceGroup = bot.getGroupOrFail(groupId);
        Group targetGroup = bot.getGroupOrFail(targetGroupId);
        AbsoluteFile file = sourceGroup.getFiles().getRoot().resolveFileById(fileId, true);
        if (file == null) {
            return DetailedFailure.fail("未找到指定群文件 (file_id=" + fileId + ")");
        }
        boolean ok = file.moveTo(targetGroup.getFiles().getRoot());
        return ok ? new Success() : DetailedFailure.fail("跨群转存群文件失败");
    }

    public ActionData<?> downloadFile(JsonObject params) throws IOException {
        String url = requireString(params, "url");
        boolean asBase64 = getBoolean(params, "base64", false);
        String name = getString(params, "name", "");

        Map<String, String> headers = new LinkedHashMap<>();
        if (params.has("headers") && !params.get("headers").isJsonNull()) {
            JsonElement hEl = params.get("headers");
            if (hEl.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : hEl.getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        headers.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } else if (hEl.isJsonArray()) {
                for (JsonElement lineEl : hEl.getAsJsonArray()) {
                    if (lineEl.isJsonPrimitive()) {
                        String line = lineEl.getAsString();
                        int idx = line.indexOf('=');
                        if (idx <= 0) {
                            idx = line.indexOf(':');
                        }
                        if (idx > 0) {
                            headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                        }
                    }
                }
            } else if (hEl.isJsonPrimitive()) {
                for (String line : hEl.getAsString().split("\\r?\\n")) {
                    int idx = line.indexOf('=');
                    if (idx <= 0) {
                        idx = line.indexOf(':');
                    }
                    if (idx > 0) {
                        headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                    }
                }
            }
        }

        byte[] data = HttpUtils.downloadBytes(url, HttpUtils.DEFAULT_DOWNLOAD_TIMEOUT_MS, HttpUtils.DEFAULT_MAX_DOWNLOAD_BYTES, headers);
        DownloadFileResp resp = new DownloadFileResp();
        if (asBase64) {
            resp.setFile("base64://" + Base64.getEncoder().encodeToString(data));
        } else {
            File downloadDir = new File(PluginRuntime.getDataFolder(), "downloads");
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }
            String fileName = !name.isBlank() ? sanitizeFileName(name) : inferFileNameFromSource(url, "download-" + System.currentTimeMillis());
            File out = new File(downloadDir, fileName);
            Files.write(out.toPath(), data);
            resp.setFile(out.getAbsolutePath());
        }
        return DetailedFailure.ok(resp);
    }

    private AbsoluteFolder resolveFolder(Group group, String folderId) {
        AbsoluteFolder root = group.getFiles().getRoot();
        if (folderId == null || folderId.isBlank() || "/".equals(folderId)) {
            return root;
        }
        return root.resolveFolderById(folderId);
    }

    private AbsoluteFile resolveFileInParentOrRoot(Group group, String fileId, String parentId) {
        AbsoluteFolder root = group.getFiles().getRoot();
        if (parentId != null && !parentId.isBlank() && !"/".equals(parentId)) {
            AbsoluteFolder parent = root.resolveFolderById(parentId);
            if (parent != null) {
                AbsoluteFile inParent = parent.resolveFileById(fileId, false);
                if (inParent != null) {
                    return inParent;
                }
            }
        }
        return root.resolveFileById(fileId, true);
    }

    private static String inferFileNameFromSource(String src, String fallback) {
        if (src == null || src.isBlank() || src.startsWith("base64://") || src.startsWith("data:")) {
            return fallback;
        }
        String clean = src;
        int q = clean.indexOf('?');
        if (q >= 0) {
            clean = clean.substring(0, q);
        }
        clean = clean.replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        String candidate = slash >= 0 ? clean.substring(slash + 1) : clean;
        candidate = sanitizeFileName(candidate);
        return candidate.isBlank() ? fallback : candidate;
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    // ==================== 群公告与精华 ====================

    public ActionData<?> getGroupNotice(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        Group group = bot.getGroupOrFail(groupId);
        List<OnlineAnnouncement> list = group.getAnnouncements().toList();
        JsonArray arr = new JsonArray();
        for (OnlineAnnouncement ann : list) {
            JsonObject item = new JsonObject();
            item.addProperty("notice_id", ann.getFid());
            item.addProperty("sender_id", ann.getSenderId());
            item.addProperty("publish_time", ann.getPublicationTime());
            item.addProperty("all_confirmed", ann.getAllConfirmed());
            item.addProperty("confirmed_members_count", ann.getConfirmedMembersCount());

            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("text", ann.getContent() != null ? ann.getContent() : "");
            JsonArray images = new JsonArray();
            AnnouncementParameters annParams = ann.getParameters();
            if (annParams != null) {
                item.addProperty("pinned", annParams.isPinned());
                item.addProperty("send_to_new_member", annParams.getSendToNewMember());
                AnnouncementImage img = annParams.getImage();
                if (img != null) {
                    JsonObject imgObj = new JsonObject();
                    imgObj.addProperty("id", img.getId());
                    imgObj.addProperty("width", img.getWidth());
                    imgObj.addProperty("height", img.getHeight());
                    imgObj.addProperty("url", img.getUrl());
                    images.add(imgObj);
                }
            }
            msgObj.add("images", images);
            msgObj.add("image", images.deepCopy());
            item.add("message", msgObj);
            arr.add(item);
        }
        return DetailedFailure.ok(arr);
    }

    public ActionData<?> sendGroupNotice(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String content = requireString(params, "content");
        String image = getString(params, "image", "");

        Group group = bot.getGroupOrFail(groupId);
        AnnouncementParametersBuilder builder = new AnnouncementParametersBuilder();
        if (hasValue(params, "pinned")) {
            builder.isPinned(getBoolean(params, "pinned", false));
        }
        if (hasValue(params, "confirm_required")) {
            builder.requireConfirmation(getBoolean(params, "confirm_required", false));
        }
        if (hasValue(params, "is_show_edit_card")) {
            builder.showEditCard(getBoolean(params, "is_show_edit_card", false));
        }

        if (!image.isBlank()) {
            try {
                byte[] imgBytes = ImgUtils.resolveBinaryBytes(image);
                String fmt = ImgUtils.detectImageFormatOrDefault(imgBytes);
                try (ExternalResource res = ExternalResource.create(imgBytes, fmt)) {
                    AnnouncementImage uploaded = group.getAnnouncements().uploadImage(res);
                    builder.image(uploaded);
                }
            } catch (Exception e) {
                PluginRuntime.getLogger().warning("群公告图片上传失败，将忽略图片仅发布文本公告: " + e.getMessage());
            }
        }

        OnlineAnnouncement published = group.getAnnouncements().publish(OfflineAnnouncement.create(content, builder.build()));
        JsonObject resp = new JsonObject();
        resp.addProperty("notice_id", published.getFid());
        return DetailedFailure.ok(resp);
    }

    public ActionData<?> deleteGroupNotice(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        String noticeId = requireString(params, "notice_id");
        boolean ok = bot.getGroupOrFail(groupId).getAnnouncements().delete(noticeId);
        return ok ? new Success() : DetailedFailure.fail("删除群公告失败 (notice_id=" + noticeId + ")");
    }

    public ActionData<?> setEssenceMsg(JsonObject params) {
        int messageId = requireInt(params, "message_id");
        MessageSource source = findMessageSource(messageId);
        if (source == null) {
            return DetailedFailure.fail("未找到对应消息，无法设为精华 (message_id=" + messageId + ")");
        }
        long groupId = resolveGroupIdFromParamsOrSource(params, source);
        if (groupId <= 0) {
            return DetailedFailure.fail("无法推断精华消息所属群号，请提供 group_id 或确认消息为群消息");
        }
        boolean ok = bot.getGroupOrFail(groupId).setEssenceMessage(source);
        return ok ? new Success() : DetailedFailure.fail("设置群精华消息失败");
    }

    public ActionData<?> deleteEssenceMsg(JsonObject params) {
        int messageId = requireInt(params, "message_id");
        MessageSource source = findMessageSource(messageId);
        if (source == null) {
            return DetailedFailure.fail("未找到对应消息，无法移除精华 (message_id=" + messageId + ")");
        }
        long groupId = resolveGroupIdFromParamsOrSource(params, source);
        if (groupId <= 0) {
            return DetailedFailure.fail("无法推断精华消息所属群号，请提供 group_id 或确认消息为群消息");
        }
        bot.getGroupOrFail(groupId).getEssences().remove(source);
        return new Success();
    }

    public ActionData<?> getEssenceMsgList(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        Group group = bot.getGroupOrFail(groupId);
        List<EssenceMessageRecord> records = group.getEssences().getPage(0, 50);
        JsonArray arr = new JsonArray();
        for (EssenceMessageRecord rec : records) {
            JsonObject item = new JsonObject();
            item.addProperty("sender_id", rec.getSenderId());
            item.addProperty("sender_nick", rec.getSenderNick() != null ? rec.getSenderNick() : "");
            item.addProperty("sender_time", rec.getSenderTime());
            item.addProperty("operator_id", rec.getOperatorId());
            item.addProperty("operator_nick", rec.getOperatorNick() != null ? rec.getOperatorNick() : "");
            item.addProperty("operator_time", rec.getOperatorTime());

            int msgId = 0;
            String cqMsg = "";
            try {
                MessageSource src = rec.getSource();
                if (src != null) {
                    msgId = DataBaseUtils.toMessageId(src.getInternalIds(), src.getBotId(), src.getFromId());
                    cachedSourceQueue.put(msgId, src);
                    if (src.isOriginalMessageInitialized()) {
                        cqMsg = OnebotMsgUtils.toCQString(src.getOriginalMessage(), msgId, this::cacheForwardMessage);
                    }
                }
            } catch (Exception ignored) {
            }
            item.addProperty("message_id", msgId);
            item.addProperty("message", cqMsg);
            item.addProperty("wording", "");
            item.add("content", OnebotMsgUtils.cqStringToSegmentArray(cqMsg));
            arr.add(item);
        }
        return DetailedFailure.ok(arr);
    }

    private MessageSource findMessageSource(int messageId) {
        MessageSource source = cachedSourceQueue.get(messageId);
        if (source == null) {
            source = DBUtils.loadMessageSourceFromDB(messageId);
        }
        return source;
    }

    private long resolveGroupIdFromParamsOrSource(JsonObject params, MessageSource source) {
        if (hasPositiveLong(params, "group_id")) {
            return requireLong(params, "group_id");
        }
        if (source != null && source.getKind() == MessageSourceKind.GROUP) {
            return source.getTargetId();
        }
        CachedMessageRecord cached = messageCache.get(
                source != null ? DataBaseUtils.toMessageId(source.getInternalIds(), source.getBotId(), source.getFromId()) : -1
        );
        return cached != null ? cached.groupId() : -1L;
    }

    // ==================== 查询 / 好友 / 系统 ====================

    public ActionData<?> getLoginInfo(JsonObject params) {
        return DetailedFailure.ok(new LoginInfoResp(bot.getId(), bot.getNick()));
    }

    public ActionData<?> getStrangerInfo(JsonObject params) {
        long userId = requireLong(params, "user_id");
        var profile = ProfileCache.query(bot, userId);
        if (profile == null) {
            return DetailedFailure.fail("查询陌生人资料失败 (user_id=" + userId + ")");
        }
        StrangerInfoResp info = new StrangerInfoResp();
        info.setUserId(userId);
        info.setNickname(profile.getNickname());
        info.setSex(profile.getSex() != null ? profile.getSex().name().toLowerCase(Locale.ROOT) : "unknown");
        info.setAge(profile.getAge());
        info.setLevel(profile.getQLevel());
        return DetailedFailure.ok(info);
    }

    public ActionData<?> getFriendList(JsonObject params) {
        List<FriendInfoResp> friendList = new LinkedList<>();
        bot.getFriends().forEach(friend ->
                friendList.add(new FriendInfoResp(friend.getId(), friend.getNick(), friend.getRemark())));
        return DetailedFailure.ok(friendList);
    }

    public ActionData<?> getGroupList(JsonObject params) {
        List<GroupInfoResp> groupList = new LinkedList<>();
        bot.getGroups().forEach(group -> groupList.add(buildGroupInfoResp(group)));
        return DetailedFailure.ok(groupList);
    }

    public ActionData<?> getGroupInfo(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        Group group = bot.getGroupOrFail(groupId);
        return DetailedFailure.ok(buildGroupInfoResp(group), "mirai 2.16 未提供群人数上限查询，max_member_count 返回 0");
    }

    private GroupInfoResp buildGroupInfoResp(Group group) {
        GroupInfoResp info = new GroupInfoResp();
        info.setGroupId(group.getId());
        info.setGroupName(group.getName());
        info.setMemberCount(group.getMembers().size() + 1);
        info.setMaxMemberCount(0);
        try {
            info.setGroupCreateTime(group.getOwner().getJoinTimestamp());
        } catch (Exception ignored) {
            info.setGroupCreateTime(0);
        }
        // mirai 2.16 的 entranceAnnouncement 在字节码里是 synthetic（Kotlin HIDDEN），Java 无法调用；
        // 群简介先留空，避免编译期依赖不可见 API。
        info.setGroupMemo("");
        return info;
    }

    @MiraiExperimentalApi
    @LowLevelApi
    public ActionData<?> getGroupMemberInfo(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        long memberId = requireLong(params, "user_id");
        boolean noCache = getBoolean(params, "no_cache", false);

        Group group = bot.getGroupOrFail(groupId);
        if (noCache) {
            try {
                long groupUin = Mirai.getInstance().getUin(group);
                var members = Mirai.getInstance().getRawGroupMemberList(bot, groupUin, groupId, group.getOwner().getId());
                var memberOpt = BaseUtils.copyIterator(members.iterator())
                        .stream()
                        .filter(memberInfo -> memberInfo.getUin() == memberId)
                        .findFirst();
                if (memberOpt.isPresent()) {
                    var memberInfo = memberOpt.get();
                    GroupMemberInfoResp resp = new GroupMemberInfoResp();
                    resp.setGroupId(group.getId());
                    resp.setUserId(memberInfo.getUin());
                    resp.setNickname(memberInfo.getNick());
                    resp.setCard(memberInfo.getNameCard());
                    resp.setJoinTime(memberInfo.getJoinTimestamp());
                    resp.setLastSentTime(memberInfo.getLastSpeakTimestamp());
                    if (memberInfo.getPermission() == MemberPermission.ADMINISTRATOR) {
                        resp.setRole("admin");
                    } else {
                        resp.setRole(memberInfo.getPermission().name().toLowerCase(Locale.ROOT));
                    }
                    resp.setTitle(memberInfo.getSpecialTitle());
                    resp.setCardChangeable(group.getBotPermission() == MemberPermission.OWNER
                            || group.getBotPermission() == MemberPermission.ADMINISTRATOR);
                    return DetailedFailure.ok(resp);
                }
                return DetailedFailure.fail("群成员不存在 (group_id=" + groupId + ", user_id=" + memberId + ")");
            } catch (Exception e) {
                PluginRuntime.getLogger().warning("getRawGroupMemberList(no_cache=true) 失败，降级使用本地缓存: " + e.getMessage());
            }
        }

        NormalMember member = (memberId == bot.getId()) ? group.getBotAsMember() : group.getOrFail(memberId);
        return DetailedFailure.ok(new MiraiGroupMemberInfoResp(member, true));
    }

    public ActionData<?> getGroupMemberList(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        List<GroupMemberInfoResp> list = new LinkedList<>();
        AtomicBoolean isBotIncluded = new AtomicBoolean(false);
        Group group = bot.getGroupOrFail(groupId);
        group.getMembers().forEach(member -> {
            if (member.getId() == bot.getId()) {
                isBotIncluded.set(true);
            }
            list.add(new MiraiGroupMemberInfoResp(member, false));
        });
        if (!isBotIncluded.get()) {
            list.add(new MiraiGroupMemberInfoResp(group.getBotAsMember(), false));
        }
        return DetailedFailure.ok(list);
    }

    public ActionData<?> getOnlineClients(JsonObject params) {
        boolean noCache = getBoolean(params, "no_cache", false);
        List<OtherClientInfo> rawList = Mirai.getInstance().getOnlineOtherClientsList(bot, !noCache);
        JsonArray clients = new JsonArray();
        if (rawList != null) {
            for (OtherClientInfo info : rawList) {
                JsonObject c = new JsonObject();
                c.addProperty("app_id", (long) info.getAppId());
                c.addProperty("device_name", info.getDeviceName() != null ? info.getDeviceName() : "");
                c.addProperty("device_kind", info.getDeviceKind() != null ? info.getDeviceKind() : "");
                if (info.getPlatform() != null) {
                    c.addProperty("platform", info.getPlatform().name().toLowerCase(Locale.ROOT));
                }
                clients.add(c);
            }
        }
        JsonObject resp = new JsonObject();
        resp.add("clients", clients);
        return DetailedFailure.ok(resp);
    }

    public ActionData<?> getStatus(JsonObject params) {
        boolean online = bot != null && bot.isOnline();
        JsonObject status = new JsonObject();
        status.addProperty("online", online);
        status.addProperty("good", online);
        status.addProperty("app_initialized", true);
        status.addProperty("app_enabled", true);
        status.addProperty("app_good", online);
        return DetailedFailure.ok(status);
    }

    public ActionData<?> getVersionInfo(JsonObject params) {
        String ver = PluginRuntime.getVersion();
        JsonObject info = new JsonObject();
        info.addProperty("app_name", "onebot-mirai");
        info.addProperty("app_full_name", "OneBot-Mirai/" + ver);
        info.addProperty("app_version", ver);
        info.addProperty("protocol_version", "v11");
        info.addProperty("coolq_directory", PluginRuntime.getDataFolder().getAbsolutePath());
        info.addProperty("coolq_edition", "mirai");
        info.addProperty("plugin_version", ver);
        info.addProperty("runtime_os", System.getProperty("os.name", "unknown"));
        return DetailedFailure.ok(info);
    }

    public ActionData<?> getGroupMsgHistory(JsonObject params) {
        long groupId = requireLong(params, "group_id");
        int count = getInt(params, "count", 20);
        if (count <= 0) {
            count = 20;
        }
        Group group = bot.getGroupOrFail(groupId);
        JsonArray messages = new JsonArray();
        try (Stream<MessageChain> stream = group.getRoamingMessages().getAllMessagesStream()) {
            stream.limit(count).forEach(chain -> messages.add(roamingChainToJson(chain, true, groupId)));
        }
        JsonObject resp = new JsonObject();
        resp.add("messages", messages);
        return DetailedFailure.ok(resp);
    }

    public ActionData<?> getFriendMsgHistory(JsonObject params) {
        long userId = requireLong(params, "user_id");
        int count = getInt(params, "count", 20);
        if (count <= 0) {
            count = 20;
        }
        Friend friend = bot.getFriendOrFail(userId);
        JsonArray messages = new JsonArray();
        try (Stream<MessageChain> stream = friend.getRoamingMessages().getAllMessagesStream()) {
            stream.limit(count).forEach(chain -> messages.add(roamingChainToJson(chain, false, userId)));
        }
        JsonObject resp = new JsonObject();
        resp.add("messages", messages);
        return DetailedFailure.ok(resp);
    }

    private JsonObject roamingChainToJson(MessageChain chain, boolean isGroup, long peerId) {
        MessageSource source = chain.get(MessageSource.Key);
        int messageId = source != null
                ? DataBaseUtils.toMessageId(source.getInternalIds(), source.getBotId(), source.getFromId())
                : 0;
        if (source != null) {
            cachedSourceQueue.put(messageId, source);
        }
        long senderId = source != null ? source.getFromId() : 0L;
        int time = source != null ? source.getTime() : 0;
        String cqMsg = OnebotMsgUtils.toCQString(chain, messageId, this::cacheForwardMessage);

        JsonObject obj = new JsonObject();
        obj.addProperty("post_type", "message");
        obj.addProperty("message_type", isGroup ? "group" : "private");
        obj.addProperty("sub_type", isGroup ? "normal" : "friend");
        obj.addProperty("self_id", bot.getId());
        obj.addProperty("message_id", messageId);
        obj.addProperty("time", time);
        obj.addProperty("user_id", senderId);
        if (isGroup) {
            obj.addProperty("group_id", peerId);
        }
        obj.addProperty("message", cqMsg);
        obj.addProperty("raw_message", cqMsg);
        obj.addProperty("font", 0);

        JsonObject sender = new JsonObject();
        sender.addProperty("user_id", senderId);
        sender.addProperty("nickname", resolveNicknameBestEffort(senderId, isGroup ? peerId : 0L, isGroup));
        obj.add("sender", sender);
        return obj;
    }

    public ActionData<?> getRecentContact(JsonObject params) {
        int count = getInt(params, "count", 10);
        if (count <= 0) {
            count = 10;
        }
        List<RecentContactRecord> snapshot;
        synchronized (recentContacts) {
            snapshot = new ArrayList<>(recentContacts.values());
        }
        Collections.reverse(snapshot);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(count, snapshot.size()); i++) {
            RecentContactRecord rec = snapshot.get(i);
            JsonObject item = new JsonObject();
            item.addProperty("peerUin", String.valueOf(rec.peerId()));
            item.addProperty("peerName", rec.peerName());
            item.addProperty("remark", rec.remark());
            item.addProperty("msgTime", String.valueOf(rec.msgTime()));
            item.addProperty("chatType", rec.group() ? 2 : 1);
            item.addProperty("msgId", String.valueOf(rec.msgId()));
            item.addProperty("sendNickName", rec.sendNickName());
            item.addProperty("sendMemberName", rec.sendMemberName());
            JsonObject lastMsg = new JsonObject();
            lastMsg.addProperty("message_id", rec.msgId());
            lastMsg.addProperty("time", rec.msgTime());
            lastMsg.addProperty("raw_message", rec.lastMessage());
            item.add("lastestMsg", lastMsg);
            arr.add(item);
        }
        return DetailedFailure.ok(arr);
    }

    public ActionData<?> markMsgAsRead(String action) {
        PluginRuntime.getLogger().info("收到 " + action + "：mirai 2.16 无公开已读上报 API（hmos 协议会自动上报已读），按 no-op 返回成功");
        return DetailedFailure.okWording("mirai 2.16 无公开已读 API，已忽略 (no-op)");
    }

    public ActionData<?> deleteFriend(JsonObject params) {
        long userId = requireLong(params, "user_id");
        bot.getFriendOrFail(userId).delete();
        return new Success();
    }

    public ActionData<?> setFriendRemark(JsonObject params) {
        long userId = requireLong(params, "user_id");
        String remark = getString(params, "remark", "");
        bot.getFriendOrFail(userId).setRemark(remark);
        return new Success();
    }

    public ActionData<?> setRestartPlugin(JsonObject params) {
        return DetailedFailure.okWording("请手动重载插件");
    }

    public ActionData<?> cleanCache() {
        int deleted = deleteFilesInDir(PluginRuntime.getImageFolder())
                + deleteFilesInDir(PluginRuntime.getRecordFolder());
        return DetailedFailure.okWording("已清理缓存文件数: " + deleted);
    }

    private int deleteFilesInDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                count += deleteFilesInDir(f);
            }
            if (f.delete()) {
                count++;
            }
        }
        return count;
    }

    public ActionData<?> canSendImage(JsonObject params) {
        return DetailedFailure.ok(new BooleanResp(true));
    }

    public ActionData<?> canSendRecord(JsonObject params) {
        return DetailedFailure.ok(new BooleanResp(true));
    }

    public ActionData<?> getRecord(JsonObject params) {
        String file = requireString(params, "file");
        File cachedFile = getCachedRecordFile(file);
        if (cachedFile == null || !cachedFile.isFile()) {
            return DetailedFailure.fail("未找到缓存的语音文件: " + ImgUtils.truncateFileForLog(file));
        }
        try {
            byte[] all = Files.readAllBytes(cachedFile.toPath());
            String hex = BaseUtils.bytesToHexString(Arrays.copyOf(all, Math.min(10, all.length)));
            String fileType;
            if (hex.startsWith("2321414D52")) {
                fileType = "amr";
            } else if (hex.startsWith("02232153494C4B5F5633") || hex.startsWith("232153494C4B5F5633")) {
                fileType = "silk";
            } else {
                fileType = "unknown";
            }
            String baseName = cachedFile.getName();
            int dot = baseName.lastIndexOf('.');
            String stem = dot > 0 ? baseName.substring(0, dot) : baseName;
            return DetailedFailure.ok(new RecordInfoResp(
                    cachedFile.getAbsolutePath(),
                    stem,
                    stem,
                    fileType
            ));
        } catch (IOException e) {
            return DetailedFailure.fail("读取缓存语音文件失败: " + e.getMessage());
        }
    }

    public ActionData<?> getImage(JsonObject params) {
        String file = requireString(params, "file");
        ImgUtils.CachedImage image = getCachedImageFile(file);
        if (image == null) {
            return DetailedFailure.fail("未找到缓存的图片信息: " + ImgUtils.truncateFileForLog(file));
        }
        File cachedFile = BaseUtils.getDataFile("image", image.fileName());
        if (cachedFile == null && image.url() != null && !image.url().isBlank()) {
            byte[] downloaded = HttpUtils.getBytesFromHttpUrl(image.url());
            if (downloaded != null) {
                File out = new File(PluginRuntime.getImageFolder(), image.fileName());
                try {
                    if (out.getParentFile() != null) {
                        out.getParentFile().mkdirs();
                    }
                    Files.write(out.toPath(), downloaded);
                    cachedFile = out;
                } catch (IOException ignored) {
                }
            }
        }
        if (cachedFile == null || !cachedFile.isFile()) {
            return DetailedFailure.fail("图片缓存文件不存在: " + ImgUtils.truncateFileForLog(file));
        }
        try {
            String fileType = getImageType(Files.readAllBytes(cachedFile.toPath()));
            return DetailedFailure.ok(new ImgInfoResp(
                    cachedFile.getAbsolutePath(),
                    image.fileName(),
                    image.md5(),
                    image.size(),
                    image.url(),
                    image.addTime(),
                    fileType
            ));
        } catch (IOException e) {
            return DetailedFailure.fail("读取图片缓存文件失败: " + e.getMessage());
        }
    }

    public ActionData<?> checkUrlSafely(JsonObject params) {
        String url = requireString(params, "url");
        boolean reachable = HttpUtils.probeUrlReachable(url, 5000);
        CheckUrlSafelyResp resp = new CheckUrlSafelyResp();
        resp.setLevel(reachable ? 1 : 0);
        return DetailedFailure.ok(resp);
    }

    public ActionData<?> getWordSlice(JsonObject params) {
        String content = getString(params, "content", "");
        WordSlicesResp resp = new WordSlicesResp();
        resp.setSlices(sliceWords(content));
        return DetailedFailure.ok(resp);
    }

    /**
     * 轻量切词：按中文字符块、连续英文/数字、标点符号切分，不引入额外分词库。
     */
    public static List<String> sliceWords(String content) {
        List<String> slices = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return slices;
        }
        StringBuilder token = new StringBuilder();
        int lastKind = 0; // 1=ASCII letter/digit, 2=CJK, 3=other symbol
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!token.isEmpty()) {
                    slices.add(token.toString());
                    token.setLength(0);
                    lastKind = 0;
                }
                continue;
            }
            int kind;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                kind = 1;
            } else if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                kind = 2;
            } else {
                kind = 3;
            }

            if (kind == 1) {
                if (lastKind != 1 && !token.isEmpty()) {
                    slices.add(token.toString());
                    token.setLength(0);
                }
                token.append(c);
                lastKind = 1;
            } else if (kind == 2) {
                if (!token.isEmpty()) {
                    slices.add(token.toString());
                    token.setLength(0);
                }
                slices.add(String.valueOf(c));
                lastKind = 2;
            } else {
                if (!token.isEmpty()) {
                    slices.add(token.toString());
                    token.setLength(0);
                }
                slices.add(String.valueOf(c));
                lastKind = 3;
            }
        }
        if (!token.isEmpty()) {
            slices.add(token.toString());
        }
        return slices;
    }
}
