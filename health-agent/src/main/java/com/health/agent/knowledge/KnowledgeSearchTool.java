package com.health.agent.knowledge;

import com.health.agent.kernel.infra.Tool;
import com.health.agent.kernel.infra.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识检索工具。Consult 等 agent 通过它查资料，而不是自己编。
 */
@Component
public class KnowledgeSearchTool implements Tool {

    private final KnowledgeClient client;

    public KnowledgeSearchTool(KnowledgeClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "knowledge_search";
    }

    @Override
    public String description() {
        return "检索医学知识库（药品说明书、临床指南、科普），返回带出处的片段。回答任何涉及医学事实的问题前都应先调用。";
    }

    @Override
    public String paramsSchema() {
        return """
                {"type":"object",
                 "properties":{
                   "query":{"type":"string","description":"检索词，用医学术语，不要用口语"},
                   "top_k":{"type":"integer","description":"返回条数，默认 3，最多 5"}
                 },
                 "required":["query"]}
                """;
    }

    @Override
    public String check(Map<String, Object> args) {
        Object query = args.get("query");
        if (query == null || String.valueOf(query).isBlank()) {
            return "query 不能为空";
        }
        return null;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        long t0 = System.currentTimeMillis();
        String query = String.valueOf(args.get("query"));
        int topK = args.get("top_k") instanceof Number n ? Math.min(5, Math.max(1, n.intValue())) : 3;

        List<KnowledgeClient.Hit> hits = client.search(query, topK);
        long elapsed = System.currentTimeMillis() - t0;

        if (hits.isEmpty()) {
            return ToolResult.ok(name(), "（未检索到相关资料，请如实说明没有依据，不要编造）", elapsed);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            sb.append("[").append(i + 1).append("] ").append(h.title()).append(drugLabel(h.otc())).append('\n');
            sb.append(h.snippet()).append('\n');
            if (!h.source().isBlank()) {
                sb.append("出处：").append(h.source()).append('\n');
            }
            sb.append('\n');
        }
        return ToolResult.ok(name(), sb.toString().trim(), elapsed);
    }

    /**
     * 把处方属性渲染给模型看。
     *
     * <p>这一行是整个"能不能推荐"的判据来源。不写的话模型会凭记忆判断某个药是不是 OTC，
     * 而它对"中国境内某成分某剂型是否非处方"的记忆极不可靠（同成分不同剂型可能一个 Rx 一个 OTC）。
     * 未标注的一律按处方药处理——宁可少推荐，不能多推荐。
     */
    private static String drugLabel(Boolean otc) {
        if (otc == null) {
            return "";
        }
        return otc ? " ｜非处方药（OTC，可推荐具体品种）"
                : " ｜处方药（Rx，不可推荐品种与剂量，只能说明说明书内容）";
    }
}
