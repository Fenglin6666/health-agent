package com.health.agent.knowledge;

import com.health.agent.kernel.infra.Tool;
import com.health.agent.kernel.infra.ToolResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检验指标参考查询。
 *
 * <p><b>为什么需要这个工具</b>：报告解读最常被问的是"这个箭头什么意思"，
 * 而回答它需要三样模型记不准的东西——参考范围、偏高偏低各意味着什么、下一步查什么。
 * 让模型凭记忆说范围，它会给一个人均适用的"正常值"，而真实报告单上的范围是这家实验室的。
 *
 * <p><b>能力边界写死在输出里</b>：每次返回都带一句"以报告单上印的范围为准"。
 * 不是免责声明，是因为参考范围真的随仪器和检测方法变——这一句不说，用户会拿我们的数
 * 去质疑自己的报告单。
 *
 * <p>数据在 {@code configs/knowledge/lab_reference.yaml}，改指标不用改代码。
 */
@Component
public class LabReferenceTool implements Tool {

    private final List<Map<String, String>> items = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public LabReferenceTool() {
        try (InputStream in = new ClassPathResource("configs/knowledge/lab_reference.yaml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Object list = root == null ? null : root.get("items");
            if (list instanceof List<?> l) {
                for (Object o : l) {
                    Map<String, String> m = new LinkedHashMap<>();
                    ((Map<String, Object>) o).forEach((k, v) -> m.put(k, v == null ? "" : String.valueOf(v)));
                    items.add(m);
                }
            }
        } catch (Exception e) {
            // 读不到就起不来：带着空表跑，工具会一直回"没有这个指标"，没人会发现是配置丢了
            throw new IllegalStateException("failed to load configs/knowledge/lab_reference.yaml", e);
        }
    }

    @Override
    public String name() {
        return "lab_reference";
    }

    @Override
    public String description() {
        return "查检验指标的参考范围、偏高/偏低通常提示什么、以及下一步该查什么。"
                + "用户贴出化验单或问「这个箭头什么意思」时先调它，不要凭记忆报参考范围。"
                + "一次可以查多个指标，用逗号分隔。";
    }

    @Override
    public String paramsSchema() {
        return """
                {"type":"object",
                 "properties":{
                   "items":{"type":"string","description":"指标名，多个用逗号分隔，如「血红蛋白,血小板」"}
                 },
                 "required":["items"]}
                """;
    }

    @Override
    public String check(Map<String, Object> args) {
        Object v = args.get("items");
        return v == null || String.valueOf(v).isBlank() ? "items 不能为空" : null;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        long t0 = System.currentTimeMillis();
        String query = String.valueOf(args.get("items"));
        StringBuilder sb = new StringBuilder();

        int found = 0;
        List<String> missed = new ArrayList<>();
        for (String raw : query.split("[,，、;；\\s]+")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            Map<String, String> hit = lookup(name);
            if (hit == null) {
                missed.add(name);
                continue;
            }
            found++;
            sb.append("【").append(hit.get("name")).append("】");
            if (!hit.get("unit").isBlank()) {
                sb.append("单位 ").append(hit.get("unit"));
            }
            sb.append('\n');
            sb.append("  参考范围：").append(hit.get("range")).append('\n');
            sb.append("  偏高：").append(hit.get("high")).append('\n');
            sb.append("  偏低：").append(hit.get("low")).append('\n');
            sb.append("  下一步：").append(hit.get("next")).append('\n');
        }

        if (!missed.isEmpty()) {
            sb.append("（参考表里没有：").append(String.join("、", missed))
                    .append("。这些可以按医学常识解释，但不要编参考范围）\n");
        }
        if (found > 0) {
            sb.append("（参考范围随仪器和检测方法不同，请以报告单上印的范围为准）");
        }
        return ToolResult.ok(name(), sb.toString().trim(), System.currentTimeMillis() - t0);
    }

    /** 先精确匹配名称，再匹配别名，最后做包含匹配——用户很少写全称。 */
    private Map<String, String> lookup(String query) {
        for (Map<String, String> it : items) {
            if (it.get("name").equals(query)) {
                return it;
            }
        }
        for (Map<String, String> it : items) {
            if (it.get("aliases").contains(query)) {
                return it;
            }
        }
        for (Map<String, String> it : items) {
            if (it.get("name").contains(query) || query.contains(it.get("name"))) {
                return it;
            }
            for (String alias : it.get("aliases").split("[,\\[\\]\\s]+")) {
                if (!alias.isBlank() && (alias.equalsIgnoreCase(query) || query.toUpperCase().contains(alias.toUpperCase()))) {
                    return it;
                }
            }
        }
        return null;
    }
}
