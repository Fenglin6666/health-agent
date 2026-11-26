package com.health.agent.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.health.agent.kernel.api.Decision;
import com.health.agent.kernel.api.KernelResult;
import com.health.agent.kernel.api.RunBudget;
import com.health.agent.kernel.domain.Session;
import com.health.agent.kernel.infra.KernelListener;
import com.health.agent.kernel.infra.ToolResult;
import com.health.agent.memory.DigestBuilder;
import com.health.agent.platform.SessionStore;
import com.health.agent.platform.config.AgentProperties;
import com.health.agent.platform.persistence.TurnEvent;
import com.health.agent.platform.persistence.TurnEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 一轮对话的编排器：从入口 agent 开始，沿交接链一直跑到有人给出回答。
 *
 * <p><b>状态模型</b>（形状和 pi agent 一致，只有一处命名不同）：
 *
 * <pre>
 * session
 *  └─ turn            一次用户输入 → 一个最终结果（回答 / 追问 / 降级）
 *      ├─ turn_start
 *      ├─ agent_start ─ llm ─ tool_start ─ tool_end ─ llm ─ agent_end
 *      ├─ handoff      ← 关闭当前 span，开下一个
 *      ├─ agent_start ─ ... ─ agent_end
 *      └─ turn_end(status)
 * </pre>
 *
 * <p><b>和 pi 的差异（有意为之）</b>：pi 里 {@code agent_start/agent_end} 包住整次运行、
 * {@code turn_start/turn_end} 包住一次 LLM 迭代。这里反过来——因为允许交接之后，
 * 一轮里可能有多个 agent，<b>agent span 必须比 turn 更细</b>，否则"交接给了谁"无处安放。
 * 用户提的"一轮就是 agent_start/agent_end"在<b>不交接时</b>成立，交接时就不成立，
 * 所以做成了它的推广：一轮 = 一个或多个 agent span。没有交接时，事件序列就是
 * turn_start → agent_start → … → agent_end → turn_end，和那个描述完全一致。
 *
 * <p><b>为什么要有这一层</b>：允许 agent 之间交接之后，"谁来执行"不再是一次性决定，
 * 而是运行中反复发生的。把这段循环塞进 ChatController 会让控制器同时管 HTTP、SSE、
 * 路由、预算、事件落库，谁也读不懂；塞进内核又违反"内核不认识业务"。
 *
 * <p><b>Manager 不是特例</b>。它和其余 agent 在同一张注册表里，只是不绑工具、用快模型、
 * 只跑一步。它输出的 {@code ROUTE} 和执行中 agent 输出的 {@code HANDOFF} 在这里
 * <b>走同一条路径</b>——校验目标、扣预算、记事件。两套逻辑迟早会走偏。
 *
 * <p><b>预算是一轮对话的，不是某个 agent 的</b>。A 交 B、B 交 C，如果每人拿满自己的步数，
 * 总步数就是三者之和，延迟翻三倍，还能互相踢皮球踢到超时。
 */
