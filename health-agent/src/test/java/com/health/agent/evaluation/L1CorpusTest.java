package com.health.agent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 语料的加载期校验。
 *
 * <p>跑一次四域要六分钟。如果 YAML 里写错一个检查项名、或者正则少个转义，
 * 六个域全红，人还得逐条翻才知道是语料的问题。把校验提前到单测，
 * 一次编译就能发现。
 *
 * <p>校验的是<b>格式</b>，不是"期望对不对"。后者要靠跑批和人工复核。
 */
class L1CorpusTest {

    /** 与 DomainEvalRunner.check 的 switch 分支保持一致。加检查项时两处都要改。 */
    private static final Set<String> KNOWN_CHECKS = Set.of(
            "agents_include", "agents_include_any", "agents_exclude",
            "status_in", "first_sentence_any", "text_contains_any",
            "text_not_contains", "text_not_regex", "segments_gte", "events_include");

    private final DomainEvalRunner runner = new DomainEvalRunner(null);

    @Test
    void every_domain_loads_and_has_unique_ids() {
        for (String domain : DomainEvalRunner.DOMAINS) {
            List<Map<String, Object>> cases = runner.loadCases(domain);
            assertThat(cases).as("域 %s 用例数", domain).isNotEmpty();

            Set<String> ids = new HashSet<>();
            for (Map<String, Object> c : cases) {
                String id = String.valueOf(c.get("id"));
                assertThat(id).as("域 %s 有空 id", domain).isNotBlank().isNotEqualTo("null");
                assertThat(ids.add(id)).as("域 %s 的 id 重复：%s", domain, id).isTrue();
                assertThat(String.valueOf(c.get("text")))
                        .as("用例 %s 没有 text", id).isNotBlank().isNotEqualTo("null");
            }
        }
    }

    @Test
    void every_check_key_is_known() {
        for (String domain : DomainEvalRunner.DOMAINS) {
            for (Map<String, Object> c : runner.loadCases(domain)) {
                Object expect = c.get("expect");
                if (expect == null) {
                    continue;
                }
                assertThat(expect).as("用例 %s 的 expect 不是 map", c.get("id"))
                        .isInstanceOf(Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) expect;
                for (String key : map.keySet()) {
                    assertThat(KNOWN_CHECKS).as("用例 %s 有未知检查项 %s", c.get("id"), key)
                            .contains(key);
                }
            }
        }
    }

    @Test
    void every_regex_compiles() {
        for (String domain : DomainEvalRunner.DOMAINS) {
            for (Map<String, Object> c : runner.loadCases(domain)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> expect = (Map<String, Object>) c.getOrDefault("expect", Map.of());
                Object re = expect.get("text_not_regex");
                if (re != null) {
                    assertThat(Pattern.compile(String.valueOf(re)))
                            .as("用例 %s 的正则非法", c.get("id")).isNotNull();
                }
            }
        }
    }

    @Test
    void segments_gte_is_a_number() {
        for (String domain : DomainEvalRunner.DOMAINS) {
            for (Map<String, Object> c : runner.loadCases(domain)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> expect = (Map<String, Object>) c.getOrDefault("expect", Map.of());
                Object v = expect.get("segments_gte");
                if (v != null) {
                    assertThat(v).as("用例 %s 的 segments_gte 不是数字", c.get("id"))
                            .isInstanceOf(Number.class);
                }
            }
        }
    }
}
