package com.health.agent.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.health.agent.kernel.app.AgentKernel;
import com.health.agent.kernel.infra.LlmClient;
import com.health.agent.kernel.infra.Tool;
import com.health.agent.kernel.infra.ToolRegistry;
import com.health.agent.kernel.infra.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 内核与领域服务的装配。
 *
 * <p>领域服务刻意不加 Spring 注解，
 * 由这里显式装配——好处是 domain 包保持零框架依赖，单测不需要容器。
 */
@Configuration
public class KernelConfig {

    @Bean
    public Tracer tracer() {
        return new Tracer();
    }

    @Bean
    public ToolRegistry toolRegistry(List<Tool> tools) {
        ToolRegistry registry = new ToolRegistry();
        tools.forEach(registry::register);
        return registry;
    }

    @Bean
    public AgentKernel agentKernel(LlmClient llm, ToolRegistry tools, Tracer tracer, AgentProperties props) {
        // historyWindow 是"每次调模型带多少条历史"，不是会话轮次上限
        return new AgentKernel(llm, tools, tracer, props.historyWindow());
    }

}
