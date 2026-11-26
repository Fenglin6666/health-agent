package com.health.agent.platform.feedback;

import com.health.agent.platform.SessionStore;
import com.health.agent.platform.api.ApiErrors;
import com.health.agent.platform.api.TenantResolver;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回答反馈入口。
 *
 * <p>这是把用户反馈接进评测闭环的第一环：写进 {@code message_feedback} 之后，
 * ops 可以按 {@code rating=down} 导出 badcase，直接变成 L1 语料。
 *
 * <p>校验顺序刻意是"参数 → 归属 → 落库"：先挡住格式错误（400），
 * 再挡越权（404），最后才写。反过来会出现"参数非法但已写入"的脏数据。
 */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final FeedbackRepository repo;
    private final SessionStore sessions;

    public FeedbackController(FeedbackRepository repo, SessionStore sessions) {
        this.repo = repo;
        this.sessions = sessions;
    }

    public record Request(
            String sessionId,
            Integer turn,
            String agent,
            String rating,
            String reason,
            String comment
    ) {
    }

    @PostMapping
    public Map<String, Object> submit(
            @RequestBody Request req,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {

        String t = TenantResolver.resolve(tenant);

        if (req.sessionId() == null || req.sessionId().isBlank()) {
            throw new ApiErrors.BadRequest("sessionId is required");
        }
        if (req.turn() == null || req.turn() < 1) {
            throw new ApiErrors.BadRequest("turn must be >= 1");
        }
        if (!Feedback.validRating(req.rating())) {
            throw new ApiErrors.BadRequest("rating must be up / down / report");
        }
        // 点踩必须说清为什么，否则这条反馈没法用于归因
        if (Feedback.DOWN.equals(req.rating()) && (req.reason() == null || req.reason().isBlank())) {
            throw new ApiErrors.BadRequest("reason is required when rating=down");
        }

        sessions.find(req.sessionId(), t).orElseThrow(
                () -> new ApiErrors.NotFound("session not found: " + req.sessionId()));

        repo.upsert(new Feedback(t, req.sessionId(), req.turn(),
                req.agent(), req.rating(), req.reason(), req.comment()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }
}
