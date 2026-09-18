package com.trae.memind.pipeline;

import com.trae.memind.domain.MemoryType;

import java.util.Map;

/** 抽取器的原始产出，尚未落库、尚未去重。 */
public record ExtractedMemory(
        MemoryType type,
        String content,
        double confidence,
        Map<String, String> metadata) {
}