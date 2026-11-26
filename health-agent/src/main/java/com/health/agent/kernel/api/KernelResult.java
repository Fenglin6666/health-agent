package com.health.agent.kernel.api;

import java.util.List;

/**
 * 内核运行结果。四类，与 {@link Decision} 对应，外加一个降级态。
 */
public record KernelResult(Kind kind, String text, Decision.Route route, int steps, long elapsedMs,
                           boolean streamed, Decision.Handoff handoff, List<String> options) {

    public KernelResult {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public enum Kind { ANSWER, CLARIFY, ROUTE, HANDOFF, DEGRADED }

    public static KernelResult answer(Decision.Answer a, int steps, long ms) {
        return new KernelResult(Kind.ANSWER, a.text(), null, steps, ms, false, null, List.of());
    }

    /** 回答已经逐字流式发出去了，调用方不要再整体重发一次。 */
    public static KernelResult answerStreamed(String text, int steps, long ms) {
        return new KernelResult(Kind.ANSWER, text, null, steps, ms, true, null, List.of());
    }

    /** 交接给另一个 agent。编排层校验目标后继续跑，不结束这一轮。 */
    public static KernelResult handoff(Decision.Handoff h, int steps, long ms) {
        return new KernelResult(Kind.HANDOFF, null, null, steps, ms, false, h, List.of());
    }

    public static KernelResult clarify(Decision.Clarify c, int steps, long ms) {
        return new KernelResult(Kind.CLARIFY, c.question(), null, steps, ms, false, null, c.options());
    }

    public static KernelResult route(Decision.Route r, int steps, long ms) {
        return new KernelResult(Kind.ROUTE, null, r, steps, ms, false, null, List.of());
    }

    public static KernelResult degraded(String reason, int steps, long ms) {
        return new KernelResult(Kind.DEGRADED, reason, null, steps, ms, false, null, List.of());
    }

    public boolean routed() {
        return kind == Kind.ROUTE;
    }
}
