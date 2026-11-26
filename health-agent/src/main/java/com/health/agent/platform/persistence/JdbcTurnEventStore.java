package com.health.agent.platform.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL 实现。(session_id, turn, seq) 唯一，重试不会写出重复事件。 */
public class JdbcTurnEventStore implements TurnEventStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcTurnEventStore.class);

    private final JdbcTemplate jdbc;

    public JdbcTurnEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(TurnEvent e) {
        try {
            jdbc.update("""
                            INSERT IGNORE INTO turn_event
                                (session_id, turn, seq, type, agent, detail, elapsed_ms)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    e.sessionId(), e.turn(), e.seq(), e.type(), e.agent(), e.detail(), e.elapsedMs());
        } catch (RuntimeException ex) {
            // 审计失败不中断对话，但必须留痕——否则"事件表缺了一段"永远没人发现
            log.warn("turn_event 写入失败 session={} turn={} seq={}: {}",
                    e.sessionId(), e.turn(), e.seq(), ex.getMessage());
        }
    }

    @Override
    public List<TurnEvent> list(String sessionId) {
        return jdbc.query("""
                        SELECT session_id, turn, seq, type, agent, detail, elapsed_ms
                        FROM turn_event WHERE session_id = ? ORDER BY turn, seq
                        """,
                (rs, i) -> new TurnEvent(
                        rs.getString("session_id"), rs.getInt("turn"), rs.getInt("seq"),
                        rs.getString("type"), rs.getString("agent"),
                        rs.getString("detail"), rs.getLong("elapsed_ms")),
                sessionId);
    }
}
