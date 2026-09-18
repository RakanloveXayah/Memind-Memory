package com.trae.memind.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.trae.memind.config.MemindProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式对话重试兜底的离线用例：本地桩服务先按用例给定的报文回应，用尽后一律返回 500。
 *
 * <p>真正的 {@code Connection reset} 无法在用例里稳定复现（要靠对端异常断开），
 * 但重试的触发条件与它是同一个——"这一轮一个增量都没交付出去"，
 * 因此用 HTTP 500 走同一条分支即可验证：抖动一次能救回来、抖动到底会如实报错、
 * 已经吐出内容之后绝不重试。
 */
class OpenAiCompatibleLlmClientStreamRetryTest {

    private static final String CONTENT_FRAME =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}";
    private static final String STOP_FRAME =
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();

    private HttpServer server;
    private int port;

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("首次调用失败且未产出任何输出时重试一次，第二轮正常交付")
    void retriesWhenNothingWasDelivered() throws IOException {
        startStub(null, CONTENT_FRAME + "\n" + STOP_FRAME);

        List<LlmClient.ChatDelta> deltas = new ArrayList<>();
        client().chatStream(List.of(), null, deltas::add);

        assertEquals(2, requests.get(), "上游抖动一次必须被补一次");
        assertEquals("你好", deltas.get(0).content());
        assertEquals("stop", deltas.get(deltas.size() - 1).finishReason());
    }

    @Test
    @DisplayName("连续两次都失败时如实抛出，不退化成一条空回答")
    void givesUpAfterMaxAttempts() throws IOException {
        startStub(null, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client().chatStream(List.of(), null, delta -> {
                }));

        assertTrue(error.getMessage().contains("HTTP 500"), "原始失败原因要透给调用方: " + error.getMessage());
        assertEquals(2, requests.get(), "重试次数必须是上限而非无限");
    }

    @Test
    @DisplayName("已经交付过正文之后不再重试，避免同一段内容渲染两遍")
    void doesNotRetryAfterFirstOutput() throws IOException {
        startStub(CONTENT_FRAME + "\ndata: {这不是合法 JSON}");

        List<LlmClient.ChatDelta> deltas = new ArrayList<>();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client().chatStream(List.of(), null, deltas::add));

        assertTrue(error.getMessage().contains("解析流式报文失败"), error.getMessage());
        assertEquals(1, deltas.size(), "正文已经吐出去了");
        assertEquals(1, requests.get(), "正文已渲染，重试只会让用户看到重复内容");
    }

    // ------------------------------------------------------------------ 桩服务

    /** 按序返回给定报文（{@code null} 表示这一次返回 500）；用尽之后一律 500，模拟"抖动还没好"。 */
    private void startStub(String... responseBodies) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/v1/chat/completions", exchange -> {
            int nth = requests.incrementAndGet();
            try {
                byte[] body = nth <= responseBodies.length && responseBodies[nth - 1] != null
                        ? responseBodies[nth - 1].getBytes(StandardCharsets.UTF_8)
                        : "{\"error\":{\"message\":\"upstream still down\"}}".getBytes(StandardCharsets.UTF_8);
                boolean failed = nth > responseBodies.length || responseBodies[nth - 1] == null;
                exchange.sendResponseHeaders(failed ? 500 : 200, failed ? body.length : 0);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private OpenAiCompatibleLlmClient client() {
        MemindProperties.Llm config = new MemindProperties.Llm(
                "http://127.0.0.1:" + port + "/v1", "stub-key", "stub-model", 10, 0d, null);
        return OpenAiCompatibleLlmClient.forChat(config, mapper);
    }
}