package com.health.agent.knowledge;

import com.health.agent.kernel.infra.Tool;
import com.health.agent.kernel.infra.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 药品说明书查询。
 *
 * <p>和 `knowledge_search` 的分工：那个返回"最相关的几段文字"，这个返回**字段**。
 * 模型问"布洛芬的禁忌是什么"，它需要的是禁忌那一栏，不是一篇要自己读的段落。
 *
 * <p>输出里第一行就是处方属性。这不是装饰——它是模型判断"能不能推荐"的唯一依据。
 * 把 otc 埋在文本中间，模型会漏看，然后推荐一个处方药。
 */
@Component
public class DrugInfoTool implements Tool {

    private final KnowledgeClient client;

    public DrugInfoTool(KnowledgeClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "drug_info";
    }

    @Override
    public String description() {
        return "查药品说明书的客观字段：适应症、成人用法用量、禁忌、不良反应、相互作用、疗程、停药指征。"
                + "返回第一行标明是处方药还是非处方药（OTC）——只有 OTC 才能推荐具体品种。"
                + "用户提到任何具体药名时都应该先调用它。";
    }

    @Override
    public String paramsSchema() {
        return """
                {"type":"object",
                 "properties":{
                   "name":{"type":"string","description":"药品通用名，不要带剂型和商品名，例如「布洛芬」而不是「芬必得缓释胶囊」"}
                 },
                 "required":["name"]}
                """;
    }

    @Override
    public String check(Map<String, Object> args) {
        Object name = args.get("name");
        if (name == null || String.valueOf(name).isBlank()) {
            return "name 不能为空";
        }
        return null;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String name = String.valueOf(args.get("name")).trim();
        long t0 = System.currentTimeMillis();
        Optional<KnowledgeClient.Drug> found = client.drug(name);
        long elapsed = System.currentTimeMillis() - t0;

        if (found.isEmpty()) {
            return ToolResult.ok(name(),
                    "（知识库里没有「" + name + "」的说明书条目。如实告诉用户没有可靠资料，不要凭记忆补。）",
                    elapsed);
        }

        KnowledgeClient.Drug d = found.get();
        StringBuilder sb = new StringBuilder();
        sb.append("【处方属性】").append(d.otc() ? "非处方药（OTC，可以推荐具体品种）" : "处方药（不可推荐品种与剂量）")
                .append('\n');
        sb.append("【通用名】").append(d.name()).append('\n');
        append(sb, "适应症", d.indications());
        append(sb, "成人用法", d.dosage());
        append(sb, "禁忌", d.contraindications());
        append(sb, "不良反应", d.adverseReactions());
        append(sb, "相互作用", d.interactions());
        append(sb, "疗程", d.duration());
        append(sb, "停药指征", d.stopSigns());
        if (!d.source().isBlank()) {
            sb.append("【出处】").append(d.source());
        }
        return ToolResult.ok(name(), sb.toString(), elapsed);
    }

    /**
     * 只在有内容时输出一行。
     *
     * <p>空字段不能输出成"【疗程】"后面跟空白——模型会以为说明书里写了疗程但被截断了，
     * 转而自己编一个。真实说明书本来就不单列疗程（openFDA 的 OTC 标签把它写在 Warnings 里），
     * 缺就是缺，不输出比输出空的好。
     */
    private static void append(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("【").append(label).append("】").append(value).append('\n');
        }
    }
}
