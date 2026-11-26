package com.health.agent.platform.config;

import com.health.agent.memory.ContradictionStore;
import com.health.agent.memory.FactLedger;
import com.health.agent.memory.HabitStore;
import com.health.agent.memory.InMemoryContradictionStore;
import com.health.agent.memory.InMemoryHabitStore;
import com.health.agent.memory.InMemoryFactLedger;
import com.health.agent.memory.JdbcContradictionStore;
import com.health.agent.memory.JdbcHabitStore;
import com.health.agent.memory.JdbcFactLedger;
import com.health.agent.platform.feedback.FeedbackRepository;
import com.health.agent.platform.feedback.InMemoryFeedbackRepository;
import com.health.agent.platform.feedback.JdbcFeedbackRepository;
import com.health.agent.platform.persistence.InMemorySessionRepository;
import com.health.agent.platform.persistence.InMemoryTurnEventStore;
import com.health.agent.platform.persistence.JdbcTurnEventStore;
import com.health.agent.platform.persistence.TurnEventStore;
import com.health.agent.platform.persistence.JdbcSessionRepository;
import com.health.agent.platform.persistence.SessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 持久化装配。
 *
 * <p><b>默认连不上就启动失败</b>，不自动降级。降级到内存后产生的会话和事实一条都存不下——
 * 那是个"看起来能用"的假状态，比启动失败更危险：用户以为记录下来了。
 *
 * <p>{@code ha.persistence.mode=memory} 是给本地调试用的**显式**开关：改 prompt 不想开
 * Docker 时手动设置，启动日志会明确警告"重启即丢"。自动降级和主动选择是两件事。
 *
 * <p>建表在 {@link SchemaInitializer} 里做，它在容器初始化阶段执行，早于端口开始接收请求。
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public SessionRepository sessionRepository(JdbcTemplate jdbc,
                                               @Value("${ha.persistence.mode:jdbc}") String mode) {
        return memory(mode) ? new InMemorySessionRepository() : new JdbcSessionRepository(jdbc);
    }

    @Bean
    public FactLedger factLedger(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                 @Value("${ha.persistence.mode:jdbc}") String mode) {
        return memory(mode)
                ? new InMemoryFactLedger()
                : new JdbcFactLedger(jdbc, new TransactionTemplate(txManager));
    }

    @Bean
    public ContradictionStore contradictionStore(JdbcTemplate jdbc,
                                                 @Value("${ha.persistence.mode:jdbc}") String mode) {
        return memory(mode) ? new InMemoryContradictionStore() : new JdbcContradictionStore(jdbc);
    }

    @Bean
    public HabitStore habitStore(JdbcTemplate jdbc,
                                 @Value("${ha.persistence.mode:jdbc}") String mode) {
        return memory(mode) ? new InMemoryHabitStore() : new JdbcHabitStore(jdbc);
    }

    @Bean
    public TurnEventStore turnEventStore(JdbcTemplate jdbc,
                                         @Value("${ha.persistence.mode:jdbc}") String mode) {
        return memory(mode) ? new InMemoryTurnEventStore() : new JdbcTurnEventStore(jdbc);
    }

    @Bean
    public FeedbackRepository feedbackRepository(JdbcTemplate jdbc,
                                                 @Value("${ha.persistence.mode:jdbc}") String mode) {
        return memory(mode) ? new InMemoryFeedbackRepository() : new JdbcFeedbackRepository(jdbc);
    }

    static boolean memory(String mode) {
        return "memory".equalsIgnoreCase(mode);
    }
}
