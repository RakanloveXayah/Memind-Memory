package com.trae.memind.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容 SSE 流式报文的解析器：逐行喂入，逐块吐出 {@link LlmClient.ChatDelta}。
 *
 * <p>独立成类是为了让"分片拼接 / 字段分流"这两处最容易出错的逻辑可以被离线用例直接断言，
 * 而不必真的连一次模型。
 *
 * <p>两条硬约束：
 * <ul>
 *   <li><b>reasoning 与 content 永不合流</b>：思考模型的 {@code delta.reasoning_content} 是思维链，
 *       混进正文会让回复变成一坨自我独白；</li>
 *   <li><b>tool_calls 必须按 index 累积</b>：{@code function.arguments} 是分片下发的，
 *       提前 {@code readTree} 只能拿到半个 JSON，必须等 {@code finish_reason} 到了再拼成完整对象。</li>
 * </ul>
 */
public final class ChatStreamParser implements Consumer<String> {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_PAYLOAD = "[DONE]";

    private final ObjectMapper mapper;
    private final Consumer<LlmClient.ChatDelta> onDelta;

    /** key 是协议里的 tool_calls[].index，LinkedHashMap 保证工具按声明顺序回灌。 */
    private final Map<Integer, ToolCallAccumulator> accumulators = new LinkedHashMap<>();

    private String finishReason;
    private boolean done;

    public ChatStreamParser(ObjectMapper mapper, Consumer<LlmClient.ChatDelta> onDelta) {
        this.mapper = mapper;
        this.onDelta = onDelta;
    }

    /** 喂入一行原始报文；非 data 行（注释、心跳、空行）直接忽略。 */
    @Override
    public void accept(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith(DATA_PREFIX)) {
            return;
        }
        String payload = trimmed.substring(DATA_PREFIX.length()).trim();
        if (payload.isEmpty()) {
            return;
        }
        if (DONE_PAYLOAD.equals(payload)) {
            flushIfPending();
            done = true;
            return;
        }

        JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("解析流式报文失败: " + payload, ex);
        }
        if (node.has("error")) {
            throw new IllegalStateException("流式对话返回错误: " + payload);
        }
        JsonNode choice = node.path("choices").path(0);
        if (choice.isMissingNode()) {
            return;
        }

        JsonNode delta = choice.path("delta");
        String reasoning = delta.path("reasoning_content").asText("");
        String content = delta.path("content").asText("");

        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                accumulate(call);
            }
        }

        String reason = choice.path("finish_reason").asText("");
        if (!reason.isEmpty()) {
            // 结束帧：此刻 arguments 才拼完整，一次性交出本轮的全部工具调用。
            List<LlmClient.ToolCall> calls = drainAccumulators();
            finishReason = reason;
            onDelta.accept(new LlmClient.ChatDelta("", "", calls, reason));
            return;
        }
        if (reasoning.isEmpty() && content.isEmpty()) {
            // 首帧（以及工具调用分片的中间帧）三者皆空，发出去只会污染前端。
            return;
        }
        onDelta.accept(new LlmClient.ChatDelta(content, reasoning, List.of(), null));
    }

    /**
     * 流在未收到 finish_reason 的情况下结束（连接被掐断等）时的兜底：
     * 把已经攒下的工具调用交出去，避免模型"已经决定调工具"却被静默丢弃。
     */
    public void finish() {
        if (done) {
            return;
        }
        flushIfPending();
    }

    /** 结束原因；供调用方在流结束后判断本轮是否异常终止。 */
    public String finishReason() {
        return finishReason;
    }

    private void flushIfPending() {
        List<LlmClient.ToolCall> calls = drainAccumulators();
        if (calls.isEmpty()) {
            return;
        }
        String reason = finishReason == null ? "tool_calls" : finishReason;
        finishReason = reason;
        onDelta.accept(new LlmClient.ChatDelta("", "", calls, reason));
    }

    private void accumulate(JsonNode call) {
        int index = call.path("index").asInt(0);
        ToolCallAccumulator accumulator = accumulators.computeIfAbsent(index, ToolCallAccumulator::new);
        String id = call.path("id").asText("");
        if (!id.isEmpty()) {
            accumulator.id = id;
        }
        JsonNode function = call.path("function");
        String name = function.path("name").asText("");
        if (!name.isEmpty()) {
            accumulator.name = name;
        }
        String arguments = function.path("arguments").asText("");
        if (!arguments.isEmpty()) {
            accumulator.arguments.append(arguments);
        }
    }

    private List<LlmClient.ToolCall> drainAccumulators() {
        if (accumulators.isEmpty()) {
            return List.of();
        }
        List<LlmClient.ToolCall> calls = new ArrayList<>(accumulators.size());
        accumulators.values().forEach(acc -> calls.add(acc.toCall()));
        accumulators.clear();
        return calls;
    }

    /** 分片累加器：id / name 取首次非空值，arguments 逐片拼接。 */
    private static final class ToolCallAccumulator {

        private final int index;
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        private ToolCallAccumulator(int index) {
            this.index = index;
        }

        private LlmClient.ToolCall toCall() {
            return new LlmClient.ToolCall(index, id, name,
                    arguments.length() == 0 ? "{}" : arguments.toString());
        }
    }
}