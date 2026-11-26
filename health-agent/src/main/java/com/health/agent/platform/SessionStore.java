package com.health.agent.platform;

import com.health.agent.kernel.domain.Message;
import com.health.agent.kernel.domain.Session;
import com.health.agent.platform.persistence.SessionRepository;
import com.health.agent.platform.persistence.SessionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话缓存 + 持久化。
 *
 * <p>进程内缓存是必须的：{@link Session} 是可变对象，同一个会话的并发请求要看到同一份状态。
 * 缓存只是"热数据"，冷数据从 {@link SessionRepository} 恢复。
 *
 * <p>缓存有 6 小时空闲淘汰：会话本身不限轮次、也不限时长，但内存里的副本不能无限留着。
 * 淘汰后下一次访问会从库里重新加载——这也是持久化存在的意义。
 */
@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    /** 空闲多久后把内存副本丢掉（不影响库里的数据）。 */
    private static final Duration IDLE_TTL = Duration.ofHours(6);

    private final Map<String, Session> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAccess = new ConcurrentHashMap<>();
    private final SessionRepository repo;

    public SessionStore(SessionRepository repo) {
        this.repo = repo;
    }

    public Session create(String userId) {
        return create(userId, Session.DEFAULT_TENANT);
    }

    public Session create(String userId, String tenantId) {
        String id = "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Session session = new Session(id, userId == null || userId.isBlank() ? "local" : userId, tenantId);
        session.attachSink(repo::appendMessage);
        // 工具通过 _owner 拿到用户归属（health_record 查档案要用），
        // 不让模型填——它不知道，只会编。
        session.put("owner", session.scopedUserId());
        try {
            repo.create(session);
        } catch (RuntimeException e) {
            log.warn("create session failed id={}: {}", id, e.getMessage());
        }
        cache.put(id, session);
        lastAccess.put(id, Instant.now());
        evictIdle();
        return session;
    }

    /**
     * 按租户查找。找不到和租户不符都返回 empty——调用方统一按 404 处理，
     * 不区分"不存在"和"存在但无权"，避免用 404/403 的差异探测别人的会话是否存在。
     */
    public Optional<Session> find(String id, String tenantId) {
        return find(id).filter(s -> s.tenantId().equals(tenantId));
    }

    public Optional<Session> find(String id) {
        Session cached = cache.get(id);
        if (cached != null) {
            lastAccess.put(id, Instant.now());
            return Optional.of(cached);
        }
        Optional<Session> loaded = repo.load(id);
        loaded.ifPresent(s -> {
            s.attachSink(repo::appendMessage);
            s.put("owner", s.scopedUserId());
            cache.put(id, s);
            lastAccess.put(id, Instant.now());
        });
        return loaded;
    }

    /** 把"当前持有控制权的 agent"写回库。交接后立刻调用，下一轮从这里开始。 */
    public void updateActiveAgent(Session session) {
        try {
            repo.updateActiveAgent(session.id(), session.activeAgent());
        } catch (RuntimeException e) {
            log.warn("update active agent failed id={}: {}", session.id(), e.getMessage());
        }
    }

    /**
     * 某租户某用户的最近会话。
     *
     * <p>优先查库（重启后也能列出来）；库里没有时退回内存缓存——
     * 内存模式下 {@link SessionRepository} 拿不到会话对象，只能由缓存兜底。
     */
    public List<SessionSummary> list(String tenantId, String userId, int limit) {
        int n = Math.max(1, Math.min(limit, 50));
        List<SessionSummary> rows = repo.listByOwner(tenantId, userId, n);
        if (!rows.isEmpty()) {
            return rows;
        }
        return cache.values().stream()
                .filter(s -> s.tenantId().equals(tenantId) && s.userId().equals(userId))
                // 和 SQL 口径一致：没有消息的会话不是一次对话
                .filter(s -> s.turnCount() > 0)
                .sorted(Comparator.comparing(
                        (Session s) -> lastAccess.getOrDefault(s.id(), Instant.EPOCH)).reversed())
                .limit(n)
                .map(s -> SessionSummary.of(s.id(), s.turnCount(), firstUserText(s), ""))
                .toList();
    }

    private static String firstUserText(Session s) {
        return s.messages().stream()
                .filter(m -> m.role() == Message.Role.USER)
                .findFirst()
                .map(Message::content)
                .orElse("");
    }

    public int size() {
        return cache.size();
    }

    private void evictIdle() {
        Instant cutoff = Instant.now().minus(IDLE_TTL);
        lastAccess.forEach((id, seen) -> {
            if (seen.isBefore(cutoff) && lastAccess.remove(id, seen)) {
                cache.remove(id);
            }
        });
    }
}
