package com.trae.memind.domain;

import java.time.Instant;

/** 原始待抽取内容（同步抽取接口依赖它实现重试语义）。 */
public record RawContent(
        String id,
        String tenantId,
        String userId,
        MemoryScope scope,
        String namespace,
        String contentType,
        String payload,
        Instant createdAt) {
}