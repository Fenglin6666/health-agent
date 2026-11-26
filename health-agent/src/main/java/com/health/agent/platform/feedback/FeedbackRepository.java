package com.health.agent.platform.feedback;

/**
 * 反馈存储端口。
 *
 * <p>只有写和计数，没有"按用户查反馈"——那属于运营侧（ops），
 * 不该出现在在线链路的依赖里。需要看数据时直接查库或走 ops。
 */
public interface FeedbackRepository {

    /** 同一 (tenant, session, turn, agent) 覆盖旧值。 */
    void upsert(Feedback feedback);

    long count();
}
