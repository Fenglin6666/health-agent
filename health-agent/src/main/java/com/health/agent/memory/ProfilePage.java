package com.health.agent.memory;

import java.time.Instant;
import java.util.List;

/**
 * 档案页（representations 层）。
 *
 * <p>只写结论与关系，不复制原文——原文在账本和对话记录里，这里放的是"当前状态"。
 * 页与页之间用 {@link Link} 连接，形成一张可导航的档案网，而不是一堆孤立字段。
 */
public record ProfilePage(
        String key,
        String title,
        FactType type,
        List<Entry> entries,
        List<Link> links,
        Instant updatedAt
) {

    /** 页内条目。records 是这条事实被记录过多少次——重复提及本身就是重要性的信号。 */
    public record Entry(
            String key,
            String value,
            int records,
            Instant firstSeen,
            Instant lastSeen,
            String evidence
    ) {}

    public record Link(String targetKey, String targetTitle, String relation) {}
}
