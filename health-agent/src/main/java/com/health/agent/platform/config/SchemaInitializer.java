package com.health.agent.platform.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 建表 + 连接探测。
 *
 * <p>跑在容器初始化阶段（{@code @PostConstruct}），早于 Web 端口开始接收请求——
 * 所以不存在"请求进来时表还没建好"的窗口。
 *
 * <p>连不上直接抛异常，让 Spring 的启动流程失败。**这是有意的**：一个连不上数据库的
 * 健康助手，能做的只有把用户的话记到内存里然后丢掉。与其跑起来骗人，不如起不来。
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final DataSource dataSource;
    private final boolean memory;

    public SchemaInitializer(DataSource dataSource,
                             @Value("${ha.persistence.mode:jdbc}") String mode) {
        this.dataSource = dataSource;
        this.memory = PersistenceConfig.memory(mode);
    }

    @PostConstruct
    void init() {
        if (memory) {
            log.warn("持久化已关闭（ha.persistence.mode=memory）：会话与账本只存内存，重启即丢");
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/mysql/schema.sql"));
            migrate(conn);
            log.info("持久化就绪：MySQL {}", conn.getMetaData().getURL());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "MySQL 连接失败，启动中止（改 ha.persistence.mode=memory 可跳过持久化）: " + e.getMessage(), e);
        }
    }

    /**
     * 增量迁移。
     *
     * <p>schema.sql 用的是 CREATE TABLE IF NOT EXISTS，对<b>已存在的表</b>加列不生效。
     * 老库升级上来会缺列，运行时报 "Unknown column"——所以补这一步：
     * 先查 information_schema，再决定要不要 ALTER（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）。
     */
    private void migrate(Connection conn) {
        ensureColumn(conn, "message", "turn",
                "ALTER TABLE message ADD COLUMN turn INT NOT NULL DEFAULT 0 AFTER session_id");
    }

    private void ensureColumn(Connection conn, String table, String column, String ddl) {
        try {
            boolean exists;
            try (var ps = conn.prepareStatement("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                    """)) {
                ps.setString(1, table);
                ps.setString(2, column);
                try (var rs = ps.executeQuery()) {
                    exists = rs.next() && rs.getInt(1) > 0;
                }
            }
            if (exists) {
                return;
            }
            try (var st = conn.createStatement()) {
                st.execute(ddl);
                log.info("迁移：{}.{} 已补上", table, column);
            }
        } catch (Exception e) {
            throw new IllegalStateException("迁移失败 " + table + "." + column, e);
        }
    }
}
