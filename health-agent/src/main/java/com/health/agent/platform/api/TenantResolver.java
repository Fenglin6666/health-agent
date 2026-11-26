package com.health.agent.platform.api;

import com.health.agent.kernel.domain.Session;

/**
 * 租户解析。
 *
 * <p>抽成独立类而不是每个 Controller 各写一遍：租户校验是安全边界，
 * 复制粘贴之后只要有一处漏了格式校验，那一处就成了注入入口。
 *
 * <p><b>当前只有 header 一种来源，没有认证——header 可伪造。</b>
 * 这里能做的是格式校验（防注入）+ 所有查询带租户条件（防漏过滤）。
 * 真正的身份校验要等接入认证后补，届时 header 只保留本地开发用。
 */
public final class TenantResolver {

    private TenantResolver() {
    }

    public static String resolve(String header) {
        if (header == null || header.isBlank()) {
            return Session.DEFAULT_TENANT;
        }
        String t = header.trim().toLowerCase();
        if (!t.matches("[a-z0-9_-]{1,32}")) {
            throw new ApiErrors.BadRequest("invalid tenant id");
        }
        return t;
    }
}
