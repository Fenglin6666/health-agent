package com.health.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 兼容协议的透传代理。
 *
 * <p>三个必须做对的地方：
 * <ol>
 *   <li><b>流式不能缓冲</b>。上游吐一个 chunk 就往下游写一个，攒完再发等于把流式变成长连接。</li>
 *   <li><b>错误要原样透传</b>。上游 402/429/500 的状态码和正文都要给回 core，
 *       否则 core 只能看到"网关 500"，分不清是没钱还是模型挂了。</li>
 *   <li><b>流式不重试</b>。已经发出去的 token 收不回来，重试会让用户看到重复内容。
 *       兜底重试只对非流式请求生效。</li>
 * </ol>
 *
 * <p><b>为什么直接写 HttpServletResponse，而不是返回 ResponseEntity：</b>
 * 返回 {@code ResponseEntity<?>} 时，Spring 选不到 SSE 写出器——它按方法声明的返回类型
 * 挑 handler，通配符泛型匹配不上 {@code StreamingResponseBody}，最后落到 Jackson，
 * 报 "No converter for Lambda with preset Content-Type text/event-stream"。
 * 单测直接调方法、检查返回对象，发现不了这个问题；真实 HTTP 一打就 500。
 * 代理这种"字节怎么进怎么出"的场景，绕过消息转换器反而更可靠。
 */
@RestController
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final GatewayProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ChatCompletionsController(GatewayProperties props) {
        this.props = props;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("service", "llm-gateway");
        out.put("upstream", props.upstreamBaseUrl());
        out.put("fallback", props.canFallback() ? props.fallbackModel() : "");
        return out;
    }

    @PostMapping("/v1/chat/completions")
    public void completions(
            @RequestBody JsonNode body,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            HttpServletResponse response) throws IOException {

        if (props.authRequired() && !props.internalToken().equals(token)) {
            writeError(response, 401, "invalid internal token");
            return;
        }
        if (body == null || !body.has("messages")) {
            writeError(response, 400, "messages is required");
            return;
        }

        boolean stream = body.path("stream").asBoolean(false);
        try {
            if (stream) {
                proxyStream(body, response);
            } else {
                proxyBlocking(body, response);
            }
        } catch (Exception e) {
            log.error("proxy failed model={} stream={}", body.path("model").asText(), stream, e);
            // 响应可能已经提交（流式写到一半），这时不能再改状态码，只能断开让客户端感知
            if (!response.isCommitted()) {
                writeError(response, 502, "upstream unreachable: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------

    private void proxyBlocking(JsonNode body, HttpServletResponse response) throws Exception {
        HttpResponse<String> resp = send(body);
        meter(body, resp.body());

        // 上游 5xx / 429 且有兜底模型 → 换模型重试一次。
        // 只在非流式做：流式的字节已经发出去了，重试等于让用户看两遍开头。
        if (shouldFallback(resp.statusCode()) && props.canFallback()) {
            log.warn("upstream {} with model={}, retry with fallback={}",
                    resp.statusCode(), body.path("model").asText(), props.fallbackModel());
            ObjectNode retry = body.deepCopy();
            retry.put("model", props.fallbackModel());
            resp = send(retry);
            meter(retry, resp.body());
        }
        writeRaw(response, resp.statusCode(), "application/json",
                resp.body().getBytes(StandardCharsets.UTF_8));
    }

    private void proxyStream(JsonNode body, HttpServletResponse response) throws Exception {
        HttpResponse<InputStream> resp = http.send(build(body), HttpResponse.BodyHandlers.ofInputStream());

        if (resp.statusCode() != 200) {
            // 流式请求的错误是普通 JSON，读出来原样返回，不能当 SSE 推给客户端
            byte[] err;
            try (InputStream in = resp.body()) {
                err = in.readAllBytes();
            }
            log.warn("upstream stream failed status={} body={}",
                    resp.statusCode(), abbreviate(new String(err, StandardCharsets.UTF_8)));
            writeRaw(response, resp.statusCode(), "application/json", err);
            return;
        }

        response.setStatus(200);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        try (InputStream in = resp.body()) {
            OutputStream out = response.getOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        }
    }

    private HttpResponse<String> send(JsonNode body) throws Exception {
        return http.send(build(body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest build(JsonNode body) {
        String url = props.upstreamBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!props.apiKey().isBlank()) {
            b.header("Authorization", "Bearer " + props.apiKey());
        }
        return b.build();
    }

    private static boolean shouldFallback(int status) {
        return status == 429 || status >= 500;
    }

    /** 计量：只记 token 数，不记内容。日志里出现用户问诊内容就是隐私事故。 */
    private void meter(JsonNode request, String responseBody) {
        try {
            JsonNode resp = mapper.readTree(responseBody);
            JsonNode usage = resp.path("usage");
            log.info("llm model={} prompt_tokens={} completion_tokens={} total_tokens={}",
                    resp.path("model").asText(request.path("model").asText()),
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0));
        } catch (Exception ignored) {
            // 计量失败不能影响主链路
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        String body = mapper.writeValueAsString(
                Map.of("error", Map.of("message", message, "type", "gateway_error")));
        writeRaw(response, status, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeRaw(HttpServletResponse response, int status,
                                 String contentType, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentType(contentType + ";charset=UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
