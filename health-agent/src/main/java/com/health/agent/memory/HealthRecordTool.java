package com.health.agent.memory;

import com.health.agent.kernel.infra.Tool;
import com.health.agent.kernel.infra.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 健康档案查询：这个用户**自己**的历史记录。
 *
 * <p>和 `knowledge_search` 的根本区别：那个查的是"医学上怎么说"，这个查的是
 * "**这个人**之前说过什么"。同样是"我能不能吃布洛芬"，有没有"胃溃疡史"是两个答案。
 *
 * <p>用户归属从会话上下文注入（`_owner`），不从参数来——让模型填 owner 等于让它编。
 */
@Component
public class HealthRecordTool implements Tool {

    private final FactLedger ledger;
    private final ContradictionStore contradictions;

    public HealthRecordTool(FactLedger ledger, ContradictionStore contradictions) {
        this.ledger = ledger;
        this.contradictions = contradictions;
    }

    @Override
    public String name() {
        return "health_record";
    }

    @Override
    public String description() {
        return "查这个用户自己的健康档案：过敏史、长期用药、慢病、孕产状态、手术史，以及待确认的矛盾记录。"
                + "回答任何涉及用药安全、既往病史的问题前都应先查——同样的症状，有过敏史和没有是两个答案。"
                + "返回空表示这个用户还没有档案，不代表「没有病史」。";
    }

    @Override
    public String paramsSchema() {
        return """
                {"type":"object",
                 "properties":{
                   "category":{"type":"string",
                     "enum":["all","allergy","medication","chronic","pregnancy","surgery"],
                     "description":"要看哪一类，默认 all"}
                 },
                 "required":[]}
                """;
    }

    @Override
    public String check(Map<String, Object> args) {
        return null;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object owner = args.get("_owner");
        if (owner == null || String.valueOf(owner).isBlank()) {
            return ToolResult.ok(name(), "（无法确定用户身份，查不到档案）", 0);
        }
        String ownerId = String.valueOf(owner);
        String category = String.valueOf(args.getOrDefault("category", "all"));

        Map<FactType, List<FactLedger.Value>> snapshot = ledger.snapshot(ownerId);
        StringBuilder sb = new StringBuilder();

        if (snapshot.isEmpty()) {
            sb.append("（这个用户还没有健康档案。可以顺带问一句有没有过敏史或长期在吃的药，")
                    .append("这些信息对后面判断用药安全很有用。）");
            return ToolResult.ok(name(), sb.toString(), 0);
        }

        append(sb, snapshot, FactType.ALLERGY, "过敏史", category);
        append(sb, snapshot, FactType.MEDICATION, "长期用药", category);
        append(sb, snapshot, FactType.CHRONIC, "慢病", category);
        append(sb, snapshot, FactType.PREGNANCY, "孕产状态", category);
        append(sb, snapshot, FactType.SURGERY, "手术史", category);

        List<Contradiction> open = contradictions.open(ownerId);
        if (!open.isEmpty()) {
            sb.append("\n【待确认】以下记录前后不一致，回答时不要当成事实，必要时向用户核实：\n");
            for (Contradiction c : open) {
                sb.append("- ").append(c.key()).append("：此前「").append(c.oldValue())
                        .append("」，本次「").append(c.newValue()).append("」\n");
            }
        }
        return ToolResult.ok(name(), sb.toString().trim(), 0);
    }

    private static void append(StringBuilder sb, Map<FactType, List<FactLedger.Value>> snapshot,
                               FactType type, String label, String category) {
        if (!"all".equalsIgnoreCase(category) && !type.name().equalsIgnoreCase(category)) {
            return;
        }
        List<FactLedger.Value> values = snapshot.get(type);
        if (values == null || values.isEmpty()) {
            return;
        }
        sb.append("【").append(label).append("】");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append("；");
            }
            FactLedger.Value v = values.get(i);
            sb.append(v.key());
            if (!v.value().equals(v.key())) {
                sb.append("（").append(v.value()).append("）");
            }
        }
        sb.append('\n');
    }
}
