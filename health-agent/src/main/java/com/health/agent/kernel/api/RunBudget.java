package com.health.agent.kernel.api;

/**
 * 一次用户输入的总预算：步数 + 截止时刻。
 *
 * <p><b>为什么预算要跨 agent 共享</b>：允许 agent 之间交接之后，"一次回答最多调几次模型"
 * 就不再是单个 agent 的事。A 交 B、B 交 C，如果每个 agent 都拿满自己的步数，
 * 总步数是三者之和，延迟和成本直接乘三，而且能互相踢皮球踢到超时。
 *
 * <p>所以预算是<b>一轮对话</b>的资源，不是某个 agent 的资源：每交接一次，
 * 花掉的步数从总预算里扣，扣完为止。{@code deadlineAt} 同理——它是整轮的墙钟上限。
 */
public record RunBudget(int maxSteps, long deadlineAt) {

    public static RunBudget of(int maxSteps, long deadlineMs) {
        return new RunBudget(maxSteps, System.currentTimeMillis() + deadlineMs);
    }

    public boolean expired() {
        return System.currentTimeMillis() >= deadlineAt;
    }

    public long remainingMs() {
        return Math.max(0, deadlineAt - System.currentTimeMillis());
    }

    /** 扣掉已经花掉的步数，截止时刻不变。 */
    public RunBudget spend(int steps) {
        return new RunBudget(Math.max(0, maxSteps - Math.max(0, steps)), deadlineAt);
    }

    public boolean exhausted() {
        return maxSteps <= 0 || expired();
    }
}
