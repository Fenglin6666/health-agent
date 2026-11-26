package com.health.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Agent 运行参数。
 *
 * <p><b>两个概念必须分清</b>：
 * <ul>
 *   <li><b>会话轮次</b>：一个 session 里用户可以聊多少轮——<b>不设上限</b>。
 *       医疗问诊本来就是长对话，截断会话等于逼用户重开，而重开就丢了上下文。</li>
 *   <li><b>单轮任务步数</b>：一次用户输入内部，内核最多循环几步（调模型 + 调工具）——
 *       <b>必须有上限</b>。没有上限的死循环会把延迟和成本拖爆，而且是发生在用户
 *       已经等待的时候。</li>
 * </ul>
 *
 * <p>所以这里只配后者。{@code historyWindow} 也不是轮次限制，它是
 * "每次调模型带多少条历史消息"的上下文窗口——会话本身无限长，进 prompt 的部分有限。
 */
@ConfigurationProperties(prefix = "ha.agent")
public record AgentProperties(
        /** 每次调模型携带的历史消息条数（上下文窗口，不是轮次上限） */
        int historyWindow,
        /** 单轮任务的默认最大步数 */
        int defaultMaxSteps,
        /**
         * 单轮任务的时间预算，防止无限循环。
         *
         * <p><b>它是护栏，不是延迟 SLA。</b>原来写 6 秒，等于把"目标延迟"当成了硬中断——
         * 而一次正常的多步检索（两次知识检索各 2 秒 + 三次模型调用）本来就要 8-10 秒，
         * 于是用户拿到的是 "timeout" 三个字母。延迟指标该单独统计，不该用杀死请求来实现。
         */
        long deadlineMs,
        /** 按 target 覆盖步数，例如 manager: 1、doctor: 6 */
        Map<String, Integer> maxSteps,
        /**
         * 一轮对话的总步数上限（跨 agent 交接累计）。
         *
         * <p>和 {@code maxSteps} 是两层：{@code maxSteps} 限"一个 agent 最多跑几步"，
         * 这个限"整轮加起来最多几步"。允许交接之后只限前者不够——A 交 B 交 C，
         * 总步数是三者之和，还能互相踢皮球踢到超时。
         */
        int turnMaxSteps
) {

    public AgentProperties {
        historyWindow = historyWindow <= 0 ? 20 : historyWindow;
        defaultMaxSteps = defaultMaxSteps <= 0 ? 4 : defaultMaxSteps;
        deadlineMs = deadlineMs <= 0 ? 15000 : deadlineMs;
        maxSteps = maxSteps == null ? Map.of() : Map.copyOf(maxSteps);
        turnMaxSteps = turnMaxSteps <= 0 ? 10 : turnMaxSteps;
    }

    public int maxStepsFor(String target) {
        Integer v = maxSteps.get(target);
        return v == null || v <= 0 ? defaultMaxSteps : v;
    }
}
