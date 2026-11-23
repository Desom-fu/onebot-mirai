package cn.evole.onebot.mirai.web.queue;

import net.mamoe.mirai.event.events.BotEvent;
import net.mamoe.mirai.event.events.BotInvitedJoinGroupRequestEvent;
import net.mamoe.mirai.event.events.MemberJoinRequestEvent;
import net.mamoe.mirai.event.events.NewFriendRequestEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 线程安全的加好友/加群请求事件 LRU 缓存。
 */
public class CacheRequestQueue extends LinkedHashMap<Long, BotEvent> {
    private static final int CACHE_SIZE = 512;

    public CacheRequestQueue() {
        super(64, 0.75f, false);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Long, BotEvent> eldest) {
        return size() > CACHE_SIZE;
    }

    @Override
    public synchronized BotEvent get(Object key) {
        return super.get(key);
    }

    @Override
    public synchronized BotEvent put(Long key, BotEvent value) {
        if (key == null || value == null) {
            return null;
        }
        return super.put(key, value);
    }

    @Override
    public synchronized BotEvent remove(Object key) {
        return super.remove(key);
    }

    public synchronized List<BotEvent> snapshotValues() {
        return new ArrayList<>(values());
    }

    public synchronized void add(NewFriendRequestEvent event) {
        put(event.getEventId(), event);
    }

    public synchronized void add(MemberJoinRequestEvent event) {
        put(event.getEventId(), event);
    }

    public synchronized void add(BotInvitedJoinGroupRequestEvent event) {
        put(event.getEventId(), event);
    }
}
