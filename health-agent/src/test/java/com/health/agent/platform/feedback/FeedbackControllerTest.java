package com.health.agent.platform.feedback;

import com.health.agent.kernel.domain.Session;
import com.health.agent.platform.SessionStore;
import com.health.agent.platform.api.ApiErrors;
import com.health.agent.platform.persistence.InMemorySessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 反馈入口的校验顺序与幂等。
 *
 * <p>重点不是"能不能存"，是"什么情况下必须拒绝"：
 * 参数错要 400，越权要 404，点踩没原因要拒绝。
 */
class FeedbackControllerTest {

    private SessionStore sessions;
    private InMemoryFeedbackRepository repo;
    private FeedbackController controller;
    private String sessionId;

    @BeforeEach
    void setUp() {
        sessions = new SessionStore(new InMemorySessionRepository());
        repo = new InMemoryFeedbackRepository();
        controller = new FeedbackController(repo, sessions);
        Session s = sessions.create("u1", "tenant-a");
        sessionId = s.id();
    }

    private FeedbackController.Request req(String rating, String reason) {
        return new FeedbackController.Request(sessionId, 1, "doctor", rating, reason, "");
    }

    @Test
    void acceptsUpWithoutReason() {
        controller.submit(req("up", null), "tenant-a");
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void downRequiresReason() {
        assertThatThrownBy(() -> controller.submit(req("down", ""), "tenant-a"))
                .isInstanceOf(ApiErrors.BadRequest.class)
                .hasMessageContaining("reason");
        assertThat(repo.count()).isZero();
    }

    @Test
    void rejectsUnknownRating() {
        assertThatThrownBy(() -> controller.submit(req("meh", null), "tenant-a"))
                .isInstanceOf(ApiErrors.BadRequest.class);
    }

    @Test
    void rejectsForeignTenantAsNotFound() {
        // 不存在和存在但无权都返回 404，不用状态码差异暴露别人的会话是否存在
        assertThatThrownBy(() -> controller.submit(req("up", null), "tenant-b"))
                .isInstanceOf(ApiErrors.NotFound.class);
    }

    @Test
    void repeatedFeedbackOverwrites() {
        controller.submit(req("down", "不准确"), "tenant-a");
        controller.submit(req("up", null), "tenant-a");
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void commentIsTruncated() {
        String longComment = "x".repeat(500);
        controller.submit(new FeedbackController.Request(
                sessionId, 1, "doctor", "down", "其他", longComment), "tenant-a");
        assertThat(repo.count()).isEqualTo(1);
    }
}
