package com.health.agent.memory;

import java.util.Map;

/**
 * 用户习惯层。
 *
 * <p><b>为什么不和健康档案放一起</b>：两者性质完全不同。
 * <ul>
 *   <li>健康档案是<b>事实</b>：过敏、慢病、用药。写错会出人命，所以要只追加的账本、
 *       要矛盾检测、要用户确认。</li>
 *   <li>习惯是<b>偏好</b>：称呼、沟通方式、作息、口味。写错只是不够贴心，改过来就行，
 *       而且它天然是"最新值有效"——用户换了作息，旧的不该再提。</li>
 * </ul>
 * 把偏好塞进只追加的账本，会让每次偏好调整都变成一条"矛盾"；把事实塞进可覆盖的表，
 * 会让过敏史被静默改写。**两种写入语义不能共用一张表。**
 *
 * <p>另一个作用是<b>控制注入预算</b>：档案段给安全信息用（400 字），
 * 习惯段单独封顶 120 字。混在一起时，几条"喜欢直接说结论"会把慢病挤掉。
 */
public interface HabitStore {

    /** 允许的习惯键。白名单之外的不写——否则模型会源源不断地发明新字段。 */
    enum HabitKey {
        ADDRESS("称呼"),
        STYLE("沟通偏好"),
        ROUTINE("作息"),
        DIET("饮食"),
        EXERCISE("运动"),
        CONCERN("关注点");

        private final String label;

        HabitKey(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static HabitKey of(String code) {
            if (code == null) {
                return null;
            }
            for (HabitKey k : values()) {
                if (k.name().equalsIgnoreCase(code.trim()) || k.label.equals(code.trim())) {
                    return k;
                }
            }
            return null;
        }
    }

    /** 写入或覆盖。习惯是"最新值有效"，不需要留痕。 */
    void put(String userId, HabitKey key, String value);

    /** 该用户的全部习惯。key 是稳定的，value 是给人看的一句话。 */
    Map<HabitKey, String> snapshot(String userId);

    /** 清空（用户要求"忘掉我的偏好"时用）。 */
    void clear(String userId);
}
