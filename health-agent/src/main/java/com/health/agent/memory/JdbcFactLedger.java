package com.health.agent.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MySQL 版账本。
 *
 * <p>写入是"账本 + 投影"两张表，放在同一个事务里：只写账本，当前值就查不到；
 * 只写投影，历史就断了。两张表必须同生共死。
 *
 * <p>投影表可以随时从账本重建（见 {@code rebuildProjection}）——所以它丢了不算数据丢失，
 * 而账本丢了就是真的丢了。这也是为什么账本表不该有 UPDATE 权限。
 */
public class JdbcFactLedger implements FactLedger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcFactLedger(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Entry append(String owner, FactType type, String key, String value,
                        double confidence, String evidence, String recordedBy) {
        return tx.execute(status -> {
            Optional<Value> previous = current(owner, type, key);

            KeyHolder keys = new GeneratedKeyHolder();
            PreparedStatementCreator insert = con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO fact_ledger
                            (owner, fact_type, fact_key, fact_value, confidence, evidence, recorded_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, owner);
                ps.setString(2, type.name());
                ps.setString(3, key);
                ps.setString(4, value);
                ps.setDouble(5, confidence);
                ps.setString(6, evidence == null ? "" : truncate(evidence, 1000));
                ps.setString(7, recordedBy == null ? "librarian" : recordedBy);
                return ps;
            };
            jdbc.update(insert, keys);
            long id = keys.getKey() == null ? 0L : keys.getKey().longValue();

            // 投影用 upsert：MySQL 的 ON DUPLICATE KEY UPDATE 在这里比"先查再写"少一次往返，
            // 也避免并发下两个写入都以为自己是第一条。
            jdbc.update("""
                            INSERT INTO fact_current (owner, fact_key, fact_type, fact_value, ledger_id)
                            VALUES (?, ?, ?, ?, ?)
                            ON DUPLICATE KEY UPDATE
                                fact_type = VALUES(fact_type),
                                fact_value = VALUES(fact_value),
                                ledger_id = VALUES(ledger_id)
                            """,
                    owner, key, type.name(), value, id);

            return new Entry(id, owner, type, key, value, confidence, evidence,
                    previous.map(Value::version).orElse(null), Instant.now(), recordedBy);
        });
    }

    @Override
    public Optional<Value> current(String owner, FactType type, String key) {
        List<Value> rows = jdbc.query("""
                        SELECT fact_type, fact_key, fact_value, ledger_id, updated_at
                        FROM fact_current WHERE owner = ? AND fact_key = ?
                        """,
                (rs, i) -> new Value(
                        FactType.valueOf(rs.getString("fact_type")),
                        rs.getString("fact_key"),
                        rs.getString("fact_value"),
                        rs.getLong("ledger_id"),
                        rs.getTimestamp("updated_at").toInstant()),
                owner, key);
        return rows.stream().findFirst();
    }

    @Override
    public List<Entry> history(String owner, FactType type, String key) {
        return jdbc.query("""
                        SELECT id, owner, fact_type, fact_key, fact_value, confidence,
                               evidence, recorded_by, created_at
                        FROM fact_ledger
                        WHERE owner = ? AND fact_type = ? AND fact_key = ?
                        ORDER BY id
                        """,
                (rs, i) -> new Entry(
                        rs.getLong("id"),
                        rs.getString("owner"),
                        FactType.valueOf(rs.getString("fact_type")),
                        rs.getString("fact_key"),
                        rs.getString("fact_value"),
                        rs.getDouble("confidence"),
                        rs.getString("evidence"),
                        null,
                        toInstant(rs.getTimestamp("created_at")),
                        rs.getString("recorded_by")),
                owner, type.name(), key);
    }

    @Override
    public Map<FactType, List<Value>> snapshot(String owner) {
        Map<FactType, List<Value>> out = new EnumMap<>(FactType.class);
        jdbc.query("""
                        SELECT fact_type, fact_key, fact_value, ledger_id, updated_at
                        FROM fact_current WHERE owner = ? ORDER BY fact_type, fact_key
                        """,
                rs -> {
                    FactType type = FactType.valueOf(rs.getString("fact_type"));
                    out.computeIfAbsent(type, k -> new ArrayList<>()).add(new Value(
                            type,
                            rs.getString("fact_key"),
                            rs.getString("fact_value"),
                            rs.getLong("ledger_id"),
                            toInstant(rs.getTimestamp("updated_at"))));
                },
                owner);
        return out;
    }

    /** 从账本重建投影。投影表损坏或换实现时用，不丢任何事实。 */
    public int rebuildProjection() {
        Integer rows = tx.execute(status -> {
            jdbc.update("DELETE FROM fact_current");
            return jdbc.update("""
                    INSERT INTO fact_current (owner, fact_key, fact_type, fact_value, ledger_id)
                    SELECT l.owner, l.fact_key, l.fact_type, l.fact_value, l.id
                    FROM fact_ledger l
                    JOIN (SELECT owner, fact_key, MAX(id) AS max_id
                          FROM fact_ledger GROUP BY owner, fact_key) m
                      ON l.id = m.max_id
                    """);
        });
        return rows == null ? 0 : rows;
    }

    public long ledgerSize() {
        return size();
    }

    @Override
    public int clear(String userId) {
        // 先删投影再删账本：反过来的话，中途失败会留下"有投影没账本"的孤儿行，
        // 而投影是读路径，孤儿行会一直出现在画像里。
        jdbc.update("DELETE FROM fact_current WHERE owner = ?", userId);
        return jdbc.update("DELETE FROM fact_ledger WHERE owner = ?", userId);
    }

    @Override
    public long size() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM fact_ledger", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<Stat> stats(String owner) {
        // 用 MAX(id) 自连接取"最近一条证据"，而不是 GROUP_CONCAT：
        // GROUP_CONCAT 受 group_concat_max_len 限制（默认 1024 字节），证据一长就被静默截断，
        // 而且分隔符要自己挑一个证据里不会出现的字符——这类隐含假设迟早出事。
        return jdbc.query("""
                        SELECT l.fact_type, l.fact_key, s.records, s.first_seen, s.last_seen, l.evidence
                        FROM fact_ledger l
                        JOIN (
                            SELECT fact_type, fact_key, COUNT(*) AS records,
                                   MIN(created_at) AS first_seen, MAX(created_at) AS last_seen,
                                   MAX(id) AS max_id
                            FROM fact_ledger
                            WHERE owner = ?
                            GROUP BY fact_type, fact_key
                        ) s ON l.id = s.max_id
                        ORDER BY l.fact_type, l.fact_key
                        """,
                (rs, i) -> new Stat(
                        FactType.valueOf(rs.getString("fact_type")),
                        rs.getString("fact_key"),
                        rs.getInt("records"),
                        toInstant(rs.getTimestamp("first_seen")),
                        toInstant(rs.getTimestamp("last_seen")),
                        rs.getString("evidence") == null ? "" : rs.getString("evidence")),
                owner);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? Instant.now() : ts.toInstant();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 账本 + 投影的一致性检查，dreaming 定期跑。 */
    public Map<String, String> verify() {
        Map<String, String> drift = new LinkedHashMap<>();
        jdbc.query("""
                        SELECT c.owner, c.fact_key, c.fact_value, l.fact_value AS latest
                        FROM fact_current c
                        JOIN fact_ledger l ON l.id = (
                            SELECT MAX(id) FROM fact_ledger
                            WHERE owner = c.owner AND fact_key = c.fact_key
                        )
                        """,
                rs -> {
                    String projected = rs.getString("fact_value");
                    String latest = rs.getString("latest");
                    if (!projected.equals(latest)) {
                        drift.put(rs.getString("owner") + "|" + rs.getString("fact_key"),
                                "projected=" + projected + " latest=" + latest);
                    }
                });
        return drift;
    }
}
