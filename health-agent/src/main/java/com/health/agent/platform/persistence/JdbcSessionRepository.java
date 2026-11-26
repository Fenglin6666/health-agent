package com.health.agent.platform.persistence;

import com.health.agent.kernel.domain.Message;
import com.health.agent.kernel.domain.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MySQL 实现。
 *
 * <p>三个刻意的选择：
 * <ol>
 *   <li><b>消息写入失败不抛异常</b>，只记日志。用户正在等回答，一次 INSERT 失败不该让对话崩掉；
 *       但也不能静默，所以异常在这里被吞并记录，而不是在 domain 层。</li>
 *   <li><b>INSERT IGNORE + (session_id, seq) 唯一键</b>：重试不会插出重复对话。
 *       用自增 id 判重是不够的——重试会拿到新的 id。</li>
 *   <li><b>没有 UPDATE 消息的方法</b>：历史不可变。</li>
 * </ol>
 */
public class JdbcSessionRepository implements SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionRepository.class);

    private final JdbcTemplate jdbc;

    public JdbcSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(Session session) {
        jdbc.update("""
                        INSERT INTO session (id, tenant_id, user_id, active_agent)
                        VALUES (?, ?, ?, ?)
                        """,
                session.id(), session.tenantId(), session.userId(), session.activeAgent());
    }

    @Override
    public Optional<Session> load(String id) {
        List<SessionRow> rows = jdbc.query("""
                        SELECT id, tenant_id, user_id, active_agent
                        FROM session WHERE id = ?
                        """,
                (rs, i) -> new SessionRow(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("user_id"), rs.getString("active_agent")),
                id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        SessionRow row = rows.get(0);

        List<Message> history = jdbc.query("""
                        SELECT role, name, content
                        FROM message WHERE session_id = ? ORDER BY seq
                        """,
                (rs, i) -> new Message(
                        Message.Role.valueOf(rs.getString("role")),
                        rs.getString("content"),
                        rs.getString("name")),
                id);

        // 必须查 MAX(seq)，不能用 history.size()：中途写入失败会留空洞，
        // 用条数当序号会让下一次追加撞上已有行，被 INSERT IGNORE 静默丢掉。
        Integer maxSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM message WHERE session_id = ?", Integer.class, id);

        Session session = new Session(row.id(), row.userId(), row.tenantId());
        session.restore(history, maxSeq == null ? 0 : maxSeq);
        session.activeAgent(row.activeAgent());
        return Optional.of(session);
    }

    @Override
    public void appendMessage(String sessionId, int turn, int seq, Message message) {
        try {
            jdbc.update("""
                            INSERT IGNORE INTO message (session_id, turn, seq, role, name, content)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    sessionId, turn, seq, message.role().name(), message.name(), message.content());
        } catch (RuntimeException e) {
            // 不往上抛：对话继续，但日志里必须留痕，否则"历史丢了一段"永远查不出来
            log.warn("append message failed session={} seq={}: {}", sessionId, seq, e.getMessage());
        }
    }

    @Override
    public void updateActiveAgent(String sessionId, String agent) {
        try {
            jdbc.update("UPDATE session SET active_agent = ? WHERE id = ?", agent, sessionId);
        } catch (RuntimeException e) {
            // 不抛：这一轮还能正常回答，只是下次可能从错误的 agent 开始
            log.warn("update active_agent failed session={}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public List<SessionSummary> listByOwner(String tenantId, String userId, int limit) {
        return jdbc.query("""
                        SELECT s.id,
                               s.updated_at,
                               (SELECT COUNT(DISTINCT m.turn) FROM message m
                                 WHERE m.session_id = s.id) AS turns,
                               (SELECT m.content FROM message m
                                 WHERE m.session_id = s.id AND m.role = 'USER'
                                 ORDER BY m.turn ASC, m.seq ASC LIMIT 1) AS preview
                        FROM session s
                        WHERE s.tenant_id = ? AND s.user_id = ?
                          -- 没有任何消息的会话不进列表：它是"打开过页面"的痕迹，不是一次对话
                          AND EXISTS (SELECT 1 FROM message m2 WHERE m2.session_id = s.id)
                        ORDER BY s.updated_at DESC
                        LIMIT ?
                        """,
                (rs, i) -> SessionSummary.of(
                        rs.getString("id"),
                        rs.getInt("turns"),
                        rs.getString("preview"),
                        rs.getString("updated_at")),
                tenantId, userId, Math.max(1, Math.min(limit, 50)));
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM session", Long.class);
        return n == null ? 0 : n;
    }

    private record SessionRow(String id, String tenantId, String userId, String activeAgent) {}
}
