package com.health.agent.platform.feedback;

/**
 * 一条回答反馈。
 *
 * <p>用 {@code (tenantId, sessionId, turn, agent)} 定位回答，不引入 message id：
 * 前端只知道自己发了第几轮、这条是谁答的，不需要理解数据库主键。
 *
 * <p>{@code rating} 只有三种：{@code up}（有用）、{@code down}（没用/有错）、
 * {@code report}（举报内容问题）。点踩必须带原因，举报原因固定为"内容问题"。
 */
public record Feedback(
        String tenantId,
        String sessionId,
        int turn,
        String agent,
        String rating,
        String reason,
        String comment
) {
    public static final String UP = "up";
    public static final String DOWN = "down";
    public static final String REPORT = "report";

    public Feedback {
        agent = agent == null ? "" : agent;
        reason = reason == null ? "" : reason;
        comment = comment == null ? "" : (comment.length() <= 200 ? comment : comment.substring(0, 200));
    }

    public static boolean validRating(String rating) {
        return UP.equals(rating) || DOWN.equals(rating) || REPORT.equals(rating);
    }
}
