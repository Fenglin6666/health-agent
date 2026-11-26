package com.health.agent.kernel.api;

import java.util.List;
import java.util.Map;

/**
 * 内核决策：四选一，全部结构化，不解析自由文本。
 *
 * <p>这是整个系统唯一的"不确定决策点"。其余环节（规则、检索、校验）都是确定性的。
 */
public sealed interface Decision
        permits Decision.ToolCall, Decision.Route, Decision.Handoff,
                Decision.Clarify, Decision.Answer {

    /** 调用工具。执行前必须过 check()。 */
    record ToolCall(String tool, Map<String, Object> args) implements Decision {
        public ToolCall {
            args = args == null ? Map.of() : Map.copyOf(args);
        }
    }

    /**
     * 路由到某个 execute。仅 Manager 配置会产出。
     *
     * <p>{@code remaining} 是这句话里**主诉求之外、同样明确**的其他诉求。
     * 没有它，多意图问题只能被答一半——第一个 agent 给出 ANSWER 后整轮就结束了。
     *
     * <p>只让 Manager 拆，不让每个 agent 都拆：拆分需要看整句话的全局，而执行中的 agent
     * 只看到自己那一段上下文，拆出来的东西容易和主诉求重复。执行中的 agent 想补充诉求，
     * 走 {@link Handoff} 就够了。
     */
    record Route(String target, String intent, double confidence,
                 String contextDigest, Map<String, Object> params,
                 List<Intent> remaining) implements Decision {
        public Route {
            params = params == null ? Map.of() : Map.copyOf(params);
            remaining = remaining == null ? List.of() : List.copyOf(remaining);
        }

        /** 兼容不拆意图的调用方。 */
        public Route(String target, String intent, double confidence,
                     String contextDigest, Map<String, Object> params) {
            this(target, intent, confidence, contextDigest, params, List.of());
        }
    }

    /** 一个待办诉求：交给谁、做什么。intent 会被写进交接原因，接手的人据此判断。 */
    record Intent(String target, String intent) {
        public Intent {
            target = target == null ? "" : target.trim();
            intent = intent == null ? "" : intent.trim();
        }
    }

    /**
     * 交接给另一个 agent 继续处理。
     *
     * <p>和 {@link Route} 的区别只在"谁有权发起"：Route 是 Manager 判断入口，
     * Handoff 是执行中的 agent 判断"这事我不擅长/还需要别人"。
     * <b>两者在编排层走同一条路径</b>——目标校验、预算扣减、事件记录都一样，
     * 所以不要写两套逻辑。
     */
    record Handoff(String target, String reason) implements Decision {}

    /**
     * 信息不足，反问一句。不是失败分支，是正常输出。
     *
     * <p>{@code options} 是给用户的候选回答，前端渲染成可点卡片。
     * 允许为空——模型没给候选时退化成纯文本追问，不影响主链路。
     * 上限 4 条：超过 4 个选项用户反而更难选。
     */
    record Clarify(String question, List<String> options) implements Decision {
        public Clarify {
            options = options == null ? List.of() : List.copyOf(options);
        }

        /** 兼容不产生候选的调用方。 */
        public Clarify(String question) {
            this(question, List.of());
        }
    }

    /** 直接回答（闲聊、寒暄、超出能力范围）。 */
    record Answer(String text) implements Decision {}

    /** 供 prompt 使用的决策类型清单。 */
    static List<String> typeNames() {
        return List.of("TOOL", "ROUTE", "HANDOFF", "CLARIFY", "ANSWER");
    }
}
