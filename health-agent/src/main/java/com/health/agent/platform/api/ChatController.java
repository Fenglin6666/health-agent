package com.health.agent.platform.api;

import com.health.agent.execute.AgentRuntime;
import com.health.agent.kernel.api.KernelResult;
import com.health.agent.kernel.domain.Session;
import com.health.agent.memory.DigestBuilder;
import com.health.agent.memory.Librarian;
import com.health.agent.platform.SessionStore;
import com.health.agent.platform.persistence.TurnEvent;
import com.health.agent.platform.persistence.TurnEventStore;
import com.health.agent.routing.OffTopicFilter;
import com.health.agent.routing.RoutingStats;
import com.health.agent.safety.RedFlagScanner;
import com.health.agent.safety.SafetyGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话入口。一次请求的完整链路：
 *
 * <pre>
 * 红旗扫描 → 域外过滤 → Manager 路由 → execute → Safety → 输出
 * </pre>
 *
 * <p>顺序不是随意的：红旗必须最先（安全优先于一切），域外过滤必须在路由之前
 * （把明显无关的挡在模型外，省一次调用），而<b>业务意图只由 Manager 判断</b>——
 * 规则不碰"这是问药还是问诊"，它只回答"这句话要不要送进模型"。
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final SessionStore sessions;
    private final RedFlagScanner redFlags;
    private final OffTopicFilter preFilter;
    private final RoutingStats stats;
    private final AgentRuntime runtime;
    private final SafetyGate safety;
    private final DigestBuilder digestBuilder;
    private final Librarian librarian;
    private final TurnEventStore turnEvents;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    /**
     * 红旗规则开关。做成可关是为了能验证"它到底有没有用"——
     * 关掉之后跑同一批用例，看模型自己能不能识别出急症。
     */
    @org.springframework.beans.factory.annotation.Value("${ha.safety.redflag-hint:true}")
    private boolean redFlagEnabled;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(SessionStore sessions,
                          RedFlagScanner redFlags,
                          OffTopicFilter preFilter,
                          RoutingStats stats,
                          AgentRuntime runtime,
                          SafetyGate safety,
                          DigestBuilder digestBuilder,
                          Librarian librarian,
                          TurnEventStore turnEvents,
                          com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.sessions = sessions;
        this.redFlags = redFlags;
        this.preFilter = preFilter;
        this.stats = stats;
        this.runtime = runtime;
        this.safety = safety;
        this.digestBuilder = digestBuilder;
        this.librarian = librarian;
        this.turnEvents = turnEvents;
        this.mapper = mapper;
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestBody(required = false) CreateRequest request) {
        // 必须先解析再落库：创建时存 "Tenant-A"、查询时解析成 "tenant-a"，
        // 会出现"刚建的会话列表里看不到"这种诡异问题。
        Session session = sessions.create(request == null ? null : request.userId(),
                TenantResolver.resolve(tenantId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", session.id());
        out.put("tenant_id", session.tenantId());
        out.put("profile_digest", digestBuilder.build(session.scopedUserId()));
        return out;
    }

    /**
     * 会话列表。侧栏用，只返回摘要，不返回消息。
     *
     * <p>按 {@code tenant + userId} 过滤，不信任前端传的 userId 之外的东西；
     * limit 上限 50，防止有人一次拉全库。
     */
    @GetMapping("/sessions")
    public Map<String, Object> listSessions(
            @RequestParam(defaultValue = "local") String userId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        String t = TenantResolver.resolve(tenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessions", sessions.list(t, userId, limit));
        return out;
    }

    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable String id, @RequestBody ChatRequest request,
                           @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        SseEmitter emitter = new SseEmitter(30_000L);
        executor.execute(() -> handle(id, request, TenantResolver.resolve(tenant), emitter));
        return emitter;
    }

    /**
     * 读会话历史。前端刷新后靠它恢复对话；进程重启后靠它证明持久化真的生效。
     *
     * <p>只返回 USER / ASSISTANT 消息。工具结果对用户没有意义，而且很长。
     */
    @GetMapping("/sessions/{id}")
    public Map<String, Object> session(@PathVariable String id,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        Session session = sessions.find(id, TenantResolver.resolve(tenant)).orElseThrow(
                () -> new ApiErrors.NotFound("session not found: " + id));
        var history = session.messages().stream()
                .filter(m -> m.role() == com.health.agent.kernel.domain.Message.Role.USER
                        || m.role() == com.health.agent.kernel.domain.Message.Role.ASSISTANT)
                .map(m -> {
                    // name 存的是产生这条消息的 agent。用 LinkedHashMap 而不是 Map.of——
                    // 用户消息的 name 是 null，Map.of 会直接抛 NPE。
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("role", m.role().name());
                    row.put("content", m.content());
                    row.put("agent", m.name() == null ? "" : m.name());
                    return row;
                })
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", session.id());
        out.put("tenant_id", session.tenantId());
        out.put("turn", session.turnCount());
        out.put("messages", history);
        return out;
    }

    /**
     * 带状态的全量历史：按轮把消息和事件合并返回。
     *
     * <p>消息回答"说了什么"，事件回答"怎么走到那句话的"——路由给了谁、交接了几次、
     * 调了哪些工具、工具返回了什么、哪一步降级了。分开看都只有一半。
     *
     * <p>轮次不用查库：{@code turn} 就是在第几条 USER 消息之后，按顺序走一遍就能还原，
     * 和 {@code message.turn} 列完全一致。少一次 join，也少一份可能漂移的口径。
     */
    @GetMapping("/sessions/{id}/timeline")
    public Map<String, Object> timeline(@PathVariable String id,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        Session session = sessions.find(id, TenantResolver.resolve(tenant)).orElseThrow(
                () -> new ApiErrors.NotFound("session not found: " + id));

        Map<Integer, List<Map<String, Object>>> turns = new java.util.TreeMap<>();
        int turn = 0;
        int seq = 0;
        for (var m : session.messages()) {
            if (m.role() == com.health.agent.kernel.domain.Message.Role.USER) {
                turn++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", ++seq);
            row.put("role", m.role().name());
            row.put("agent", m.name() == null ? "" : m.name());
            row.put("content", m.content());
            turns.computeIfAbsent(Math.max(turn, 1), k -> new java.util.ArrayList<>()).add(row);
        }

        Map<Integer, List<Map<String, Object>>> eventTurns = new java.util.TreeMap<>();
        for (TurnEvent e : turnEvents.list(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", e.seq());
            row.put("type", e.type());
            row.put("agent", e.agent() == null ? "" : e.agent());
            row.put("detail", parseDetail(e.detail()));
            eventTurns.computeIfAbsent(e.turn(), k -> new java.util.ArrayList<>()).add(row);
        }

        java.util.Set<Integer> all = new java.util.TreeSet<>();
        all.addAll(turns.keySet());
        all.addAll(eventTurns.keySet());

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Integer t : all) {
            List<Map<String, Object>> evs = eventTurns.getOrDefault(t, List.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("turn", t);
            row.put("status", statusOf(evs));
            row.put("agents", agentsOf(evs));
            row.put("messages", turns.getOrDefault(t, List.of()));
            row.put("events", evs);
            out.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", id);
        body.put("turn_count", out.size());
        body.put("turns", out);
        return body;
    }

    /** 一轮的结局：turn_end 的 status，没写就是 running（可能是进程中途挂了）。 */
    private static String statusOf(List<Map<String, Object>> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if ("turn_end".equals(events.get(i).get("type"))) {
                Object d = events.get(i).get("detail");
                if (d instanceof Map<?, ?> m && m.get("status") != null) {
                    return String.valueOf(m.get("status"));
                }
            }
        }
        return "running";
    }

    /** 这一轮经过哪些 agent，按出现顺序去重——交接链一眼能看出来。 */
    private static List<String> agentsOf(List<Map<String, Object>> events) {
        List<String> out = new java.util.ArrayList<>();
        for (Map<String, Object> e : events) {
            String a = String.valueOf(e.get("agent"));
            if (!"agent_start".equals(e.get("type")) || a.isBlank() || out.contains(a)) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    private Object parseDetail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 路由统计。口径是"有多少消息被挡在模型之外"，不是"规则承接了多少"。
     *
     * <p>后者会诱导把业务判断塞进规则里，那正是要避免的。
     */
    @GetMapping("/routing/stats")
    public Map<String, Long> routingStats() {
        return stats.snapshot();
    }






    private void handle(String id, ChatRequest request, String tenantId, SseEmitter emitter) {
        try {
            Session session = sessions.find(id, tenantId).orElseThrow(
                    () -> new ApiErrors.NotFound("session not found: " + id));
            // 图片只活在这一轮：先挂上，回答完清掉。历史里只留模型的解读结论。
            if (request.imageBase64() != null && !request.imageBase64().isBlank()) {
                session.turnImage(request.imageBase64());
            }
            // 只发图片时 text 是空串。直接存空串，刷新后历史里就是一个空气泡——
            // 用户看不到自己发过什么。存占位符，至少能看出"这里发过一张图"。
            boolean hasImage = request.imageBase64() != null && !request.imageBase64().isBlank();
            String userText = request.text() == null ? "" : request.text();
            session.addUser(userText.isBlank() && hasImage ? "[图片]" : userText);

            // ① 红旗：只做**内部提示**，不向用户输出固定话术
            //
            // 曾经的做法是命中就返回一段"请立即拨打 120"。问题不在漏诊，在**过度肯定**：
            // 系统只看到一句话，不知道这是不是正在发生、是不是在转述别人的事、是不是电影情节。
            // 用模板替用户下结论，是拿"看起来很专业"换"实际上不负责任"。
            //
            // 现在的做法：把信号作为提示注入上下文，由模型组织语言——它可以说
            // "如果这是正在发生的"，也可以先给判断依据。判断权归模型，规则只负责提醒它别漏。
            String redFlagHint = "";
            var flag = redFlagEnabled
                    ? redFlags.scan(request.text(), request.age())
                    : java.util.Optional.<com.health.agent.safety.RedFlag>empty();
            if (flag.isPresent()) {
                stats.inc("redflag");
                redFlagHint = flag.get().id();
            }

            // ② 域外过滤：只拦"明确不是健康问题"的，不判断业务意图
            var blocked = preFilter.check(request.text());
            if (blocked.isPresent()) {
                var b = blocked.get();
                stats.inc(b.kind());
                finish(emitter, session, b.reply(), "prefilter", Map.of("layer", "prefilter", "reason", b.kind()));
                return;
            }

            // ③ 编排：从上一轮的持有者开始，沿交接链跑到有人回答。
            //
            // 入口不是固定的 Manager——用户上一句在问药，这一句大概率还在问药，
            // 每轮都从 Manager 重判等于把已建立的上下文丢掉重来。判错了当前 agent
            // 会自己交接出去（它有交接权），所以"从上次的地方继续"是安全的。
            AgentRuntime.TurnResult turn = runtime.run(session, redFlagHint,
                    token -> emit(emitter, "token", Map.of("delta", token)),
                    (type, data) -> {
                        if ("handoff".equals(type)) {
                            stats.inc("handoff");
                        }
                        emit(emitter, type, data);
                    });
            session.turnImage(null);
            finish(emitter, session, turn.last(), turn.finalAgent(), Map.of(
                    "layer", "runtime",
                    "agent", turn.finalAgent(),
                    "handoffs", turn.handoffs(),
                    "steps", turn.steps()));
        } catch (Exception e) {
            log.error("chat failed session={}", id, e);
            // 不发 completeWithError：那会让 Spring 尝试渲染 JSON 错误响应，
            // 而 response 已经是 text/event-stream，会二次报错。
            try {
                emit(emitter, "error", Map.of(
                        "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端可能已断开
            }
        }
    }

    private void finish(SseEmitter emitter, Session session, KernelResult result, String agent,
                        Map<String, Object> meta) {
        // 追问候选要先发：前端收到 token 时就要知道这条气泡下面挂哪些卡片。
        // 老客户端不认识这个事件，忽略即可，不影响正文。
        if (result.kind() == KernelResult.Kind.CLARIFY && !result.options().isEmpty()) {
            emit(emitter, "clarify", Map.of("options", result.options()));
        }
        String text = result.kind() == KernelResult.Kind.DEGRADED
                ? degradedReply(result.text())
                : (result.text() == null ? "" : result.text());

        if (result.streamed()) {
            // 正文已经逐字发出去了，这里只补记录和审查。
            // 注意：Safety 的"输出前必过"在流式下无法严格成立——字已经到用户眼前了。
            // self 模式只记录不改写，所以没有实际差异；一旦切到 public 模式（会改写文本），
            // 这条路必须改回缓冲，否则改写只作用于日志、不作用于用户看到的内容。
            session.addAssistant(text, agent);
            var verdict = safety.review(text, "self", false);
            Map<String, Object> done = new LinkedHashMap<>(meta);
            done.put("safety_issues", verdict.issues());
            done.put("streamed", true);
            done.put("turn", session.turnCount());
            emit(emitter, "done", done);
            emitter.complete();
            librarian.consolidateAsync(session);
            return;
        }

        finish(emitter, session, text, agent, meta);
    }

    /**
     * 降级原因 → 用户能看懂的一句话。
     *
     * <p>原来直接把 reason 当正文返回，用户看到的是 "timeout" 三个字母——
     * 这比"我不知道"更糟，因为它连"发生了什么"都没说清。
     */
    /**
     * 解析租户。
     *
     * <p><b>当前只有 header 一种来源，没有认证——header 可伪造。</b>
     * 这里能做的是：格式校验（防注入）+ 所有查询带租户条件（防漏过滤）。
     * 真正的身份校验要等接入认证后补，届时 header 只保留本地开发用。
     */

    private static String degradedReply(String reason) {
        return switch (reason == null ? "" : reason) {
            case "timeout" -> "这次处理超时了，没能在限定时间内完成。可以再试一次，或者把问题说得更具体一点。";
            case "max_steps_reached" -> "这个问题需要多步处理，我没能在限定步骤内完成。拆成更具体的问题再问一次会更好。";
            case "partial_done" -> "你这句话里有几件事，我处理完了一部分，剩下的没能在本轮完成。"
                    + "可以把剩下的那部分单独发一次，我接着处理。";
            default -> "这次没能处理完，请再试一次。";
        };
    }

    private void finish(SseEmitter emitter, Session session, String text, String agent,
                        Map<String, Object> meta) {
        // 域外拦截不经过 AgentRuntime，事件要在这里补。
        // 不补的话 timeline 永远停在 running——运维看到会以为卡住了，
        // 统计里也算不出拦截率。这类请求虽然没进模型，但同样是一轮对话。
        int turn = session.turnCount();
        turnEvents.append(TurnEvent.of(session.id(), turn, 1, "turn_start", agent, "{}", 0L));

        var verdict = safety.review(text, "self", false);
        emit(emitter, "token", Map.of("delta", verdict.text()));
        session.addAssistant(verdict.text(), agent);

        turnEvents.append(TurnEvent.of(session.id(), turn, 2, "turn_end", agent,
                "{\"status\":\"prefilter\"}", 0L));

        Map<String, Object> done = new LinkedHashMap<>(meta);
        done.put("safety_issues", verdict.issues());
        // 轮次只用于观测：会话不限轮次，这个数字会一直涨
        done.put("turn", session.turnCount());
        emit(emitter, "done", done);
        emitter.complete();

        // 记忆整理放最后且异步：它要调一次模型，不能拖慢用户看到的响应
        librarian.consolidateAsync(session);
    }

    /** 手动触发一次记忆整理，返回写入条数与最新画像。 */
    @PostMapping("/sessions/{id}/consolidate")
    public Map<String, Object> consolidate(@PathVariable String id,
                                           @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        Session session = sessions.find(id, TenantResolver.resolve(tenant)).orElseThrow(
                () -> new ApiErrors.NotFound("session not found: " + id));
        int written = librarian.consolidate(session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("facts_written", written);
        out.put("digest", digestBuilder.build(session.scopedUserId()));
        return out;
    }

    /** 设置会话位置，供 place agent 查附近药店。坐标用高德坐标系（GCJ-02）。 */
    @PostMapping("/sessions/{id}/location")
    public Map<String, Object> setLocation(@PathVariable String id, @RequestBody LocationRequest request,
                                           @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        Session session = sessions.find(id, TenantResolver.resolve(tenant)).orElseThrow(
                () -> new ApiErrors.NotFound("session not found: " + id));
        session.put("lat", request.lat());
        session.put("lng", request.lng());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("lat", request.lat());
        out.put("lng", request.lng());
        return out;
    }

    public record LocationRequest(double lat, double lng) {}

    private void emit(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    public record CreateRequest(String userId) {}

    /**
     * @param imageBase64 当轮图片（base64，不带 data: 前缀）。不落库、不进历史。
     */
    public record ChatRequest(String text, Integer age, String imageBase64) {}
}
