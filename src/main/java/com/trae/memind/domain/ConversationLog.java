package com.trae.memind.domain;

import java.time.Instant;

/** 原始对话日志（对应文档架构图中的 conversation_logs）。 */
public record ConversationLog(
        String id,
        String tenantId,
        String userId,
        MemoryScope scope,
        String namespace,
        String sessionId,
        String role,
        String content,
        Instant createdAt) {
}