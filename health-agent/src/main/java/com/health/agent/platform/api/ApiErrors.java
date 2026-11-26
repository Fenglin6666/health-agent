package com.health.agent.platform.api;

/**
 * 接口层的两类错误。
 *
 * <p>分开定义而不是共用一个 IllegalArgumentException：状态码不同，且
 * "不存在"与"参数非法"混在一起时，调用方只能靠 message 字符串判断。
 */
public final class ApiErrors {

    private ApiErrors() {}

    /** 404：资源不存在，或存在但不属于当前租户。 */
    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** 400：参数非法（如租户 ID 格式不对）。 */
    public static class BadRequest extends RuntimeException {
        public BadRequest(String message) {
            super(message);
        }
    }
}
