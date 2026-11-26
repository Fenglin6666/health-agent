package com.health.agent.kernel.domain;

import com.health.agent.kernel.infra.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话状态。一个会话内的多轮对话、工具结果、路由上下文都挂在这里。
 *
 * <p><b>会话本身不设轮次上限</b>：医疗问诊天然是长对话，截断等于逼用户重开，
 * 而重开就丢了上下文。真正需要设上限的是"单轮任务内部的内核循环步数"
 * （见 {@code AgentConfig.maxSteps}）——那个不限会导致死循环，直接拖爆延迟和成本。
 *
 * <p>所以这里只累计 {@link #turnCount()} 用于观测，不做任何拒绝。
 * 进 prompt 的历史由 {@link #recent(int)} 按窗口截取，那是上下文窗口，不是轮次限制。
 *
 * <p>注意：这里只放"运行时状态"。持久化由 memory 模块负责，两者不混。
 */
public class Session {

    public static final String DEFAULT_TENANT = "default";

    private final String id;
    private final String userId;
    private final String tenantId;
    private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final AtomicInteger turns = new AtomicInteger();
    /** 消息序号。持久化时和 session_id 一起做唯一键，保证重试不会插出重复消息。 */
    private final AtomicInteger seq = new AtomicInteger();
    private volatile MessageSink sink = MessageSink.NOOP;
    private String activeAgent;
    /**
     * 当轮图片（base64）。只活在这一轮里，不落库、不进 attributes。
     *
     * <p>不放 attributes 是刻意的：attributes 会以 {@code _前缀} 注入所有工具参数，
     * 一张体检报告的 base64 有几百 KB，塞进工具参数会直接撑爆请求体。
     */
    private volatile String turnImage;

    public Session(String id, String userId) {
        this(id, userId, DEFAULT_TENANT);
    }

    public Session(String id, String userId, String tenantId) {
        this.id = id;
        this.userId = userId;
        this.tenantId = tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId;
    }

    public String id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public String tenantId() {
        return tenantId;
    }

    /**
     * 带租户作用域的用户标识，用于账本、画像这类跨会话存储。
     *
     * <p>用拼接而不是改存储层接口：账本本来就是按字符串 key 存，
     * 加前缀就能隔离，没必要为了多租户把整个 FactLedger 的签名改一遍。
     */
    public String scopedUserId() {
        return tenantId + ":" + userId;
    }

    public String activeAgent() {
        return activeAgent;
    }

    public void activeAgent(String agent) {
        this.activeAgent = agent;
    }

    public List<Message> messages() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    /** 最近 n 条消息，用于组装上下文时控制 token。 */
    public List<Message> recent(int n) {
        synchronized (messages) {
            int from = Math.max(0, messages.size() - n);
            return List.copyOf(messages.subList(from, messages.size()));
        }
    }

    /** 已发生的用户轮次数。仅用于观测与评测，不参与任何限制判断。 */
    public int turnCount() {
        return turns.get();
    }

    /** 已追加的消息条数（含工具结果）。 */
    public int messageSeq() {
        return seq.get();
    }

    /** 挂上持久化出口。由存储层在创建或恢复会话时调用。 */
    public void attachSink(MessageSink sink) {
        this.sink = sink == null ? MessageSink.NOOP : sink;
    }

    /**
     * 从存储恢复历史。只在反序列化时调用——正常流程不要用它，
     * 否则会绕过 {@link MessageSink}，历史写不进库。
     */
    public void restore(List<Message> history, int lastSeq) {
        synchronized (messages) {
            messages.clear();
            messages.addAll(history);
        }
        seq.set(lastSeq);
        turns.set((int) history.stream().filter(m -> m.role() == Message.Role.USER).count());
    }

    public void addUser(String text) {
        turns.incrementAndGet();
        append(Message.user(text));
    }

    public void addAssistant(String text, String agent) {
        append(Message.assistant(text, agent));
    }

    public void observe(ToolResult result) {
        append(Message.tool(result.toolName(), result.payloadForModel()));
    }

    private void append(Message message) {
        int next = seq.incrementAndGet();
        messages.add(message);
        sink.append(id, turns.get(), next, message);
    }

    public String turnImage() {
        return turnImage;
    }

    public void turnImage(String base64) {
        this.turnImage = base64;
    }

    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T fallback) {
        Object v = attributes.get(key);
        return v == null ? fallback : (T) v;
    }

    public Map<String, Object> attributes() {
        return Map.copyOf(attributes);
    }
}
