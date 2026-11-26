package com.health.agent;

import com.health.agent.llm.LlmProperties;
import com.health.agent.platform.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({LlmProperties.class, AgentProperties.class})
public class HealthAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthAgentApplication.class, args);
    }
}
