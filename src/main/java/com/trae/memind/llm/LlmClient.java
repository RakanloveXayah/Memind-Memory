package com.trae.memind.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Consumer;

/** LLM 能力抽象：抽取、洞察提炼、上下文扩展共用的最小接口。 */
public interface LlmClient {

    /**
     * 流式对话的一个增量块。
     *
     * @param content      正文增量（思考模型只在这里放最终回答）
     * @param reasoning    思维链增量（思考模型专有，绝不能与正文合流）
     * @param toolCalls    完整工具调用；仅在收到 finish_reason 时才非空
     * @param finishReason 结束原因：stop / tool_calls / length；无结束帧时为 null
     */
    record ChatDelta(String content, String reasoning, List<ToolCall> toolCalls, String finishReason) {

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /**
     * 一次工具调用请求。
     *
     * @param arguments 完整 JSON 字符串（协议上是分片下发的，需按 index 拼接）
     */
    record ToolCall(int index, String id, String name, String arguments) {
    }

    /**
     * 带历史的流式对话：传入完整 messages（含 assistant 的 tool_calls 与 role=tool 的结果），逐块回调。
     *
     * @param messages 完整消息数组，顺序即上下文顺序
     * @param tools    OpenAI tools schema；为空表示本轮不允许调用工具
     * @param onDelta  增量回调，按到达顺序触发
     */
    default void chatStream(List<ObjectNode> messages, ArrayNode tools, Consumer<ChatDelta> onDelta) {
        throw new UnsupportedOperationException("当前 LLM 客户端不支持流式对话");
    }

    /** 当前是否具备真实的对话模型能力（未配置 API Key 时为 false，抽取会降级到规则引擎）。 */
    boolean chatAvailable();

    /** 返回模型输出的原始文本。 */
    String chat(String systemPrompt, String userPrompt);

    /** 向量维度，落库与相似度计算都依赖它保持一致。 */
    int embeddingDimension();

    /** 向量能力是否由远程模型提供（false 表示本地特征哈希降级）。 */
    boolean embeddingRemote();

    /** 批量向量化，顺序与入参严格对应。 */
    List<float[]> embed(List<String> texts);

    default float[] embedOne(String text) {
        return embed(List.of(text)).get(0);
    }

    /** 提供方名称，便于日志排查。 */
    String provider();
}