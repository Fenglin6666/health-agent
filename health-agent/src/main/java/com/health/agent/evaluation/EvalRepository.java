package com.health.agent.evaluation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 评测跑批的读写。
 *
 * <p>只解决一件事：<b>这次比上次好还是差</b>。语料存在文件里，不走数据库——
 * 改语料应该走代码评审，而不是在库里改一行没人看得见。
 */
@Repository
public class EvalRepository {

    private final JdbcTemplate jdbc;

    public EvalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 开一次跑批，返回 runId。 */
    public long startRun(String setName, String snapshot, Long baselineId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO eval_run(set_name, snapshot, baseline_id) VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, setName);
            ps.setString(2, snapshot);
            if (baselineId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, baselineId);
            }
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void saveResult(long runId, String caseId, String expected, String actual,
                           boolean passed, String reason) {
        jdbc.update("INSERT INTO eval_result(run_id, case_id, expected, actual, passed, reason) "
                        + "VALUES (?,?,?,?,?,?)",
                runId, caseId, truncate(expected), truncate(actual), passed ? 1 : 0, truncate(reason));
    }

    public void finishRun(long runId, int total, int passed) {
        jdbc.update("UPDATE eval_run SET total=?, passed=?, finished_at=CURRENT_TIMESTAMP(3) WHERE id=?",
                total, passed, runId);
    }

    /** 同一语料集上一次跑批的 id，作为本次基线。 */
    public Long latestRun(String setName, long excludeRunId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM eval_run WHERE set_name=? AND id<>? AND finished_at IS NOT NULL "
                        + "ORDER BY id DESC LIMIT 1", Long.class, setName, excludeRunId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 与基线逐例对比。
     *
     * <p>只看"这次 96%"没有意义，要看"比上次多了哪 3 例失败"——所以 diff 是跑批的核心产出。
     */
    public Diff diff(long runId, long baselineId) {
        List<String> nowFailed = failedCases(runId);
        List<String> beforeFailed = failedCases(baselineId);
        List<String> regressed = nowFailed.stream().filter(c -> !beforeFailed.contains(c)).toList();
        List<String> fixed = beforeFailed.stream().filter(c -> !nowFailed.contains(c)).toList();
        return new Diff(regressed, fixed, nowFailed.size(), beforeFailed.size());
    }

    private List<String> failedCases(long runId) {
        return jdbc.queryForList(
                "SELECT case_id FROM eval_result WHERE run_id=? AND passed=0 ORDER BY case_id",
                String.class, runId);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    /** 新增失败 / 已修复 / 本次失败数 / 基线失败数。 */
    public record Diff(List<String> regressed, List<String> fixed, int nowFailed, int beforeFailed) {
        public boolean worse() {
            return !regressed.isEmpty();
        }
    }
}
