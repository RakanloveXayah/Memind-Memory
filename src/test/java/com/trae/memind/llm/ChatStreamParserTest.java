package com.trae.memind.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 报文解析的离线用例：用固定报文覆盖流式对话里最容易出错的三处。
 *
 * <p>不连模型也能回归——真实模型的输出每次都不一样，靠它验证解析逻辑迟早会变成"看运气"。
 */
class ChatStreamParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("首帧空 delta 被丢弃，reasoning 与 content 分流到各自字段")
    void splitsReasoningFromContent() {
        List<LlmClient.ChatDelta> deltas = feed(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"用户想要\"},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"最近的数据\"},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"近 30 天 GMV \"},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"128.4 万。\"},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                ": keep-alive",
                "data: [DONE]");

        assertEquals(5, deltas.size(), "首帧空 delta 必须被丢弃，否则前端会收到一串空增量");
        assertEquals("用户想要最近的数据", join(deltas, true));
        assertEquals("近 30 天 GMV 128.4 万。", join(deltas, false));
        assertFalse(join(deltas, false).contains("用户想要"), "思维链一旦混进正文就无法区分了");
        LlmClient.ChatDelta last = deltas.get(deltas.size() - 1);
        assertEquals("stop", last.finishReason());
        assertTrue(last.toolCalls().isEmpty());
    }

    @Test
    @DisplayName("tool_calls 按 index 累加分片参数，收到 finish_reason 才交出完整对象")
    void assemblesFragmentedToolCalls() throws Exception {
        List<LlmClient.ChatDelta> deltas = feed(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"query_sales\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"{\\\"metric\\\":\"}}]},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"gmv\\\",\\\"time_range\\\":\\\"30d\\\"}\"}}]},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: [DONE]");

        assertEquals(1, deltas.size(), "参数没拼完之前不能生成任何 ChatDelta");
        LlmClient.ChatDelta flush = deltas.get(0);
        assertEquals("tool_calls", flush.finishReason());
        assertTrue(flush.hasToolCalls());

        LlmClient.ToolCall call = flush.toolCalls().get(0);
        assertEquals("call_1", call.id());
        assertEquals("query_sales", call.name());
        assertEquals(0, call.index());
        // 这一行才是重点：分片拼接后的 arguments 必须是可解析的完整 JSON
        assertEquals("gmv", mapper.readTree(call.arguments()).path("metric").asText());
        assertEquals("30d", mapper.readTree(call.arguments()).path("time_range").asText());
    }

    @Test
    @DisplayName("一次返回多个工具调用时按 index 分别累积、按序交出")
    void keepsMultipleToolCallsOrderedByIndex() {
        List<LlmClient.ChatDelta> deltas = feed(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\","
                        + "\"function\":{\"name\":\"get_current_time\",\"arguments\":\"{}\"}},"
                        + "{\"index\":1,\"id\":\"call_b\",\"function\":{\"name\":\"calculator\",\"arguments\":\"{\\\"expression\\\":\"}}]},"
                        + "\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,"
                        + "\"function\":{\"arguments\":\"\\\"1+1\\\"}\"}}]},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");

        assertEquals(1, deltas.size());
        List<LlmClient.ToolCall> calls = deltas.get(0).toolCalls();
        assertEquals(2, calls.size());
        assertEquals("get_current_time", calls.get(0).name());
        assertEquals("{}", calls.get(0).arguments());
        assertEquals("calculator", calls.get(1).name());
        assertEquals("{\"expression\":\"1+1\"}", calls.get(1).arguments());
    }

    @Test
    @DisplayName("流被上游截断时 finish() 兜底交出已攒下的工具调用")
    void flushesPendingToolCallsWhenStreamTruncated() {
        List<LlmClient.ChatDelta> deltas = new ArrayList<>();
        ChatStreamParser parser = new ChatStreamParser(mapper, deltas::add);

        parser.accept("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\","
                + "\"function\":{\"name\":\"run_sql\",\"arguments\":\"{\\\"sql\\\":\\\"select 1\\\"}\"}}]},"
                + "\"finish_reason\":null}]}");
        assertTrue(deltas.isEmpty(), "没有 finish_reason 之前不能提前交出半个 JSON");

        parser.finish();
        assertEquals(1, deltas.size());
        assertEquals("run_sql", deltas.get(0).toolCalls().get(0).name());
        assertEquals("tool_calls", deltas.get(0).finishReason());
    }

    @Test
    @DisplayName("上游错误帧转成可读异常，而不是静默变成一条空回答")
    void surfacesUpstreamErrorFrame() {
        ChatStreamParser parser = new ChatStreamParser(mapper, delta -> {
        });
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> parser.accept("data: {\"error\":{\"message\":\"rate limit exceeded\"}}"));
        assertTrue(error.getMessage().contains("rate limit exceeded"));
    }

    // ------------------------------------------------------------------ 辅助

    private List<LlmClient.ChatDelta> feed(String... lines) {
        List<LlmClient.ChatDelta> deltas = new ArrayList<>();
        ChatStreamParser parser = new ChatStreamParser(mapper, deltas::add);
        for (String line : lines) {
            parser.accept(line);
        }
        parser.finish();
        return deltas;
    }

    private static String join(List<LlmClient.ChatDelta> deltas, boolean reasoning) {
        StringBuilder builder = new StringBuilder();
        for (LlmClient.ChatDelta delta : deltas) {
            String part = reasoning ? delta.reasoning() : delta.content();
            if (part != null) {
                builder.append(part);
            }
        }
        return builder.toString();
    }
}