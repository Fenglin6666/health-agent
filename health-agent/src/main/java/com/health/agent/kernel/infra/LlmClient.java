package com.health.agent.kernel.infra;

import com.health.agent.kernel.api.Decision;
import com.health.agent.kernel.domain.Message;

import java.util.List;
import java.util.function.Consumer;

/**
 * 模型调用抽象。业务只依赖这个接口，实现放在 llm 包。
 *
 * <p>这是全系统唯一允许发请求到模型 API 的出口（另一个出口是 knowledge）。
 */
public interface LlmClient {

    /** 结构化决策：返回 ToolCall / Route / Clarify / Answer 之一。 */
    Decision decide(LlmRequest request);

    /** 纯文本生成（非流式，用于离线整理、评测）。 */
    String generate(LlmRequest request);

    /** 流式生成，逐 token 回调。 */
    void generateStream(LlmRequest request, Consumer<String> onToken);

    record LlmRequest(
            String model,
            String systemPrompt,
            List<Message> messages,
            List<ToolRegistry.ToolSpec> tools,
            double temperature,
            int maxTokens,
            /**
             * 当轮附带的图片（base64，不带 data: 前缀）。null 表示纯文本。
             *
             * <p><b>刻意不进历史</b>：图片只在当轮请求里出现一次，助手给出的解读结论会作为
             * 普通消息落库。下一轮模型看得到结论、看不到原图——重发原图会让 token 翻倍，
             * 而结论已经包含了这一轮需要的信息。
             */
            String imageBase64
    ) {
        public LlmRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        /** 纯文本请求。图片是少数情况，不该逼所有调用点都多传一个 null。 */
        public LlmRequest(String model, String systemPrompt, List<Message> messages,
                          List<ToolRegistry.ToolSpec> tools, double temperature, int maxTokens) {
            this(model, systemPrompt, messages, tools, temperature, maxTokens, null);
        }

        public static LlmRequest of(String model, String systemPrompt, List<Message> messages) {
            return new LlmRequest(model, systemPrompt, messages, List.of(), 0.2, 1024);
        }
    }
}
