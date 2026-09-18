package com.trae.memind.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 分块器：把长文本切成适合单次抽取的片段（对应文档写入路径中的 Chunker 环节）。
 *
 * <p>优先在段落边界切分，其次在句末标点切分，避免把一句话拦腰截断。
 */
public class Chunker {

    private static final String SENTENCE_ENDINGS = "。！？!?\n";

    private final int chunkSize;

    public Chunker(int chunkSize) {
        this.chunkSize = Math.max(200, chunkSize);
    }

    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String remaining = text.trim();
        while (remaining.length() > chunkSize) {
            int cut = lastBoundary(remaining, chunkSize);
            chunks.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
        return chunks.stream().filter(chunk -> !chunk.isBlank()).toList();
    }

    private int lastBoundary(String text, int limit) {
        for (int index = Math.min(limit, text.length() - 1); index > limit / 2; index--) {
            if (SENTENCE_ENDINGS.indexOf(text.charAt(index)) >= 0) {
                return index + 1;
            }
        }
        return limit;
    }
}