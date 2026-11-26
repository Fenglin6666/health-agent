package com.health.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 事实账本：只追加，不修改，不删除。
 *
 * <p>为什么不用可覆盖字段：如果"过敏史"是一个能改的字段，某次会话模型理解错了、
 * 写了"无过敏史"，旧值就被静默覆盖——医疗场景里这是致命的。账本保证每次变更都留痕
 * （谁写的、依据哪句话、何时），当前值由投影推导，任何时刻可回溯。
 *
 * <p>数据库实现要求：应用账号只有 INSERT + SELECT 权限，没有 UPDATE / DELETE。
 */
public interface FactLedger {

    /** 追加一条事实。返回写入的记录。 */
    Entry append(String userId, FactType type, String key, String value,
                 double confidence, String sourceMsgId, String recordedBy);

    /** 当前值（从投影读，O(1)）。 */
    Optional<Value> current(String userId, FactType type, String key);

    /** 某个事实的全部历史，按时间正序。 */
    List<Entry> history(String userId, FactType type, String key);

    /** 某个用户的全部当前事实，按类型分组。 */
    Map<FactType, List<Value>> snapshot(String userId);

    /** 账本总条数（不是去重后的条数）。监控与运维用。 */
    long size();

    /**
     * 清空某个用户的全部事实与投影，返回删除的账本条数。
     *
     * <p><b>这是对"只追加、不删除"的唯一一处例外，而且必须由用户自己触发。</b>
     *
     * <p>为什么这个例外是正当的：不删除是为了防止**系统静默覆盖**——模型理解错了就写一条
     * "无过敏史"，把旧值盖掉，而用户毫不知情。这和"用户说'把我的资料都删了'"是两件事：
     * 前者是系统替用户做决定，后者是用户在做决定。拒绝后者不是谨慎，是把架构约束
     * 凌驾于用户对自己数据的处置权之上。
     *
     * <p>所以：只有显式的用户操作（`DELETE /api/v1/memory/profile`）能走到这里，
     * 任何自动化流程（Librarian、矛盾检测、dreaming）都不许调用。
     */
    int clear(String userId);

    /**
     * 每个事实的统计：记录次数、首次/最近时间、最近一条证据。
     *
     * <p>档案页要用它——没有它就得对每个 key 各查一次历史，是典型的 N+1。
     */
    List<Stat> stats(String userId);

    record Stat(
            FactType type,
            String key,
            int records,
            Instant firstSeen,
            Instant lastSeen,
            String latestEvidence
    ) {}

    /** 账本条目。 */
    record Entry(
            long id,
            String userId,
            FactType type,
            String key,
            String value,
            double confidence,
            String sourceMsgId,
            Long supersedes,
            Instant recordedAt,
            String recordedBy
    ) {}

    /** 投影：由账本推导出的当前值。 */
    record Value(
            FactType type,
            String key,
            String value,
            long version,
            Instant updatedAt
    ) {}
}
