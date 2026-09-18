package com.trae.memind.web;

import com.trae.memind.domain.ConversationLog;
import com.trae.memind.domain.ExtractionStatus;
import com.trae.memind.domain.MemoryItem;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.llm.LlmClient;
import com.trae.memind.pipeline.ExtractionInput;
import com.trae.memind.pipeline.ExtractionOutcome;
import com.trae.memind.pipeline.ExtractionPipeline;
import com.trae.memind.retrieval.ContextCompiler;
import com.trae.memind.retrieval.RetrievalEngine;
import com.trae.memind.retrieval.RetrievalResult;
import com.trae.memind.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 记忆引擎对外接入层（对应文档 4.2 的 REST API 方式）。
 *
 * <p>租户上下文由 {@code X-Tenant-Id} / {@code X-User-Id} / {@code X-Memory-Scope} 请求头提供，
 * 所有端点都不接受调用方显式传入 tenantId，从根本上杜绝越权访问。
 */
@RestController
@RequestMapping("/open/v1")
public class MemoryController {

    private static final String VERSION = "0.1.0";

    private final ExtractionPipeline pipeline;
    private final RetrievalEngine retrievalEngine;
    private final ContextCompiler contextCompiler;
    private final LlmClient llmClient;

    public MemoryController(ExtractionPipeline pipeline,
                            RetrievalEngine retrievalEngine,
                            ContextCompiler contextCompiler,
                            LlmClient llmClient) {
        this.pipeline = pipeline;
        this.retrievalEngine = retrievalEngine;
        this.contextCompiler = contextCompiler;
        this.llmClient = llmClient;
    }

    @GetMapping("/health")
    public MemoryApi.HealthResponse health() {
        return new MemoryApi.HealthResponse("UP", VERSION, llmClient.provider(),
                llmClient.chatAvailable(), llmClient.embeddingRemote());
    }

