package cn.evole.onebot.mirai.util;

import cn.evole.onebot.mirai.core.session.BotSession;
import cn.evole.onebot.mirai.core.session.SessionManager;
import cn.evole.onebot.sdk.util.DataBaseUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Contact;
import net.mamoe.mirai.contact.Friend;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.*;
import net.mamoe.mirai.utils.ExternalResource;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * OneBot v11 与 Mirai 2.16 消息双向转换器（含 CQ 码分词器、图片协议上传、合并转发节点解析）。
 */
public class OnebotMsgUtils {

    private static final PlainText MSG_EMPTY = new PlainText("");
    private static final Pattern VALID_CQ_TYPE = Pattern.compile("^[A-Za-z0-9_.-]+$");

    /**
     * 解析后的消息段表示（用于测试和双向转换）。
     */
    public record MessageSegment(String type, Map<String, String> data) {
        public String get(String key) {
            return data.get(key);
        }

        public String getOrDefault(String key, String defaultValue) {
            return data.getOrDefault(key, defaultValue);
        }
    }

    /**
     * 合并转发节点解析中间结构（兼容 NapCat 新字段与 go-cqhttp 旧字段）。
     */
    public record ParsedForwardNode(long userId, String nickname, int time, JsonElement content) {
    }

    /**
     * 消息段显式失败异常（用于需要向上层返回明确失败原因的场景）。
     */
    public static class MessageSegmentException extends IllegalStateException {
        public MessageSegmentException(String message) {
            super(message);
        }

