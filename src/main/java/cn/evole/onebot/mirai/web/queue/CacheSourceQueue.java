package cn.evole.onebot.mirai.web.queue;

import cn.evole.onebot.sdk.util.DataBaseUtils;
import net.mamoe.mirai.message.data.MessageSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 线程安全的消息 Source LRU 缓存（供撤回、引用回复、设精华等使用）。
 */
public class CacheSourceQueue extends LinkedHashMap<Integer, MessageSource> {
    private static final int CACHE_SIZE = 2048;

    public CacheSourceQueue() {
        super(256, 0.75f, true);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Integer, MessageSource> eldest) {
        return size() > CACHE_SIZE;
    }

    @Override
    public synchronized MessageSource get(Object key) {
        return super.get(key);
    }

    @Override
    public synchronized MessageSource put(Integer key, MessageSource value) {
        if (key == null || value == null) {
            return null;
        }
        return super.put(key, value);
    }

    public synchronized int add(MessageSource source) {
        if (source == null) {
            return 0;
        }
        int id = DataBaseUtils.toMessageId(source.getInternalIds(), source.getBotId(), source.getFromId());
        put(id, source);
        return id;
    }
}
