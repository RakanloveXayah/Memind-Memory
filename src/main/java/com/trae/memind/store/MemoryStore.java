package com.trae.memind.store;

import com.trae.memind.domain.ConversationLog;
import com.trae.memind.domain.InsightLevel;
import com.trae.memind.domain.InsightNode;
import com.trae.memind.domain.MemoryItem;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.domain.MemoryType;
import com.trae.memind.domain.RawContent;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 存储层抽象（PostgreSQL + pgvector）。
 *
 * <p>隔离是双重的：接口签名强制携带 {@code namespace + scope}，应用层拼不出跨租户查询；
 * 数据库层再叠加 RLS，由存储实现在事务内注入租户上下文。两层互为兜底。
 *
 * <p>向量语义召回与语义去重都下推到 pgvector（{@link #searchByVector}），
 * 应用层只在洞察节点这种小集合上做本地余弦计算。
 */
public interface MemoryStore {

    /** 幂等写入记忆条目（同 ID 覆盖）。 */
    void saveMemories(List<MemoryItem> items);

    /** 读取命名空间下的全部记忆，不含向量列——供 Insight Tree 整合使用。 */
    List<MemoryItem> findMemories(String namespace, MemoryScope scope);

    /** 仅取 ID 与正文，供 BM25 通道构建关键词索引。 */
    List<MemoryText> findMemoryTexts(String namespace, MemoryScope scope);

    /** pgvector 近邻检索，按相似度降序返回不超过 topK 条高于 minSimilarity 的命中。 */
    List<VectorHit> searchByVector(String namespace, MemoryScope scope, float[] queryEmbedding,
                                   int topK, double minSimilarity);

    /** 按 ID 批量取回记忆（用于把各召回通道的候选合并成完整对象）。 */
    List<MemoryItem> findMemoriesByIds(String namespace, MemoryScope scope, Collection<String> ids);

    /** 命名空间下已有的内容指纹，用于精确去重。 */
    Set<String> findContentHashes(String namespace, MemoryScope scope);

    /**
     * 清空命名空间下的全部记忆，返回实际清理的条数。
     *
     * <p>软删除（{@code active = FALSE}）而非物理删除：检索、去重、整合三条路径都只认
     * {@code active = TRUE}，置位后效果等同于清空，而原始对话与抽取链路留作审计。
     */
    int deleteMemories(String namespace, MemoryScope scope);

    /** 幂等写入洞察节点（同 ID 覆盖），用于重新提炼后的版本更新。 */
    void saveInsightNodes(List<InsightNode> nodes);

    List<InsightNode> findInsightNodes(String namespace, MemoryScope scope, InsightLevel level);

    /** 删除某层级全部节点，用于整合前重建该层。 */
    void deleteInsightNodes(String namespace, MemoryScope scope, InsightLevel level);

    void saveConversationLog(ConversationLog log);

    void saveRawContent(RawContent rawContent);

    /** 列出全部活跃命名空间，供定时批量整合遍历（内部开启跨租户读）。 */
    List<NamespaceRef> listNamespaces();

    /** 命名空间 + 作用域 + 归属信息。 */
    record NamespaceRef(String namespace, MemoryScope scope, String tenantId, String userId) {
    }

    /** BM25 通道所需的最小字段集合。 */
    record MemoryText(String id, MemoryType type, String content) {
    }

    /** 向量检索命中：记忆 ID 与余弦相似度（1 - 余弦距离）。 */
    record VectorHit(String memoryId, double similarity) {
    }
}