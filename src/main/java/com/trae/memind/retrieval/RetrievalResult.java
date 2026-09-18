package com.trae.memind.retrieval;

import com.trae.memind.domain.InsightNode;
import com.trae.memind.domain.MemoryItem;

import java.util.List;

/**
 * 多层级检索结果。
 *
 * @param memories   融合排序后的记忆条目
 * @param insights   命中的 Insight Tree 节点（Root 优先）
 * @param signals    查询分析产出
 * @param strategy   本次使用的检索策略
 */
public record RetrievalResult(
        List<ScoredMemory> memories,
        List<ScoredInsight> insights,
        QuerySignals signals,
        String strategy) {

    public static RetrievalResult empty(QuerySignals signals, String strategy) {
        return new RetrievalResult(List.of(), List.of(), signals, strategy);
    }

    /** @param channels 命中该条记忆的召回通道，用于可解释性与调参 */
    public record ScoredMemory(MemoryItem item, double score, List<String> channels) {
    }

    /** @param levelWeight Insight Tree 检索中按层级施加的权重（越抽象越优先） */
    public record ScoredInsight(InsightNode node, double score, double levelWeight) {
    }
}