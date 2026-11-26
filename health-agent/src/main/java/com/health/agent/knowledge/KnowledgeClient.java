package com.health.agent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识服务客户端。全系统唯一允许发 HTTP 到 Python 服务的出口。
 *
 * <p>降级策略：服务不可用时返回空结果，不抛异常。知识检索失败不该让整个对话崩掉——
 * 模型会在没有依据的情况下作答，Safety 会标注"无出处"。
 */
@Component
public class KnowledgeClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public KnowledgeClient(ObjectMapper mapper,
                           @Value("${knowledge.base-url:http://localhost:8001}") String baseUrl) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
    }

    public List<Hit> search(String query, int topK) {
        try {
            String url = "%s/search?q=%s&top_k=%d".formatted(
                    baseUrl, URLEncoder.encode(query, StandardCharsets.UTF_8), topK);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    // 6 秒：远程 embedding 让检索从 80ms 变成 300-800ms（实测）。
                    // 原来 800ms 的超时会造成"随机无出处"——检索超时被当成没查到，
                    // 而模型会据此说"我没有可靠资料"。超时值必须覆盖真实 P99。
                    .timeout(Duration.ofMillis(6000))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("knowledge search http {}", response.statusCode());
                return List.of();
            }
            JsonNode root = mapper.readTree(response.body());
            List<Hit> hits = new ArrayList<>();
            for (JsonNode node : root.path("hits")) {
                JsonNode otcNode = node.path("otc");
                hits.add(new Hit(
                        node.path("title").asText(""),
                        node.path("snippet").asText(""),
                        node.path("source").asText(""),
                        // null = 非药品或未标注。调用方必须按"不是 OTC"处理（fail-safe）
                        otcNode.isBoolean() ? otcNode.asBoolean() : null,
                        node.path("score").asDouble(0)
                ));
            }
            return hits;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("knowledge search unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * @param otc True=非处方药，False=处方药，null=非药品或未标注。
     *            这个字段来自知识库，不是模型判断——"某成分在中国是不是 OTC"
     *            连同一成分不同剂型都不一样，靠模型记忆必错。
     */
    public record Hit(String title, String snippet, String source, Boolean otc, double score) {}

    /**
     * 药品结构化字段。查不到时返回 empty——调用方必须如实说"没有资料"，不能编。
     */
    public record Drug(String name, String title, String source, boolean otc, String indications,
                       String dosage, String contraindications, String adverseReactions,
                       String interactions, String duration, String stopSigns) {}

    public Optional<Drug> drug(String name) {
        try {
            String url = "%s/drug?name=%s".formatted(
                    baseUrl, URLEncoder.encode(name, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(6000))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("drug http {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(response.body());
            if (!root.path("found").asBoolean(false)) {
                return Optional.empty();
            }
            return Optional.of(new Drug(
                    root.path("name").asText(""),
                    root.path("title").asText(""),
                    root.path("source").asText(""),
                    root.path("otc").asBoolean(false),
                    root.path("indications").asText(""),
                    root.path("adult_dosage").asText(""),
                    root.path("contraindications").asText(""),
                    root.path("adverse_reactions").asText(""),
                    root.path("interactions").asText(""),
                    root.path("duration").asText(""),
                    root.path("stop_signs").asText("")));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("drug lookup unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
