package com.health.agent.safety;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 输出闸门：所有回答在返回前必过。不调模型，纯规则。
 *
 * <p>两种模式（见 ADR-13）：
 * <ul>
 *   <li>{@code self}（默认）：只记录越界表述，不改写，免责声明可关</li>
 *   <li>{@code public}：改写诊断性表述，强制免责声明</li>
 * </ul>
 *
 * <p>模式开关只在这里生效——散落到各 agent 的 prompt 里就会不一致。
 */
@Component
public class SafetyGate {

    /** 诊断性表述。命中不代表回答错误，只代表"表述越界"。 */
    private static final List<String> DIAGNOSTIC_PHRASES = List.of(
            "确诊", "诊断为", "你得了", "可以确定是", "一定是", "肯定是"
    );

    private static final String DISCLAIMER =
            "以上为健康信息整理，非医疗诊断。如症状持续或加重，请及时就医。";

    public SafetyVerdict review(String output, String mode, boolean redFlagHit) {
        String text = output == null ? "" : output;
        List<String> issues = new ArrayList<>();

        for (String phrase : DIAGNOSTIC_PHRASES) {
            if (text.contains(phrase)) {
                issues.add("diagnostic_phrase:" + phrase);
            }
        }

        if ("public".equalsIgnoreCase(mode) && !issues.isEmpty()) {
            text = soften(text);
        }

        boolean needDisclaimer = redFlagHit || !"self".equalsIgnoreCase(mode);
        if (needDisclaimer && !text.contains("非医疗诊断")) {
            text = text + "\n\n" + DISCLAIMER;
        }

        return new SafetyVerdict(text, List.copyOf(issues), !issues.isEmpty());
    }

    /** 把断言改成"可能相关"。只做替换，不改结构——改写结构要靠模型，那是另一层的事。 */
    private static String soften(String text) {
        return text
                .replace("确诊", "提示")
                .replace("诊断为", "考虑")
                .replace("你得了", "可能与以下情况相关：")
                .replace("可以确定是", "需要进一步确认是否为")
                .replace("一定是", "可能与")
                .replace("肯定是", "可能与");
    }

    public record SafetyVerdict(String text, List<String> issues, boolean violated) {}
}
