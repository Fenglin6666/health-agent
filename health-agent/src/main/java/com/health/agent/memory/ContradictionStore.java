package com.health.agent.memory;

import java.util.List;

/**
 * 矛盾存储。
 *
 * <p>只追加 + 状态变更（OPEN → RESOLVED / DISMISSED），不删除。
 * 一条矛盾被"解决"本身也是信息——它说明用户确认过。
 */
public interface ContradictionStore {

    Contradiction save(Contradiction contradiction);

    /** 未处理的矛盾，新的在前。 */
    List<Contradiction> open(String owner);

    List<Contradiction> list(String owner, int limit);

    /** 标记处理结果。返回是否命中。 */
    boolean resolve(long id, Contradiction.Status status);

    long count();

    /** 清空某个用户的全部矛盾记录，返回删除条数。只在用户显式清空画像时调用。 */
    int clear(String owner);
}
