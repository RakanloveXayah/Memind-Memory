package com.trae.memind.pipeline;

import com.trae.memind.domain.ConversationLog;
import com.trae.memind.domain.MemoryScope;

import java.util.List;

/**
 * 一次抽取请求的输入。
 *
 * @param scope       目标作用域
 * @param contentType 内容类型：CONVERSATION / DOCUMENT / TOOL_CALL / AGENT_TIMELINE
 * @param sessionId   会话 ID
 * @param transcript  待抽取的正文（对话已展开为 "role: content" 形式）
 * @param logs        需要同步落库的原始对话（非对话类型为空）
 */
public record ExtractionInput(
        MemoryScope scope,
        String contentType,
        String sessionId,
        String transcript,
        List<ConversationLog> logs) {

    public ExtractionInput {
        logs = logs == null ? List.of() : List.copyOf(logs);
        contentType = contentType == null || contentType.isBlank() ? "CONVERSATION" : contentType;
    }
}