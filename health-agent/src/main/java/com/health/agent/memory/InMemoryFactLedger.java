package com.health.agent.memory;


import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版账本。开发与测试用；生产实现见 {@link JdbcFactLedger}。
 *
 * <p>接口不变，换实现不改调用方——这是"换存储改 1 个文件"的落地方式。
 *
 * <p>不加 {@code @Component}：由 {@code PersistenceConfig} 按数据库可用性决定装配哪个实现，
 * 两个实现都注册成 Bean 会直接冲突。
 */
public class InMemoryFactLedger implements FactLedger {

    private final AtomicLong seq = new AtomicLong();
    private final List<Entry> ledger = new CopyOnWriteArrayList<>();
    /** key: userId|type|key */
    private final Map<String, Value> projection = new ConcurrentHashMap<>();

    @Override
    public synchronized Entry append(String userId, FactType type, String key, String value,
                                     double confidence, String sourceMsgId, String recordedBy) {
        String composite = composite(userId, type, key);
        Value previous = projection.get(composite);

        Entry entry = new Entry(
                seq.incrementAndGet(),
                userId,
                type,
                key,
                value,
                confidence,
                sourceMsgId,
                previous == null ? null : previous.version(),
                Instant.now(),
                recordedBy
        );

        ledger.add(entry);
        projection.put(composite, new Value(type, key, value, entry.id(), entry.recordedAt()));
        return entry;
    }

    @Override
    public Optional<Value> current(String userId, FactType type, String key) {
        return Optional.ofNullable(projection.get(composite(userId, type, key)));
    }

    @Override
    public List<Entry> history(String userId, FactType type, String key) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ledger) {
            if (e.userId().equals(userId) && e.type() == type && e.key().equals(key)) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public Map<FactType, List<Value>> snapshot(String userId) {
        Map<FactType, List<Value>> out = new EnumMap<>(FactType.class);
        projection.forEach((composite, value) -> {
            if (composite.startsWith(userId + "|")) {
                out.computeIfAbsent(value.type(), k -> new ArrayList<>()).add(value);
            }
        });
        return out;
    }

    /** 仅供测试与调试：账本总条数。 */
    public int ledgerSize() {
        return ledger.size();
    }

    @Override
    public synchronized int clear(String userId) {
        int before = (int) ledger.stream().filter(e -> e.userId().equals(userId)).count();
        ledger.removeIf(e -> e.userId().equals(userId));
        projection.keySet().removeIf(k -> k.startsWith(userId + "|"));
        return before;
    }

    @Override
    public long size() {
        return ledger.size();
    }

    @Override
    public List<Stat> stats(String userId) {
        Map<String, Stat> acc = new LinkedHashMap<>();
        for (Entry e : ledger) {
            if (!e.userId().equals(userId)) {
                continue;
            }
            String k = e.type().name() + "|" + e.key();
            Stat prev = acc.get(k);
            acc.put(k, new Stat(
                    e.type(),
                    e.key(),
                    prev == null ? 1 : prev.records() + 1,
                    prev == null ? e.recordedAt() : prev.firstSeen(),
                    e.recordedAt(),
                    e.sourceMsgId() == null ? "" : e.sourceMsgId()));
        }
        return new ArrayList<>(acc.values());
    }

    /** 校验投影与账本是否偏离，dreaming 定期调用。 */
    public Map<String, String> verify() {
        Map<String, Value> expected = new LinkedHashMap<>();
        for (Entry e : ledger) {
            expected.put(composite(e.userId(), e.type(), e.key()),
                    new Value(e.type(), e.key(), e.value(), e.id(), e.recordedAt()));
        }
        Map<String, String> drift = new LinkedHashMap<>();
        expected.forEach((k, v) -> {
            Value actual = projection.get(k);
            if (actual == null || !actual.value().equals(v.value())) {
                drift.put(k, "expected=" + v.value() + " actual=" + (actual == null ? "null" : actual.value()));
            }
        });
        return drift;
    }

    private static String composite(String userId, FactType type, String key) {
        return userId + "|" + type.name() + "|" + key;
    }
}
