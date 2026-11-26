package com.health.agent.platform.persistence;

import com.health.agent.kernel.domain.Message;
import com.health.agent.kernel.domain.Session;

import java.util.Optional;

/**
 * 内存实现。没有持久化时用它，行为和改造前一致。
 *
 * <p>{@link #load} 永远返回 empty：会话对象本身由 {@code SessionStore} 的缓存持有，
 * 进程重启就没了——这正是"内存模式"的含义。
 */
public class InMemorySessionRepository implements SessionRepository {

    @Override
    public void create(Session session) {
        // 无需持久化
    }

    @Override
    public Optional<Session> load(String id) {
        return Optional.empty();
    }

    @Override
    public void appendMessage(String sessionId, int turn, int seq, Message message) {
        // 无需持久化
    }

    @Override
    public void updateActiveAgent(String sessionId, String agent) {
        // 内存实现里 Session 对象本身就是状态，无需另存
    }

    @Override
    public long count() {
        return 0;
    }
}
