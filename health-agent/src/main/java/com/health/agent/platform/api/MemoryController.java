package com.health.agent.platform.api;

import com.health.agent.memory.Contradiction;
import com.health.agent.memory.ContradictionStore;
import com.health.agent.memory.DigestBuilder;
import com.health.agent.memory.FactLedger;
import com.health.agent.memory.HabitStore;
import com.health.agent.memory.ProfileAggregator;
import com.health.agent.memory.ProfilePage;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆档案与矛盾处理。
 *
 * <p>三个入口对应三件事：
 * <ul>
 *   <li>{@code GET /profile}：看聚合后的档案页（representations）</li>
 *   <li>{@code GET /contradictions}：看待确认项</li>
 *   <li>{@code POST /contradictions/{id}/resolve}：确认或驳回</li>
 * </ul>
 *
 * <p>确认/驳回只改矛盾的状态，**不改账本**——账本记的是"用户说过什么"，
 * 用户确认后如果值需要修正，应该由用户自己再说一遍，产生一条新记录。
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final ProfileAggregator aggregator;
    private final ContradictionStore contradictions;
    private final DigestBuilder digestBuilder;
    private final FactLedger ledger;
    private final HabitStore habits;

    public MemoryController(ProfileAggregator aggregator,
                            ContradictionStore contradictions,
                            DigestBuilder digestBuilder,
                            FactLedger ledger,
                            HabitStore habits) {
        this.aggregator = aggregator;
        this.contradictions = contradictions;
        this.digestBuilder = digestBuilder;
        this.ledger = ledger;
        this.habits = habits;
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(defaultValue = "local") String userId) {
        String owner = owner(tenantId, userId);
        List<ProfilePage> pages = aggregator.build(owner);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", owner);
        out.put("page_count", pages.size());
        out.put("pages", pages);
        out.put("digest", digestBuilder.build(owner));
        out.put("open_contradictions", contradictions.open(owner).size());
        return out;
    }

    @GetMapping("/contradictions")
    public Map<String, Object> list(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(defaultValue = "local") String userId,
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(defaultValue = "20") int limit) {
        String owner = owner(tenantId, userId);
        List<Contradiction> rows = "open".equalsIgnoreCase(status)
                ? contradictions.open(owner)
                : contradictions.list(owner, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", owner);
        out.put("count", rows.size());
        out.put("items", rows);
        return out;
    }

    @PostMapping("/contradictions/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable long id,
                                       @RequestParam(defaultValue = "RESOLVED") String status) {
        Contradiction.Status target;
        try {
            target = Contradiction.Status.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status 只能是 RESOLVED / DISMISSED");
        }
        boolean ok = contradictions.resolve(id, target);
        return Map.of("ok", ok, "id", id, "status", target.name());
    }

    /**
     * 清空患者画像：健康档案（账本 + 投影 + 矛盾）+ 习惯层。
     *
     * <p><b>只清画像，不删对话</b>。用户说"忘掉我的资料"，指的是那些被抽取出来的结论，
     * 不是"把我跟你聊过的话也删了"——那是另一个动作，真要做应该单独开一个入口。
     *
     * <p>用 DELETE 而不是 POST /clear：它是幂等的删除语义，重复调用结果一样。
     * 这个接口是账本"只追加不删除"的唯一出口，只能由用户显式触发。
     */
    @DeleteMapping("/profile")
    public Map<String, Object> clearProfile(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(defaultValue = "local") String userId) {
        String owner = owner(tenantId, userId);
        int facts = ledger.clear(owner);
        int conflicts = contradictions.clear(owner);
        habits.clear(owner);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("owner", owner);
        out.put("facts_cleared", facts);
        out.put("contradictions_cleared", conflicts);
        // 清完立刻回读一次：让前端拿到"现在的画像确实空了"，而不是假设它空了
        out.put("digest", digestBuilder.build(owner));
        return out;
    }

    /** 账本总条数 + 投影一致性，运维用。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "ledger_entries", ledger.size(),
                "contradictions", contradictions.count());
    }

    private static String owner(String tenantId, String userId) {
        return tenantId + ":" + userId;
    }
}
