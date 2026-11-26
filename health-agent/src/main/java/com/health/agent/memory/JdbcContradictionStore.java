package com.health.agent.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** MySQL 版矛盾存储。 */
public class JdbcContradictionStore implements ContradictionStore {

    private final JdbcTemplate jdbc;

    public JdbcContradictionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Contradiction save(Contradiction c) {
        KeyHolder keys = new GeneratedKeyHolder();
        PreparedStatementCreator insert = con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO contradiction
                        (owner, fact_type, fact_key, kind, severity, old_value, new_value, detail, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.owner());
            ps.setString(2, c.type().name());
            ps.setString(3, c.key());
            ps.setString(4, c.kind().name());
            ps.setString(5, c.severity().name());
            ps.setString(6, truncate(c.oldValue(), 500));
            ps.setString(7, truncate(c.newValue(), 500));
            ps.setString(8, truncate(c.detail(), 1000));
            ps.setString(9, c.status().name());
            return ps;
        };
        jdbc.update(insert, keys);
        long id = keys.getKey() == null ? 0L : keys.getKey().longValue();
        return c.withId(id);
    }

    @Override
    public List<Contradiction> open(String owner) {
        return query(owner, "AND status = 'OPEN'", 50);
    }

    @Override
    public List<Contradiction> list(String owner, int limit) {
        return query(owner, "", limit);
    }

    private List<Contradiction> query(String owner, String extraWhere, int limit) {
        return jdbc.query("""
                        SELECT id, owner, fact_type, fact_key, kind, severity, old_value, new_value,
                               detail, status, created_at
                        FROM contradiction
                        WHERE owner = ? %s
                        ORDER BY id DESC
                        LIMIT ?
                        """.formatted(extraWhere),
                (rs, i) -> new Contradiction(
                        rs.getLong("id"),
                        rs.getString("owner"),
                        FactType.valueOf(rs.getString("fact_type")),
                        rs.getString("fact_key"),
                        Contradiction.Kind.valueOf(rs.getString("kind")),
                        Contradiction.Severity.valueOf(rs.getString("severity")),
                        rs.getString("old_value"),
                        rs.getString("new_value"),
                        rs.getString("detail"),
                        Contradiction.Status.valueOf(rs.getString("status")),
                        toInstant(rs.getTimestamp("created_at"))),
                owner, limit);
    }

    @Override
    public boolean resolve(long id, Contradiction.Status status) {
        return jdbc.update("""
                UPDATE contradiction SET status = ?, resolved_at = CURRENT_TIMESTAMP(3)
                WHERE id = ?
                """, status.name(), id) > 0;
    }

    @Override
    public int clear(String owner) {
        return jdbc.update("DELETE FROM contradiction WHERE owner = ?", owner);
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM contradiction", Long.class);
        return n == null ? 0 : n;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? Instant.now() : ts.toInstant();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
