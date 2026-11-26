package com.health.agent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.health.agent.kernel.infra.Tool;
import com.health.agent.kernel.infra.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索（药品 Agent 专用）。
 *
 * <p><b>它存在的理由</b>：知识库是快照。药监局发新说明书、药品召回、集采换规格，
 * 这些都不会自动进库。用户问"这个药是不是被召回了""国内现在还有这个剂型吗"，
 * 本地库答不了，只能联网。
 *
 * <p><b>硬约束：必须带出处</b>。输出里每条都附 URL，没有 URL 的结果直接丢弃——
 * 一条查不到来源的药品信息，比没有信息更危险。模型看到没有 URL 的结果也不会引用。
 *
 * <p><b>没配 key 时的行为</b>：明确告诉模型"联网不可用，不要凭记忆补"。
 * 不静默返回空——空结果会被模型当成"网上也查不到"，而真相是我们没连上网。
 *
 * <p>接口契约按 Tavily 的格式实现（POST /search，body 带 api_key/query，
 * 返回 results[].title/url/content）。换供应商只需要改 {@code ha.search.endpoint} 和这里的解析。
 */
@Component
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final ObjectMapper mapper;
    private final String endpoint;
    private final String key;
    private final HttpClient http;

    public WebSearchTool(ObjectMapper mapper,
                         @Value("${ha.search.endpoint:https://api.tavily.com/search}") String endpoint,
                         @Value("${ha.search.key:}") String key) {
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.key = key;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        log.info("web search ready: keyPresent={}", key != null && !key.isBlank());
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "联网搜索药品相关信息（新说明书、召回、集采、上市情况）。每条结果都带来源链接。"
                + "只在本地药品库查不到、或需要确认「现在/国内」的时效信息时用。"
                + "返回结果里没有链接的一律不要引用。";
    }

    @Override
    public String paramsSchema() {
        return """
                {"type":"object",
                 "properties":{
                   "query":{"type":"string","description":"搜索词，尽量具体，如「布洛芬 说明书 修订 2026」"}
                 },
                 "required":["query"]}
                """;
    }

    @Override
    public String check(Map<String, Object> args) {
        Object q = args.get("query");
        return q == null || String.valueOf(q).isBlank() ? "query 不能为空" : null;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        long t0 = System.currentTimeMillis();
        String query = String.valueOf(args.get("query")).trim();

        if (key == null || key.isBlank()) {
            return ToolResult.ok(name(),
                    "（联网搜索未配置，查不到实时信息。这一点要如实告诉用户，"
                            + "只能给知识库里已有的内容，不要凭记忆补药品的时效信息。）",
                    System.currentTimeMillis() - t0);
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("api_key", key);
            body.put("query", query);
            body.put("max_results", 5);
            body.put("search_depth", "basic");

            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("web search http {}: {}", resp.statusCode(), abbreviate(resp.body()));
                return ToolResult.ok(name(), "（联网搜索暂时不可用，按本地知识回答并说明这一点）",
                        System.currentTimeMillis() - t0);
            }
            return ToolResult.ok(name(), render(resp.body()), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("web search failed: {}", e.toString());
            return ToolResult.ok(name(), "（联网搜索失败：" + e.getClass().getSimpleName()
                    + "。按本地知识回答并说明这一点）", System.currentTimeMillis() - t0);
        }
    }

    private String render(String raw) throws Exception {
        JsonNode root = mapper.readTree(raw);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return "（没有搜到结果）";
        }
        StringBuilder sb = new StringBuilder("【联网结果】\n");
        int n = 0;
        for (JsonNode r : results) {
            String url = r.path("url").asText("");
            // 没有链接的结果直接丢：查不到出处的药品信息不该出现在回答里
            if (url.isBlank()) {
                continue;
            }
            n++;
            sb.append(n).append(". ").append(r.path("title").asText("(无标题)")).append('\n');
            String content = r.path("content").asText("");
            if (!content.isBlank()) {
                sb.append("   ").append(abbreviate(content)).append('\n');
            }
            sb.append("   来源：").append(url).append('\n');
            if (n >= 4) {
                break;
            }
        }
        if (n == 0) {
            return "（搜到了结果但没有一条带来源链接，按无结果处理）";
        }
        sb.append("（引用时请带上来源链接，不要改写来源里的数字）");
        return sb.toString().trim();
    }

    private static String abbreviate(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }
}
