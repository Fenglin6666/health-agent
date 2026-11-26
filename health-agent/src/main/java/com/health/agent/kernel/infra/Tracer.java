package com.health.agent.kernel.infra;

import com.health.agent.kernel.api.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 轻量 trace：每次决策一条 span。用于延迟归因与成本归因。
 *
 * <p>不引 OpenTelemetry 的原因：单机项目，先把结构化日志跑通，需要时再换实现。
 */
public class Tracer {

    private static final Logger log = LoggerFactory.getLogger(Tracer.class);

    private final List<Span> spans = new CopyOnWriteArrayList<>();

    public void record(String agent, int step, Decision decision, long elapsedMs, int tokens) {
        Span span = new Span(agent, step, decision.getClass().getSimpleName(), elapsedMs, tokens);
        spans.add(span);
        log.debug("trace agent={} step={} type={} elapsed={}ms tokens={}",
                agent, step, span.type(), elapsedMs, tokens);
    }

    public List<Span> spans() {
        return List.copyOf(spans);
    }

    public long totalElapsedMs() {
        return spans.stream().mapToLong(Span::elapsedMs).sum();
    }

    public int totalTokens() {
        return spans.stream().mapToInt(Span::tokens).sum();
    }

    public void clear() {
        spans.clear();
    }

    public record Span(String agent, int step, String type, long elapsedMs, int tokens) {}
}
