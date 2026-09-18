package com.trae.memind.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trae.memind.config.MemindProperties;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.llm.LlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抽取入口的质量闸门用例。
 *
 * <p>起因是实测里发现的一条脏记忆：模型把助手的反问「请问今天需要监控哪些核心指标？」当成用户偏好
 * 存了下来（置信度 0.58，正好越过 0.5 的阈值），此后每次查询都会把它召回进上下文。
 * 疑问句不是事实，入口就必须挡住，两条抽取路径都要挡。
 */
class HybridExtractionStrategyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("LLM 抽取路径丢弃疑问句，只留可复用的事实")
    void dropsInterrogativeFromLlmPath() {
        String response = """
                [{"type":"METRIC_PREFERENCE","content":"请问今天需要监控哪些核心指标？","confidence":0.58},
                 {"type":"PROFILE","content":"用户是增长运营，负责跨境电商的渠道投放。","confidence":0.9}]
                """;
        ExtractionStrategy strategy = new HybridExtractionStrategy(chatClient(response), mapper, properties());

        List<ExtractedMemory> extracted = strategy.extract("随便一句", MemoryScope.USER);

        assertEquals(1, extracted.size(), "疑问句必须在入口被挡掉，实际 " + extracted);
        assertTrue(extracted.get(0).content().contains("增长运营"));
    }

    @Test
    @DisplayName("规则抽取路径同样丢弃疑问句")
    void dropsInterrogativeFromRulePath() {
        ExtractionStrategy strategy = new HybridExtractionStrategy(offlineClient(), mapper, properties());

        List<ExtractedMemory> extracted =
                strategy.extract("请问今天需要监控哪些核心指标？\n我是林悦，做增长运营的。", MemoryScope.USER);

        assertTrue(extracted.stream().noneMatch(item -> item.content().contains("请问")),
                "规则命中关键词也不能把反问存成偏好：" + extracted);
        assertTrue(extracted.stream().anyMatch(item -> item.content().contains("林悦")),
                "真实事实不能被一起误杀：" + extracted);
    }

    // ------------------------------------------------------------------ 桩

    private MemindProperties properties() {
        return new MemindProperties(8, null, null,
                new MemindProperties.Extraction(1200, 0.5, 12), null, null, null);
    }

    /** 具备对话能力的桩：无论输入是什么都返回给定报文，用于验证抽取结果的后处理。 */
    private static LlmClient chatClient(String cannedResponse) {
        return new StubClient(true, cannedResponse);
    }

    /** 不具备对话能力的桩：抽取会走关键词规则路径。 */
    private static LlmClient offlineClient() {
        return new StubClient(false, "");
    }

    private record StubClient(boolean available, String response) implements LlmClient {

        @Override
        public boolean chatAvailable() {
            return available;
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            return response;
        }

        @Override
        public int embeddingDimension() {
            return 8;
        }

        @Override
        public boolean embeddingRemote() {
            return false;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(text -> new float[8]).toList();
        }

        @Override
        public String provider() {
            return "stub";
        }
    }
}