@Component
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    /** 没有 activeAgent 时的入口。 */
    private static final String ENTRY = "manager";

    private final ExecuteAgents agents;
    private final SessionStore sessions;
    private final DigestBuilder digestBuilder;
    private final TurnEventStore events;
    private final AgentProperties props;
    private final ObjectMapper mapper;

    public AgentRuntime(ExecuteAgents agents, SessionStore sessions, DigestBuilder digestBuilder,
                        TurnEventStore events, AgentProperties props, ObjectMapper mapper) {
        this.agents = agents;
        this.sessions = sessions;
        this.digestBuilder = digestBuilder;
        this.events = events;
        this.props = props;
        this.mapper = mapper;
    }

    /** 事件出口：SSE 只关心"现在交给谁了"，不关心每一步的细节。 */
    @FunctionalInterface
    public interface EventSink {
        void emit(String type, Map<String, Object> data);
    }

    public record TurnResult(KernelResult last, String finalAgent, int handoffs, int steps) {}

    /**
     * 跑完一轮。调用方负责把 token 写出去，这里只负责"交给谁、跑多久、记什么"。
     *
     * <p>起点是<b>上一轮的持有者</b>，不是 Manager。用户上一句在问药，这一句大概率还在问药；
     * 每轮都从 Manager 重新判一次，等于把已经建立的上下文丢掉重来。判错了也没关系——
     * 当前 agent 有交接权，它自己会把人送走。
     *
     * @param extraContext 额外上下文（例如红旗提示），拼在画像后面。没有就传空串。
     */
    public TurnResult run(Session session, String extraContext, Consumer<String> onToken, EventSink sink) {
        int turn = session.turnCount();
        RunBudget budget = RunBudget.of(props.turnMaxSteps(), props.deadlineMs());
        EventLog log = new EventLog(session.id(), turn, events, mapper);

        String current = resumeAgent(session);
        boolean resumed = !ENTRY.equals(current);
        log.append("turn_start", current, Map.of("turn", turn, "resumed", resumed));
        sink.emit("agent", Map.of("agent", current, "turn", turn, "resumed", resumed));

        String digest = digestBuilder.build(session.scopedUserId());
        if (extraContext != null && !extraContext.isBlank()) {
            digest = digest.isBlank() ? extraContext : digest + "\n" + extraContext;
        }

        // 本轮还没处理的诉求。只由 Manager 的 ROUTE 填充，执行中的 agent 不往里加——
        // 它看到的是局部上下文，拆出来的诉求容易和主诉求重复。
        Deque<Decision.Intent> pending = new ArrayDeque<>();
        int handoffs = 0;
        int spent = 0;

        while (!budget.exhausted()) {
            log.append("agent_start", current, Map.of("handoffs", handoffs));
            KernelResult result = agents.run(current, session, digest, onToken, budget,
                    listener(log, sink));

            int steps = Math.max(1, result.steps());
            spent += steps;
            budget = budget.spend(steps);
            log.append("agent_end", current, Map.of("kind", result.kind().name(), "steps", steps));

            switch (result.kind()) {
                case ROUTE, HANDOFF -> {
                    boolean isRoute = result.kind() == KernelResult.Kind.ROUTE;
                    String target = isRoute ? result.route().target() : result.handoff().target();
                    String reason = isRoute ? result.route().intent() : result.handoff().reason();

                    // 交接前校验目标存在。不存在的目标不能进内核——那会在 prompt 加载时炸掉整轮。
                    if (!agents.has(target)) {
                        String error = "target 不存在：" + target
                                + "（可选：" + String.join(", ", agents.targets()) + "）";
                        log.append("handoff_rejected", current,
                                Map.of("target", target, "error", error));
                        // 喂回历史让模型自己改，而不是替它猜一个目标——猜错等于静默降级
                        session.observe(ToolResult.error("handoff", error));
                        sink.emit("handoff_rejected", Map.of("from", current, "target", target));
                        continue;
                    }

                    handoffs++;
                    log.append(isRoute ? "route" : "handoff", current,
                            Map.of("to", target, "reason", reason == null ? "" : reason));
                    sink.emit("handoff", Map.of("from", current, "to", target));

                    // Manager 拆出来的其他诉求，排队等主诉求答完再处理。
                    // 目标不存在或和主目标重复的直接丢掉——留着只会在后面变成一次无效交接。
                    if (isRoute) {
                        java.util.List<String> planned = new java.util.ArrayList<>();
                        for (Decision.Intent it : result.route().remaining()) {
                            if (it.target().isEmpty() || it.target().equals(target) || !agents.has(it.target())) {
                                continue;
                            }
                            pending.add(it);
                            planned.add(it.target() + "：" + it.intent());
                        }
                        if (!planned.isEmpty()) {
                            log.append("plan", current, Map.of("remaining", planned));
                            sink.emit("plan", Map.of("remaining", planned));
                        }
                    }

                    // 把交接写进对话历史，接手的 agent 才看得见"我从哪来、为什么是我"。
                    //
                    // 只靠共享 session 不够：下一个人能看到用户原话和上一个 agent 的工具结果，
                    // 但看不到对方的判断——"这人有胃溃疡，别用布洛芬"这种结论在 reason 里，
                    // 不落到历史里就丢了。丢了它，接手的人等于从头再问一遍。
                    //
                    // 用 TOOL 角色而不是 SYSTEM：这条是"上一个 agent 交出来的东西"，
                    // 语义上更接近工具产出；而且 SYSTEM 消息夹在对话中间，各家模型表现不一致。
                    session.observe(ToolResult.ok("handoff",
                            "【交接】" + current + " → " + target
                                    + (reason == null || reason.isBlank() ? "" : "｜原因：" + reason),
                            0L));

                    current = target;
                    // 立刻落库：下次用户发问要从这里开始，进程重启也不能丢
                    session.activeAgent(current);
                    sessions.updateActiveAgent(session);
                }
                case CLARIFY -> {
                    log.append("clarify", current, Map.of("text", safe(result.text())));
                    // 追问会结束这一轮，但这句话里可能还有别的诉求没办。
                    // 写进历史而不是丢掉：下一轮还是从当前 agent 开始，它看得到这条，
                    // 用户补充完信息后能顺手把剩下的办掉。不写进历史，那些诉求就永远消失了。
                    if (!pending.isEmpty()) {
                        java.util.List<String> left = pending.stream()
                                .map(it -> it.target() + "：" + it.intent()).toList();
                        session.observe(ToolResult.ok("pending",
                                "【未处理】这句话里还有这些诉求没办：" + String.join("；", left)
                                        + "。用户回答完上面的问题后，如果这些还没解决，请处理或交接。"
                                        + "如果用户已经换了话题，忽略这条。", 0L));
                        log.append("pending_kept", current, Map.of("remaining", left));
                    }
                    return done(log, sink, result, current, handoffs, spent, "clarify");
                }
                case ANSWER -> {
                    log.append("answer", current, Map.of("streamed", result.streamed()));
                    Decision.Intent next = nextPending(pending, current);
                    if (next == null) {
                        return done(log, sink, result, current, handoffs, spent, "answer");
                    }
                    // 主诉求已经答完，但这句话里还有别的诉求。不结束这一轮，接着办。
                    // 用户会看到第二段回答——这是有意的：多意图就该有多段回应，
                    // 硬拼成一段反而让两件事的边界糊掉。
                    //
                    // 这一段必须自己落库：ChatController 只收最后一段，前面的不写进历史，
                    // 下一轮谁都看不到自己刚才说过什么。
                    if (result.text() != null && !result.text().isBlank()) {
                        session.addAssistant(result.text(), current);
                    }
                    handoffs++;
                    log.append("handoff", current,
                            Map.of("to", next.target(), "reason", "继续处理：" + next.intent()));
                    sink.emit("handoff", Map.of("from", current, "to", next.target()));
                    // 单列一个事件：前端据此在两段回答之间加分隔。
                    // handoff 在普通路由时也会发，分不清"换人"和"同一轮的第二段"。
                    sink.emit("continue", Map.of("next", next.target(), "intent", next.intent()));
                    session.observe(ToolResult.ok("handoff",
                            "【交接】" + current + " → " + next.target()
                                    + "｜已完成：" + current + " 负责的部分"
                                    + "｜接下来：" + next.intent(), 0L));
                    current = next.target();
                    session.activeAgent(current);
                    sessions.updateActiveAgent(session);
                    continue;
                }
                case DEGRADED -> {
                    log.append("degraded", current, Map.of("reason", safe(result.text())));
                    return done(log, sink, result, current, handoffs, spent, "degraded");
                }
            }
        }

        // 预算耗尽时区分两种结局：一种是真的卡住了，一种是"答了一半还有剩下的"。
        // 后者不该说"没找到答案"——用户明明已经看到一部分回答了，得说清还剩什么。
        boolean partial = !pending.isEmpty();
        java.util.List<String> left = pending.stream().map(Decision.Intent::intent).toList();
        log.append("turn_end", current,
                Map.of("status", partial ? "partial_done" : "budget_exhausted",
                        "handoffs", handoffs, "steps", spent, "remaining", left));
        return new TurnResult(
                KernelResult.degraded(partial ? "partial_done" : "max_steps_reached", spent, props.deadlineMs()),
                current, handoffs, spent);
    }

    /** 统一的收尾：写 turn_end 再返回。四个出口走同一处，避免漏记。 */
    private TurnResult done(EventLog log, EventSink sink, KernelResult result, String agent,
                            int handoffs, int spent, String status) {
        log.append("turn_end", agent,
                Map.of("status", status, "handoffs", handoffs, "steps", spent));
        sink.emit("turn_end", Map.of("status", status, "agent", agent));
        return new TurnResult(result, agent, handoffs, spent);
    }

    /**
     * 取下一条待办。跳过三种无效项：没写目标的、目标就是当前 agent 的（它刚答完）、
     * 目标不存在的。跳过而不是报错——这是规划层的失误，不该让用户承担一轮失败。
     */
    private Decision.Intent nextPending(Deque<Decision.Intent> queue, String current) {
        while (!queue.isEmpty()) {
            Decision.Intent it = queue.poll();
            if (it.target().isEmpty() || it.target().equals(current) || !agents.has(it.target())) {
                continue;
            }
            return it;
        }
        return null;
    }

    /** 上一轮的持有者，校验过才用。库里可能留着已经下线的 agent 名。 */
    private String resumeAgent(Session session) {
        String active = session.activeAgent();
        if (active != null && !active.isBlank() && agents.has(active)) {
            return active;
        }
        return ENTRY;
    }

    /**
     * 把内核的每一步落成事件。
     *
     * <p>只有消息没有事件，回答错了没法复盘：是路由错了、检索没命中、还是模型自己跑偏？
     * 事件流就是这条因果链。
     */
    private KernelListener listener(EventLog log, EventSink sink) {
        return new KernelListener() {
            @Override
            public void onStep(String agent, int step, Decision decision, long elapsedMs) {
                log.append("llm", agent, Map.of(
                        "step", step,
                        "decision", decision.getClass().getSimpleName().replace("Decision$", ""),
                        "elapsed_ms", elapsedMs));
            }

            @Override
            public void onToolStart(String agent, String toolName, Map<String, Object> args) {
                log.append("tool_start", agent, Map.of("name", toolName, "args", args));
            }

            @Override
            public void onToolEnd(String agent, ToolResult result) {
                log.append("tool_end", agent, Map.of(
                        "name", result.toolName(),
                        "ok", result.ok(),
                        "error", result.error() == null ? "" : result.error()));
                sink.emit("tool", Map.of("name", result.toolName(), "ok", result.ok()));
            }
        };
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 事件写入器。seq 在一轮内自增，跨轮重置——查询时按 (turn, seq) 排序就是完整时间线。
     *
     * <p>写失败不抛：用户正在等回答，审计记录写不进去不该让这一轮崩掉。
     */
    private static final class EventLog {
        private final String sessionId;
        private final int turn;
        private final TurnEventStore store;
        private final ObjectMapper mapper;
        private int seq;

        EventLog(String sessionId, int turn, TurnEventStore store, ObjectMapper mapper) {
            this.sessionId = sessionId;
            this.turn = turn;
            this.store = store;
            this.mapper = mapper;
        }

        void append(String type, String agent, Map<String, Object> detail) {
            Map<String, Object> payload = new LinkedHashMap<>(detail);
            String json;
            try {
                json = mapper.writeValueAsString(payload);
            } catch (Exception e) {
                json = String.valueOf(payload);
            }
            store.append(TurnEvent.of(sessionId, turn, ++seq, type, agent, json, 0));
        }
    }
}
