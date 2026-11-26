package com.health.agent.kernel.app;

import com.health.agent.kernel.api.AgentConfig;
import com.health.agent.kernel.api.Decision;
import com.health.agent.kernel.api.KernelResult;
import com.health.agent.kernel.api.RunBudget;
import com.health.agent.kernel.domain.Session;
import com.health.agent.kernel.infra.KernelListener;
import com.health.agent.kernel.infra.LlmClient;
import com.health.agent.kernel.infra.Tool;
import com.health.agent.kernel.infra.ToolRegistry;
import com.health.agent.kernel.infra.ToolResult;
import com.health.agent.kernel.infra.Tracer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Agent 内核：一个循环，四个出口。
 *
 * <p>刻意保持"哑"——不含任何医疗知识、prompt、业务判断。所有差异都通过
 * {@link AgentConfig} 注入。这样加一个能力不需要改内核。
 *
 * <p>循环体五步：组装上下文 → 调模型 → 记录 trace → 分发决策 → 观察结果。
 * 严格把"调模型"和"调工具"分成两个阶段，中间留出校验和记录的位置。
 */
public class AgentKernel {

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final Tracer tracer;
    private final int historyWindow;

    public AgentKernel(LlmClient llm, ToolRegistry tools, Tracer tracer) {
        this(llm, tools, tracer, 20);
    }

    public AgentKernel(LlmClient llm, ToolRegistry tools, Tracer tracer, int historyWindow) {
        this.llm = llm;
        this.tools = tools;
        this.tracer = tracer;
        this.historyWindow = historyWindow;
    }

    public KernelResult run(AgentConfig cfg, Session session) {
        return run(cfg, session, token -> { });
    }

    /**
     * 带流式出口的运行。
     *
     * <p>决策仍是一次性 JSON（工具调用必须结构化），但<b>最终回答是流式的</b>：
     * 模型输出 {@code {"decision":"ANSWER"}} 表示"该回答了"，正文由内核再发一次请求流式生成。
     *
     * <p>为什么值得多这一次调用：原来答案塞在 JSON 的 text 字段里，模型必须把整段话生成完、
     * 再被解析、再一次性发出去——用户 4.8 秒什么都看不到。拆开之后，第一个字约 2.5 秒就到。
     * 多出来的成本只是"决策那次请求再发一遍上下文"，输入 token 很便宜，延迟却差一倍。
     */
    public KernelResult run(AgentConfig cfg, Session session, Consumer<String> onToken) {
        return run(cfg, session, onToken,
                RunBudget.of(cfg.maxSteps(), cfg.deadlineMs()), KernelListener.NOOP);
    }

    /**
     * 带预算与步骤回调的运行。
     *
     * <p>预算是<b>整轮对话</b>的，不是这个 agent 的：调用方扣掉已花的步数再传进来。
     * 允许 agent 交接之后，如果每个 agent 都拿满自己的步数，A→B→C 的总步数就是三者之和，
     * 延迟和成本会乘三，还能互相踢皮球踢到超时。
     *
     * <p>监听器只负责"喊一声"，内核不认识存储层——落库、推送由实现方决定。
     */
    public KernelResult run(AgentConfig cfg, Session session, Consumer<String> onToken,
                            RunBudget budget, KernelListener listener) {
        long start = System.currentTimeMillis();
        int limit = Math.min(cfg.maxSteps(), Math.max(0, budget.maxSteps()));

        for (int step = 0; step < limit; step++) {
            if (budget.expired()) {
                return KernelResult.degraded("timeout", step, elapsed(start));
            }

            LlmClient.LlmRequest request = assemble(cfg, session);
            Decision decision = llm.decide(request);

            long elapsed = elapsed(start);
            tracer.record(cfg.name(), step, decision, elapsed, estimateTokens(request));
            listener.onStep(cfg.name(), step, decision, elapsed);

            switch (decision) {
                case Decision.ToolCall call -> executeTool(call, session, cfg.name(), listener);
                case Decision.Route route -> {
                    return KernelResult.route(route, step + 1, elapsed);
                }
                case Decision.Handoff handoff -> {
                    return KernelResult.handoff(handoff, step + 1, elapsed);
                }
                case Decision.Clarify clarify -> {
                    return KernelResult.clarify(clarify, step + 1, elapsed);
                }
                case Decision.Answer answer -> {
                    if (answer.text() == null || answer.text().isBlank()) {
                        // 模型说"该回答了"但没给正文——正文流式生成
                        String text = streamAnswer(cfg, session, onToken);
                        return KernelResult.answerStreamed(text, step + 1, elapsed(start));
                    }
                    return KernelResult.answer(answer, step + 1, elapsed);
                }
            }
        }

        // 超步降级：不再循环，用已有信息给"当前能确定的部分"
        return KernelResult.degraded("max_steps_reached", limit, elapsed(start));
    }

