package com.health.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关配置。
 *
 * <p>密钥只存在这里，不出现在 core。core 拿的是 {@code internal-token}，
 * 它只能证明"我是内网调用方"，换不到上游凭证——这样即使 core 被攻破，
 * 攻击者也拿不到模型账号。
 */
@ConfigurationProperties(prefix = "ha.gateway")
public record GatewayProperties(
        /** 上游地址，含版本段，如 https://api.deepseek.com/v1 */
        String upstreamBaseUrl,
        String apiKey,
        /** 主模型失败时的兜底模型，空表示不兜底 */
        String fallbackModel,
        int timeoutSeconds,
        /** 内网调用令牌。空表示不校验（仅本地开发） */
        String internalToken,
        /** 上游 5xx / 429 时是否换 fallback 模型重试一次 */
        boolean fallbackEnabled
) {
    public GatewayProperties {
        upstreamBaseUrl = upstreamBaseUrl == null || upstreamBaseUrl.isBlank()
                ? "https://api.openai.com/v1" : upstreamBaseUrl;
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        fallbackModel = fallbackModel == null ? "" : fallbackModel;
        internalToken = internalToken == null ? "" : internalToken;
    }

    public boolean authRequired() {
        return !internalToken.isBlank();
    }

    public boolean canFallback() {
        return fallbackEnabled && !fallbackModel.isBlank();
    }
}
