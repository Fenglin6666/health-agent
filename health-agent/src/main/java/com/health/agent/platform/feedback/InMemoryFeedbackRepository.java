package com.health.agent.platform.feedback;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内存实现，给 {@code ha.persistence.mode=memory} 用。
 *
 * <p>和会话的内存模式一样：重启即丢。它存在的意义是让本地调试不必起 MySQL，
 * 不是给生产用的降级方案。
 */
public class InMemoryFeedbackRepository implements FeedbackRepository {

    private final Map<String, Feedback> store = new LinkedHashMap<>();

    @Override
    public synchronized void upsert(Feedback f) {
        store.put(key(f), f);
    }

    @Override
    public synchronized long count() {
        return store.size();
    }

    private static String key(Feedback f) {
        return f.tenantId() + "|" + f.sessionId() + "|" + f.turn() + "|" + f.agent();
    }
}
