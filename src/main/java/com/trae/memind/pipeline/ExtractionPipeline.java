package com.trae.memind.pipeline;

import com.trae.memind.config.MemindProperties;
import com.trae.memind.domain.ConversationLog;
import com.trae.memind.domain.MemoryItem;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.domain.RawContent;
import com.trae.memind.insight.InsightTreeEngine;
import com.trae.memind.llm.LlmClient;
import com.trae.memind.store.MemoryStore;
import com.trae.memind.tenant.TenantContext;
import com.trae.memind.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 记忆抽取流水线：Raw Data → Chunker → Extraction Strategy → Memory Items → 去重 → 落库 → 触发整合。
 *
 * <p>对应文档 5.1 写入路径。默认写入端点是 fire-and-forget（{@link #submitAsync}），
 * 同步端点（{@link #extractNow}）返回真实的抽取状态与产出，供需要重试语义的调用方使用。
 */
@Service
public class ExtractionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ExtractionPipeline.class);

    private final MemoryStore store;
    private final ExtractionStrategy strategy;
    private final Deduplicator deduplicator;
    private final Chunker chunker;
    private final LlmClient llmClient;
    private final InsightTreeEngine insightTreeEngine;
    private final TaskExecutor executor;
    private final MemindProperties properties;

    public ExtractionPipeline(MemoryStore store,
                              ExtractionStrategy strategy,
                              Deduplicator deduplicator,
                              LlmClient llmClient,
                              InsightTreeEngine insightTreeEngine,
                              @Qualifier("memindExecutor") TaskExecutor executor,
                              MemindProperties properties) {
        this.store = store;
        this.strategy = strategy;
        this.deduplicator = deduplicator;
        this.llmClient = llmClient;
        this.insightTreeEngine = insightTreeEngine;
        this.executor = executor;
        this.properties = properties;
        this.chunker = new Chunker(properties.extraction().chunkSize());
    }

    /**
     * fire-and-forget 写入：立即返回，抽取与整合在线程池中完成，不阻塞对话主链路。
     *
     * @return 原始内容 ID，仅代表任务已派发
     */
    public String submitAsync(TenantContext context, ExtractionInput input) {
        String rawContentId = newId();
        RawContent rawContent = toRawContent(context, input, rawContentId);
        store.saveRawContent(rawContent);
        executor.execute(() -> TenantContext.runWith(context, () -> {
            try {
                run(context, input, rawContentId);
            } catch (Exception ex) {
                log.error("异步抽取失败 rawContentId={}", rawContentId, ex);
            }
        }));
        return rawContentId;
    }

    /** 同步抽取：返回真实状态，仅当 status 为 SUCCESS 时调用方才应清除本地重试状态。 */
    public ExtractionOutcome extractNow(TenantContext context, ExtractionInput input) {
        String rawContentId = newId();
        store.saveRawContent(toRawContent(context, input, rawContentId));
        try {
            List<MemoryItem> items = run(context, input, rawContentId);
            return ExtractionOutcome.success(rawContentId, items, strategy.name(), chunkCount(input));
        } catch (Exception ex) {
            log.error("同步抽取失败 rawContentId={}", rawContentId, ex);
            return ExtractionOutcome.failed(rawContentId, ex.getMessage());
        }
    }

    /** 已存在原始内容的重试入口：复用 rawContentId，便于调用方做幂等重试。 */
    public ExtractionOutcome retry(TenantContext context, String rawContentId, ExtractionInput input) {
        try {
            List<MemoryItem> items = run(context, input, rawContentId);
            return ExtractionOutcome.success(rawContentId, items, strategy.name(), chunkCount(input));
        } catch (Exception ex) {
            log.error("重试抽取失败 rawContentId={}", rawContentId, ex);
            return ExtractionOutcome.failed(rawContentId, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ 内部流程

    private List<MemoryItem> run(TenantContext context, ExtractionInput input, String rawContentId) {
        MemoryScope scope = input.scope();
        String namespace = context.namespaceOf(scope);

        for (ConversationLog logEntry : input.logs()) {
            store.saveConversationLog(logEntry);
        }

        int chunkCount = chunkCount(input);
        List<MemoryItem> persisted = new ArrayList<>();
        Instant now = Instant.now();

        for (String chunk : chunker.split(input.transcript())) {
            List<ExtractedMemory> drafts = strategy.extract(chunk, scope);
            if (drafts.isEmpty()) {
                continue;
            }
            List<float[]> embeddings = llmClient.embed(drafts.stream().map(ExtractedMemory::content).toList());
            List<MemoryItem> candidates = new ArrayList<>(drafts.size());
            for (int i = 0; i < drafts.size(); i++) {
                ExtractedMemory draft = drafts.get(i);
                candidates.add(new MemoryItem(
                        newId(),
                        context.tenantId(),
                        context.userId(),
                        scope,
                        namespace,
                        draft.type(),
                        draft.content(),
                        Hashing.ofContent(draft.content()),
                        draft.confidence(),
                        embeddings.get(i),
                        draft.metadata(),
                        rawContentId,
                        now,
                        now));
            }
            List<MemoryItem> kept = deduplicator.filter(candidates, namespace, scope);
            if (!kept.isEmpty()) {
                store.saveMemories(kept);
                persisted.addAll(kept);
            }
        }

        log.info("抽取完成 namespace={} scope={} 分块={} 新增记忆={} 策略={}",
                namespace, scope, chunkCount, persisted.size(), strategy.name());

        if (properties.consolidation().immediate()) {
            insightTreeEngine.consolidateAsync(context, scope);
        }
        return persisted;
    }

    private int chunkCount(ExtractionInput input) {
        return chunker.split(input.transcript()).size();
    }

    private RawContent toRawContent(TenantContext context, ExtractionInput input, String rawContentId) {
        return new RawContent(
                rawContentId,
                context.tenantId(),
                context.userId(),
                input.scope(),
                context.namespaceOf(input.scope()),
                input.contentType(),
                input.transcript(),
                Instant.now());
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}