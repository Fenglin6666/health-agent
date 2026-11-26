package com.health.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.health.agent.kernel.api.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 路由解析的边界。remaining 是模型最容易写歪的字段——它不是必填，
 * 写错了不该让整轮失败，所以这里锁住"缺失、写歪、超量"三种情况的降级行为。
 */
class DecisionParserTest {

    private final DecisionParser parser = new DecisionParser(new ObjectMapper());

    @Test
    void parsesRemainingIntents() {
        Decision d = parser.parse("""
                {"decision":"ROUTE","target":"doctor","intent":"symptom","confidence":0.9,
                 "context_digest":"头疼加化验单","params":{},
                 "remaining":[{"target":"report","intent":"解读化验单"}]}
                """);
        assertInstanceOf(Decision.Route.class, d);
        Decision.Route r = (Decision.Route) d;
        assertEquals("doctor", r.target());
        assertEquals(1, r.remaining().size());
        assertEquals("report", r.remaining().get(0).target());
        assertEquals("解读化验单", r.remaining().get(0).intent());
    }

    @Test
    void parsesClarifyOptions() {
        Decision d = parser.parse("""
                {"decision":"CLARIFY","question":"疼多久了？",
                 "options":["不到一天","一到三天","超过一周"]}
                """);
        assertInstanceOf(Decision.Clarify.class, d);
        Decision.Clarify c = (Decision.Clarify) d;
        assertEquals("疼多久了？", c.question());
        assertEquals(3, c.options().size());
        assertEquals("一到三天", c.options().get(1));
    }

    @Test
    void clarifyWithoutOptionsIsEmpty() {
        Decision d = parser.parse("{\"decision\":\"CLARIFY\",\"question\":\"再说具体点\"}");
        assertEquals(0, ((Decision.Clarify) d).options().size());
    }

    @Test
    void clarifyOptionsAreCappedAndFiltered() {
        // 超过 4 个截断；超过 20 字丢弃（那种候选点下去等于替用户说一整句话）
        Decision d = parser.parse("""
                {"decision":"CLARIFY","question":"哪边疼？",
                 "options":["左边","右边","两边","说不清","第五个",
                            "这是一段非常长的描述超过二十个字应该被丢掉"]}
                """);
        Decision.Clarify c = (Decision.Clarify) d;
        assertEquals(4, c.options().size());
        assertEquals("说不清", c.options().get(3));
    }

    @Test
    void missingRemainingIsEmpty() {
        Decision d = parser.parse("{\"decision\":\"ROUTE\",\"target\":\"care\",\"intent\":\"location\"}");
        assertEquals(0, ((Decision.Route) d).remaining().size());
    }

    @Test
    void toleratesPlainStringRemaining() {
        Decision d = parser.parse("{\"decision\":\"ROUTE\",\"target\":\"doctor\",\"remaining\":[\"解读化验单\"]}");
        Decision.Route r = (Decision.Route) d;
        assertEquals(1, r.remaining().size());
        assertTrue(r.remaining().get(0).target().isEmpty());
        assertEquals("解读化验单", r.remaining().get(0).intent());
    }

    @Test
    void capsRemainingAtTwo() {
        Decision d = parser.parse("""
                {"decision":"ROUTE","target":"doctor","remaining":[
                  {"target":"report","intent":"a"},{"target":"care","intent":"b"},{"target":"pharma","intent":"c"}]}
                """);
        assertEquals(2, ((Decision.Route) d).remaining().size());
    }

    @Test
    void skipsBlankRemainingItems() {
        Decision d = parser.parse("{\"decision\":\"ROUTE\",\"target\":\"doctor\",\"remaining\":[{\"target\":\"\"}]}");
        assertEquals(0, ((Decision.Route) d).remaining().size());
    }

    /**
     * 实测形态：模型把 DeepSeek 的 DSML 工具调用标记漏进 content，且退化成带空格、
     * 半角竖线的样子。它不是回答，不能当文本发给用户。
     */
    @Test
    void rejectsDegradedDsmlMarkup() {
        Decision d = parser.parse("""
                {"kind":"hospital","address":"河南省商丘市","keywords":"三甲 急诊"}</ || DSML || parameter>
                < || DSML || parameter name="tool" string="true">find_care</ || DSML || parameter>
                < || DSML || parameter name="decision" string="true">TOOL</ || DSML || parameter>
                </ || DSML || invoke>
                </ || DSML || calls>
                """);
        assertInstanceOf(Decision.Answer.class, d);
        assertNull(((Decision.Answer) d).text(), "泄漏的协议标记必须触发重生成，不能当回答");
    }

    /** 标准形态：全角双竖线、无空格。 */
    @Test
    void rejectsStandardDsmlMarkup() {
        Decision d = parser.parse(
                "<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name=\"find_care\"></｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>");
        assertInstanceOf(Decision.Answer.class, d);
        assertNull(((Decision.Answer) d).text());
    }

    /** 纯文本回答必须原样保留——检测不能误伤正常内容。 */
    @Test
    void keepsPlainTextAnswer() {
        Decision d = parser.parse("建议先去急诊做个心电图，排除心梗。");
        assertEquals("建议先去急诊做个心电图，排除心梗。", ((Decision.Answer) d).text());
    }
}
