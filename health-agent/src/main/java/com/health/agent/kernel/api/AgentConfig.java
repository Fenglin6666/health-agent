package com.health.agent.kernel.api;

import java.util.List;

/**
 * 一次 Agent 运行的配置。全部来自 YAML / 资源文件，不允许硬编码在业务代码里。
 *
 * @param name           agent 名（manager / doctor / pharma / report / care / wellness）
 * @param systemPrompt   系统提示词正文
 * @param maxSteps       内核循环硬上限
 * @param deadlineMs     单次运行的时间预算
 * @param model          模型标识，null 表示用默认
 * @param outputTemplate 输出模板标识（self / public）
 * @param toolNames      允许这个 agent 使用的工具，空表示不给工具
 */
public record AgentConfig(
        String name,
        String systemPrompt,
        int maxSteps,
        long deadlineMs,
        String model,
        String outputTemplate,
        List<String> toolNames
) {
    public static final int DEFAULT_MAX_STEPS = 6;
    public static final long DEFAULT_DEADLINE_MS = 6000L;

    public AgentConfig {
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
    }

    public static AgentConfig of(String name, String systemPrompt, String model) {
        return new AgentConfig(name, systemPrompt, DEFAULT_MAX_STEPS, DEFAULT_DEADLINE_MS,
                model, "self", List.of());
    }

    public AgentConfig withTools(List<String> names) {
        return new AgentConfig(name, systemPrompt, maxSteps, deadlineMs, model, outputTemplate, names);
    }

    public AgentConfig withMaxSteps(int steps) {
        return new AgentConfig(name, systemPrompt, steps, deadlineMs, model, outputTemplate, toolNames);
    }

    public AgentConfig withDeadlineMs(long ms) {
        return new AgentConfig(name, systemPrompt, maxSteps, ms, model, outputTemplate, toolNames);
    }
}
