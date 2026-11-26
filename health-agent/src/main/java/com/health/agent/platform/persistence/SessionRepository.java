package com.health.agent.platform.persistence;

import com.health.agent.kernel.domain.Message;
import com.health.agent.kernel.domain.Session;

import java.util.List;
import java.util.Optional;

/**
 * 会话持久化端口。
 *
 * <p>只做三件事：建会话、按 id 恢复、追加消息。**没有 update / delete 消息的方法**——
 * 对话历史不可变，这是和事实账本同一条原则：限制接口的灵活性，换可靠性。
 */
public interface SessionRepository {

    void create(Session session);

    /** 恢复会话（含全部消息）。找不到返回 empty。 */
    Optional<Session> load(String id);

    /** 追加一条消息。实现方必须保证 (sessionId, seq) 幂等。 */
    /**
     * @param turn 第几轮（从 1 开始）。一次用户输入算一轮，工具结果与回答归同一轮。
     */
    void appendMessage(String sessionId, int turn, int seq, Message message);

    /** 更新"当前由哪个 agent 持有控制权"。下一轮从这里接着跑。 */
    void updateActiveAgent(String sessionId, String agent);

    /** 会话总数，用于运行时状态展示。 */
    long count();

    /**
     * 某个租户下某个用户的最近会话。
     *
     * <p>给默认实现返回空列表：内存实现拿不到"跨会话"的视图（会话对象由 SessionStore 持有），
     * 由它自己兜底。这样接口不用为了一个实现类强行加依赖。
     */
    default List<SessionSummary> listByOwner(String tenantId, String userId, int limit) {
        return List.of();
    }
}
