package com.health.agent.platform.feedback;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC 实现。
 *
 * <p>用 {@code ON DUPLICATE KEY UPDATE} 而不是"先查再写"：后者在并发下会插出重复行，
 * 唯一键虽然有兜底，但异常会冒到接口层变成 500。一条 SQL 解决，别给并发留窗口。
 */
public class JdbcFeedbackRepository implements FeedbackRepository {

    private final JdbcTemplate jdbc;

    public JdbcFeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(Feedback f) {
        jdbc.update("""
                        INSERT INTO message_feedback(tenant_id, session_id, turn, agent, rating, reason, comment)
                        VALUES (?,?,?,?,?,?,?)
                        ON DUPLICATE KEY UPDATE rating=VALUES(rating), reason=VALUES(reason), comment=VALUES(comment)
                        """,
                f.tenantId(), f.sessionId(), f.turn(), f.agent(), f.rating(), f.reason(), f.comment());
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM message_feedback", Long.class);
        return n == null ? 0 : n;
    }
}
