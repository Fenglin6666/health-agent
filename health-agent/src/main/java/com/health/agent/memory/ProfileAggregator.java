package com.health.agent.memory;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 档案页聚合器。
 *
 * <p>输入是账本投影（当前值）+ 统计（记录次数、首末时间），输出是可读的档案页。
 * 页面关系来自 {@code configs/memory/pages.yaml}——加一页不改代码。
 *
 * <p>刻意<b>不缓存</b>：两次 SQL 就能算完，缓存反而引入"档案页过期"这种难查的问题。
 */
@Component
public class ProfileAggregator {

    private final FactLedger ledger;
    private final List<PageSpec> specs;

    public ProfileAggregator(FactLedger ledger) {
        this.ledger = ledger;
        this.specs = loadSpecs();
    }

    public List<ProfilePage> build(String owner) {
        Map<FactType, List<FactLedger.Value>> snapshot = ledger.snapshot(owner);
        Map<String, FactLedger.Stat> stats = new HashMap<>();
        for (FactLedger.Stat s : ledger.stats(owner)) {
            stats.put(s.type().name() + "|" + s.key(), s);
        }

        // 先算哪些页非空，链接只连到真实存在的页——指向空页的链接是噪声
        Map<FactType, List<ProfilePage.Entry>> entriesByType = new EnumMap<>(FactType.class);
        for (PageSpec spec : specs) {
            List<FactLedger.Value> values = snapshot.get(spec.type());
            if (values == null || values.isEmpty()) {
                continue;
            }
            List<ProfilePage.Entry> entries = new ArrayList<>();
            for (FactLedger.Value v : values) {
                FactLedger.Stat s = stats.get(spec.type().name() + "|" + v.key());
                entries.add(new ProfilePage.Entry(
                        v.key(),
                        v.value(),
                        s == null ? 1 : s.records(),
                        s == null ? v.updatedAt() : s.firstSeen(),
                        s == null ? v.updatedAt() : s.lastSeen(),
                        s == null ? "" : s.latestEvidence()));
            }
            entriesByType.put(spec.type(), entries);
        }

        List<ProfilePage> pages = new ArrayList<>();
        for (PageSpec spec : specs) {
            List<ProfilePage.Entry> entries = entriesByType.get(spec.type());
            if (entries == null) {
                continue;
            }
            List<ProfilePage.Link> links = new ArrayList<>();
            for (LinkSpec link : spec.links()) {
                if (!entriesByType.containsKey(link.type())) {
                    continue;
                }
                PageSpec target = specOf(link.type());
                links.add(new ProfilePage.Link(
                        pageKey(link.type()),
                        target == null ? link.type().name() : target.title(),
                        link.relation()));
            }
            Instant updated = entries.stream()
                    .map(ProfilePage.Entry::lastSeen)
                    .filter(java.util.Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElse(Instant.now());
            pages.add(new ProfilePage(pageKey(spec.type()), spec.title(), spec.type(), entries, links, updated));
        }
        return pages;
    }

    private PageSpec specOf(FactType type) {
        return specs.stream().filter(s -> s.type() == type).findFirst().orElse(null);
    }

    private static String pageKey(FactType type) {
        return "page:" + type.name().toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private static List<PageSpec> loadSpecs() {
        try (InputStream in = new ClassPathResource("configs/memory/pages.yaml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            List<Map<String, Object>> raw = (List<Map<String, Object>>) root.getOrDefault("pages", List.of());
            List<PageSpec> out = new ArrayList<>();
            for (Map<String, Object> p : raw) {
                FactType type = FactType.valueOf(String.valueOf(p.get("type")));
                List<Map<String, Object>> links = (List<Map<String, Object>>) p.getOrDefault("links", List.of());
                List<LinkSpec> linkSpecs = new ArrayList<>();
                for (Map<String, Object> l : links) {
                    linkSpecs.add(new LinkSpec(
                            FactType.valueOf(String.valueOf(l.get("type"))),
                            String.valueOf(l.get("relation"))));
                }
                out.add(new PageSpec(type, String.valueOf(p.get("title")), linkSpecs));
            }
            return out;
        } catch (Exception e) {
            // 配置错就起不来，别带着空档案页跑——那种问题只会表现为"页面怎么什么都没有"
            throw new IllegalStateException("failed to load configs/memory/pages.yaml", e);
        }
    }

    private record PageSpec(FactType type, String title, List<LinkSpec> links) {}

    private record LinkSpec(FactType type, String relation) {}
}
