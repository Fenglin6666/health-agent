package com.health.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.health.agent.kernel.domain.Message;
import com.health.agent.kernel.domain.Session;
import com.health.agent.kernel.infra.LlmClient;
import com.health.agent.llm.LlmProperties;
import com.health.agent.platform.config.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Librarian：会话结束后把对话整理成结构化事实，写入 append-only 账本。
 *
 * <p>对应设计文档的 dreaming（离线整理）。第一版只做"事件抽取 → 写账本"这一件事，
 * 不做档案页聚合、不做矛盾检测——那两件事等账本积累够数据再上。
 *
 * <p>为什么异步：整理要调一次 LLM（1-3 秒），不能让它拖慢用户看到的响应。
 */
@Component
public class Librarian {

    private static final Logger log = LoggerFactory.getLogger(Librarian.class);

    /** 对话太短不值得抽取，省一次调用。 */
    private static final int MIN_TRANSCRIPT_CHARS = 20;

    /**
     * 只看最近几轮。全量重抽会导致同一句话被反复写入账本，
     * 而事实通常出现在用户刚说的那句话里。
     */
    private static final int WINDOW = 6;

    private final LlmClient llm;
    private final PromptLoader prompts;
    private final FactLedger ledger;
    private final ContradictionDetector detector;
    private final ContradictionStore contradictions;
    private final ObjectMapper mapper;
    private final HabitStore habits;
    private final String model;

    public Librarian(LlmClient llm, PromptLoader prompts, FactLedger ledger,
                     ContradictionDetector detector, ContradictionStore contradictions,
                     HabitStore habits, ObjectMapper mapper, LlmProperties props) {
        this.llm = llm;
        this.prompts = prompts;
        this.ledger = ledger;
        this.detector = detector;
        this.contradictions = contradictions;
        this.habits = habits;
        this.mapper = mapper;
        this.model = props.modelManager();
    }

    /** 异步整理，不阻塞调用方。失败只记日志，不影响对话。 */
    public void consolidateAsync(Session session) {
        Thread.ofVirtual().start(() -> {
            try {
                int n = consolidate(session);
                if (n > 0) {
                    log.info("librarian: {} facts recorded for session {}", n, session.id());
                }
            } catch (RuntimeException e) {
                log.warn("librarian failed for session {}: {}", session.id(), e.getMessage());
            }
        });
    }

    /** 同步整理，返回写入的条数。评测和手动触发用这个。 */
    public int consolidate(Session session) {
        String transcript = buildTranscript(session);
        if (transcript.length() < MIN_TRANSCRIPT_CHARS) {
            return 0;
        }

        String system = prompts.load("prompts/librarian/extract.v1.md");
        String raw = llm.generate(LlmClient.LlmRequest.of(
                model, system, List.of(Message.user(transcript))));

        JsonNode root = parseJson(raw);
        writeHabits(session, root.path("habits"));

        JsonNode facts = root.path("facts");
        if (!facts.isArray()) {
            return 0;
        }

        int written = 0;
        for (JsonNode f : facts) {
            FactType type = parseType(f.path("type").asText(""));
            String key = f.path("key").asText("").trim();
            double confidence = f.path("confidence").asDouble(0.0);
            if (type == null || key.isEmpty() || confidence < 0.6) {
                continue;
            }
            String value = f.path("value").asText(key);

            // 先检测再写入：写完再比就分不清哪条是旧的
            for (Contradiction c : detector.detect(session.scopedUserId(), type, key, value, ledger)) {
                if (!alreadyOpen(session.scopedUserId(), c)) {
                    contradictions.save(c);
                    log.info("contradiction {} {} {} -> {}", c.kind(), key, c.oldValue(), c.newValue());
                }
            }

            ledger.append(
                    session.scopedUserId(),
                    type,
                    key,
                    value,
                    confidence,
                    f.path("evidence").asText(""),
                    "librarian"
            );
            written++;
        }
        return written;
    }

    /**
     * 写习惯层。
     *
     * <p>和事实不同，这里<b>不查矛盾、不要求确认、直接覆盖</b>——用户说"以后说话简短点"，
     * 意思就是"从现在开始"，不需要再问一遍"您之前喜欢详细解释，确认改吗"。
     * 把偏好当事实处理，会让产品显得迟钝。
     *
     * <p>key 走白名单，模型编出来的新字段一律丢弃：不设白名单，习惯表会长出几十个
     * 同义字段（"沟通风格""说话方式""语气偏好"），既注入不下也没法用。
     */
    private void writeHabits(Session session, JsonNode node) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode h : node) {
            HabitStore.HabitKey key = HabitStore.HabitKey.of(h.path("key").asText(""));
            String value = h.path("value").asText("").trim();
            if (key == null || value.isEmpty() || value.length() > 100) {
                continue;
            }
            habits.put(session.scopedUserId(), key, value);
        }
    }

    /**
     * 同一句话被反复整理时不要重复报矛盾。
     *
     * <p>只看 key + 新值：同一个值反复出现是同一条待办，值又变了才是新问题。
     */
    private boolean alreadyOpen(String owner, Contradiction c) {
        return contradictions.open(owner).stream()
                .anyMatch(o -> o.key().equals(c.key())
                        && ContradictionDetector.normalize(o.newValue())
                        .equals(ContradictionDetector.normalize(c.newValue())));
    }

    private String buildTranscript(Session session) {
        var all = session.messages();
        int from = Math.max(0, all.size() - WINDOW);
        StringBuilder sb = new StringBuilder();
        for (Message m : all.subList(from, all.size())) {
            if (m.role() == Message.Role.USER) {
                sb.append("用户：").append(m.content()).append('\n');
            } else if (m.role() == Message.Role.ASSISTANT) {
                sb.append("助手：").append(m.content()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private JsonNode parseJson(String raw) {
        try {
            String s = raw == null ? "" : raw.trim();
            int l = s.indexOf('{');
            int r = s.lastIndexOf('}');
            if (l >= 0 && r > l) {
                s = s.substring(l, r + 1);
            }
            return mapper.readTree(s);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static FactType parseType(String code) {
        try {
            return FactType.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
