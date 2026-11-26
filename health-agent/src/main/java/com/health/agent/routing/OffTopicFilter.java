package com.health.agent.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 前置过滤：只拦"明确不是健康问题"的输入。
 *
 * <p><b>它不做业务路由。</b>判断"这是问药还是问诊"必须交给 LLM——用关键词判业务意图，
 * 一旦判错，整条链路都错，而且错得理直气壮（用户看不出来）。最典型的事故：
 * "我爸突然说话不清、右手抬不起来" 命中"怎么回事"之类的词，被路由到科普，答一段养生建议。
 *
 * <p>这里只回答一个问题：<b>这句话要不要送进模型？</b>两种情况下答案是"不送"：
 * <ol>
 *   <li>纯寒暄（整句精确匹配，带任何别的内容都不算）</li>
 *   <li>明确的域外请求（写代码、解方程、写文案），且<b>不含任何健康信号词</b></li>
 * </ol>
 *
 * <p>第 2 条的"且不含健康词"是关键：<b>股票跌了睡不着</b> 既命中"股票"又命中"睡不好"，
 * 这种必须放行。漏拦的代价是多一次模型调用（400ms），误拦的代价是用户被拒绝回答——
 * 两者不对等，所以一律偏向放行。
 */
@Component
public class OffTopicFilter {

    private static final Logger log = LoggerFactory.getLogger(OffTopicFilter.class);

    /** 命中即拦截的结果。reply 是直接返回给用户的文本，不经过模型。 */
    public record Block(String kind, String reply) {}

    private final Set<String> greetings = new HashSet<>();
    private final List<String> offTopicPhrases = new ArrayList<>();
    private final List<String> healthWords = new ArrayList<>();

    public OffTopicFilter() {
        try (InputStream in = new ClassPathResource("configs/rules/off_topic.yaml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            if (root != null) {
                loadGreetings(root.get("greetings"));
                loadOffTopic(root.get("off_topic"));
                loadList(root.get("health"), healthWords);
            }
            log.info("域外过滤加载：寒暄 {} 条，域外信号 {} 条，健康信号 {} 条",
                    greetings.size(), offTopicPhrases.size(), healthWords.size());
        } catch (Exception e) {
            // 配置读不到就起不来。带着空词表跑等于关闭过滤，而"过滤静默失效"没人会发现。
            throw new IllegalStateException("failed to load configs/rules/off_topic.yaml", e);
        }
    }

    public Optional<Block> check(String input) {
        String text = normalize(input);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (greetings.contains(text)) {
            return Optional.of(new Block("greeting", GREETING_REPLY));
        }

        String hit = firstHit(text, offTopicPhrases);
        if (hit == null) {
            return Optional.empty();
        }
        // 双命中一律放行：宁可多调一次模型，也不把可能沾健康的问题挡在外面
        if (firstHit(text, healthWords) != null) {
            log.debug("域外信号 '{}' 与健康信号同现，放行给 Manager", hit);
            return Optional.empty();
        }
        log.debug("域外拦截：命中 '{}'", hit);
        return Optional.of(new Block("off_topic", OFF_TOPIC_REPLY));
    }

    private static final String GREETING_REPLY =
            "你好，我是健康管家。可以描述症状、问药品用法、让我解读检查报告，或者帮你找附近的药店和医院。";

    private static final String OFF_TOPIC_REPLY =
            "我是健康助手，只处理症状、用药、检查报告和就医相关的问题，这个我帮不上忙。"
                    + "如果有身体不舒服的地方，可以直接说。";

    private static String firstHit(String text, List<String> phrases) {
        for (String p : phrases) {
            if (text.contains(p)) {
                return p;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void loadGreetings(Object node) {
        if (node instanceof List<?> list) {
            for (Object item : list) {
                greetings.add(normalize(String.valueOf(item)));
            }
        }
    }

    /** 域外信号按类别分组，这里只关心"有哪些词"，分类是给人看的。 */
    private void loadOffTopic(Object node) {
        if (node instanceof Map<?, ?> map) {
            for (Object value : ((Map<String, Object>) map).values()) {
                loadList(value, offTopicPhrases);
            }
        }
    }

    private static void loadList(Object node, List<String> target) {
        if (node instanceof List<?> list) {
            for (Object item : list) {
                String s = normalize(String.valueOf(item));
                if (!s.isEmpty()) {
                    target.add(s);
                }
            }
        }
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase()
                .replaceAll("[\\s，。？！、,.?!~…—\\-]+", "")
                .trim();
    }
}
