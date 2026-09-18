package com.trae.memind.retrieval;

import com.trae.memind.config.MemindProperties;
import com.trae.memind.domain.InsightLevel;
import com.trae.memind.domain.InsightNode;
import com.trae.memind.domain.MemoryItem;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.llm.LlmClient;
import com.trae.memind.store.Bm25Index;
import com.trae.memind.store.MemoryStore;
import com.trae.memind.tenant.TenantContext;
import com.trae.memind.util.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 多层级检索引擎（对应文档 5.2 检索路径）。
 *
 * <p>并行召回三条通道——向量语义、BM25 关键词、Insight Tree 洞察——再用 RRF 融合，
 * 随后施加相关性闸门、时序加权与置信度过滤。Insight Tree 通道通过节点的成员指针回连到原始记忆，
 * 相当于沿树做了一次一跳扩展，让「抽象理解」能把「具体事实」一起带出来。
 *
 * <p>融合之后仍要过一道原始余弦相似度的闸门：RRF 只保留"排第几"，丢掉"有多像"，
 * 于是哪怕一条记忆与问题毫不相干，只要在某个通道里排第一，融合分看起来照样正常。
 * 这是"每次注入的都不相关"的根因，闸门阈值见 {@code memind.retrieval.min-relevance}。
 *
 * <p>当 {@code memind.retrieval.strategy=SIMPLE} 时全部为本地计算，不产生额外的 LLM 调用。
 */
