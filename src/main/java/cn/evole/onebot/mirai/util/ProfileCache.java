package cn.evole.onebot.mirai.util;

import net.mamoe.mirai.Bot;
import net.mamoe.mirai.Mirai;
import net.mamoe.mirai.data.UserProfile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户资料缓存。
 *
 * <p>mirai 的 {@code queryProfile} 是网络接口；群消息、私聊消息、群成员列表如果逐条调用，
 * 活跃群里会明显拖慢事件处理。这里按 QQ 号做 TTL 缓存，资料本身仍然完整返回，
 * 只是把重复查询合并掉。</p>
 */
public final class ProfileCache {

    /** 正常资料缓存 10 分钟。 */
    private static final long TTL_MS = 10 * 60 * 1000L;
    /** 查询失败/资料为空时缓存 60 秒，避免风暴式重试。 */
    private static final long NEGATIVE_TTL_MS = 60 * 1000L;
    /** 最多缓存 4096 个 QQ，超过后清理过期项。 */
    private static final int MAX_SIZE = 4096;

    private static final ConcurrentHashMap<Long, Entry> CACHE = new ConcurrentHashMap<>();

    private ProfileCache() {
    }

    /**
     * 获取用户资料；失败返回 null，不抛异常。
     */
    public static UserProfile query(Bot bot, long userId) {
        if (bot == null || userId <= 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(userId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.profile();
        }

        UserProfile profile = null;
        try {
            profile = Mirai.getInstance().queryProfile(bot, userId);
        } catch (Exception e) {
            if (PluginRuntime.isDebug()) {
                PluginRuntime.getLogger().debug("查询用户资料失败 (user=" + userId + "): " + e.getMessage());
            }
        }

        if (CACHE.size() >= MAX_SIZE) {
            prune(now);
        }
        long ttl = profile != null ? TTL_MS : NEGATIVE_TTL_MS;
        CACHE.put(userId, new Entry(profile, now + ttl));
        return profile;
    }

    /** 资料变更后可主动失效。 */
    public static void invalidate(long userId) {
        CACHE.remove(userId);
    }

    public static void clear() {
        CACHE.clear();
    }

    private static void prune(long now) {
        CACHE.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        if (CACHE.size() < MAX_SIZE) {
            return;
        }
        int remove = CACHE.size() - MAX_SIZE + 1;
        for (Map.Entry<Long, Entry> e : CACHE.entrySet()) {
            if (remove-- <= 0) {
                break;
            }
            CACHE.remove(e.getKey());
        }
    }

    private record Entry(UserProfile profile, long expiresAt) {
    }
}
