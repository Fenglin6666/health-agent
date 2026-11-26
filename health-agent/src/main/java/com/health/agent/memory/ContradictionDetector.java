package com.health.agent.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 矛盾检测：新事实和当前投影对不上时，产出一条待确认项。
 *
 * <p>只做规则，不调模型。理由是**可解释**：每条矛盾都能说清"依据哪条规则、比的是哪两个值"。
 * 用模型判断"这两句话矛盾吗"，你没法向用户解释为什么，也没法回归测试。
 *
 * <p>检测的时机是"写入前"——写完再比就分不清哪条是旧的。
 */
@Component
public class ContradictionDetector {

    /** 表示"没有"的说法。过敏史里这几种写法都出现过。 */
    private static final List<String> NEGATIONS = List.of("无", "否认", "没有", "阴性", "未见");

    /** 表示"已停药"的说法。 */
    private static final List<String> STOPPED = List.of("停", "已停", "停药", "中断");

    public List<Contradiction> detect(String owner, FactType type, String key, String newValue,
                                      FactLedger ledger) {
        var current = ledger.current(owner, type, key);
        if (current.isEmpty()) {
            return List.of();
        }
        String oldValue = current.get().value();
        if (equivalent(oldValue, newValue)) {
            return List.of();
        }
        return rules(owner, type, key, oldValue, newValue);
    }

    private List<Contradiction> rules(String owner, FactType type, String key,
                                      String oldValue, String newValue) {
        List<Contradiction> out = new ArrayList<>();

        if (type == FactType.ALLERGY) {
            boolean oldNeg = isNegation(oldValue);
            boolean newNeg = isNegation(newValue);
            if (oldNeg != newNeg) {
                out.add(Contradiction.of(owner, type, key,
                        Contradiction.Kind.ALLERGY_FLIP, Contradiction.Severity.HIGH,
                        oldValue, newValue,
                        oldNeg
                                ? "此前记录为无过敏，本次记录了具体过敏原——可能是新发现，也可能是理解错了"
                                : "此前记录了过敏原，本次记录为无——极可能是模型误判，需确认后才能采信"));
            } else {
                out.add(Contradiction.of(owner, type, key,
                        Contradiction.Kind.VALUE_CHANGED, Contradiction.Severity.MEDIUM,
                        oldValue, newValue, "过敏原记录发生变化"));
            }
            return out;
        }

        if (type == FactType.MEDICATION) {
            if (containsAny(oldValue, STOPPED) && !containsAny(newValue, STOPPED)) {
                out.add(Contradiction.of(owner, type, key,
                        Contradiction.Kind.MEDICATION_RESTART, Contradiction.Severity.MEDIUM,
                        oldValue, newValue, "此前记录为已停药，本次记录为在用——确认是重新开始服用，还是听错了"));
            } else if (containsAny(newValue, STOPPED) && !containsAny(oldValue, STOPPED)) {
                // 停药是有意行为，不算矛盾，但值确实变了，留给档案页展示
                return List.of();
            } else {
                out.add(Contradiction.of(owner, type, key,
                        Contradiction.Kind.DOSAGE_CHANGE, Contradiction.Severity.MEDIUM,
                        oldValue, newValue, "用法或剂量发生变化"));
            }
            return out;
        }

        if (type == FactType.CHRONIC) {
            out.add(Contradiction.of(owner, type, key,
                    Contradiction.Kind.CONDITION_CHANGE, Contradiction.Severity.LOW,
                    oldValue, newValue, "慢病记录发生变化"));
            return out;
        }

        out.add(Contradiction.of(owner, type, key,
                Contradiction.Kind.VALUE_CHANGED, Contradiction.Severity.LOW,
                oldValue, newValue, "记录值发生变化"));
        return out;
    }

    private static boolean isNegation(String value) {
        return containsAny(value, NEGATIONS);
    }

    private static boolean containsAny(String value, List<String> words) {
        if (value == null) {
            return false;
        }
        return words.stream().anyMatch(value::contains);
    }

    /** 去掉空白和标点，避免"每天 50 微克"和"每天50微克"被判成两个值。 */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s，。、；：,.;:（）()\\[\\]【】]", "").trim();
    }

    /**
     * 判断两个值是不是"同一事实的不同说法"。
     *
     * <p>这条规则是评测逼出来的：第一版只做去空格比较，结果"五年"和"5 年"、
     * "50μg 每日一次"和"每天50微克"全被报成矛盾。**矛盾检测最大的风险不是漏报，
     * 是噪声**——用户看三次假警报之后，真的那条也不会看了。
     *
     * <p>判据分两步，顺序不能反：
     * <ol>
     *   <li>数值必须一致。剂量、年限这类事实，数字变了就是真的变了，
     *       文本再像也不能放过（"每天50微克" vs "每天100微克"）。</li>
     *   <li>数值一致时看文本相似度。相似说明只是换了说法，不报。</li>
     * </ol>
     */
    static boolean equivalent(String a, String b) {
        String ca = canonical(a);
        String cb = canonical(b);
        if (ca.equals(cb)) {
            return true;
        }
        if (!numbers(ca).equals(numbers(cb))) {
            return false;
        }
        return jaccard(ca, cb) >= 0.6;
    }

    /** 归一化：单位同义、中文数词转阿拉伯、去掉"每日一次"这类冗余。 */
    static String canonical(String value) {
        if (value == null) {
            return "";
        }
        String s = value.toLowerCase();
        // "每日一次"就是"每天"，多出来的"一次"会让字符集合对不上
        s = s.replaceAll("(每日|每天|一日|1日)(一次|1次)", "每天");
        s = s.replace("μg", "微克").replace("mcg", "微克").replace("ug", "微克")
                .replace("mg", "毫克").replace("ml", "毫升")
                .replace("每日", "每天").replace("一日", "每天");
        s = chineseDigits(s);
        return normalize(s);
    }

    /** 只替换"数词 + 量词"里的数词，避免把"二甲双胍"里的"二"改成 2。 */
    private static String chineseDigits(String s) {
        String[] cn = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        for (int i = 0; i < cn.length; i++) {
            s = s.replaceAll(cn[i] + "(?=[年月天次片粒袋周])", String.valueOf(i + 1));
        }
        return s;
    }

    /** 抽出全部数字串。用 List 而不是 Set：重复的数字也是信息。 */
    private static List<String> numbers(String s) {
        List<String> out = new ArrayList<>();
        var m = java.util.regex.Pattern.compile("\\d+(\\.\\d+)?").matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        out.sort(String::compareTo);
        return out;
    }

    private static double jaccard(String a, String b) {
        var sa = bigrams(a);
        var sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0;
        }
        var inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        var union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    private static List<String> bigrams(String s) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }
}
