package com.health.agent.platform.persistence;

/**
 * 会话列表里的一行。
 *
 * <p>只带列表需要的最小信息：不返回全部消息，避免列表接口变成"批量拉历史"。
 * 点进某个会话时再走 {@code GET /sessions/{id}/timeline}。
 */
public record SessionSummary(
        String id,
        int turns,
        String preview,
        String updatedAt
) {
    /** 列表里的摘要长度。太长会把侧栏撑开，太短看不出是哪次对话。 */
    public static final int PREVIEW_MAX = 24;

    /**
     * MySQL DATETIME 字符串没有时区信息，而库里的 NOW() 是 UTC（服务器 time_zone=SYSTEM=UTC）。
     * 直接透给前端会差 8 小时——界面上 15:22 建的会话显示成 07:22。
     * 补上 Z 让前端按 UTC 解析、再转本地时区，比在 SQL 里转更可靠。
     */
    private static String isoUtc(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String t = s.trim().replace(' ', 'T');
        if (t.length() == 19) {
            t = t + ".000";
        }
        return t.endsWith("Z") ? t : t + "Z";
    }

    public static SessionSummary of(String id, int turns, String firstUserText, String updatedAt) {
        String p = firstUserText == null ? "" : firstUserText.strip().replaceAll("\\s+", " ");
        if (p.length() > PREVIEW_MAX) {
            p = p.substring(0, PREVIEW_MAX) + "…";
        }
        return new SessionSummary(id, turns, p, isoUtc(updatedAt));
    }
}
