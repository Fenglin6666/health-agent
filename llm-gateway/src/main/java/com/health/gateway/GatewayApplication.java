package com.health.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 模型接入网关。
 *
 * <p>存在的理由只有一条：<b>模型凭证不该散在业务进程里</b>。
 * 拆出去之后，换模型、加灰度、算 token 成本都只动这一个服务，
 * core 只需要知道"往哪儿发"。
 *
 * <p>它刻意做得很薄——只是 OpenAI 协议的透传代理，不理解 Decision、不认识 agent。
 * 一旦它开始理解业务，就会变成第二个 core。
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
