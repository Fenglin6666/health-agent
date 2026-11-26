package com.health.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.health.agent.kernel.api.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把模型返回的 JSON 转成 {@link Decision}。
 *
 * <p>三条原则：
 * <ol>
 *   <li>不解析自由文本——只认 JSON，格式不对就重试或降级</li>
 *   <li>字段缺失不抛异常，用安全默认值</li>
 *   <li>无法识别时降级为 Answer，保证链路不断。<b>协议标记除外</b>——它不是回答，
 *       是没走通的工具调用，必须重生成</li>
 * </ol>
 */
public final class DecisionParser {

    private static final Logger log = LoggerFactory.getLogger(DecisionParser.class);

    private final ObjectMapper mapper;

    public DecisionParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Decision parse(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            // 空响应不等于"该报错"。实测：模型看过工具结果后，json_object 模式下偶尔
            // 什么都不返回（它想直接说话，却被格式约束住）。这时正确动作是让内核
            // 用流式重新生成一次回答，而不是把一句"抱歉"甩给用户。
            // null 在 Answer 里的语义就是"该回答了，但正文还没生成"。
            return new Decision.Answer(null);
        }

        // 协议标记不是回答，是没走通的工具调用。重发同一个 prompt 大概率还吐标记，
        // 所以不在这里重试，直接交回内核走流式重生成——那条路径不带工具清单。
        if (ProtocolMarkup.present(rawContent)) {
            log.warn("protocol markup leaked into decision content, length={}", rawContent.length());
            return new Decision.Answer(null);
        }

        String json = extractJson(rawContent);
        try {
            JsonNode node = mapper.readTree(json);
            String type = node.path("decision").asText("").toUpperCase();

            return switch (type) {
                case "TOOL" -> new Decision.ToolCall(
                        node.path("tool").asText(),
                        toMap(node.path("args")));
                case "ROUTE" -> new Decision.Route(
                        node.path("target").asText(),
                        node.path("intent").asText(""),
                        node.path("confidence").asDouble(0.0),
                        node.path("context_digest").asText(""),
                        toMap(node.path("params")),
                        toIntents(node.path("remaining")));
                case "HANDOFF" -> new Decision.Handoff(
                        node.path("target").asText(""),
                        node.path("reason").asText(""));
                case "CLARIFY" -> new Decision.Clarify(
                        node.path("question").asText("能再说得具体一点吗？"),
                        options(node.path("options")));
                // 不带 text 的 ANSWER 表示"该回答了，但正文由内核流式生成"。
                // 不能退回 rawContent——那会把整段 JSON 当成回答发给用户。
                case "ANSWER" -> {
                    String text = node.path("text").asText("");
                    yield new Decision.Answer(text.isBlank() ? null : text);
                }
                default -> new Decision.Answer(rawContent);
            };
        } catch (Exception e) {
            // 格式不符合约定：当作纯文本回答，链路不断
            return new Decision.Answer(rawContent);
        }
    }

    /** 容忍 ```json 包裹和前后多余文字。 */
    private static String extractJson(String raw) {
        String s = raw.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int end = s.indexOf("```", start + 1);
            if (start > 0 && end > start) {
                return s.substring(start + 1, end).trim();
            }
        }
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        return (l >= 0 && r > l) ? s.substring(l, r + 1) : s;
    }

    /**
     * 追问候选：最多 4 条，去空、去超长。
     *
     * <p>模型偶尔会把整段解释塞进候选，那种卡片点下去等于替用户说了一句话，
     * 所以超过 20 字直接丢弃——宁可少给一个选项，也不要给一个不能用的。
     */
    private List<String> options(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            String t = n.asText("").trim();
            if (t.isEmpty() || t.length() > 20) {
                continue;
            }
            out.add(t);
            if (out.size() == 4) {
                break;
            }
        }
        return out;
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> out = new HashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class)));
        return out;
    }

    /**
     * 解析剩余诉求。容忍三种写法：对象数组、字符串数组、单个对象——
     * 模型在 remaining 上不如 target 稳定，格式放宽比让它重试划算。
     */
    private java.util.List<Decision.Intent> toIntents(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return java.util.List.of();
        }
        java.util.List<Decision.Intent> out = new java.util.ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                Decision.Intent it = one(n);
                if (it != null) out.add(it);
            }
        } else {
            Decision.Intent it = one(node);
            if (it != null) out.add(it);
        }
        return out.size() > 2 ? out.subList(0, 2) : out;
    }

    private Decision.Intent one(JsonNode n) {
        if (n == null) return null;
        if (n.isTextual()) {
            String s = n.asText().trim();
            return s.isEmpty() ? null : new Decision.Intent("", s);
        }
        String target = n.path("target").asText("").trim();
        String intent = n.path("intent").asText(n.path("reason").asText("")).trim();
        if (target.isEmpty() && intent.isEmpty()) return null;
        return new Decision.Intent(target, intent);
    }
}
