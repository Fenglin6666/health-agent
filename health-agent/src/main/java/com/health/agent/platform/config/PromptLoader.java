package com.health.agent.platform.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 加载器。
 *
 * <p>存在的理由：prompt 是资产，要能 diff、能回滚、能被评测集覆盖。写在 Java 字符串里，
 * 这三件事一件都做不了。
 *
 * <p><b>片段复用</b>：用 {@code {{include:片段名}}} 引入 {@code prompts/fragments/片段名.md}。
 * 只解决"同一段规则要在多个 agent 里保持一致"这一个问题——红旗清单改一处，doctor 和 care
 * 同时生效。不做条件加载、不做按需激活：四个 agent 的 prompt 加起来不到 400 行，
 * 那套机制的成本大于收益。
 */
@Component
public class PromptLoader {

    private static final Pattern INCLUDE = Pattern.compile("\\{\\{include:([a-zA-Z0-9_-]+)}}");
    private static final int MAX_DEPTH = 3;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String load(String classpathLocation) {
        return cache.computeIfAbsent(classpathLocation, path -> expand(read(path), 0));
    }

    private String expand(String text, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("prompt 片段嵌套超过 " + MAX_DEPTH + " 层，可能存在循环引用");
        }
        Matcher m = INCLUDE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String fragment = read("prompts/fragments/" + m.group(1) + ".md");
            m.appendReplacement(sb, Matcher.quoteReplacement(expand(fragment, depth + 1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("prompt not found: " + path, e);
        }
    }

    /** 简单占位符替换。模板变量统一用 {{name}} 形式。 */
    public static String fill(String template, Map<String, String> vars) {
        String out = template;
        for (var entry : vars.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }
}
