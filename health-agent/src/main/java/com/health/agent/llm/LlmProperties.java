package com.health.agent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型接入配置。只放连接信息，不放业务。
 *
 * <p>前缀用 {@code ha.llm} 而不是 {@code llm}：Spring 的 relaxed binding 会把环境变量
 * {@code LLM_API_KEY} 直接映射到 {@code llm.api-key}，而很多宿主环境（IDE、容器、CI）
 * 都注入了这个通用变量名，导致配置被静默覆盖。加一层前缀可以隔离。
 */
@ConfigurationProperties(prefix = "ha.llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String modelManager,
        String modelExecute,
        int timeoutSeconds,
        int maxRetries,
        boolean offline,
        /** 调用 llm-gateway 时出示的内网令牌。直连模型时留空。 */
        String internalToken
) {
    public LlmProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl;
        modelManager = modelManager == null || modelManager.isBlank() ? "gpt-4o-mini" : modelManager;
        modelExecute = modelExecute == null || modelExecute.isBlank() ? "gpt-4o" : modelExecute;
        timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        maxRetries = maxRetries < 0 ? 1 : maxRetries;
        internalToken = internalToken == null ? "" : internalToken;
    }

    /**
     * 只有显式开启才走离线桩。
     *
     * <p>之前是"没有 api-key 就自动离线"。拆出网关后 core 本来就没有密钥，
     * 再按密钥判断会让它静默走桩——用户看到一堆模板回答，日志里却没有任何错误。
     * 现在没配网关也没配密钥，会直接连接失败，这是对的：宁可报错，不要假装能用。
     */
    public boolean isOfflineEffective() {
        return offline;
    }
}
