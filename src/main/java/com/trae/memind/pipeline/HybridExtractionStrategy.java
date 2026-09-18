package com.trae.memind.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trae.memind.config.MemindProperties;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.domain.MemoryType;
import com.trae.memind.llm.LlmClient;
import com.trae.memind.util.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合抽取策略：优先使用 LLM 语义抽取，模型不可用或输出不可解析时降级到关键词规则。
 *
 * <p>这是文档「类型化处理器 + 插件特定抽取策略」的落地：USER 与 AGENT 使用不同的抽取提示词与规则集，
 * 从源头保证两个作用域不会互相污染。
 */
@Component
public class HybridExtractionStrategy implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(HybridExtractionStrategy.class);

    private static final Map<MemoryScope, List<Rule>> RULES = Map.of(
            MemoryScope.USER, List.of(
                    new Rule(MemoryType.TIME_PREFERENCE,
                            "最近", "上周", "本周", "本月", "上个月", "昨天", "近30天", "近7天", "过去", "同比", "环比"),
                    new Rule(MemoryType.DIMENSION_PREFERENCE,
                            "拆分", "维度", "分组", "占比", "对比", "group by", "按渠道", "按地区"),
                    new Rule(MemoryType.METRIC_PREFERENCE,
                            "gmv", "订单", "销售额", "转化率", "留存", "客单价", "指标", "uv", "pv", "roi", "arpu"),
                    new Rule(MemoryType.COMMUNICATION_STYLE,
                            "直接", "简洁", "简单说", "别废话", "不用解释", "详细说", "展开说"),
                    new Rule(MemoryType.PREFERENCE,
                            "偏好", "喜欢", "习惯用", "希望", "不要", "更倾向", "优先"),
                    new Rule(MemoryType.QUERY_PATTERN,
                            "先看", "再看", "习惯先", "通常先", "一般先", "然后再"),
                    new Rule(MemoryType.PROFILE,
                            "我是", "我在", "我负责", "我的岗位", "我们公司", "我是做", "工作经验"),
                    new Rule(MemoryType.BUSINESS_CONTEXT,
                            "业务", "客户", "行业", "场景", "租户")),
            MemoryScope.AGENT, List.of(
                    new Rule(MemoryType.SQL_TEMPLATE, "select ", " from ", "sql", " join "),
                    new Rule(MemoryType.FAILURE_LESSON,
                            "失败", "报错", "异常", "超时", "错误", "不可用", "限流"),
                    new Rule(MemoryType.DATA_SOURCE_TRAIT,
                            "表", "字段", "数据源", "实时性", "延迟", "汇总表", "明细表"),
                    new Rule(MemoryType.TOOL_USAGE,
                            "调用", "工具", "接口", "执行了", "参数"),
                    new Rule(MemoryType.BEST_PRACTICE,
                            "最佳实践", "建议", "优先使用", "应该", "推荐")));

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final MemindProperties.Extraction config;

    public HybridExtractionStrategy(LlmClient llmClient, ObjectMapper mapper, MemindProperties properties) {
        this.llmClient = llmClient;
        this.mapper = mapper;
        this.config = properties.extraction();
    }

    @Override
    public String name() {
        return llmClient.chatAvailable() ? "llm-hybrid" : "rule-based";
    }

    @Override
    public List<ExtractedMemory> extract(String text, MemoryScope scope) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (llmClient.chatAvailable()) {
            try {
                List<ExtractedMemory> extracted = extractByLlm(text, scope);
                if (!extracted.isEmpty()) {
                    return extracted;
                }
                log.debug("LLM 未抽取出记忆，降级到规则引擎");
            } catch (Exception ex) {
                log.warn("LLM 抽取失败，降级到规则引擎: {}", ex.getMessage());
            }
        }
        return extractByRules(text, scope);
    }

    // ------------------------------------------------------------------ LLM 抽取

    private List<ExtractedMemory> extractByLlm(String text, MemoryScope scope) throws Exception {
        String systemPrompt = buildSystemPrompt(scope);
        String response = llmClient.chat(systemPrompt, text);
        String json = JsonExtractor.extractArray(response);
        if (json == null) {
            return List.of();
        }
        JsonNode array = mapper.readTree(json);
        if (!array.isArray()) {
            return List.of();
        }
        List<ExtractedMemory> result = new ArrayList<>();
        for (JsonNode node : array) {
            String content = node.path("content").asText("").trim();
            if (!isStorable(content)) {
                continue;
            }
            double confidence = node.path("confidence").asDouble(0.7d);
            confidence = Math.max(0d, Math.min(1d, confidence));
            if (confidence < config.minConfidence()) {
                continue;
            }
            MemoryType type = MemoryType.parse(node.path("type").asText(null), scope);
            result.add(new ExtractedMemory(type, content, confidence, readMetadata(node.path("metadata"))));
        }
        return result.stream().limit(config.maxItemsPerChunk()).toList();
    }

    private String buildSystemPrompt(MemoryScope scope) {
        String types = MemoryType.of(scope).stream().map(Enum::name).reduce((a, b) -> a + ", " + b).orElse("");
        String focus = scope == MemoryScope.USER
                ? "从对话中抽取关于【用户本人】的稳定长期记忆：身份背景、偏好、查询习惯、沟通风格、业务上下文。"
                : "从工具调用与执行轨迹中抽取关于【Agent 自身】的可复用经验：工具使用方式、数据源特性、SQL 模板、最佳实践与失败教训。";
        return """
                你是 Memind 记忆抽取引擎。%s
                只抽取跨会话仍然有复用价值的信息；忽略寒暄、一次性操作细节，也不要抽取助手自身的回答内容。
                疑问句、助手的反问、以及只在一次对话里露过面的临时话题（例如随口问过一句天气）都不是长期记忆。
                可选类型（只能从中选择，禁止自造）：%s
                严格输出 JSON 数组，元素结构为：
                {"type":"类型","content":"一句自包含的中文记忆陈述","confidence":0.85,"metadata":{}}
                要求：content 必须自包含（不依赖上下文即可理解），用陈述句；没有可记录内容时输出 []。
                最多输出 %d 条。禁止输出解释文字，禁止使用 markdown 代码块。
                """.formatted(focus, types, config.maxItemsPerChunk());
    }

    /**
     * 能否落库。
     *
     * <p>实测踩过的坑：模型把助手的反问「请问今天需要监控哪些核心指标？」当成用户偏好存了下来，
     * 之后每次检索都会被召回，既污染画像又浪费上下文。疑问句不是事实，这类内容必须在入口挡掉，
     * 而不是指望下游的置信度过滤——它给的置信度是 0.58，正好在阈值之上。
     */
    private static boolean isStorable(String content) {
        if (content.isEmpty()) {
            return false;
        }
        if (content.endsWith("？") || content.endsWith("?")) {
            return false;
        }
        return !content.startsWith("请问");
    }

    private Map<String, String> readMetadata(JsonNode node) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> metadata.put(entry.getKey(), entry.getValue().asText()));
        }
        return metadata;
    }

    // ------------------------------------------------------------------ 规则抽取

    private List<ExtractedMemory> extractByRules(String text, MemoryScope scope) {
        List<Rule> rules = RULES.getOrDefault(scope, List.of());
        Map<String, ExtractedMemory> deduped = new LinkedHashMap<>();
        for (String unit : splitUnits(text)) {
            if (unit.length() < 6 || !isStorable(unit)) {
                continue;
            }
            String lower = unit.toLowerCase();
            for (Rule rule : rules) {
                if (rule.matches(lower)) {
                    deduped.putIfAbsent(unit, new ExtractedMemory(
                            rule.type(), unit, rule.confidence(unit), new HashMap<>()));
                    break;
                }
            }
            if (deduped.size() >= config.maxItemsPerChunk()) {
                break;
            }
        }
        return new ArrayList<>(deduped.values());
    }

    /** 按换行与句末标点切分为语义单元，并剥离 "user:" / "assistant:" 之类的前缀。 */
    private List<String> splitUnits(String text) {
        List<String> units = new ArrayList<>();
        for (String rawLine : text.split("\\r?\\n|(?<=[。！？!?;；])")) {
            String unit = rawLine.trim();
            if (unit.isEmpty()) {
                continue;
            }
            int colon = unit.indexOf(':');
            if (colon > 0 && colon < 12) {
                String prefix = unit.substring(0, colon).toLowerCase();
                if (prefix.matches("[a-z_\\u4e00-\\u9fff]+") && !prefix.contains(" ")) {
                    unit = unit.substring(colon + 1).trim();
                }
            }
            if (!unit.isEmpty()) {
                units.add(unit);
            }
        }
        return units;
    }

    private record Rule(MemoryType type, List<String> keywords) {

        Rule(MemoryType type, String... keywords) {
            this(type, List.of(keywords));
        }

        boolean matches(String lowercasedUnit) {
            return keywords.stream().anyMatch(lowercasedUnit::contains);
        }

        /** 命中关键词越多、单元越长，置信度越高（封顶 0.75）。 */
        double confidence(String unit) {
            long hits = keywords.stream().filter(unit.toLowerCase()::contains).count();
            return Math.min(0.75d, 0.5d + 0.05d * hits + Math.min(0.1d, unit.length() / 500d));
        }
    }
}