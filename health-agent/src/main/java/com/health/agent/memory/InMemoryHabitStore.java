package com.health.agent.memory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 习惯层的内存实现。只在 {@code ha.persistence.mode=memory} 和单测里用。 */
public class InMemoryHabitStore implements HabitStore {

    private final Map<String, Map<HabitKey, String>> data = new ConcurrentHashMap<>();

    @Override
    public void put(String userId, HabitKey key, String value) {
        if (userId == null || key == null || value == null || value.isBlank()) {
            return;
        }
        data.computeIfAbsent(userId, k -> new EnumMap<>(HabitKey.class)).put(key, value.trim());
    }

    @Override
    public Map<HabitKey, String> snapshot(String userId) {
        Map<HabitKey, String> m = data.get(userId);
        return m == null ? Map.of() : Map.copyOf(m);
    }

    @Override
    public void clear(String userId) {
        data.remove(userId);
    }
}
