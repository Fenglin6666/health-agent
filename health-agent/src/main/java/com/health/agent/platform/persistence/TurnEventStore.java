package com.health.agent.platform.persistence;

import java.util.List;

/**
 * 轮次事件存储。
 *
 * <p>写事件不能影响对话：失败只记日志，不抛给用户——用户正在等回答，
 * 一条审计记录写不进去不该让这一轮崩掉。所以实现方必须自己吞异常。
 */
public interface TurnEventStore {

    void append(TurnEvent event);

    /** 按 (turn, seq) 正序返回整个会话的事件流。 */
    List<TurnEvent> list(String sessionId);
}
