package com.health.agent.memory;

/**
 * 关键事实类型。这些是"必须留痕"的事实——一旦被静默覆盖，后果是致命的。
 *
 * <p>非关键事实（沟通偏好、关注点）不走账本，走可覆盖的画像字段。
 */
public enum FactType {

    ALLERGY("过敏史"),
    CHRONIC("慢病"),
    MEDICATION("长期用药"),
    PREGNANCY("孕产状态"),
    SURGERY("手术史");

    private final String label;

    FactType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
