package com.health.agent.memory;

import java.time.Instant;

/**
 * 记忆矛盾：新事实与既有事实对不上。
 *
 * <p>为什么要单独存而不是覆盖：账本记的是"用户说过什么"，矛盾记的是"这两句话对不上，
 * 需要人来判断哪句是真的"。前者是事实，后者是**待办**——混在一起就没人处理了。
 *
 * <p>最典型也最危险的一类：过敏史从"无"变成"青霉素"。可能是用户刚想起来，
 * 也可能是模型听错了。不确认就按新值走，可能开错药；按旧值走，可能漏掉真实过敏。
 */
public record Contradiction(
        long id,
        String owner,
        FactType type,
        String key,
        Kind kind,
        Severity severity,
        String oldValue,
        String newValue,
        String detail,
        Status status,
        Instant createdAt
) {

    public enum Kind {
        /** 过敏史翻转：无 → 有，或有 → 无。 */
        ALLERGY_FLIP,
        /** 用药状态变化：停药后又记录为在用。 */
        MEDICATION_RESTART,
        /** 剂量或用法变了。 */
        DOSAGE_CHANGE,
        /** 慢病诊断变了。 */
        CONDITION_CHANGE,
        /** 其他值变化。 */
        VALUE_CHANGED
    }

    public enum Severity { HIGH, MEDIUM, LOW }

    public enum Status { OPEN, RESOLVED, DISMISSED }

    public static Contradiction of(String owner, FactType type, String key, Kind kind,
                                   Severity severity, String oldValue, String newValue, String detail) {
        return new Contradiction(0, owner, type, key, kind, severity,
                oldValue, newValue, detail, Status.OPEN, Instant.now());
    }

    public Contradiction withId(long id) {
        return new Contradiction(id, owner, type, key, kind, severity,
                oldValue, newValue, detail, status, createdAt);
    }

    public Contradiction withStatus(Status status) {
        return new Contradiction(id, owner, type, key, kind, severity,
                oldValue, newValue, detail, status, createdAt);
    }
}
