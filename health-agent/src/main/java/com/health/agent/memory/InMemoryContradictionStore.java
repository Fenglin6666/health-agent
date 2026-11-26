package com.health.agent.memory;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 内存版矛盾存储。没有数据库时用，行为和 MySQL 版一致。 */
public class InMemoryContradictionStore implements ContradictionStore {

    private final AtomicLong seq = new AtomicLong();
    private final Map<Long, Contradiction> rows = new ConcurrentHashMap<>();

    @Override
    public Contradiction save(Contradiction contradiction) {
        Contradiction stored = contradiction.withId(seq.incrementAndGet());
        rows.put(stored.id(), stored);
        return stored;
    }

    @Override
    public List<Contradiction> open(String owner) {
        return rows.values().stream()
                .filter(c -> c.owner().equals(owner) && c.status() == Contradiction.Status.OPEN)
                .sorted(Comparator.comparingLong(Contradiction::id).reversed())
                .toList();
    }

    @Override
    public List<Contradiction> list(String owner, int limit) {
        List<Contradiction> all = new ArrayList<>(rows.values().stream()
                .filter(c -> c.owner().equals(owner))
                .sorted(Comparator.comparingLong(Contradiction::id).reversed())
                .toList());
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    @Override
    public boolean resolve(long id, Contradiction.Status status) {
        Contradiction c = rows.get(id);
        if (c == null) {
            return false;
        }
        rows.put(id, c.withStatus(status));
        return true;
    }

    @Override
    public int clear(String owner) {
        int before = rows.size();
        rows.values().removeIf(c -> c.owner().equals(owner));
        return before - rows.size();
    }

    @Override
    public long count() {
        return rows.size();
    }
}
