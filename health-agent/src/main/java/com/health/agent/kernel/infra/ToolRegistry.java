package com.health.agent.kernel.infra;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表。Spring 启动时把所有 {@link Tool} Bean 自动注册进来。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(Tool tool) {
        Tool existing = tools.putIfAbsent(tool.name(), tool);
        if (existing != null) {
            throw new IllegalStateException("duplicate tool name: " + tool.name());
        }
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 已注册的工具名，排序后返回。错误信息里要告诉模型"有哪些能用"，否则它只会重复瞎猜。 */
    public List<String> names() {
        return tools.keySet().stream().sorted().toList();
    }

    public List<ToolSpec> specs() {
        return tools.values().stream()
                .sorted(Comparator.comparing(Tool::name))
                .map(t -> new ToolSpec(t.name(), t.description(), t.paramsSchema()))
                .toList();
    }

    public record ToolSpec(String name, String description, String paramsSchema) {}
}
