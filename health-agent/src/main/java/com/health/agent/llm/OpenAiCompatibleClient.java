package com.health.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.health.agent.kernel.api.Decision;
import com.health.agent.kernel.domain.Message;
import com.health.agent.kernel.infra.LlmClient;
import com.health.agent.kernel.infra.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * OpenAI 兼容协议的模型接入实现。
 *
 * <p>为什么自写而不是用框架：这层本质是 HTTP + SSE + JSON 校验，一千行以内能覆盖；
 * 框架的抽象会挡住 token 计数和延迟归因，而这两个是本项目的核心指标。
 *
 * <p>兼容范围：任何提供 /chat/completions 的服务（OpenAI、DeepSeek、通义、智谱、
 * vLLM、Ollama 的 OpenAI 兼容端点）。
 */
@Component
public class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    /**
     * 决策输出契约。所有 agent 共用一套 JSON 格式，内核只认这四种 decision。
     *
     * <p>放在内核层而不是各 prompt 里，理由：一是 json_object 模式要求 prompt 含 "json"，
     * 二是避免新增 agent 时忘记写格式导致解析失败。
     */
    /**
     * 决策契约。**按有没有工具分两版**——这一点很关键。
     *
     * <p>有工具的 agent 回答要展开成长文，如果塞进 JSON 的 text 字段，模型必须把整段话
     * 生成完、再被解析、再一次性发出去，首 token 会慢一倍。所以它们的 ANSWER 只发一个信号，
     * 正文由内核另起一次流式请求生成。
     *
     * <p>两版契约不能合并成"一处说带 text、另一处说别带"——模型会挑先看到的那条执行。
     * 指令自相矛盾时，模型不会追问，只会照它更熟悉的那条办。
     */
    private static String jsonContract(boolean hasTools) {
        String answerLine = hasTools
                ? "- 可以回答了：只输出 {\"decision\":\"ANSWER\"}，**不要带 text 字段**——正文会单独生成并流式输出\n"
                : "- 直接回答：{\"decision\":\"ANSWER\",\"text\":\"给用户看的完整回答\"}\n";
        return """
                【输出格式】严格输出一个 JSON 对象，不要 markdown 代码块，不要任何解释文字。
                - 调用工具：{"decision":"TOOL","tool":"工具名","args":{...}}
                - 需要反问：{"decision":"CLARIFY","question":"一句追问","options":["候选1","候选2"]}
                  options 是给用户点的候选回答：2-4 条，每条不超过 12 字，写成用户会直接说出口的短句
                  （如"不到一天""一到三天""超过一周"）。拿不准用户会怎么答时可以不写这个字段。
                """ + answerLine + """
                - 路由（仅 Manager）：{"decision":"ROUTE","target":"<目标名>","intent":"...","confidence":0.9,"context_digest":"...","params":{}}
                - 交接（执行 agent）：{"decision":"HANDOFF","target":"<目标名>","reason":"为什么交给对方"}
                  target 必须取系统提示里列出的目标之一，**不要自创、不要用简称**
                """;
    }

    /** 最后一条 user 消息。图片只挂它，避免历史里每条都带图。 */
    private static Message lastUser(LlmClient.LlmRequest request) {
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            Message m = request.messages().get(i);
            if (m.role() == Message.Role.USER) {
                return m;
            }
        }
        return null;
    }

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final LlmProperties props;
    private final DecisionParser parser;

    public OpenAiCompatibleClient(ObjectMapper mapper, LlmProperties props) {
        this.mapper = mapper;
        this.props = props;
        this.parser = new DecisionParser(mapper);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("llm client ready: baseUrl={} model={}/{} offline={} keyPresent={}",
                props.baseUrl(), props.modelManager(), props.modelExecute(),
                props.isOfflineEffective(), props.apiKey() != null && !props.apiKey().isBlank());
    }

    @Override
    public Decision decide(LlmRequest request) {
        if (offline()) {
            return offlineDecision(request);
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt <= props.maxRetries(); attempt++) {
            try {
                String content = chat(request, true);
                return parser.parse(content);
            } catch (RuntimeException e) {
                last = e;
                log.warn("decide failed attempt={} reason={}", attempt, e.getMessage());
            }
        }
        throw last == null ? new IllegalStateException("decide failed") : last;
    }

    @Override
    public String generate(LlmRequest request) {
        if (offline()) {
            return offlineText(request);
        }
        return chat(request, false);
    }

    @Override
    public void generateStream(LlmRequest request, Consumer<String> onToken) {
        HttpRequest httpRequest = buildRequest(request, false, true);
        try {
            HttpResponse<Stream<String>> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("llm stream http " + response.statusCode());
            }
            response.body()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).trim())
                    .takeWhile(data -> !data.equals("[DONE]"))
                    .forEach(data -> {
                        String delta = extractDelta(data);
                        if (delta != null && !delta.isEmpty()) {
                            onToken.accept(delta);
                        }
                    });
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("llm stream failed: " + e.getMessage(), e);
        }
    }

    private String chat(LlmRequest request, boolean jsonMode) {
        HttpRequest httpRequest = buildRequest(request, jsonMode, false);
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("llm http " + response.statusCode() + ": " + truncate(response.body()));
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                log.debug("llm usage prompt={} completion={} total={}",
                        usage.path("prompt_tokens").asInt(),
                        usage.path("completion_tokens").asInt(),
                        usage.path("total_tokens").asInt());
            }
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("llm call failed: " + e.getMessage(), e);
        }
    }

    private HttpRequest buildRequest(LlmRequest request, boolean jsonMode, boolean stream) {
        String model = request.model() == null || request.model().isBlank()
                ? props.modelExecute()
                : request.model();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        if (stream) {
            body.put("stream", true);
        }

        ArrayNode messages = body.putArray("messages");
        String systemPrompt = request.systemPrompt();
        if (jsonMode) {
            // json_object 模式要求 prompt 中出现 "json" 字样（DeepSeek/OpenAI 都会校验）。
            // 把输出契约收在内核层，避免每个 prompt 各写一遍、也避免写漏。
            systemPrompt = (systemPrompt == null || systemPrompt.isBlank() ? "" : systemPrompt + "\n\n")
                    + jsonContract(!request.tools().isEmpty());
        }
        // 工具清单渲染进 prompt，而不是走 API 原生 tools 参数：
        // 各家对 tool_calls / tool_call_id 的配对要求不一致（DeepSeek 会直接 422），
        // 而本项目的工具调用本来就是"应用层模拟"，没必要绑死在某家的协议上。
        if (!request.tools().isEmpty()) {
            StringBuilder sb = new StringBuilder(systemPrompt == null ? "" : systemPrompt);
            sb.append("\n\n【可用工具】\n");
            for (ToolRegistry.ToolSpec spec : request.tools()) {
                sb.append("- ").append(spec.name()).append("：").append(spec.description())
                        .append("\n  参数 schema：").append(spec.paramsSchema()).append('\n');
            }
            sb.append("调用方式：{\"decision\":\"TOOL\",\"tool\":\"工具名\",\"args\":{...}}");
            systemPrompt = sb.toString();
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.addObject().put("role", "system").put("content", systemPrompt);
        }
        for (Message m : request.messages()) {
            ObjectNode node = messages.addObject();
            // 工具结果作为普通 user 消息回填：不走 tool role，避免 tool_call_id 配对问题。
            // 前缀标出是工具输出，模型能区分，人也看得懂。
            if (m.role() == Message.Role.TOOL) {
                node.put("role", "user");
                node.put("content", "[工具 " + (m.name() == null ? "" : m.name()) + " 的输出]\n"
                        + (m.content() == null ? "" : m.content()));
                continue;
            }
            node.put("role", switch (m.role()) {
                case SYSTEM -> "system";
                case USER -> "user";
                case ASSISTANT -> "assistant";
                case TOOL -> "user";
            });
            // 当轮图片只挂最后一条 user 消息：历史里没有图，所以必然是这一条。
            // 多模态格式是 content 数组，纯文本仍用字符串——两者不能混，接口会拒。
            boolean withImage = request.imageBase64() != null && !request.imageBase64().isBlank()
                    && m.role() == Message.Role.USER
                    && m == lastUser(request);
            if (withImage) {
                ArrayNode parts = node.putArray("content");
                parts.addObject().put("type", "text")
                        .put("text", m.content() == null ? "" : m.content());
                parts.addObject().put("type", "image_url").putObject("image_url")
                        .put("url", "data:image/jpeg;base64," + request.imageBase64());
            } else {
                node.put("content", m.content() == null ? "" : m.content());
            }
        }

        if (jsonMode) {
            body.putObject("response_format").put("type", "json_object");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(props.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));

        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + props.apiKey());
        }
        // 走网关时用内网令牌，不走网关（直连）时为空
        if (props.internalToken() != null && !props.internalToken().isBlank()) {
            builder.header("X-Internal-Token", props.internalToken());
        }
        return builder.build();
    }

    private JsonNode parseSchema(String schema) {
        try {
            return mapper.readTree(schema);
        } catch (Exception e) {
            ObjectNode empty = mapper.createObjectNode();
            empty.put("type", "object");
            return empty;
        }
    }

    private String extractDelta(String data) {
        try {
            JsonNode node = mapper.readTree(data);
            return node.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 离线模式：未配置 LLM_API_KEY 时用固定逻辑跑通链路，方便直接体验。
    // 这不是降级容错，是开发期脚手架——配了 key 就不会走到这里。
    // ------------------------------------------------------------------

    private boolean offline() {
        return props.isOfflineEffective();
    }

    private Decision offlineDecision(LlmRequest request) {
        String prompt = request.systemPrompt() == null ? "" : request.systemPrompt();
        String lastUser = lastUserMessage(request);

        if (prompt.contains("路由器")) {
            return new Decision.Route("doctor", "symptom", 0.5,
                    "离线模式：默认路由到问诊", Map.of());
        }
        return new Decision.Answer(offlineText(request));
    }

    private String offlineText(LlmRequest request) {
        String lastUser = lastUserMessage(request);
        return "（离线模式）我收到了：" + lastUser
                + "\n\n配置 `LLM_API_KEY` 后，这里会变成完整的问诊回复。"
                + "\n\n离线模式下仍可验证：红旗中断、意图漏斗、Safety 审查这三条链路。";
    }

    private static String lastUserMessage(LlmRequest request) {
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            Message m = request.messages().get(i);
            if (m.role() == Message.Role.USER) {
                return m.content() == null ? "" : m.content();
            }
        }
        return "";
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
