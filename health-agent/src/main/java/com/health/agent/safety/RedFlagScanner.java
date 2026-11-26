package com.health.agent.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 红旗扫描器：每轮输入全量扫，不依赖科室分类，成本 &lt; 1ms。
 *
 * <p>为什么独立于意图识别：科室分片越高效，漏检越安静。"上腹痛"会被判成消化内科，
 * 但下壁心梗就表现为上腹痛——追问也问不出来，患者只会说"就是胃疼"。
 * 这条通道是唯一不依赖分类准确率的安全网。
 */
@Component
public class RedFlagScanner {

    private static final Logger log = LoggerFactory.getLogger(RedFlagScanner.class);

    private final List<RedFlag> flags = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public RedFlagScanner(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("configs/safety/red_flags.yaml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Object node = root == null ? null : root.get("red_flags");
            if (node instanceof List<?> items) {
                for (Object item : items) {
                    RedFlag flag = mapper.convertValue(item, RedFlag.class);
                    if (flag.id() != null && (!flag.anyOf().isEmpty() || !flag.anyPattern().isEmpty())) {
                        flags.add(flag);
                    }
                }
            }
            log.info("red flags loaded: {}", flags.size());
        } catch (Exception e) {
            throw new IllegalStateException("failed to load red flags", e);
        }
    }

    public Optional<RedFlag> scan(String input, Integer age) {
        for (RedFlag flag : flags) {
            if (flag.matches(input, age)) {
                log.warn("red flag hit: {}", flag.id());
                return Optional.of(flag);
            }
        }
        return Optional.empty();
    }

    public int size() {
        return flags.size();
    }
}