@Service
public class RetrievalEngine {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEngine.class);

    private static final String CHANNEL_VECTOR = "vector";
    private static final String CHANNEL_BM25 = "bm25";
    private static final String CHANNEL_INSIGHT = "insight";

    private static final double WEIGHT_VECTOR = 1.0d;
    private static final double WEIGHT_BM25 = 1.0d;
    private static final double WEIGHT_INSIGHT = 0.8d;

    /** 时序加权下限：稳定记忆不因久远而被清空，新近记忆只获得有限的加成。 */
    private static final double RECENCY_FLOOR = 0.7d;

    private final MemoryStore store;
    private final LlmClient llmClient;
    private final QueryAnalyzer analyzer;
    private final MemindProperties.Retrieval config;

    public RetrievalEngine(MemoryStore store, LlmClient llmClient, QueryAnalyzer analyzer, MemindProperties properties) {
        this.store = store;
        this.llmClient = llmClient;
        this.analyzer = analyzer;
        this.config = properties.retrieval();
    }

    /** 跨作用域检索：USER 与 AGENT 各自按自己的隔离边界召回后合并。 */
    public RetrievalResult retrieve(TenantContext context, List<MemoryScope> scopes, String query, Integer topK) {
        List<RetrievalResult> results = scopes.stream()
                .map(scope -> retrieve(context, scope, query, topK))
                .toList();
        if (results.size() == 1) {
            return results.get(0);
        }
        QuerySignals signals = results.stream()
                .map(RetrievalResult::signals)
                .filter(candidate -> candidate != null && !candidate.keywords().isEmpty())
                .findFirst()
                .orElse(results.get(0).signals());

        Map<String, RetrievalResult.ScoredMemory> mergedMemories = new LinkedHashMap<>();
        Map<String, RetrievalResult.ScoredInsight> mergedInsights = new LinkedHashMap<>();
        for (RetrievalResult result : results) {
            for (RetrievalResult.ScoredMemory scored : result.memories()) {
                mergedMemories.merge(scored.item().id(), scored,
                        (left, right) -> left.score() >= right.score() ? left : right);
            }
            for (RetrievalResult.ScoredInsight scored : result.insights()) {
                mergedInsights.merge(scored.node().id(), scored,
                        (left, right) -> left.score() >= right.score() ? left : right);
            }
        }
        int limit = resolveTopK(topK);
        return new RetrievalResult(
                mergedMemories.values().stream()
                        .sorted(Comparator.comparingDouble(RetrievalResult.ScoredMemory::score).reversed())
                        .limit(limit)
                        .toList(),
                mergedInsights.values().stream()
                        .sorted(Comparator.comparingDouble(RetrievalResult.ScoredInsight::score).reversed())
                        .toList(),
                signals,
                config.strategy());
    }

    /** 单作用域检索。 */
    public RetrievalResult retrieve(TenantContext context, MemoryScope scope, String query, Integer topK) {
        QuerySignals signals = analyzer.analyze(query);
        String namespace = context.namespaceOf(scope);
        List<MemoryStore.MemoryText> texts = store.findMemoryTexts(namespace, scope);
        if (texts.isEmpty()) {
            return RetrievalResult.empty(signals, config.strategy());
        }
        float[] queryEmbedding = llmClient.embedOne(query);

        List<InsightNode> insightNodes = loadInsightNodes(namespace, scope);
        List<RetrievalResult.ScoredInsight> scoredInsights = scoreInsights(insightNodes, queryEmbedding);

        List<RrfFuser.RankedList> rankedLists = List.of(
                new RrfFuser.RankedList(CHANNEL_VECTOR,
                        vectorRanked(namespace, scope, queryEmbedding), WEIGHT_VECTOR),
                new RrfFuser.RankedList(CHANNEL_BM25,
                        bm25Ranked(texts, query), WEIGHT_BM25),
                new RrfFuser.RankedList(CHANNEL_INSIGHT,
                        insightExpandedRanked(scoredInsights), WEIGHT_INSIGHT));

        Map<String, RrfFuser.Fused> fused = new RrfFuser(config.rrfK()).fuse(rankedLists);
        if (fused.isEmpty()) {
            return RetrievalResult.empty(signals, config.strategy());
        }
        Map<String, MemoryItem> byId = store.findMemoriesByIds(namespace, scope, fused.keySet()).stream()
                .collect(Collectors.toMap(MemoryItem::id, Function.identity(), (a, b) -> a));

        Instant now = Instant.now();
        List<RetrievalResult.ScoredMemory> scored = new ArrayList<>();
        for (Map.Entry<String, RrfFuser.Fused> entry : fused.entrySet()) {
            MemoryItem item = byId.get(entry.getKey());
            if (item == null || item.confidence() < config.minConfidence()) {
                continue;
            }
            // 相关性闸门：见 minRelevance 的说明——RRF 的排名分不能反映"到底像不像"。
            // 没有向量的历史数据不做判断，交给置信度与关键词通道决定。
            if (passesRelevanceGate(item.embedding(), Vectors.cosine(item.embedding(), queryEmbedding))) {
                double score = entry.getValue().score() * recencyFactor(item, now);
                scored.add(new RetrievalResult.ScoredMemory(item, score, entry.getValue().channels()));
            }
        }
        List<RetrievalResult.ScoredMemory> top = scored.stream()
                .sorted(Comparator.comparingDouble(RetrievalResult.ScoredMemory::score).reversed())
                .limit(resolveTopK(topK))
                .toList();

        log.debug("检索完成 namespace={} scope={} 候选={} 命中={} 洞察={}",
                namespace, scope, texts.size(), top.size(), scoredInsights.size());
        return new RetrievalResult(top, scoredInsights, signals, config.strategy());
    }

    // ------------------------------------------------------------------ 各召回通道

    /** 向量通道：语义召回完全下推给 pgvector 的 HNSW/IVFFlat 索引，应用层不再暴力扫描。 */
    private List<String> vectorRanked(String namespace, MemoryScope scope, float[] queryEmbedding) {
        return store.searchByVector(namespace, scope, queryEmbedding, config.vectorTopK(), 0d).stream()
                .map(MemoryStore.VectorHit::memoryId)
                .toList();
    }

    private List<String> bm25Ranked(List<MemoryStore.MemoryText> memories, String query) {
        Bm25Index index = new Bm25Index(memories.stream()
                .map(item -> new Bm25Index.Doc(item.id(), item.content()))
                .toList());
        return index.topK(query, config.bm25TopK()).stream()
                .filter(entry -> entry.getValue() > 0d)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 洞察通道：命中洞察所指向的原始记忆，把抽象理解与具体事实一并带出。 */
    private List<String> insightExpandedRanked(List<RetrievalResult.ScoredInsight> insights) {
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        for (RetrievalResult.ScoredInsight insight : insights) {
            for (String memberId : insight.node().memberIds()) {
                ordered.putIfAbsent(memberId, Boolean.TRUE);
            }
        }
        return List.copyOf(ordered.keySet());
    }

    private List<InsightNode> loadInsightNodes(String namespace, MemoryScope scope) {
        List<InsightNode> nodes = new ArrayList<>();
        nodes.addAll(store.findInsightNodes(namespace, scope, InsightLevel.ROOT));
        nodes.addAll(store.findInsightNodes(namespace, scope, InsightLevel.BRANCH));
        nodes.addAll(store.findInsightNodes(namespace, scope, InsightLevel.LEAF));
        return nodes;
    }

    /**
     * 洞察评分：语义相似度 × 层级权重，并通过相关性闸门。
     *
     * <p>ROOT 与 BRANCH 承载整体理解，在预算裁剪时优先级更高；但"优先级高"不等于"无条件注入"。
     * 早先的实现让它们绕过相关性判断，结果是任何问题都会注入同一批抽象理解——问天气时也在讲
     * GMV 与增长运营，既浪费预算又把模型带偏。层级只影响排序，进不进上下文由相关性决定。
     */
    private List<RetrievalResult.ScoredInsight> scoreInsights(List<InsightNode> nodes, float[] queryEmbedding) {
        record Ranked(InsightNode node, double score, double levelWeight) {
        }
        List<Ranked> ranked = nodes.stream()
                .map(node -> new Ranked(node,
                        Vectors.cosine(node.embedding(), queryEmbedding),
                        levelWeight(node.level())))
                .filter(item -> passesRelevanceGate(item.node().embedding(), item.score()))
                .toList();

        // 非叶子节点不参与 topK 截断（数量本就由整合流程控制），叶子节点按热度取前 insightTopK 个。
        List<RetrievalResult.ScoredInsight> kept = ranked.stream()
                .filter(item -> item.node().level() != InsightLevel.LEAF)
                .sorted(Comparator.comparingDouble((Ranked item) -> item.score()).reversed())
                .map(item -> new RetrievalResult.ScoredInsight(item.node(),
                        item.score() * item.levelWeight(), item.levelWeight()))
                .collect(Collectors.toCollection(ArrayList::new));

        ranked.stream()
                .filter(item -> item.node().level() == InsightLevel.LEAF)
                .sorted(Comparator.comparingDouble((Ranked item) -> item.score()).reversed())
                .limit(config.insightTopK())
                .forEach(item -> kept.add(new RetrievalResult.ScoredInsight(
                        item.node(), item.score() * item.levelWeight(), item.levelWeight())));
        return kept;
    }

    private static double levelWeight(InsightLevel level) {
        return switch (level) {
            case ROOT -> 1.0d;
            case BRANCH -> 0.9d;
            case LEAF -> 0.75d;
        };
    }

    /**
     * 相关性闸门。
     *
     * <p>没有向量的条目（历史脏数据、写入时未带向量）不做判断——宁可让置信度与关键词通道去决定，
     * 也不要因为"算不出相似度"就静默丢掉一条本来正确的记忆。
     */
    private boolean passesRelevanceGate(float[] embedding, double relevance) {
        if (embedding == null || embedding.length == 0) {
            return true;
        }
        return relevance >= config.minRelevance();
    }

    // ------------------------------------------------------------------ 排序后处理

    /** 指数衰减 + 下限，避免时序信号淹没长期稳定的记忆。 */
    private double recencyFactor(MemoryItem item, Instant now) {
        long ageDays = Math.max(0L, Duration.between(item.createdAt(), now).toDays());
        double halfLife = Math.max(1, config.timeDecayHalfLifeDays());
        double decay = Math.pow(0.5d, ageDays / halfLife);
        return RECENCY_FLOOR + (1d - RECENCY_FLOOR) * decay;
    }

    private int resolveTopK(Integer topK) {
        return topK == null || topK <= 0 ? config.finalTopK() : Math.min(topK, 100);
    }
}