    /**
     * 写入端点（fire-and-forget）。
     * HTTP 成功仅代表 Memind 已接受并派发任务，不代表抽取已完成。
     */
    @PostMapping("/memory/extract")
    public ResponseEntity<MemoryApi.ExtractAcceptedResponse> extract(@RequestBody MemoryApi.ExtractRequest request) {
        TenantContext context = TenantContext.current();
        MemoryScope scope = resolveScope(request, context);
        String rawContentId = pipeline.submitAsync(context, buildInput(request, context, scope));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new MemoryApi.ExtractAcceptedResponse(rawContentId, ExtractionStatus.ACCEPTED.name()));
    }

    /**
     * 同步写入端点：返回真实的抽取状态与产出。
     * 调用方应仅在 status=SUCCESS 时清除本地重试状态。
     */
    @PostMapping("/memory/sync/extract")
    public MemoryApi.SyncExtractResponse syncExtract(@RequestBody MemoryApi.ExtractRequest request) {
        TenantContext context = TenantContext.current();
        MemoryScope scope = resolveScope(request, context);
        ExtractionOutcome outcome = pipeline.extractNow(context, buildInput(request, context, scope));
        return new MemoryApi.SyncExtractResponse(
                outcome.rawContentId(),
                outcome.status().name(),
                outcome.strategy(),
                outcome.chunkCount(),
                outcome.items().size(),
                outcome.items().stream().map(MemoryController::toDto).toList(),
                outcome.message());
    }

    /** 检索端点：返回融合排序后的记忆与洞察，含命中通道，便于调参与可解释性排查。 */
    @PostMapping("/memory/retrieve")
    public MemoryApi.RetrieveResponse retrieve(@RequestBody MemoryApi.RetrieveRequest request) {
        requireQuery(request.query());
        TenantContext context = TenantContext.current();
        RetrievalResult result = retrievalEngine.retrieve(context, resolveScopes(request.scopes()),
                request.query(), request.topK());
        return new MemoryApi.RetrieveResponse(
                result.strategy(),
                result.signals().keywords(),
                result.signals().timeSignals(),
                result.signals().entities(),
                result.memories().stream()
                        .map(scored -> new MemoryApi.RetrievedMemoryDto(
                                scored.item().id(),
                                scored.item().type().name(),
                                scored.item().scope().name(),
                                scored.item().content(),
                                scored.item().confidence(),
                                round(scored.score()),
                                scored.channels(),
                                scored.item().createdAt().toString()))
                        .toList(),
                result.insights().stream()
                        .map(scored -> new MemoryApi.RetrievedInsightDto(
                                scored.node().id(),
                                scored.node().level().name(),
                                scored.node().scope().name(),
                                scored.node().groupKey(),
                                scored.node().content(),
                                scored.node().confidence(),
                                round(scored.score())))
                        .toList());
    }

    /** 上下文组装端点：返回可直接注入 System Prompt 的文本。 */
    @PostMapping("/memory/compile_context")
    public MemoryApi.CompileContextResponse compileContext(@RequestBody MemoryApi.CompileContextRequest request) {
        requireQuery(request.query());
        TenantContext context = TenantContext.current();
        RetrievalResult result = retrievalEngine.retrieve(context, resolveScopes(request.scopes()),
                request.query(), request.topK());
        ContextCompiler.CompiledContext compiled = contextCompiler.compile(result, request.tokenBudget());
        return new MemoryApi.CompileContextResponse(
                compiled.text(),
                compiled.estimatedTokens(),
                compiled.memoryCount(),
                compiled.insightCount(),
                compiled.truncated(),
                result.strategy());
    }

    // ------------------------------------------------------------------ 内部工具

    private ExtractionInput buildInput(MemoryApi.ExtractRequest request, TenantContext context, MemoryScope scope) {
        if (request.messages() != null && !request.messages().isEmpty()) {
            StringBuilder transcript = new StringBuilder();
            List<ConversationLog> logs = new ArrayList<>();
            Instant now = Instant.now();
            String sessionId = request.sessionId() == null ? "default" : request.sessionId();
            for (MemoryApi.MessageDto message : request.messages()) {
                if (message == null || message.content() == null || message.content().isBlank()) {
                    continue;
                }
                String role = message.role() == null || message.role().isBlank() ? "user" : message.role();
                transcript.append(role).append(": ").append(message.content().trim()).append('\n');
                logs.add(new ConversationLog(
                        UUID.randomUUID().toString().replace("-", ""),
                        context.tenantId(),
                        context.userId(),
                        scope,
                        context.namespaceOf(scope),
                        sessionId,
                        role,
                        message.content().trim(),
                        now));
            }
            if (transcript.isEmpty()) {
                throw new IllegalArgumentException("messages 中没有任何有效内容");
            }
            return new ExtractionInput(scope,
                    request.contentType() == null ? "CONVERSATION" : request.contentType(),
                    sessionId, transcript.toString(), logs);
        }
        if (request.text() != null && !request.text().isBlank()) {
            return new ExtractionInput(scope,
                    request.contentType() == null ? "DOCUMENT" : request.contentType(),
                    request.sessionId(), request.text(), List.of());
        }
        throw new IllegalArgumentException("请求必须提供 messages 或 text");
    }

    private MemoryScope resolveScope(MemoryApi.ExtractRequest request, TenantContext context) {
        return request.scope() == null || request.scope().isBlank()
                ? context.scope()
                : MemoryScope.from(request.scope());
    }

    private List<MemoryScope> resolveScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of(MemoryScope.USER, MemoryScope.AGENT);
        }
        List<MemoryScope> resolved = scopes.stream().map(MemoryScope::from).distinct().toList();
        return resolved.isEmpty() ? List.of(MemoryScope.USER) : resolved;
    }

    private static void requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    static MemoryApi.MemoryItemDto toDto(MemoryItem item) {
        return new MemoryApi.MemoryItemDto(
                item.id(),
                item.type().name(),
                item.scope().name(),
                item.namespace(),
                item.content(),
                item.confidence(),
                item.metadata(),
                item.sourceId(),
                item.createdAt().toString());
    }
}