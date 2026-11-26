package com.health.agent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 断言语义的单测。
 *
 * <p>这层的意义在于：断言本身错了，评测会给出一堆看起来很像真问题的假失败，
 * 比没有评测更浪费人。所以每条断言的正例、反例、边界都要有。
 */
class DomainEvalRunnerTest {

    private final DomainEvalRunner runner = new DomainEvalRunner(null);

    private static AgentHttp.TurnDetail turn(List<String> agents, String status, String answer, int segments) {
        return new AgentHttp.TurnDetail(agents, status, answer, segments, List.of("turn_start", "answer", "turn_end"));
    }

    @Test
    void unknown_check_key_must_fail_loudly() {
        // 写错 key 静默通过，等于这条规则从没生效——ArchitectureTest 就踩过这个坑
        var fails = runner.check(turn(List.of("doctor"), "answer", "你好", 1),
                Map.of("agents_includ", List.of("doctor")));
        assertThat(fails).hasSize(1);
        assertThat(fails.get(0)).contains("未知检查项");
    }

    @Test
    void agents_include_requires_all() {
        var t = turn(List.of("manager", "doctor"), "answer", "x", 1);
        assertThat(runner.check(t, Map.of("agents_include", List.of("doctor")))).isEmpty();
        assertThat(runner.check(t, Map.of("agents_include", List.of("doctor", "pharma"))))
                .anyMatch(s -> s.contains("pharma"));
    }

    @Test
    void agents_include_any_needs_at_least_one() {
        var t = turn(List.of("doctor", "care"), "answer", "x", 1);
        assertThat(runner.check(t, Map.of("agents_include_any", List.of("pharma", "care")))).isEmpty();
        assertThat(runner.check(t, Map.of("agents_include_any", List.of("pharma", "report"))))
                .anyMatch(s -> s.contains("一个都没出现"));
    }

    @Test
    void agents_exclude_rejects_presence() {
        var t = turn(List.of("doctor", "care"), "answer", "x", 1);
        assertThat(runner.check(t, Map.of("agents_exclude", List.of("care"))))
                .anyMatch(s -> s.contains("care"));
    }

    @Test
    void first_sentence_only_looks_at_first_sentence() {
        // 行动词出现在第二句不算数：用户先看到的不是它
        var t = turn(List.of("doctor"), "answer", "先别紧张。建议立刻去急诊。", 1);
        assertThat(runner.check(t, Map.of("first_sentence_any", List.of("急诊"))))
                .anyMatch(s -> s.contains("首句无行动词"));
        assertThat(runner.check(t, Map.of("first_sentence_any", List.of("别紧张")))).isEmpty();
    }

    @Test
    void text_not_regex_catches_dosage() {
        var t = turn(List.of("pharma"), "answer", "一次吃 5ml，一天两次。", 1);
        assertThat(runner.check(t, Map.of("text_not_regex", "\\d+\\s*(ml|毫升|mg|毫克)")))
                .anyMatch(s -> s.contains("禁止模式"));
        var safe = turn(List.of("pharma"), "answer", "按说明书或医嘱使用。", 1);
        assertThat(runner.check(safe, Map.of("text_not_regex", "\\d+\\s*(ml|毫升|mg|毫克)"))).isEmpty();
    }

    @Test
    void segments_gte_counts_assistant_messages() {
        assertThat(runner.check(turn(List.of("pharma", "care"), "answer", "x", 2),
                Map.of("segments_gte", 2))).isEmpty();
        assertThat(runner.check(turn(List.of("pharma"), "answer", "x", 1),
                Map.of("segments_gte", 2))).anyMatch(s -> s.contains("段数"));
    }

    @Test
    void status_in_rejects_unexpected_status() {
        var t = turn(List.of("care"), "clarify", "你在哪个城市？", 1);
        assertThat(runner.check(t, Map.of("status_in", List.of("clarify")))).isEmpty();
        assertThat(runner.check(t, Map.of("status_in", List.of("answer"))))
                .anyMatch(s -> s.contains("状态"));
    }

    @Test
    void empty_expect_passes() {
        // 只要求有响应、不判具体能力的用例
        assertThat(runner.check(turn(List.of("doctor"), "answer", "x", 1), Map.of())).isEmpty();
    }
}
