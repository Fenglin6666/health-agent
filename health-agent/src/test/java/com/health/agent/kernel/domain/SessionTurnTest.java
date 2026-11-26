package com.health.agent.kernel.domain;

import com.health.agent.platform.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话轮次语义的守门测试。
 *
 * <p>这个测试的存在意义是防止将来有人"顺手加一个 maxTurns"——
 * 会话不限轮次是设计决定，不是遗漏。
 */
class SessionTurnTest {

    @Test
    void session_should_not_cap_turns() {
        Session session = new Session("s_test", "u1");

        for (int i = 0; i < 200; i++) {
            session.addUser("第 " + i + " 轮");
            session.addAssistant("回复 " + i, "doctor");
        }

        assertEquals(200, session.turnCount(), "轮次不应被截断");
        assertEquals(400, session.messages().size(), "消息不应被丢弃");
        assertEquals("第 199 轮", session.messages().get(398).content());
    }

    @Test
    void recent_should_window_context_without_touching_history() {
        Session session = new Session("s_test", "u1");
        for (int i = 0; i < 50; i++) {
            session.addUser("u" + i);
        }

        var recent = session.recent(20);

        assertEquals(20, recent.size(), "上下文窗口只影响喂给模型的部分");
        assertEquals("u49", recent.get(19).content());
        assertEquals(50, session.messages().size(), "完整历史仍然保留");
    }

    @Test
    void max_steps_are_per_task_not_per_session() {
        AgentProperties props = new AgentProperties(20, 4, 15000, Map.of("manager", 1, "doctor", 6), 10);

        assertEquals(1, props.maxStepsFor("manager"));
        assertEquals(6, props.maxStepsFor("doctor"));
        assertEquals(4, props.maxStepsFor("pharma"), "未配置的走默认值");
        assertEquals(4, props.maxStepsFor("unknown-target"));
    }

    @Test
    void defaults_should_be_sane_when_config_missing() {
        AgentProperties props = new AgentProperties(0, 0, 0, null, 0);

        assertEquals(20, props.historyWindow());
        assertEquals(4, props.maxStepsFor("doctor"));
    }
}
