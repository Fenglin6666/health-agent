package com.health.agent.kernel.infra;

import java.util.Map;

/**
 * 工具协议。所有能力（知识检索、档案查询、地点）都实现它。
 *
 * <p>约定：{@link #check} 必须在 {@link #execute} 之前调用。先验证后执行是红线——
 * 副作用一旦发生，再报错就晚了。
 */
public interface Tool {

    String name();

    String description();

    /** JSON Schema 字符串，用于告诉模型参数结构。 */
    String paramsSchema();

    /**
     * 执行前校验。
     *
     * @return null 表示通过；非 null 是给模型看的错误说明
     */
    default String check(Map<String, Object> args) {
        return null;
    }

    ToolResult execute(Map<String, Object> args);
}
