package com.health.agent.kernel.infra;

/**
 * 工具执行结果。失败也返回对象（不抛异常），让内核把错误喂回模型自纠。
 */
public record ToolResult(
        String toolName,
        boolean ok,
        String output,
        String error,
        long elapsedMs
) {

    public static ToolResult ok(String toolName, String output, long elapsedMs) {
        return new ToolResult(toolName, true, output, null, elapsedMs);
    }

    public static ToolResult error(String toolName, String error) {
        return new ToolResult(toolName, false, null, error, 0L);
    }

    /** 喂回模型的历史文本。成功给内容，失败给错误说明，模型据此决定下一步。 */
    public String payloadForModel() {
        return ok ? output : "[tool_error] " + error;
    }
}
