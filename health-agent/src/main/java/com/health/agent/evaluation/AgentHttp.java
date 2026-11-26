package com.health.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评测用的服务客户端。
 *
 * <p>评测必须<b>对运行中的服务发真实请求</b>，不能进程内直调——否则测的是类，不是系统。
 * 这条约束的代价是需要一套 HTTP 协议细节（建会话、读完 SSE、取 timeline），
 * 提出来单独放，免得每加一层评测就抄一遍。
 *
 * <p>两个容易踩的坑，这里一次性处理掉：
 * <ol>
 *   <li><b>SSE 必须读完</b>。读一半就关，服务端 {@code turn_end} 不写，timeline 永远停在 running，
 *       评测会看到一堆"卡住"的假失败。</li>
 *   <li><b>timeline 取最后一轮</b>。会话是多轮的，取错了会把上一轮的结果当成本轮。</li>
 * </ol>
 */
final class AgentHttp {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 一轮的完整观测：经过谁、怎么结束、说了什么、分了几段。 */
    record TurnDetail(
            List<String> agents,
            String status,
            String answer,
            int assistantSegments,
            List<String> eventTypes
    ) {
        boolean clarify() {
            return "clarify".equals(status);
        }
    }

    String createSession(String baseUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/sessions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":\"eval\"}"))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body()).path("session_id").asText();
    }

    /** 发一条消息并等这一轮彻底结束（读完 SSE）。 */
    void send(String baseUrl, String sid, String text) throws Exception {
        String body = mapper.writeValueAsString(Map.of("text", text));
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/v1/sessions/" + sid + "/messages"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = resp.body()) {
            in.readAllBytes();
        }
    }

    TurnDetail fetch(String baseUrl, String sid) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/v1/sessions/" + sid + "/timeline"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode turns = mapper.readTree(resp.body()).path("turns");
        if (!turns.isArray() || turns.isEmpty()) {
            return new TurnDetail(List.of(), "", "", 0, List.of());
        }
        JsonNode last = turns.get(turns.size() - 1);

        List<String> agents = new ArrayList<>();
        last.path("agents").forEach(n -> agents.add(n.asText()));

        StringBuilder answer = new StringBuilder();
        int segments = 0;
        for (JsonNode m : last.path("messages")) {
            if ("ASSISTANT".equals(m.path("role").asText())) {
                answer.append(m.path("content").asText());
                segments++;
            }
        }

        List<String> events = new ArrayList<>();
        last.path("events").forEach(e -> events.add(e.path("type").asText()));

        return new TurnDetail(agents, last.path("status").asText(), answer.toString(), segments, events);
    }

    /** 新建会话 → 发一句 → 取结果，最常用的组合。 */
    TurnDetail ask(String baseUrl, String text) throws Exception {
        String sid = createSession(baseUrl);
        send(baseUrl, sid, text);
        return fetch(baseUrl, sid);
    }
}
