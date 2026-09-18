package com.trae.memind.pipeline;

import com.trae.memind.domain.MemoryScope;

import java.util.List;

/**
 * 抽取策略（对应文档写入路径中的 Extraction Strategy 环节）。
 *
 * <p>不同作用域使用不同策略：USER 侧关注画像与偏好，AGENT 侧关注工具经验与数据源特性。
 */
public interface ExtractionStrategy {

    List<ExtractedMemory> extract(String text, MemoryScope scope);

    /** 策略名称，用于日志与响应。 */
    String name();
}