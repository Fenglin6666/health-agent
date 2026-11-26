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
 * 病种 → 国内强势医院参考。
 *
 * <p>和 {@link DepartmentIndex} 的分工：那个回答"挂哪个科"，这个回答"哪个方向看得好"。
 * 前者是分诊问题，后者是选择问题，用户问"去哪家最好"时只答前者是不够的。
 *
 * <p><b>它是参考不是排名</b>。数据在 yaml 里，按公开常识整理，会过时。所以输出层必须带
 * "非官方排名、以医院官方为准"的限定语——把参考说成权威榜单，是拿用户的选择权换我们的确定性。
 */
@Component
public class ReferralIndex {

    public record Referral(String condition, List<String> hospitals) {}

    private final List<Map<String, Object>> items = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public ReferralIndex() {
        try (InputStream in = new ClassPathResource("configs/knowledge/referral.yaml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Object list = root == null ? null : root.get("items");
            if (list instanceof List<?> l) {
                for (Object o : l) {
                    items.add((Map<String, Object>) o);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to load configs/knowledge/referral.yaml", e);
        }
    }

    /** 按症状/病名关键词匹配。命中第一个就返回——同时命中多个时先给的通常是更具体的。 */
    public Optional<Referral> match(String symptom) {
        if (symptom == null || symptom.isBlank()) {
            return Optional.empty();
        }
        for (Map<String, Object> item : items) {
            Object kws = item.get("keywords");
            if (!(kws instanceof List<?> list)) {
                continue;
            }
            for (Object k : list) {
                if (symptom.contains(String.valueOf(k))) {
                    return Optional.of(new Referral(
                            String.valueOf(item.get("condition")),
                            ((List<?>) item.get("hospitals")).stream().map(String::valueOf).toList()));
                }
            }
        }
        return Optional.empty();
    }
}
