package com.health.agent.kernel.domain;

/**
 * 会话消息。工具调用结果也作为消息进入历史，让模型在下一轮自行纠错。
 *
 * <p><b>这里刻意没有 {@code system()} 工厂方法</b>。历史里只该有"用户说了什么、助手答了什么、
 * 工具返回了什么"；系统提示词属于 agent 自己的配置，由 {@code AgentConfig} 在每次调用时
 * 单独提供，<b>永远不进历史</b>。
 *
 * <p>删掉这个工厂不是洁癖：只要它存在，就有人会把上一轮的系统提示塞进 session，
 * 交接之后下一个 agent 就会收到"别人的系统提示词 + 自己的系统提示词"，两条指令互相打架，
 * 而模型不会报错，只会照它更熟悉的那条办。
 */
public record Message(Role role, String content, String name) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public static Message user(String text) {
        return new Message(Role.USER, text, null);
    }

    /**
     * @param agent 产生这条回答的 agent。存进 name 列，事后能回答
     *              "这句话是谁说的"——多 agent 交接之后，没有这个信息就没法复盘。
     */
    public static Message assistant(String text, String agent) {
        return new Message(Role.ASSISTANT, text, agent);
    }

    public static Message tool(String toolName, String result) {
        return new Message(Role.TOOL, result, toolName);
    }
}
