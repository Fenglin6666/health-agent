package com.health.agent.memory;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 构造每次会话预加载的"患者画像"（injected context）。
 *
 * <p><b>两段，各有预算</b>：
 * <ul>
 *   <li>档案段 ≤ 400 字：待确认 → 过敏 → 用药 → 慢病 → 状态 → 手术。顺序按安全性排，
 *       超长截断尾部——前几项写错会出人命，后面的只是体验优化。</li>
 *   <li>习惯段 ≤ 120 字：称呼、沟通偏好、作息。单独封顶是刻意的：混在一个预算里，
 *       几条"喜欢直接说结论"就会把慢病挤掉。**安全信息不能被体验信息挤占。**</li>
 * </ul>
 *
 * <p>为什么待确认排第一：一条未确认的过敏史矛盾，比"每天 50 微克"这种细节重要一个量级。
 * 而且它是**一次性的**——用户确认后就不再出现，不会长期占用预算。
 */
@Component
public class DigestBuilder {

    private static final int MAX_CHARS = 400;
    private static final int MAX_HABIT_CHARS = 120;
    private static final int MAX_CONTRADICTIONS = 3;

    private final FactLedger ledger;
    private final ContradictionStore contradictions;
    private final HabitStore habits;

    public DigestBuilder(FactLedger ledger, ContradictionStore contradictions, HabitStore habits) {
        this.ledger = ledger;
        this.contradictions = contradictions;
        this.habits = habits;
    }

    public String build(String owner) {
        String profile = buildProfile(owner);
        String habit = buildHabit(owner);
        if (profile.isEmpty()) {
            return habit;
        }
        return habit.isEmpty() ? profile : profile + "\n" + habit;
    }

    /** 档案段：健康事实。没有事实也没有待确认项时返回空串。 */
    public String buildProfile(String owner) {
        Map<FactType, List<FactLedger.Value>> snapshot = ledger.snapshot(owner);
        List<Contradiction> open = contradictions.open(owner);
        if (snapshot.isEmpty() && open.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("【患者画像】\n");
        appendContradictions(sb, open);
        append(sb, "过敏", snapshot.get(FactType.ALLERGY));
        append(sb, "长期用药", snapshot.get(FactType.MEDICATION));
        append(sb, "慢病", snapshot.get(FactType.CHRONIC));
        append(sb, "状态", snapshot.get(FactType.PREGNANCY));
        append(sb, "手术", snapshot.get(FactType.SURGERY));

        String text = sb.toString().trim();
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS) + "…";
    }

    /**
     * 习惯段：偏好，单独封顶。
     *
     * <p>输出成"称呼：张阿姨；沟通偏好：喜欢先说结论"这种一行式，而不是分条列。
     * 分条会让模型把它当清单逐条执行，一行式它更容易当成语气参考——**我们要的是语气，不是规则**。
     */
    public String buildHabit(String owner) {
        Map<HabitStore.HabitKey, String> snapshot = habits.snapshot(owner);
        if (snapshot.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【习惯】");
        boolean first = true;
        for (HabitStore.HabitKey k : HabitStore.HabitKey.values()) {
            String v = snapshot.get(k);
            if (v == null || v.isBlank()) {
                continue;
            }
            if (!first) {
                sb.append("；");
            }
            sb.append(k.label()).append("：").append(v);
            first = false;
        }
        if (first) {
            return "";
        }
        String text = sb.toString();
        return text.length() <= MAX_HABIT_CHARS ? text : text.substring(0, MAX_HABIT_CHARS) + "…";
    }

    /**
     * 待确认项要写成"问题"，不是"结论"。
     *
     * <p>如果直接写"过敏：青霉素"，模型会当成事实用；写成"此前无、本次青霉素，需确认"，
     * 模型才会在下一轮顺带问一句。**提示词的措辞决定了模型的行为**。
     */
    private static void appendContradictions(StringBuilder sb, List<Contradiction> open) {
        if (open.isEmpty()) {
            return;
        }
        sb.append("待确认（不要当成事实，必要时向用户核实）：");
        int n = 0;
        for (Contradiction c : open) {
            if (n++ > 0) {
                sb.append("；");
            }
            sb.append(c.key()).append(" 此前为「").append(c.oldValue())
                    .append("」，本次为「").append(c.newValue()).append("」");
            if (n >= MAX_CONTRADICTIONS) {
                break;
            }
        }
        sb.append('\n');
    }

    private static void append(StringBuilder sb, String label, List<FactLedger.Value> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sb.append(label).append("：");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(values.get(i).key());
            if (!values.get(i).value().equals(values.get(i).key())) {
                sb.append("（").append(values.get(i).value()).append("）");
            }
        }
        sb.append('\n');
    }
}
