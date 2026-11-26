package com.health.agent.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 域外过滤的守门测试。
 *
 * <p>重点不是"能拦住多少"，而是"<b>不能误拦什么</b>"——这个过滤器的失败模式
 * 只有一种会伤到人：把健康问题挡在外面。所以每个"应该放行"的用例都比"应该拦截"重要。
 */
class OffTopicFilterTest {

    private final OffTopicFilter filter = new OffTopicFilter();

    @Test
    void plain_greeting_is_blocked() {
        assertEquals("greeting", filter.check("你好").orElseThrow().kind());
        assertEquals("greeting", filter.check("谢谢！").orElseThrow().kind());
    }

    @Test
    void greeting_with_health_content_must_pass() {
        // 整句匹配才拦。"你好，我头疼" 不是寒暄，是问诊。
        assertTrue(filter.check("你好，我头疼两天了").isEmpty());
    }

    @Test
    void obvious_off_topic_is_blocked() {
        assertEquals("off_topic", filter.check("帮我写个 python 脚本").orElseThrow().kind());
        assertEquals("off_topic", filter.check("解一下这个方程").orElseThrow().kind());
        assertEquals("off_topic", filter.check("帮我写首诗").orElseThrow().kind());
    }

    @Test
    void off_topic_signal_with_health_word_must_pass() {
        // 这条是核心：域外词和健康词同现，一律交给 LLM 判断，不拦
        assertTrue(filter.check("股票跌了，我最近睡不着").isEmpty());
        assertTrue(filter.check("我血压高，帮我写个记录血压的小程序").isEmpty());
        assertTrue(filter.check("写代码熬到三点，头疼").isEmpty());
    }

    @Test
    void symptom_without_any_signal_must_pass() {
        // 没有域外信号 → 永远放行，不管多像闲聊
        assertTrue(filter.check("最近总觉得累").isEmpty());
        assertTrue(filter.check("我妈最近记性差").isEmpty());
        assertTrue(filter.check("我爸突然说话不清，右手抬不起来").isEmpty());
    }

    @Test
    void empty_input_does_not_throw() {
        assertTrue(filter.check("").isEmpty());
        assertTrue(filter.check(null).isEmpty());
    }
}
