package com.trae.memind.retrieval;

import com.trae.memind.util.Tokenizer;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询分析器：不调用 LLM，纯本地规则，保证简单检索策略的低延迟（对齐文档 8.1）。
 */
@Component
public class QueryAnalyzer {

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(最近\\s*\\d+\\s*(天|日|周|个月|月)|近\\s*\\d+\\s*(天|日|周|月)|(上|本|这)(周|月|个季度|季度)|今天|昨天|前天|去年|今年|同比|环比)");

    private static final Pattern ENTITY_PATTERN = Pattern.compile(
            "[\"“']([^\"”']{1,24})[\"”']|\\b([A-Za-z_][A-Za-z0-9_]{1,31})\\b");

    private static final int MAX_KEYWORDS = 12;

    public QuerySignals analyze(String query) {
        if (query == null || query.isBlank()) {
            return new QuerySignals(List.of(), List.of(), List.of());
        }
        return new QuerySignals(extractKeywords(query), extractTimeSignals(query), extractEntities(query));
    }

    private List<String> extractKeywords(String query) {
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (String token : Tokenizer.tokenize(query)) {
            if (token.length() < 2) {
                continue;
            }
            frequency.merge(token, 1, Integer::sum);
        }
        return frequency.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue()).reversed())
                .limit(MAX_KEYWORDS)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<String> extractTimeSignals(String query) {
        Matcher matcher = TIME_PATTERN.matcher(query);
        return matcher.results().map(result -> result.group().replaceAll("\\s+", "")).distinct().toList();
    }

    private List<String> extractEntities(String query) {
        Matcher matcher = ENTITY_PATTERN.matcher(query);
        List<String> entities = new java.util.ArrayList<>();
        while (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (value != null && !value.isBlank()) {
                entities.add(value);
            }
        }
        return entities.stream().distinct().limit(10).toList();
    }
}