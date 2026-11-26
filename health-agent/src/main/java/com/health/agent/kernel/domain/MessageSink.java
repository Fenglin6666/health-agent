package com.health.agent.kernel.domain;

/**
 * 消息追加出口。
 *
 * <p>domain 只依赖这个接口，不知道背后是 MySQL 还是内存——这是"换存储不改领域模型"的边界。
 *
 * <p>实现方必须自己吞掉异常并记日志：持久化失败不应该让用户正在进行的对话中断，
 * 但也不能静默——所以吞异常的位置在基础设施层，不在 domain。
 */
@FunctionalInterface
public interface MessageSink {

    /**
     * @param turn 这条消息属于第几轮对话（从 1 开始）。一次用户输入算一轮，
     *             这一轮里的工具结果、助手回答都归到同一个 turn。
     */
    void append(String sessionId, int turn, int seq, Message message);

    /** 空实现：不持久化时用，省掉到处判空。 */
    MessageSink NOOP = (sessionId, turn, seq, message) -> { };
}
