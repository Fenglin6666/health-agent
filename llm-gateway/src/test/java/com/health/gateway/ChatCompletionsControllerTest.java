package com.health.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 网关的协议行为测试。
 *
 * <p><b>必须走 MockMvc，不能直接调 controller 方法。</b>
 * 第一版就是直接调方法、断言返回的 ResponseEntity，结果漏掉了一个真实故障：
 * Spring 按方法声明的返回类型选 handler，{@code ResponseEntity<?>} 匹配不上 SSE 写出器，
 * 真实 HTTP 请求直接 500，而单测全绿。MockMvc 会走完整的返回值处理链，
 * 这类"对象对、协议错"的问题才暴露得出来。
 */
class ChatCompletionsControllerTest {

    private HttpServer upstream;
    private final List<String> receivedBodies = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Responder responder;

    interface Responder {
        void respond(HttpExchange ex) throws Exception;
    }

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", ex -> {
            calls.incrementAndGet();
            receivedBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                responder.respond(ex);
            } catch (Exception e) {
                ex.sendResponseHeaders(500, -1);
            }
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    private MockMvc mvc(String token) {
        GatewayProperties props = new GatewayProperties(
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1",
                "test-key", "fallback-model", 10, token, true);
        return MockMvcBuilders.standaloneSetup(new ChatCompletionsController(props)).build();
    }

    @Test
    void passes_through_success_response() throws Exception {
        responder = ex -> {
            byte[] out = "{\"model\":\"m\",\"choices\":[],\"usage\":{\"total_tokens\":7}}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        };
        mvc("").perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[]}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"total_tokens\":7")));
    }

    @Test
    void passes_through_upstream_error_verbatim() throws Exception {
        // 402 必须原样回去：core 要靠它区分"没钱"和"网关挂了"
        responder = ex -> {
            byte[] out = "{\"error\":{\"message\":\"Insufficient Balance\"}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(402, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        };
        mvc("").perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[]}"))
                .andExpect(status().is(402))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Insufficient Balance")));
    }

    @Test
    void streams_without_buffering() throws Exception {
        responder = ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0); // 0 = chunked
            try (OutputStream os = ex.getResponseBody()) {
                os.write("data: {\"delta\":\"你\"}\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        };
        // 这条用真实 HTTP 走一遍返回值处理链：第一版就是这里 500
        mvc("").perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[],\"stream\":true}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"delta\":\"你\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("[DONE]")));
    }

    @Test
    void streaming_error_is_json_not_sse() throws Exception {
        responder = ex -> {
            byte[] err = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(429, err.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(err);
            }
        };
        mvc("").perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[],\"stream\":true}"))
                .andExpect(status().is(429))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("rate limited")));
    }

    @Test
    void retries_with_fallback_model_on_5xx() throws Exception {
        responder = ex -> {
            if (calls.get() == 1) {
                byte[] err = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(500, err.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(err);
                }
                return;
            }
            byte[] ok = "{\"model\":\"fallback-model\",\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, ok.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(ok);
            }
        };
        mvc("").perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"primary\",\"messages\":[]}"))
                .andExpect(status().isOk());
        assertThat(calls.get()).isEqualTo(2);
        assertThat(receivedBodies.get(1)).contains("\"model\":\"fallback-model\"");
    }

    @Test
    void rejects_wrong_internal_token() throws Exception {
        responder = ex -> ex.sendResponseHeaders(200, -1);
        mvc("secret").perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[]}"))
                .andExpect(status().isUnauthorized());
        assertThat(calls.get()).isZero();
    }
}
