package com.trae.memind.chat;

import java.util.List;

/**
 * 流式聊天的 SSE 事件契约。
 *
 * <p>与 {@code MemoryApi} 同样采用「一个容器类 + 嵌套 record」的写法，让前端、实测脚本与
 * 离线用例引用的字段名只有一个来源。
 *
 * <p>事件顺序固定为：{@code meta} → （{@code reasoning} / {@code delta} / {@code tool_call} /
 * {@code tool_result}）* → {@code done} → {@code writeback}，任何阶段出问题则插入 {@code error}。
 */
public final class ChatEvents {

    private ChatEvents() {
    }

    /**
     * 首帧：检索与上下文组装的结果。
     *
     * <p>思考模型的首字延迟很长，先把这一帧推给前端，界面才不会"假死"。
     *
     * @param degraded 是否处于离线降级（未配置对话模型，由规则引擎应答）
     * @param context  注入 System Prompt 的完整上下文原文，供前端折叠展示
     */
    public record Meta(String sessionId, String provider, boolean degraded, String strategy,
                       int memoryCount, int insightCount, int estimatedTokens, boolean truncated,
                       String context, List<ToolInfo> tools) {
    }

    /** 暴露给模型的能力声明，便于前端展示"这轮它能调哪些工具"。 */
    public record ToolInfo(String name, String type, String description) {
    }

    /** 思维链增量；永远是独立的字段，绝不混入正文。 */
    public record Reasoning(String text) {
    }

    /** 正文增量。 */
    public record Delta(String text) {
    }

    public record ToolCall(int round, String id, String name, String type, String arguments) {
    }

    /**
     * 工具执行结果。
     *
     * @param ok        失败时 result 是 {@code {"error":"..."}}，仍会照常回灌给模型让它纠错
     * @param elapsedMs 执行耗时
     */
    public record ToolResult(int round, String id, String name, boolean ok, long elapsedMs, String result) {
    }

    /**
     * 写回结果。
     *
     * @param userStatus  USER 侧抽取状态：SUCCESS / FAILED / SKIPPED
     * @param agentStatus AGENT 侧抽取状态；本轮没有工具调用时为 SKIPPED
     */
    public record Writeback(String userStatus, int userItems, String agentStatus, int agentItems, String message) {
    }

    /**
     * 本轮回答的收尾统计。
     *
     * @param rounds         实际走过的模型轮次（含逼出结论的那一轮）
     * @param reasoningChars 思维链字符数；与 contentChars 对比可验证"思考模型分流"是否生效
     */
    public record Done(int rounds, int contentChars, int reasoningChars, int toolCalls, long elapsedMs) {
    }

    /** @param stage 出错阶段：chat / writeback */
    public record Error(String stage, String message) {
    }
}