    /**
     * 流式生成最终回答。
     *
     * <p>不带工具定义：这一步只负责把话说出来，再给工具只会让它继续绕。
     */
    private String streamAnswer(AgentConfig cfg, Session session, Consumer<String> onToken) {
        List<com.health.agent.kernel.domain.Message> messages =
                new ArrayList<>(session.recent(historyWindow));
        messages.add(com.health.agent.kernel.domain.Message.user(
                "现在给出最终回答。直接输出给用户看的正文，不要 JSON，不要解释你在做什么。"));

        // 必须带上当轮图片：决策那步看得到图，生成回答这步看不到，就会出现
        // "路由判断正确、正文却说没收到内容"——模型不是撒谎，是这两次请求不是同一个上下文。
        LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                cfg.model(), cfg.systemPrompt(), messages, List.of(), 0.2, 1024, session.turnImage());

        StringBuilder sb = new StringBuilder();
        llm.generateStream(request, delta -> {
            sb.append(delta);
            onToken.accept(delta);
        });

        // 流式偶尔一个 token 都不返回（实测约 1/4 的概率，工具调用之后更容易出现）。
        // 这时用户看到的是空白，比报错更糟——**空响应必须兜底**，不能当成"模型无话可说"。
        if (sb.toString().isBlank()) {
            String fallback = llm.generate(request);
            if (fallback != null && !fallback.isBlank()) {
                onToken.accept(fallback);
                return fallback;
            }
        }
        return sb.toString();
    }

    /** 组装上下文。工具列表按 agent 配置裁剪，不给它用不上的工具。 */
    private LlmClient.LlmRequest assemble(AgentConfig cfg, Session session) {
        List<ToolRegistry.ToolSpec> specs = cfg.toolNames().isEmpty()
                ? List.of()
                : cfg.toolNames().stream()
                        .map(tools::find)
                        .flatMap(Optional::stream)
                        .map(t -> new ToolRegistry.ToolSpec(t.name(), t.description(), t.paramsSchema()))
                        .toList();

        return new LlmClient.LlmRequest(
                cfg.model(),
                cfg.systemPrompt(),
                session.recent(historyWindow),
                specs,
                0.2,
                1024,
                session.turnImage()
        );
    }

    /**
     * 工具执行：先校验后执行，异常不往外抛。
     *
     * <p>失败也写回历史，让模型下一轮自己纠错——比硬编码重试灵活。
     */
    private void executeTool(Decision.ToolCall call, Session session, String agent, KernelListener listener) {
        // 两道校验都在执行之前：工具名存在、参数合法。失败也写回历史让模型自纠。
        Optional<Tool> found = tools.find(call.tool());
        if (found.isEmpty()) {
            ToolResult error = ToolResult.error(call.tool(), "unknown tool: " + call.tool()
                    + "（可用：" + String.join(", ", tools.names()) + "）");
            session.observe(error);
            listener.onToolEnd(agent, error);
            return;
        }
        Tool tool = found.get();

        String checkError = tool.check(call.args());
        if (checkError != null) {
            ToolResult error = ToolResult.error(tool.name(), "参数不合法：" + checkError);
            session.observe(error);
            listener.onToolEnd(agent, error);
            return;
        }

        listener.onToolStart(agent, tool.name(), call.args());

        long t0 = System.currentTimeMillis();
        try {
            // 会话上下文以 "_" 前缀注入工具参数：坐标、用户画像这类信息模型不可能知道，
            // 让它填只会逼它编。加前缀避免覆盖模型自己给的同名参数。
            Map<String, Object> merged = new HashMap<>(call.args());
            session.attributes().forEach((k, v) -> merged.putIfAbsent("_" + k, v));

            ToolResult result = tool.execute(merged);
            session.observe(result);
            listener.onToolEnd(agent, result);
        } catch (RuntimeException e) {
            ToolResult error = ToolResult.error(tool.name(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            session.observe(error);
            listener.onToolEnd(agent, error);
        } finally {
            tracer.record(tool.name(), -1, new Decision.ToolCall(tool.name(), call.args()),
                    System.currentTimeMillis() - t0, 0);
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /** 粗略估算：中文约 1.5 字符/token，英文约 4 字符/token，取中间值。 */
    private static int estimateTokens(LlmClient.LlmRequest request) {
        int chars = request.systemPrompt() == null ? 0 : request.systemPrompt().length();
        chars += request.messages().stream().mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum();
        return Math.max(1, chars / 2);
    }
}
