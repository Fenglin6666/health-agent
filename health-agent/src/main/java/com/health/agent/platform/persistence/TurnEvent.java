package com.health.agent.platform.persistence;

/**
 * 一轮对话里发生的一件事。
 *
 * <p>消息表只记"说了什么"，这张表记"怎么走到那句话的"：路由给了谁、交接了几次、
 * 调了哪些工具、工具返回了什么、哪一步降级了。两者合起来才是全量历史——
 * 只有消息的话，回答错了没法复盘是路由错、检索错还是模型错。
 *
 * <p>{@code detail} 存 JSON 字符串而不是结构化列：事件类型的字段差异太大，
 * 拆成列会变成一张宽表加一堆 NULL。
 */
public record TurnEvent(
        String sessionId,
        int turn,
        int seq,
        String type,
        String agent,
        String detail,
        long elapsedMs
) {
    public static TurnEvent of(String sessionId, int turn, int seq, String type, String agent,
                               String detail, long elapsedMs) {
        return new TurnEvent(sessionId, turn, seq, type, agent, detail, elapsedMs);
    }
}
