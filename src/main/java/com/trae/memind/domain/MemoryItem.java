package com.trae.memind.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 结构化记忆条目（Memory Item）——抽取流水线的最终产出。
 *
 * @param id          记忆 ID
 * @param tenantId    租户 ID
 * @param userId      用户 ID
 * @param scope       作用域
 * @param namespace   存储命名空间
 * @param type        记忆类型
 * @param content     记忆内容（自然语言）
 * @param contentHash 内容指纹，用于精确去重
 * @param confidence  置信度
 * @param embedding   内容向量
 * @param metadata    附加信息（工具名、参数、数据源等）
 * @param sourceId    来源原始内容 ID
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record MemoryItem(
        String id,
        String tenantId,
        String userId,
        MemoryScope scope,
        String namespace,
        MemoryType type,
        String content,
        String contentHash,
        double confidence,
        float[] embedding,
        Map<String, String> metadata,
        String sourceId,
        Instant createdAt,
        Instant updatedAt) {

    public MemoryItem withEmbedding(float[] newEmbedding) {
        return new MemoryItem(id, tenantId, userId, scope, namespace, type, content, contentHash,
                confidence, newEmbedding, metadata, sourceId, createdAt, updatedAt);
    }
}