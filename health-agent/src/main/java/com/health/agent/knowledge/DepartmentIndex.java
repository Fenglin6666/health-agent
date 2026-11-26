package com.health.agent.knowledge;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 症状 → 科室 的参考表。
 *
 * <p><b>它只给建议，不做决定。</b>输出里必须带"通常"两个字，模型可以推翻它——
 * 比如它判断这是急诊，就该直接说去急诊，而不是挂某个专科门诊。
 *
 * <p>为什么用规则表而不是让模型自己想：模型当然知道"头痛挂神经内科"，
 * 但推荐医院时需要**一个确定的科室名**去检索，让模型每次现编一个词，检索结果会飘。
 * 表的价值是稳定，不是聪明。
 */
@Component
public class DepartmentIndex {

    private final List<String> emergencySignals = new ArrayList<>();
    private final List<Dept> departments = new ArrayList<>();

    public DepartmentIndex() {
        try (InputStream in = new ClassPathResource("configs/knowledge/departments.yaml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            if (root != null) {
                addAll(root.get("emergency"), emergencySignals);
                if (root.get("departments") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            List<String> keywords = new ArrayList<>();
                            addAll(m.get("keywords"), keywords);
                            departments.add(new Dept(String.valueOf(m.get("name")), keywords));
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to load configs/knowledge/departments.yaml", e);
        }
    }

    /**
     * 按症状文本匹配科室。命中急诊信号时直接返回急诊科。
     *
     * <p>打分方式：命中关键词越多越靠前，同分取先出现的（表里越靠前的越常见）。
     */
    public Optional<String> suggest(String symptom) {
        if (symptom == null || symptom.isBlank()) {
            return Optional.empty();
        }
        String text = symptom.toLowerCase();
        if (emergencySignals.stream().anyMatch(text::contains)) {
            return Optional.of("急诊科");
        }

        String best = null;
        int bestScore = 0;
        for (Dept d : departments) {
            int score = (int) d.keywords().stream().filter(text::contains).count();
            if (score > bestScore) {
                bestScore = score;
                best = d.name();
            }
        }
        return Optional.ofNullable(best);
    }

    private static void addAll(Object node, List<String> target) {
        if (node instanceof List<?> list) {
            for (Object o : list) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) {
                    target.add(s);
                }
            }
        }
    }

    private record Dept(String name, List<String> keywords) {}
}
