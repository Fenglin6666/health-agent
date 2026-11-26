package com.health.agent.kernel.infra;

import com.health.agent.kernel.api.Decision;

/**
 * 内核步骤回调。用来把"这一步做了什么"抛给外层记录，而不让内核自己知道存储。
 *
 * <p>内核保持哑：它不认识 MySQL、不认识事件表，只负责在关键节点喊一声。
 * 落库、推送、埋点都由实现方决定。
 */
public interface KernelListener {

    /** 一次模型调用结束、决策已解析。 */
    void onStep(String agent, int step, Decision decision, long elapsedMs);

    /**
     * 工具执行<b>开始</b>。参数校验通过、即将真正调用时触发。
     *
     * <p>有 start 才能看出"卡在哪"——只有 end 的话，一个超时没返回的工具
     * 在日志里完全不存在，排查时只能靠猜。
     */
    void onToolStart(String agent, String toolName, java.util.Map<String, Object> args);

    /** 一次工具执行结束（含失败）。 */
    void onToolEnd(String agent, ToolResult result);

    KernelListener NOOP = new KernelListener() {
        @Override
        public void onStep(String agent, int step, Decision decision, long elapsedMs) { }

        @Override
        public void onToolStart(String agent, String toolName, java.util.Map<String, Object> args) { }

        public void onToolEnd(String agent, ToolResult result) { }
    };
}
