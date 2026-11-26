package com.health.agent.evaluation;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * L1 域过程评测。
 *
 * <p>三层评测各回答一个问题：
 * <ul>
 *   <li><b>L0 端到端</b>（{@link EvaluationRunner#runRoute}）：用户能不能拿到对的结果。</li>
 *   <li><b>L1 域过程</b>（本类）：某个域的中间行为对不对——该追问的追问了吗、该闭嘴的闭嘴了吗。</li>
 *   <li><b>L2 组件</b>（单测）：单个类算得对不对。</li>
 * </ul>
 *
 * <p>只有 L0 时会遇到"知道坏了，不知道哪坏了"：一条回答不理想，可能是路由错、可能是该追问没追问、
 * 也可能是答得太啰嗦。L1 把域内可判定的行为拆成一条条断言，让失败能定位到具体规则。
 *
 * <p><b>只做确定性断言，不做 LLM 互评。</b>L1 的价值是稳定可回归，
 * 引入模型判模型会把噪声带进门禁。判不了的（如"医学上对不对"）留给人工抽检，
 * 在域规格里标了"人工"的验收项不在这里假装自动。
 *
 * <p><b>未知检查项一律判失败。</b>写错一个 key 就静默通过，比没有评测更危险——
 * 它会让人以为这条规则在守着。
 */
@Component
public class DomainEvalRunner {

    /** 支持的域。加域时同步加语料文件与控制器白名单。 */
    public static final List<String> DOMAINS = List.of("sc", "ks", "ri", "nv");

    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?\\n]");

    private final AgentHttp agentHttp = new AgentHttp();
    private final EvalRepository evalRepo;

    public DomainEvalRunner(EvalRepository evalRepo) {
        this.evalRepo = evalRepo;
    }

    /**
     * 跑一个域的 L1 用例。
     *
     * @param baseUrl  运行中的服务地址
     * @param domain   sc / ks / ri / nv
     * @param baseline 对比基线。null = 自动找上一次；0 = 不对比（用于作废基线之后的首跑）
     */
    public EvaluationRunner.EvalReport run(String baseUrl, String domain, Long baseline) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> raw = loadCases(domain);
        String setName = "l1-" + domain;
        if (baseline == null) {
            baseline = evalRepo.latestRun(setName, -1L);
        } else if (baseline == 0) {
            baseline = null;
        }
        long runId = evalRepo.startRun(setName, "ts=" + java.time.Instant.now(), baseline);

        List<EvaluationRunner.CaseResult> results = new ArrayList<>();
        List<Long> durations = new ArrayList<>();
        int passed = 0;
        int degraded = 0;
        int clarify = 0;

        for (Map<String, Object> c : raw) {
            String id = String.valueOf(c.get("id"));
            String text = String.valueOf(c.get("text"));
            @SuppressWarnings("unchecked")
            Map<String, Object> expect = (Map<String, Object>) c.getOrDefault("expect", Map.of());

            long t0 = System.currentTimeMillis();
            AgentHttp.TurnDetail turn;
            String actual;
            boolean ok;
            String note;
            try {
                turn = agentHttp.ask(baseUrl, text);
                durations.add(System.currentTimeMillis() - t0);
                actual = turn.agents() + " / " + turn.status();
                List<String> failures = check(turn, expect);
                ok = failures.isEmpty();
                note = ok ? "" : String.join("；", failures);
            } catch (Exception e) {
                durations.add(System.currentTimeMillis() - t0);
                actual = "(error)";
                ok = false;
                note = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            if (ok) {
                passed++;
            }
            if (actual.contains("degraded")) {
                degraded++;
            }
            if (actual.contains("clarify")) {
                clarify++;
            }
            results.add(new EvaluationRunner.CaseResult(text, expect.toString(), actual, ok, note));
            evalRepo.saveResult(runId, id, expect.toString(), actual, ok, note);
        }

        int total = results.size();
        evalRepo.finishRun(runId, total, passed);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("run_id", runId);
        extra.put("domain", domain);
        extra.put("degraded", degraded);
        extra.put("clarify", clarify);
        extra.put("p50_ms", percentile(durations, 50));
        extra.put("p95_ms", percentile(durations, 95));
        if (baseline != null) {
            EvalRepository.Diff d = evalRepo.diff(runId, baseline);
            extra.put("baseline_id", baseline);
            extra.put("regressed", d.regressed());
            extra.put("fixed", d.fixed());
            extra.put("worse", d.worse());
        }
        return new EvaluationRunner.EvalReport("l1-" + domain, total, passed,
                ratio(passed, total), results, extra, System.currentTimeMillis() - start);
    }

    // ------------------------------------------------------------------
    // 断言
    // ------------------------------------------------------------------

    /** 返回失败原因列表；空表示全过。包级可见，便于单测覆盖断言语义。 */
    List<String> check(AgentHttp.TurnDetail t, Map<String, Object> expect) {
        List<String> fail = new ArrayList<>();
        String answer = t.answer() == null ? "" : t.answer();
        String first = firstSentence(answer);

        for (Map.Entry<String, Object> e : expect.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            switch (key) {
                case "agents_include" -> {
                    for (String a : strings(v)) {
                        if (!t.agents().contains(a)) {
                            fail.add("缺能力 " + a);
                        }
                    }
                }
                case "agents_include_any" -> {
                    // "至少覆盖其中一个"：三意图场景下内核只保留 2 条待办，
                    // 要求指定某一个会把合法的另一条路径判成失败。
                    List<String> any = strings(v);
                    if (any.stream().noneMatch(t.agents()::contains)) {
                        fail.add("这些能力一个都没出现：" + any);
                    }
                }
                case "agents_exclude" -> {
                    for (String a : strings(v)) {
                        if (t.agents().contains(a)) {
                            fail.add("不该出现 " + a);
                        }
                    }
                }
                case "status_in" -> {
                    if (!strings(v).contains(t.status())) {
                        fail.add("状态 " + t.status() + " 不在 " + strings(v));
                    }
                }
                case "first_sentence_any" -> {
                    List<String> words = strings(v);
                    if (words.stream().noneMatch(first::contains)) {
                        fail.add("首句无行动词（" + words + "）：" + abbreviate(first));
                    }
                }
                case "text_contains_any" -> {
                    List<String> words = strings(v);
                    if (words.stream().noneMatch(answer::contains)) {
                        fail.add("回答缺关键词（" + words + "）");
                    }
                }
                case "text_not_contains" -> {
                    for (String w : strings(v)) {
                        if (answer.contains(w)) {
                            fail.add("回答不该出现「" + w + "」");
                        }
                    }
                }
                case "text_not_regex" -> {
                    Pattern p = Pattern.compile(String.valueOf(v));
                    if (p.matcher(answer).find()) {
                        fail.add("命中禁止模式 " + v);
                    }
                }
                case "segments_gte" -> {
                    int min = ((Number) v).intValue();
                    if (t.assistantSegments() < min) {
                        fail.add("回答段数 " + t.assistantSegments() + " < " + min);
                    }
                }
                case "events_include" -> {
                    for (String ev : strings(v)) {
                        if (!t.eventTypes().contains(ev)) {
                            fail.add("缺事件 " + ev);
                        }
                    }
                }
                default -> fail.add("未知检查项 " + key);
            }
        }
        return fail;
    }

    private static String firstSentence(String text) {
        String s = text.strip();
        var m = SENTENCE_END.matcher(s);
        if (m.find()) {
            return s.substring(0, m.start());
        }
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(v));
    }

    private static String abbreviate(String s) {
        String t = s.strip();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }

    private static double ratio(int a, int b) {
        return b == 0 ? 0.0 : Math.round(a * 1000.0 / b) / 10.0;
    }

    private static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    // ------------------------------------------------------------------
    // 语料
    // ------------------------------------------------------------------

    /** 包级可见：语料校验单测要用同一套加载逻辑，不能各写一份。 */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> loadCases(String domain) {
        String path = "configs/evaluation/l1/l1-" + domain.toLowerCase(Locale.ROOT) + ".yaml";
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Object cases = root == null ? null : root.get("cases");
            if (!(cases instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalStateException("语料为空: " + path);
            }
            return (List<Map<String, Object>>) list;
        } catch (Exception e) {
            throw new IllegalStateException("L1 语料加载失败: " + path, e);
        }
    }
}