        public MessageSegmentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * CQ 码分词器：支持多 CQ 码、前后文字、相邻 CQ 码、反转义（&amp; 最后反转义）及非法 CQ 码容错。
     */
    public static List<MessageSegment> parseCQString(@Nullable String message, boolean autoEscape) {
        List<MessageSegment> result = new ArrayList<>();
        if (message == null || message.isEmpty()) {
            return result;
        }
        if (autoEscape) {
            result.add(new MessageSegment("text", Map.of("text", message)));
            return result;
        }

        int len = message.length();
        int pos = 0;
        while (pos < len) {
            int cqStart = message.indexOf("[CQ:", pos);
            if (cqStart == -1) {
                appendTextSegment(result, unescapeText(message.substring(pos)));
                break;
            }
            if (cqStart > pos) {
                appendTextSegment(result, unescapeText(message.substring(pos, cqStart)));
            }

            int cqEnd = message.indexOf(']', cqStart + 4);
            int nextCq = message.indexOf("[CQ:", cqStart + 4);
            if (cqEnd == -1 || (nextCq != -1 && nextCq < cqEnd)) {
                // 未闭合或嵌套非法 CQ 码：记录 warning，保留原始文本，继续解析后续段
                int fallbackEnd = (nextCq != -1 && (cqEnd == -1 || nextCq < cqEnd)) ? nextCq : len;
                String malformed = message.substring(cqStart, fallbackEnd);
                PluginRuntime.getLogger().warning("检测到未闭合或非法的 CQ 码，已按纯文本保留: " + ImgUtils.truncateFileForLog(malformed));
                appendTextSegment(result, unescapeText(malformed));
                pos = fallbackEnd;
                continue;
            }

            String inner = message.substring(cqStart + 4, cqEnd);
            String[] parts = inner.split(",", -1);
            String type = parts[0].trim();
            if (type.isEmpty() || !VALID_CQ_TYPE.matcher(type).matches()) {
                String malformed = message.substring(cqStart, cqEnd + 1);
                PluginRuntime.getLogger().warning("检测到非法 CQ 码类型，已按纯文本保留: " + ImgUtils.truncateFileForLog(malformed));
                appendTextSegment(result, unescapeText(malformed));
                pos = cqEnd + 1;
                continue;
            }

            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 1; i < parts.length; i++) {
                String kv = parts[i];
                if (kv.isEmpty()) {
                    continue;
                }
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    String key = kv.substring(0, eq).trim();
                    String val = unescapeCQParam(kv.substring(eq + 1));
                    if (!key.isEmpty()) {
                        params.put(key, val);
                    }
                }
            }
            result.add(new MessageSegment(type, params));
            pos = cqEnd + 1;
        }
        return result;
    }

    private static void appendTextSegment(List<MessageSegment> list, String text) {
        if (text != null && !text.isEmpty()) {
            list.add(new MessageSegment("text", Map.of("text", text)));
        }
    }

    /**
     * 将 JSON 字符串 / JSON 对象 / JSON 数组统一解析为 MessageSegment 列表。
     * autoEscape 仅影响 JSON 字符串消息，不影响对象和数组。
     */
    public static List<MessageSegment> parseMessageSegments(@Nullable JsonElement message, boolean autoEscape) {
        if (message == null || message.isJsonNull()) {
            return List.of();
        }
        if (message.isJsonPrimitive()) {
            return parseCQString(message.getAsString(), autoEscape);
        }
        if (message.isJsonObject()) {
            MessageSegment seg = parseSingleSegmentObject(message.getAsJsonObject());
            return seg != null ? List.of(seg) : List.of();
        }
        if (message.isJsonArray()) {
            List<MessageSegment> segments = new ArrayList<>();
            for (JsonElement elem : message.getAsJsonArray()) {
                if (elem == null || !elem.isJsonObject()) {
                    PluginRuntime.getLogger().warning("跳过非对象的消息段元素: " + elem);
                    continue;
                }
                MessageSegment seg = parseSingleSegmentObject(elem.getAsJsonObject());
                if (seg != null) {
                    segments.add(seg);
                }
            }
            return segments;
        }
        PluginRuntime.getLogger().warning("无法识别的消息格式: " + message);
        return List.of();
    }

    @Nullable
    private static MessageSegment parseSingleSegmentObject(JsonObject obj) {
        if (!obj.has("type") || obj.get("type").isJsonNull()) {
            PluginRuntime.getLogger().warning("消息段缺少 type 字段，已跳过: " + obj);
            return null;
        }
        String type = obj.get("type").getAsString().trim();
        if (type.isEmpty()) {
            PluginRuntime.getLogger().warning("消息段 type 为空，已跳过: " + obj);
            return null;
        }
        Map<String, String> data = new LinkedHashMap<>();
        if (obj.has("data") && obj.get("data").isJsonObject()) {
            JsonObject dataObj = obj.getAsJsonObject("data");
            for (Map.Entry<String, JsonElement> entry : dataObj.entrySet()) {
                JsonElement val = entry.getValue();
                if (val == null || val.isJsonNull()) {
                    data.put(entry.getKey(), "");
                } else if (val.isJsonPrimitive()) {
                    data.put(entry.getKey(), val.getAsString());
                } else {
                    data.put(entry.getKey(), val.toString());
                }
            }
        }
        return new MessageSegment(type, data);
    }

    /**
     * 将 CQ 字符串转换为 OneBot v11 消息段 JSON 数组（用于 postMessageFormat=array 和 get_forward_msg）。
     */
    public static JsonArray cqStringToSegmentArray(@Nullable String cqString) {
        JsonArray array = new JsonArray();
        for (MessageSegment seg : parseCQString(cqString, false)) {
            JsonObject segObj = new JsonObject();
            segObj.addProperty("type", seg.type());
            JsonObject dataObj = new JsonObject();
            for (Map.Entry<String, String> entry : seg.data().entrySet()) {
                dataObj.addProperty(entry.getKey(), entry.getValue());
            }
            segObj.add("data", dataObj);
            array.add(segObj);
        }
        return array;
    }

    /**
     * 解析入口（保留公开方法签名）：将 OneBot 消息（字符串 / 单段对象 / 段数组）转换为 Mirai MessageChain。
     */
    public static MessageChain messageToChains(Bot bot, Contact contact, JsonElement message, boolean autoEscape) {
        if (message == null || message.isJsonNull()) {
            return new MessageChainBuilder().build();
        }
        boolean isArray = message.isJsonArray();
        List<MessageSegment> segments = parseMessageSegments(message, autoEscape);
        MessageChainBuilder builder = new MessageChainBuilder();
        RuntimeException fatalFailure = null;

        for (MessageSegment seg : segments) {
            try {
                Message converted = convertToMiraiMessage(bot, contact, seg.type(), seg.data());
                if (converted != null && converted != MSG_EMPTY) {
                    if (converted instanceof PlainText pt && pt.getContent().isEmpty()) {
                        continue;
                    }
                    builder.append(converted);
                }
            } catch (MessageSegmentException e) {
                // 关键媒体/明确不支持段（如图片解码/下载/上传失败、缺缩略图视频、不支持的 location 等）直接向上抛出，避免静默丢图
                if (!isArray || segments.size() == 1 || "image".equals(seg.type())) {
                    throw e;
                }
                PluginRuntime.getLogger().warning("消息段解析失败，已跳过 (type=" + seg.type() + "): " + e.getMessage());
                fatalFailure = e;
            } catch (Exception e) {
                PluginRuntime.getLogger().warning("消息段解析异常，已跳过 (type=" + seg.type() + "): " + e.getMessage());
                fatalFailure = new MessageSegmentException("消息段解析失败 (type=" + seg.type() + "): " + e.getMessage(), e);
            }
        }

        MessageChain chain = builder.build();
        if (chain.isEmpty() && fatalFailure != null) {
            throw fatalFailure;
        }
        return chain;
    }

    /**
     * 判断 JSON 消息体是否为合并转发节点数组（用于 send_msg / send_group_msg 兼容）。
     */
    public static boolean isForwardNodeArray(@Nullable JsonElement message) {
        if (message == null || !message.isJsonArray()) {
            return false;
        }
        JsonArray arr = message.getAsJsonArray();
        if (arr.isEmpty()) {
            return false;
        }
        for (JsonElement elem : arr) {
            if (!elem.isJsonObject()) {
                return false;
            }
            JsonObject obj = elem.getAsJsonObject();
            if (!obj.has("type") || !"node".equals(obj.get("type").getAsString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断消息体是否仅包含 nudge/poke 副作用段。
     */
    public static boolean hasOnlySideEffectSegments(@Nullable JsonElement message, boolean autoEscape) {
        List<MessageSegment> segments = parseMessageSegments(message, autoEscape);
        if (segments.isEmpty()) {
            return false;
        }
        for (MessageSegment seg : segments) {
            if ("nudge".equals(seg.type())) {
                continue;
            }
            if ("poke".equals(seg.type()) && (seg.data().containsKey("qq") || seg.data().containsKey("user_id"))
                    && !seg.data().containsKey("type")) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * 解析合并转发节点列表，兼容 NapCat 新字段 (user_id/nickname/message) 与 go-cqhttp 旧字段 (uin/name/content)。
     */
    public static List<ParsedForwardNode> parseForwardNodes(@Nullable JsonElement messagesEl, long defaultUserId, int defaultTime) {
        if (messagesEl == null || !messagesEl.isJsonArray()) {
            throw new IllegalArgumentException("messages 参数必须是 JSON 数组");
        }
        List<ParsedForwardNode> result = new ArrayList<>();
        JsonArray array = messagesEl.getAsJsonArray();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                PluginRuntime.getLogger().warning("合并转发节点不是有效 JSON 对象，已跳过: " + element);
                continue;
            }
            JsonObject rawObj = element.getAsJsonObject();
            JsonObject dataObj;
            if (rawObj.has("type") && !rawObj.get("type").isJsonNull()) {
                String type = rawObj.get("type").getAsString();
                if (!"node".equals(type)) {
                    PluginRuntime.getLogger().warning("合并转发节点类型不是 node，已跳过: " + type);
                    continue;
                }
                if (rawObj.has("data") && rawObj.get("data").isJsonObject()) {
                    dataObj = rawObj.getAsJsonObject("data");
                } else {
                    PluginRuntime.getLogger().warning("合并转发 node 节点缺少 data 对象，已跳过");
                    continue;
                }
            } else {
                // 直接传入 node data 对象
                dataObj = rawObj;
            }

            long userId = readLongField(dataObj, "user_id", readLongField(dataObj, "uin", defaultUserId));
            String nickname = readStringField(dataObj, "nickname", readStringField(dataObj, "name", ""));
            int time = (int) readLongField(dataObj, "time", defaultTime);
            if (time <= 0) {
                time = defaultTime;
            }
            JsonElement content = null;
            if (dataObj.has("content") && !dataObj.get("content").isJsonNull()) {
                content = dataObj.get("content");
            } else if (dataObj.has("message") && !dataObj.get("message").isJsonNull()) {
                content = dataObj.get("message");
            }
            if (content == null) {
                PluginRuntime.getLogger().warning("合并转发节点缺少 content/message 内容，已跳过");
                continue;
            }
            result.add(new ParsedForwardNode(userId, nickname, time, content));
        }
        return result;
    }

    private static long readLongField(JsonObject obj, String key, long defaultVal) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        try {
            String raw = obj.get(key).getAsString().trim();
            if (raw.isEmpty()) {
                return defaultVal;
            }
            return Long.parseLong(raw);
        } catch (Exception ignored) {
            return defaultVal;
        }
    }

    private static String readStringField(JsonObject obj, String key, String defaultVal) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception ignored) {
            return defaultVal;
        }
    }

    /**
     * 将整条消息链转换为 CQ 字符串；若包含 ForwardMessage，则按 messageId 触发回调缓存。
     */
    public static String toCQString(MessageChain chain, int messageId, @Nullable BiConsumer<Integer, ForwardMessage> forwardRecorder) {
        if (chain == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SingleMessage single : chain) {
            if (single instanceof ForwardMessage forwardMessage) {
                if (forwardRecorder != null) {
                    forwardRecorder.accept(messageId, forwardMessage);
                }
                sb.append("[CQ:forward,id=").append(messageId).append("]");
            } else {
                sb.append(toCQString(single));
            }
        }
        return sb.toString();
    }

    public static String toCQString(SingleMessage message) {
        if (message instanceof PlainText text) {
            return escapeText(text.getContent());
        } else if (message instanceof At at) {
            return "[CQ:at,qq=" + at.getTarget() + "]";
        } else if (message instanceof AtAll) {
            return "[CQ:at,qq=all]";
        } else if (message instanceof SuperFace superFace) {
            // 超级表情没有 OneBot v11 标准段；降级为 face，至少保留表情 id。
            return "[CQ:face,id=" + superFace.getFace() + "]";
        } else if (message instanceof Face face) {
            return "[CQ:face,id=" + face.getId() + "]";
        } else if (message instanceof Dice dice) {
            return "[CQ:dice,result=" + dice.getValue() + "]";
        } else if (message instanceof RockPaperScissors rps) {
            int rpsVal = switch (rps) {
                case ROCK -> 1;
                case SCISSORS -> 2;
                case PAPER -> 3;
            };
            return "[CQ:rps,result=" + rpsVal + "]";
        } else if (message instanceof MarketFace marketFace) {
            return "[CQ:mface,id=" + marketFace.getId() + ",summary=" + escapeParam(marketFace.getName()) + "]";
        } else if (message instanceof VipFace vipFace) {
            return "[CQ:vipface,id=" + vipFace.getKind().getId()
                    + ",name=" + escapeParam(vipFace.getKind().getName())
                    + ",count=" + vipFace.getCount() + "]";
        } else if (message instanceof PokeMessage pokeMessage) {
            return "[CQ:poke,type=" + pokeMessage.getPokeType()
                    + ",id=" + pokeMessage.getId()
                    + ",name=" + escapeParam(pokeMessage.getName()) + "]";
        } else if (message instanceof Image image) {
            String url = "";
            try {
                url = Image.queryUrl(image);
            } catch (Exception ignored) {
            }
            return "[CQ:image,file=" + escapeParam(image.getImageId()) + ",url=" + escapeParam(url) + "]";
        } else if (message instanceof FlashImage flashImage) {
            String url = "";
            try {
                url = Image.queryUrl(flashImage.getImage());
            } catch (Exception ignored) {
            }
            return "[CQ:image,file=" + escapeParam(flashImage.getImage().getImageId())
                    + ",url=" + escapeParam(url) + ",type=flash,sub_type=1]";
        } else if (message instanceof ForwardMessage) {
            return "[CQ:forward,id=0]";
        } else if (message instanceof ShortVideo video) {
            return "[CQ:video,file=" + escapeParam(video.getFilename()) + "]";
        } else if (message instanceof FileMessage fileMsg) {
            return "[CQ:file,file=" + escapeParam(fileMsg.getName())
                    + ",file_id=" + escapeParam(fileMsg.getId())
                    + ",file_size=" + fileMsg.getSize() + "]";
        } else if (message instanceof MusicShare ms) {
            return "[CQ:music,type=custom,url=" + escapeParam(ms.getJumpUrl())
                    + ",audio=" + escapeParam(ms.getMusicUrl())
                    + ",title=" + escapeParam(ms.getTitle())
                    + ",content=" + escapeParam(ms.getSummary())
                    + ",image=" + escapeParam(ms.getPictureUrl()) + "]";
        } else if (message instanceof ServiceMessage serviceMessage) {
            if (serviceMessage.getContent().contains("xml version")) {
                return "[CQ:xml,data=" + escapeParam(serviceMessage.getContent()) + "]";
            } else {
                return "[CQ:json,data=" + escapeParam(serviceMessage.getContent()) + "]";
            }
        } else if (message instanceof LightApp app) {
            return "[CQ:json,data=" + escapeParam(app.getContent()) + "]";
        } else if (message instanceof MessageSource) {
            return "";
        } else if (message instanceof QuoteReply quoteReply) {
            return "[CQ:reply,id=" + DataBaseUtils.toMessageId(
                    quoteReply.getSource().getInternalIds(),
                    quoteReply.getSource().getBotId(),
                    quoteReply.getSource().getFromId()
            ) + "]";
        } else if (message instanceof OnlineAudio audio) {
            return "[CQ:record,url=" + escapeParam(audio.getUrlForDownload())
                    + ",file=" + BaseUtils.bytesToHexString(audio.getFileMd5()) + "]";
        } else if (message instanceof Audio audio) {
            return "[CQ:record,url=,file=" + BaseUtils.bytesToHexString(audio.getFileMd5()) + "]";
        } else {
            PluginRuntime.getLogger().warning("此处消息的转义尚未被插件支持: " + message);
            return "";
        }
    }

    public static String escapeText(@Nullable String msg) {
        if (msg == null) {
            return "";
        }
        return msg.replace("&", "&amp;")
                .replace("[", "&#91;")
                .replace("]", "&#93;");
    }

    public static String escapeParam(@Nullable String msg) {
        if (msg == null) {
            return "";
        }
        return msg.replace("&", "&amp;")
                .replace("[", "&#91;")
                .replace("]", "&#93;")
                .replace(",", "&#44;");
    }

    public static String unescapeText(@Nullable String msg) {
        if (msg == null) {
            return "";
        }
        return msg.replace("&#91;", "[")
                .replace("&#93;", "]")
                .replace("&#44;", ",")
                .replace("&amp;", "&");
    }

    public static String unescapeCQParam(@Nullable String msg) {
        if (msg == null) {
            return "";
        }
        // 必须最后反转义 &amp;，避免 &amp;#91; 被二次反转义成 '['
        return msg.replace("&#91;", "[")
                .replace("&#93;", "]")
                .replace("&#44;", ",")
                .replace("&amp;", "&");
    }

    private static Message convertToMiraiMessage(Bot bot, Contact contact, String type, Map<String, String> args) {
        switch (type) {
            case "text" -> {
                return new PlainText(args.getOrDefault("text", ""));
            }
            case "at" -> {
                if (!(contact instanceof Group)) {
                    PluginRuntime.getLogger().warning("私聊中不能发送 At，已跳过");
                    return MSG_EMPTY;
                }
                String qq = args.get("qq");
                if (qq == null || qq.isBlank()) {
                    PluginRuntime.getLogger().warning("At 消息段缺少 qq 参数，已跳过");
                    return MSG_EMPTY;
                }
                if ("all".equalsIgnoreCase(qq.trim())) {
                    return AtAll.INSTANCE;
                }
                try {
                    long targetId = Long.parseLong(qq.trim());
                    return new At(targetId);
                } catch (NumberFormatException e) {
                    PluginRuntime.getLogger().warning("At 消息段 qq 参数无效，已跳过: " + qq);
                    return MSG_EMPTY;
                }
            }
            case "face" -> {
                String idStr = args.get("id");
                if (idStr == null || idStr.isBlank()) {
                    PluginRuntime.getLogger().warning("face 消息段缺少 id 参数，已跳过");
                    return MSG_EMPTY;
                }
                return new Face(Integer.parseInt(idStr.trim()));
            }
            case "mface" -> {
                PluginRuntime.getLogger().warning("mirai 2.16 无公开商城表情 (mface) 构造 API，已降级发送");
                String summary = args.get("summary");
                if (summary != null && !summary.isBlank()) {
                    return new PlainText(summary);
                }
                String faceId = args.getOrDefault("face_id", args.get("id"));
                if (faceId != null && !faceId.isBlank()) {
                    try {
                        int parsedId = Integer.parseInt(faceId.trim());
                        if (parsedId >= 0 && parsedId <= 500) {
                            return new Face(parsedId);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                return new PlainText("[表情]");
            }
            case "emoji" -> {
                String idStr = args.get("id");
                if (idStr == null || idStr.isBlank()) {
                    return MSG_EMPTY;
                }
                return new PlainText(new String(Character.toChars(Integer.parseInt(idStr.trim()))));
            }
            case "image" -> {
                // cache / proxy / timeout 按协议忽略不报错
                String file = args.get("file");
                if (file == null || file.isBlank()) {
                    file = args.get("url");
                }
                boolean isFlash = "1".equals(args.get("sub_type")) || "flash".equalsIgnoreCase(args.get("type"));
                Message imgMsg = constructImageMsg(file, contact, isFlash);
                if (PluginRuntime.isDebug()) {
                    PluginRuntime.getLogger().debug("Image uploaded: " + imgMsg);
                }
                return imgMsg;
            }
            case "record" -> {
                String file = args.get("file");
                if (file == null || file.isBlank()) {
                    file = args.get("url");
                }
                try {
                    return VoiceUtils.sendRecordMsg(file, contact);
                } catch (Exception e) {
                    throw new MessageSegmentException(e.getMessage(), e);
                }
            }
            case "video" -> {
                return constructVideoMsg(args, contact);
            }
            case "reply" -> {
                return constructReplyMsg(bot, args);
            }
            case "forward" -> {
                return constructForwardRefMsg(bot, args);
            }
            case "share" -> {
                String url = args.get("url");
                String title = args.get("title");
                if (StringUtils.isEmpty(url, title)) {
                    throw new MessageSegmentException("share 消息缺少 url 或 title 参数");
                }
                return RichMessage.share(
                        url,
                        title,
                        args.getOrDefault("content", ""),
                        args.getOrDefault("image", "")
                );
            }
            case "music" -> {
                String musicType = args.get("type");
                if (musicType == null || musicType.isBlank()) {
                    throw new MessageSegmentException("music 消息缺少 type 参数");
                }
                return switch (musicType) {
                    case "qq" -> MusicUtils.QQMusic.send(args.get("id"));
                    case "163" -> MusicUtils.NeteaseMusic.send(args.get("id"));
                    case "custom" -> {
                        try {
                            yield MusicUtils.custom(
                                    args.get("url"),
                                    args.get("audio"),
                                    args.get("title"),
                                    args.get("content"),
                                    args.get("image")
                            );
                        } catch (IllegalArgumentException e) {
                            throw new MessageSegmentException(e.getMessage(), e);
                        }
                    }
                    default -> throw new MessageSegmentException("不支持的音乐分享类型: " + musicType);
                };
            }
            case "contact" -> {
                String contactType = args.get("type");
                String idStr = args.get("id");
                if ("qq".equals(contactType) && idStr != null && !idStr.isBlank()) {
                    return RichMessageUtils.contactQQ(bot, Long.parseLong(idStr.trim()));
                }
                throw new MessageSegmentException("mirai 2.16 不支持发送群名片推荐或缺少有效 contact 参数 (type=" + contactType + ")");
            }
            case "shake" -> {
                return PokeMessage.ChuoYiChuo;
            }
            case "poke" -> {
                // 如果提供 qq / user_id 且未指定 PokeMessage type，按戳一戳动作处理
                String qq = args.getOrDefault("qq", args.get("user_id"));
                if (qq != null && !qq.isBlank() && !args.containsKey("type")) {
                    sendNudge(bot, contact, Long.parseLong(qq.trim()));
                    return MSG_EMPTY;
                }
                String pokeTypeStr = args.get("type");
                String pokeIdStr = args.get("id");
                if (pokeTypeStr != null && pokeIdStr != null) {
                    int pt = Integer.parseInt(pokeTypeStr.trim());
                    int pid = Integer.parseInt(pokeIdStr.trim());
                    return Arrays.stream(PokeMessage.values)
                            .filter(p -> p.getPokeType() == pt && p.getId() == pid)
                            .findFirst()
                            .orElse(PokeMessage.ChuoYiChuo);
                }
                if (pokeIdStr != null) {
                    int pid = Integer.parseInt(pokeIdStr.trim());
                    return Arrays.stream(PokeMessage.values)
                            .filter(p -> p.getId() == pid)
                            .findFirst()
                            .orElse(PokeMessage.ChuoYiChuo);
                }
                return PokeMessage.ChuoYiChuo;
            }
            case "nudge" -> {
                String target = args.getOrDefault("qq", args.get("user_id"));
                if (target == null || target.isBlank()) {
                    throw new MessageSegmentException("nudge 消息段缺少 qq 参数");
                }
                sendNudge(bot, contact, Long.parseLong(target.trim()));
                return MSG_EMPTY;
            }
            case "dice" -> {
                String valStr = args.getOrDefault("value", args.getOrDefault("result", args.get("id")));
                if (valStr == null || valStr.isBlank()) {
                    return Dice.random();
                }
                int val = Integer.parseInt(valStr.trim());
                if (val < 1 || val > 6) {
                    throw new MessageSegmentException("dice 点数必须在 1-6 之间: " + val);
                }
                return new Dice(val);
            }
            case "rps" -> {
                String valStr = args.getOrDefault("value", args.getOrDefault("result", args.get("id")));
                if (valStr == null || valStr.isBlank()) {
                    return RockPaperScissors.random();
                }
                int val = Integer.parseInt(valStr.trim());
                return switch (val) {
                    case 1 -> RockPaperScissors.ROCK;
                    case 2 -> RockPaperScissors.SCISSORS;
                    case 3 -> RockPaperScissors.PAPER;
                    default -> throw new MessageSegmentException("rps 取值必须在 1-3 之间: " + val);
                };
            }
            case "xml" -> {
                String data = args.get("data");
                if (data == null || data.isBlank()) {
                    throw new MessageSegmentException("xml 消息缺少 data 参数");
                }
                return RichMessageUtils.xmlMessage(data);
            }
            case "json" -> {
                String data = args.get("data");
                if (data == null || data.isBlank()) {
                    throw new MessageSegmentException("json 消息缺少 data 参数");
                }
                return data.contains("\"app\"") ? new LightApp(data) : RichMessageUtils.jsonMessage(data);
            }
            case "markdown", "keyboard" -> {
                String data = args.getOrDefault("data", args.get("content"));
                if (data == null || data.isBlank()) {
                    throw new MessageSegmentException(type + " 消息缺少 JSON data/content 参数");
                }
                try {
                    JsonElement parsed = JsonParser.parseString(data);
                    if (!parsed.isJsonObject()) {
                        throw new MessageSegmentException(type + " 消息内容必须是合法 JSON 对象");
                    }
                    return new LightApp(parsed.toString());
                } catch (MessageSegmentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new MessageSegmentException(type + " 消息 JSON 解析失败: " + e.getMessage(), e);
                }
            }
            case "location" -> throw new MessageSegmentException("mirai 2.16 无公开位置消息 (location) 构造 API");
            default -> {
                PluginRuntime.getLogger().warning("不支持的消息段类型，已跳过: " + type);
                return MSG_EMPTY;
            }
        }
    }

    private static void sendNudge(Bot bot, Contact contact, long targetId) {
        if (contact instanceof Group group) {
            var member = group.get(targetId);
            if (member == null) {
                throw new MessageSegmentException("群聊中未找到戳一戳目标成员: " + targetId);
            }
            member.nudge().sendTo(group);
        } else if (contact instanceof Friend friend) {
            friend.nudge().sendTo(friend);
        } else if (bot != null) {
            Friend friend = bot.getFriend(targetId);
            if (friend == null) {
                throw new MessageSegmentException("未找到戳一戳目标好友: " + targetId);
            }
            friend.nudge().sendTo(friend);
        } else {
            throw new MessageSegmentException("当前会话不支持戳一戳");
        }
    }

    private static Message constructVideoMsg(Map<String, String> args, Contact contact) {
        String thumb = args.getOrDefault("thumb", args.get("cover"));
        if (thumb == null || thumb.isBlank()) {
            throw new MessageSegmentException("mirai 发送视频需要缩略图（缺少 thumb 参数）");
        }
        String file = args.getOrDefault("file", args.get("url"));
        if (file == null || file.isBlank()) {
            throw new MessageSegmentException("video 消息缺少 file 参数");
        }
        String fileName = args.get("name");
        if (fileName == null || fileName.isBlank()) {
            fileName = inferFileName(file, "video.mp4");
        }
        try {
            byte[] videoBytes = ImgUtils.resolveBinaryBytes(file);
            byte[] thumbBytes = ImgUtils.resolveBinaryBytes(thumb);
            String thumbFmt = ImgUtils.detectImageFormatOrDefault(thumbBytes);
            try (ExternalResource videoRes = ExternalResource.create(videoBytes, "mp4");
                 ExternalResource thumbRes = ExternalResource.create(thumbBytes, thumbFmt)) {
                return contact.uploadShortVideo(videoRes, thumbRes, fileName);
            }
        } catch (Exception e) {
            throw new MessageSegmentException("发送视频失败 (file=" + ImgUtils.truncateFileForLog(file) + "): " + e.getMessage(), e);
        }
    }

    private static String inferFileName(String pathOrUrl, String fallback) {
        if (pathOrUrl == null || pathOrUrl.isBlank() || pathOrUrl.startsWith("base64://") || pathOrUrl.startsWith("data:")) {
            return fallback;
        }
        String clean = pathOrUrl;
        int q = clean.indexOf('?');
        if (q >= 0) {
            clean = clean.substring(0, q);
        }
        clean = clean.replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        String candidate = slash >= 0 ? clean.substring(slash + 1) : clean;
        return candidate.isBlank() ? fallback : candidate;
    }

    private static Message constructReplyMsg(Bot bot, Map<String, String> args) {
        String idStr = args.get("id");
        if (idStr == null || idStr.isBlank()) {
            throw new MessageSegmentException("reply 消息缺少 id 参数");
        }
        int messageId;
        try {
            messageId = Integer.parseInt(idStr.trim());
        } catch (NumberFormatException e) {
            throw new MessageSegmentException("reply 消息 id 格式无效: " + idStr, e);
        }
        MessageSource source = null;
        if (bot != null && SessionManager.containsSession(bot.getId())) {
            BotSession session = SessionManager.get(bot.getId());
            if (session != null && session.getApiImpl() != null) {
                source = session.getApiImpl().getCachedSourceQueue().get(messageId);
            }
        }
        if (source == null) {
            source = DBUtils.loadMessageSourceFromDB(messageId);
        }
        if (source == null) {
            throw new MessageSegmentException("未找到被回复的消息 (message_id=" + messageId + ")");
        }
        return MessageSource.quote(source);
    }

    private static Message constructForwardRefMsg(Bot bot, Map<String, String> args) {
        String idStr = args.getOrDefault("id", args.get("message_id"));
        if (idStr == null || idStr.isBlank()) {
            throw new MessageSegmentException("forward 消息缺少 id 参数");
        }
        if (bot != null && SessionManager.containsSession(bot.getId())) {
            BotSession session = SessionManager.get(bot.getId());
            if (session != null && session.getApiImpl() != null) {
                try {
                    int msgId = Integer.parseInt(idStr.trim());
                    ForwardMessage cached = session.getApiImpl().getForwardMessage(msgId);
                    if (cached != null) {
                        return cached;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        throw new MessageSegmentException("未找到对应的转发消息 (id=" + idStr + ")");
    }

    /**
     * 统一图片上传入口：通过 ExternalResource.create(byte[], formatName) 上传。
     */
    public static Image uploadImageFromBytes(byte[] bytes, @Nullable String formatName, Contact contact) {
        if (bytes == null || bytes.length == 0) {
            throw new MessageSegmentException("图片字节数据为空");
        }
        String fmt = (formatName == null || formatName.isBlank() || "unknown".equalsIgnoreCase(formatName))
                ? ImgUtils.detectImageFormatOrDefault(bytes)
                : formatName.toLowerCase(Locale.ROOT);
        try (ExternalResource resource = ExternalResource.create(bytes, fmt)) {
            Image image = ExternalResource.uploadAsImage(resource, contact);
            if (image == null) {
                throw new MessageSegmentException("图片上传失败：mirai 返回空 Image");
            }
            return image;
        } catch (IOException e) {
            throw new MessageSegmentException("上传图片失败: " + e.getMessage(), e);
        }
    }

    private static Message constructImageMsg(@Nullable String fileStr, Contact contact, boolean flash) {
        if (fileStr == null || fileStr.isBlank()) {
            throw new MessageSegmentException("图片 file 参数为空");
        }
        String safeFile = ImgUtils.truncateFileForLog(fileStr);
        try {
            ImgUtils.ParsedImageSource parsed = ImgUtils.parseImageSource(fileStr);
            Image image;
            switch (parsed.kind()) {
                case BASE64, DATA_URI_BASE64 -> {
                    byte[] bytes = ImgUtils.decodeBase64Flexible(parsed.value());
                    String fmt = ImgUtils.getImageType(bytes);
                    if ("unknown".equals(fmt) && parsed.mimeFormat() != null) {
                        fmt = parsed.mimeFormat();
                    }
                    image = uploadImageFromBytes(bytes, fmt, contact);
                }
                case HTTP -> {
                    byte[] bytes = HttpUtils.downloadBytes(parsed.value());
                    String fmt = ImgUtils.detectImageFormatOrDefault(bytes);
                    image = uploadImageFromBytes(bytes, fmt, contact);
                }
                case FILE_URI -> {
                    File file = new File(parsed.value());
                    if (!file.isFile() || !file.canRead()) {
                        throw new IOException("本地图片文件不存在或不可读: " + ImgUtils.truncateFileForLog(parsed.value()));
                    }
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    String fmt = ImgUtils.detectImageFormatOrDefault(bytes);
                    image = uploadImageFromBytes(bytes, fmt, contact);
                }
                case LOCAL_PATH -> {
                    File file = new File(parsed.value());
                    if (file.isFile() && file.canRead()) {
                        byte[] bytes = Files.readAllBytes(file.toPath());
                        String fmt = ImgUtils.detectImageFormatOrDefault(bytes);
                        image = uploadImageFromBytes(bytes, fmt, contact);
                    } else {
                        image = resolveImageFromCacheOrId(parsed.value(), contact, false);
                    }
                }
                case MIRAI_ID -> image = resolveImageFromCacheOrId(parsed.value(), contact, true);
                default -> throw new IOException("不支持的图片协议");
            }
            if (flash) {
                return FlashImage.from(image);
            }
            return image;
        } catch (MessageSegmentException e) {
            throw e;
        } catch (Exception e) {
            throw new MessageSegmentException("图片处理或上传失败 (file=" + safeFile + "): " + e.getMessage(), e);
        }
    }

    private static Image resolveImageFromCacheOrId(String nameOrId, Contact contact, boolean allowFromIdFallback) throws IOException {
        // 1. 先查本地 image 缓存目录直接文件
        File directCached = new File(PluginRuntime.getImageFolder(), nameOrId);
        if (directCached.isFile() && directCached.canRead()) {
            byte[] bytes = Files.readAllBytes(directCached.toPath());
            return uploadImageFromBytes(bytes, ImgUtils.detectImageFormatOrDefault(bytes), contact);
        }

        // 2. 查 .cqimg / .image 元数据缓存
        ImgUtils.CachedImage cachedMeta = ImgUtils.getCachedImageFile(nameOrId);
        if (cachedMeta != null) {
            File rawFile = BaseUtils.getDataFile("image", cachedMeta.fileName());
            if (rawFile != null && rawFile.isFile() && rawFile.canRead()
                    && !rawFile.getName().endsWith(".cqimg") && !rawFile.getName().endsWith(".image")) {
                byte[] bytes = Files.readAllBytes(rawFile.toPath());
                return uploadImageFromBytes(bytes, ImgUtils.detectImageFormatOrDefault(bytes), contact);
            }
            if (cachedMeta.url() != null && !cachedMeta.url().isBlank()) {
                byte[] bytes = HttpUtils.downloadBytes(cachedMeta.url());
                return uploadImageFromBytes(bytes, ImgUtils.detectImageFormatOrDefault(bytes), contact);
            }
        }

        // 3. mirai 图片 ID 兜底：Image.fromId best-effort
        if (allowFromIdFallback || nameOrId.startsWith("/") || (nameOrId.startsWith("{") && nameOrId.contains("}"))) {
            return Image.fromId(nameOrId);
        }

        throw new IOException("本地图片文件不存在且未命中图片缓存: " + ImgUtils.truncateFileForLog(nameOrId));
    }
}
