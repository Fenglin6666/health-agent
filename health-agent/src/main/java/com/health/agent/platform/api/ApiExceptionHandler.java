package com.health.agent.platform.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把接口层的两类异常映射成状态码。
 *
 * <p>之前没这层时，跨租户访问返回 500——用户看到的是"服务器错误"，
 * 既误导运维（以为是 bug），也让攻击者能用状态码差异做探测。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiErrors.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(ApiErrors.NotFound e) {
        return body(HttpStatus.NOT_FOUND, "10001", e.getMessage(), false);
    }

    @ExceptionHandler(ApiErrors.BadRequest.class)
    public ResponseEntity<Map<String, Object>> badRequest(ApiErrors.BadRequest e) {
        return body(HttpStatus.BAD_REQUEST, "10002", e.getMessage(), false);
    }

    private static ResponseEntity<Map<String, Object>> body(
            HttpStatus status, String code, String message, boolean retryable) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("message", message == null ? "" : message);
        out.put("retryable", retryable);
        return ResponseEntity.status(status).body(out);
    }
}
