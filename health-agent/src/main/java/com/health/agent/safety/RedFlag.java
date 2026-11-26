package com.health.agent.safety;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 一条红旗规则。
 *
 * @param anyOf      命中其中任意一个词即进入候选
 * @param anyPattern 命中其中任意一个正则即进入候选（处理"吐了一口血"这类变体）
 * @param andAny     还需命中其中任意一个词（空表示不要求）
 * @param ageMax     年龄上限。**age 未知时不排除**——宁可误报，不可漏
 */
public record RedFlag(
        String id,
        List<String> anyOf,
        List<String> anyPattern,
        List<String> andAny,
        Integer ageMax,
        String message
) {

    /** 正则编译有开销，规则是固定的，缓存起来。 */
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    public RedFlag {
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
        anyPattern = anyPattern == null ? List.of() : List.copyOf(anyPattern);
        andAny = andAny == null ? List.of() : List.copyOf(andAny);
    }

    public boolean matches(String input, Integer age) {
        if (input == null || input.isBlank()) {
            return false;
        }
        if (anyOf.isEmpty() && anyPattern.isEmpty()) {
            return false;
        }
        // 关键：age 为 null 表示"不知道"，此时不能因为年龄条件把儿童急症规则整个关掉。
        // 漏掉热性惊厥的代价远大于给成年人误报一次。
        if (ageMax != null && age != null && age > ageMax) {
            return false;
        }

        boolean first = anyOf.stream().anyMatch(input::contains)
                || anyPattern.stream().anyMatch(p ->
                        PATTERN_CACHE.computeIfAbsent(p, Pattern::compile).matcher(input).find());
        if (!first) {
            return false;
        }
        return andAny.isEmpty() || andAny.stream().anyMatch(input::contains);
    }
}
