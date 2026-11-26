package com.health.agent.memory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 习惯层的 MySQL 实现。
 *
 * <p>用 {@code ON DUPLICATE KEY UPDATE} 而不是"先查再写"：习惯是"最新值有效"，
 * 覆盖是正确语义；而先查再写在并发下会写出两条。
 *
 * <p>这张表<b>刻意没有</b>历史与审计字段。事实需要留痕，偏好不需要——
 * 给偏好加审计只会让"用户说过喜欢简短回答"这种小事变成需要解释的记录。
 */
public class JdbcHabitStore implements HabitStore {

    private final JdbcTemplate jdbc;

    public JdbcHabitStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void put(String userId, HabitKey key, String value) {
        if (userId == null || key == null || value == null || value.isBlank()) {
            return;
        }
        jdbc.update("""
                INSERT INTO user_habit (owner, habit_key, habit_value, updated_at)
                VALUES (?, ?, ?, NOW(3))
                ON DUPLICATE KEY UPDATE habit_value = VALUES(habit_value), updated_at = NOW(3)
                """, userId, key.name(), value.trim());
    }

    @Override
    public Map<HabitKey, String> snapshot(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT habit_key, habit_value FROM user_habit WHERE owner = ?", userId);
        Map<HabitKey, String> out = new EnumMap<>(HabitKey.class);
        for (Map<String, Object> row : rows) {
            HabitKey k = HabitKey.of(String.valueOf(row.get("habit_key")));
            if (k != null) {
                out.put(k, String.valueOf(row.get("habit_value")));
            }
        }
        return out;
    }

    @Override
    public void clear(String userId) {
        jdbc.update("DELETE FROM user_habit WHERE owner = ?", userId);
    }
}
