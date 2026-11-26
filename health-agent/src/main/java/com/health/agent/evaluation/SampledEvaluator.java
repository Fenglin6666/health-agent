package com.health.agent.evaluation;

import com.health.agent.kernel.api.KernelResult;
import com.health.agent.kernel.domain.Session;
import com.health.agent.execute.ExecuteAgents;
import com.health.agent.routing.OffTopicFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 抽样评测：量两个数——<b>健康问题覆盖率</b>和<b>域外过滤率</b>。
 *
 * <p>刻意只跑 Manager，不跑完整链路。理由：
 * <ol>
 *   <li>这两个指标问的都是"这句话有没有被当成健康问题处理"，是路由层的判断；</li>
 *   <li>完整链路一条要 8-10 秒，50 条就是 8 分钟，跑不动也就没人会跑；</li>
 *   <li>把回答质量混进来，会让"路由错了"和"答得不好"变成同一个数字，无法定位。</li>
 * </ol>
 *
 * <p><b>不判断"路由到哪个 agent 才对"</b>。那需要给 5000 条数据逐条标 target，
 * 标出来的东西还随架构调整而失效。这里只问一个更稳的问题：
 * <b>它有没有被当成健康问题接住</b>。换架构、加 agent，这个口径都不变。
 */
@Component
public class SampledEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SampledEvaluator.class);

    /** 被当作健康问题接住的路由目标。加新 agent 时同步这里。 */
    private static final List<String> HEALTH_TARGETS =
            List.of("doctor", "pharma", "report", "care");

    /**
     * 场景 → 可接受的目标。用来量"路由到对的能力了吗"。
     *
     * <p>映射刻意<b>宽容</b>：一个场景列几个都算对。理由是这套 agent 的边界本来就有重叠
     * （"用药与药物安全"既可能该给 doctor 也可能该给 pharma），把映射收窄只会制造假失败，
     * 让指标变成"我有没有按自己想的拆"，而不是"系统有没有走错门"。
     *
     * <p>它的作用是<b>防回归</b>：改 prompt、加 agent 之后，如果某个场景的整体命中率掉了，
     * 说明路由被改坏了。它不是用来证明"新架构更好"的。
     */
    private static final Map<String, List<String>> SCENE_TARGETS;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("检查报告解读", List.of("report", "doctor"));
        m.put("就医地点与挂号", List.of("care", "doctor"));
        m.put("用药与药物安全", List.of("pharma", "doctor"));
        m.put("红旗与急症", List.of("doctor"));
        m.put("一般症状问诊", List.of("doctor"));
        m.put("信息不足与表达模糊", List.of("doctor"));
        m.put("慢病管理", List.of("doctor"));
        m.put("孕产与哺乳", List.of("doctor"));
        m.put("儿童与新生儿", List.of("doctor"));
        m.put("肿瘤相关", List.of("doctor"));
        m.put("术后与康复", List.of("doctor"));
        m.put("心理与情绪", List.of("doctor"));
        m.put("皮肤问题", List.of("doctor"));
        m.put("性与生殖健康", List.of("doctor"));
        m.put("越界、偏方与不当请求", List.of("doctor"));
        SCENE_TARGETS = Map.copyOf(m);
    }

    private static final List<String> DEFAULT_TARGETS = List.of("doctor");

    private final ExecuteAgents agents;
    private final OffTopicFilter preFilter;
    private final Path dataset;

    public SampledEvaluator(ExecuteAgents agents,
                            OffTopicFilter preFilter,
                            @Value("${ha-eval.dataset:docs/test-data/medical-agent-5000-questions.csv}")
                            String datasetPath) {
        this.agents = agents;
        this.preFilter = preFilter;
        this.dataset = Path.of(datasetPath);
    }

    public record Case(String id, String scene, String question, boolean crisis) {}

    public Map<String, Object> run(int n, long seed) {
        List<Case> sample = sample(n, seed);
        if (sample.isEmpty()) {
            return Map.of("error", "数据集读不到或为空：" + dataset.toAbsolutePath());
        }

        List<Map<String, Object>> healthFailures = new ArrayList<>();
        int covered = 0, blocked = 0, answered = 0, degraded = 0;
        int sceneHit = 0;
        Map<String, int[]> sceneStats = new LinkedHashMap<>();
        List<Long> latencies = new ArrayList<>();

        for (Case c : sample) {
            long t0 = System.currentTimeMillis();
            Outcome o = classify(c.question());
            latencies.add(System.currentTimeMillis() - t0);

            List<String> allowed = SCENE_TARGETS.getOrDefault(c.scene(), DEFAULT_TARGETS);
            boolean sceneOk = o.healthCovered() && allowed.contains(o.detail());
            int[] stat = sceneStats.computeIfAbsent(c.scene(), k -> new int[2]);
            stat[0]++;
            if (sceneOk) {
                stat[1]++;
                sceneHit++;
            }

            if (o.healthCovered()) {
                covered++;
            } else {
                switch (o.kind()) {
                    case "blocked" -> blocked++;
                    case "answered" -> answered++;
                    default -> degraded++;
                }
                healthFailures.add(Map.of(
                        "id", c.id(), "scene", c.scene(), "question", abbreviate(c.question()),
                        "actual", o.detail(), "reason", o.kind()));
            }
        }

        List<Map<String, Object>> sceneBreakdown = new ArrayList<>();
        sceneStats.forEach((scene, stat) -> sceneBreakdown.add(Map.of(
                "scene", scene, "total", stat[0], "hit", stat[1],
                "rate", pct(stat[1], stat[0]))));

        List<Map<String, Object>> offCases = loadOffTopic();
        List<Map<String, Object>> offFailures = new ArrayList<>();
        int offFiltered = 0, offLeaked = 0, mixedPassed = 0, mixedTotal = 0;

        for (Map<String, Object> c : offCases) {
            String text = String.valueOf(c.get("text"));
            String kind = String.valueOf(c.get("kind"));
            Outcome o = classify(text);

            if (kind.startsWith("mixed_")) {
                // 含健康词的混合请求：被接住才是对的，过滤掉算误杀
                mixedTotal++;
                if (o.healthCovered()) {
                    mixedPassed++;
                } else {
                    offFailures.add(Map.of("text", text, "kind", kind,
                            "actual", o.detail(), "reason", "混合请求被误杀"));
                }
                continue;
            }
            if (o.filtered()) {
                offFiltered++;
            } else {
                offLeaked++;
                offFailures.add(Map.of("text", text, "kind", kind,
                        "actual", o.detail(), "reason", "域外请求被放行"));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sample_size", sample.size());
        out.put("seed", seed);
        out.put("health", Map.of(
                "total", sample.size(),
                "covered", covered,
                "coverage", pct(covered, sample.size()),
                "blocked", blocked,
                "answered_off", answered,
                "degraded", degraded));
        out.put("offtopic", Map.of(
                "total", offFiltered + offLeaked,
                "filtered", offFiltered,
                "rate", pct(offFiltered, offFiltered + offLeaked),
                "leaked", offLeaked,
                "mixed_total", mixedTotal,
                "mixed_passed", mixedPassed));
        out.put("routing", Map.of(
                "hit", sceneHit,
                "total", sample.size(),
                "accuracy", pct(sceneHit, sample.size())));
        out.put("latency_ms", Map.of("p50", percentile(latencies, 50), "max", latencies.stream()
                .mapToLong(Long::longValue).max().orElse(0)));
        out.put("scene_breakdown", sceneBreakdown);
        out.put("health_failures", healthFailures);
        out.put("offtopic_failures", offFailures);
        return out;
    }

    // ------------------------------------------------------------------

    private record Outcome(String kind, String detail) {
        boolean healthCovered() {
            return "route".equals(kind);
        }

        boolean filtered() {
            return "blocked".equals(kind) || "answered".equals(kind);
        }
    }

    /** 跑一条问题：先过域外规则，再过 Manager。 */
    private Outcome classify(String question) {
        var blocked = preFilter.check(question);
        if (blocked.isPresent()) {
            return new Outcome("blocked", "prefilter:" + blocked.get().kind());
        }
        Session session = new Session("eval-" + Integer.toHexString(question.hashCode()), "eval");
        session.addUser(question);
        KernelResult r;
        try {
            r = agents.run("manager", session, "");
        } catch (Exception e) {
            log.warn("route failed: {}", e.toString());
            return new Outcome("degraded", "exception:" + e.getClass().getSimpleName());
        }
        return switch (r.kind()) {
            case ROUTE -> {
                String target = r.route() == null ? "" : r.route().target();
                yield HEALTH_TARGETS.contains(target)
                        ? new Outcome("route", target)
                        : new Outcome("degraded", "unknown_target:" + target);
            }
            // 评测只跑 Manager，理论上不会交接；真出现说明 Manager 配置被改坏了
            case HANDOFF -> new Outcome("degraded", "unexpected_handoff:"
                    + (r.handoff() == null ? "" : r.handoff().target()));
            case CLARIFY -> new Outcome("route", "clarify");
            case ANSWER -> new Outcome("answered", abbreviate(r.text()));
            case DEGRADED -> new Outcome("degraded", String.valueOf(r.text()));
        };
    }

    // ------------------------------------------------------------------

    List<Case> sample(int n, long seed) {
        List<Case> all = loadDataset();
        if (all.isEmpty()) {
            return List.of();
        }
        List<Case> copy = new ArrayList<>(all);
        Collections.shuffle(copy, new Random(seed));
        return copy.subList(0, Math.min(n, copy.size()));
    }

    private List<Case> loadDataset() {
        if (!Files.isReadable(dataset)) {
            log.warn("评测数据集不存在：{}", dataset.toAbsolutePath());
            return List.of();
        }
        List<Case> out = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(dataset, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return out;
            }
            List<String> header = parseCsv(lines.get(0));
            int iId = header.indexOf("id"), iScene = header.indexOf("scene");
            int iQ = header.indexOf("question"), iCrisis = header.indexOf("crisis");
            for (int i = 1; i < lines.size(); i++) {
                List<String> f = parseCsv(lines.get(i));
                if (f.size() <= iQ) {
                    continue;
                }
                out.add(new Case(
                        iId >= 0 && iId < f.size() ? f.get(iId) : String.valueOf(i),
                        iScene >= 0 && iScene < f.size() ? f.get(iScene) : "",
                        f.get(iQ),
                        iCrisis >= 0 && iCrisis < f.size() && "1".equals(f.get(iCrisis))));
            }
        } catch (IOException e) {
            log.warn("读数据集失败：{}", e.toString());
        }
        return out;
    }

    /** RFC4180 最小实现：处理引号包裹与双引号转义。问句里逗号很多，不能简单 split。 */
    static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadOffTopic() {
        try (InputStream in = new org.springframework.core.io.ClassPathResource(
                "configs/evaluation/off_topic_cases.yaml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Object cases = root == null ? null : root.get("cases");
            return cases instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        } catch (Exception e) {
            log.warn("域外用例加载失败：{}", e.toString());
            return List.of();
        }
    }

    private static double pct(int a, int b) {
        return b == 0 ? 0 : Math.round(a * 10000.0 / b) / 100.0;
    }

    private static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, sorted.size() * p / 100));
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= 60 ? t : t.substring(0, 60) + "…";
    }
}
