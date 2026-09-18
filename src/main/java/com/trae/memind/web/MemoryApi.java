package com.trae.memind.web;

import java.util.List;
import java.util.Map;

/** REST 接入层的请求 / 响应契约。 */
public final class MemoryApi {

    private MemoryApi() {
    }

    /** 单条对话消息。 */
    public record MessageDto(String role, String content) {
    }

    /**
     * 写入请求。
     *
     * @param sessionId   会话 ID
     * @param contentType CONVERSATION / DOCUMENT / TOOL_CALL / AGENT_TIMELINE
     * @param messages    对话消息；与 text 二选一
     * @param text        非对话正文（文档、工具轨迹、音频逐字稿等）
     * @param scope       目标作用域：USER / AGENT；缺省时取请求头 X-Memory-Scope
     */
    public record ExtractRequest(String sessionId, String contentType, List<MessageDto> messages,
                                 String text, String scope) {
    }

    /** fire-and-forget 写入响应：仅代表任务已派发，抽取结果需异步获取。 */
    public record ExtractAcceptedResponse(String rawContentId, String status) {
    }

    public record MemoryItemDto(String id, String type, String scope, String namespace, String content,
                                double confidence, Map<String, String> metadata, String sourceId, String createdAt) {
    }

    /** 同步写入响应：只有 status=SUCCESS 才代表记忆已落库。 */
    public record SyncExtractResponse(String rawContentId, String status, String strategy, int chunkCount,
                                      int itemCount, List<MemoryItemDto> items, String message) {
    }

    public record RetrieveRequest(String query, List<String> scopes, Integer topK) {
    }

    public record RetrievedMemoryDto(String id, String type, String scope, String content, double confidence,
                                     double score, List<String> channels, String createdAt) {
    }

    /**
     * 流式聊天请求。
     *
     * @param message     用户这句话
     * @param sessionId   会话 ID，缺省时服务端生成；同时作为写回记忆的 session
     * @param scopes      检索作用域，缺省 USER + AGENT
     * @param topK        向量/融合后的召回条数上限
     * @param tokenBudget 注入上下文的最大 Token 预算
     * @param enableTools 是否允许模型调用工具，缺省 true
     * @param writeback   本轮结束后是否自动写回记忆，缺省取服务端配置
     */
    public record ChatRequest(String message, String sessionId, List<String> scopes, Integer topK,
                              Integer tokenBudget, Boolean enableTools, Boolean writeback) {
    }

    public record RetrievedInsightDto(String id, String level, String scope, String groupKey, String content,
                                      double confidence, double score) {
    }

    public record RetrieveResponse(String strategy, List<String> keywords, List<String> timeSignals,
                                   List<String> entities, List<RetrievedMemoryDto> memories,
                                   List<RetrievedInsightDto> insights) {
    }

    public record CompileContextRequest(String query, List<String> scopes, Integer topK, Integer tokenBudget) {
    }

    public record CompileContextResponse(String context, int estimatedTokens, int memoryCount, int insightCount,
                                         boolean truncated, String strategy) {
    }

    public record HealthResponse(String status, String version, String llmProvider, boolean chatAvailable,
                                 boolean embeddingRemote) {
    }

    public record ConsolidateResponse(String status, String message) {
    }

    public record InsightTreeResponse(String namespace, String scope, List<RetrievedInsightDto> nodes) {
    }
}