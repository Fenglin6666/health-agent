package com.health.agent.routing;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 路由统计。
 *
 * <p>口径跟着新架构改了：不再统计"L1/L2/L3 各承接多少"，而是统计
 * <b>有多少消息被挡在模型之外</b>，以及模型最终判成了什么。
 *
 * <p>为什么换口径：旧口径鼓励"让规则多接一点"，那正是错的方向——规则接得越多，
 * 业务判断就越不可靠。新口径只看两件事：拦截是否生效、路由结果分布是否合理。
 */
@Component
public class RoutingStats {

    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();

    public void inc(String key) {
        counts.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String key : new String[]{
                "redflag", "greeting", "off_topic",
                "manager_route", "manager_clarify", "manager_answer", "manager_degraded"}) {
            out.put(key, count(key));
        }
        return out;
    }

    private long count(String key) {
        LongAdder adder = counts.get(key);
        return adder == null ? 0 : adder.sum();
    }
}
