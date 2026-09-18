package com.trae.memind.pipeline;

import com.trae.memind.domain.ExtractionStatus;
import com.trae.memind.domain.MemoryItem;

import java.util.List;

/**
 * 一次抽取请求的结果。
 *
 * @param status       抽取状态。只有 SUCCESS 才代表记忆已落库；调用方据此清理本地重试状态
 * @param rawContentId 原始内容 ID，重试时复用
 * @param items        实际落库的记忆条目（已去重）
 * @param strategy     命中的抽取策略名称
 * @param chunkCount   分块数量
 * @param message      失败原因或附加说明
 */
public record ExtractionOutcome(
        ExtractionStatus status,
        String rawContentId,
        List<MemoryItem> items,
        String strategy,
        int chunkCount,
        String message) {

    public static ExtractionOutcome success(String rawContentId, List<MemoryItem> items,
                                            String strategy, int chunkCount) {
        return new ExtractionOutcome(ExtractionStatus.SUCCESS, rawContentId, items, strategy, chunkCount, null);
    }

    public static ExtractionOutcome failed(String rawContentId, String message) {
        return new ExtractionOutcome(ExtractionStatus.FAILED, rawContentId, List.of(), null, 0, message);
    }
}