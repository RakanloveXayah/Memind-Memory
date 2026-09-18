package com.trae.memind.domain;

import java.time.Instant;
import java.util.List;

/**
 * Insight Tree 节点。
 *
 * @param id         节点 ID
 * @param tenantId   租户 ID
 * @param userId     用户 ID
 * @param scope      作用域
 * @param namespace  存储命名空间
 * @param level      层级：LEAF / BRANCH / ROOT
 * @param groupKey   Leaf 的语义分组键（BRANCH / ROOT 为 null）
 * @param content    提炼后的洞察文本
 * @param parentId   父节点 ID（Leaf 的父为 Branch，Branch 的父为 Root）
 * @param memberIds  构成该节点的下级节点或记忆条目 ID
 * @param embedding  洞察文本的向量
 * @param confidence 置信度
 * @param version    版本号：由成员集合哈希派生，成员变化即代表需要重新提炼
 * @param createdAt  创建时间
 * @param updatedAt  最近一次更新（重新提炼）时间
 */
public record InsightNode(
        String id,
        String tenantId,
        String userId,
        MemoryScope scope,
        String namespace,
        InsightLevel level,
        String groupKey,
        String content,
        String parentId,
        List<String> memberIds,
        float[] embedding,
        double confidence,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public InsightNode withParent(String newParentId) {
        return new InsightNode(id, tenantId, userId, scope, namespace, level, groupKey, content,
                newParentId, memberIds, embedding, confidence, version, createdAt, updatedAt);
    }
}