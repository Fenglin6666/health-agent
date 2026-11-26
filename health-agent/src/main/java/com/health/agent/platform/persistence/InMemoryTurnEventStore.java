package com.health.agent.platform.persistence;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 内存实现。只在 ha.persistence.mode=memory 与单测里用。 */
public class InMemoryTurnEventStore implements TurnEventStore {

    private final Map<String, Map<String, TurnEvent>> data = new ConcurrentHashMap<>();

    @Override
    public void append(TurnEvent e) {
        data.computeIfAbsent(e.sessionId(), k -> new ConcurrentHashMap<>())
                .putIfAbsent(e.turn() + ":" + e.seq(), e);
    }

    @Override
    public List<TurnEvent> list(String sessionId) {
        Map<String, TurnEvent> m = data.get(sessionId);
        if (m == null) {
            return List.of();
        }
        return m.values().stream()
                .sorted(Comparator.comparingInt(TurnEvent::turn).thenComparingInt(TurnEvent::seq))
                .toList();
    }
}
