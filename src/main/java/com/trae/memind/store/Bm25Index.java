package com.trae.memind.store;

import com.trae.memind.util.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BM25 关键词召回索引（对应文档 5.2 并行召回中的 BM25 通道）。
 *
 * <p>索引在检索时由候选记忆集合即时构建：文档 8.1 给出的规模是「每用户 1000~5000 条记忆」，
 * 该量级下即时构建的代价远低于维护一套跨进程一致的倒排索引。
 * 迁移到 PostgreSQL 后可替换为 tsvector / GIN 索引。
 */
public class Bm25Index {

    private static final double K1 = 1.2d;
    private static final double B = 0.75d;

    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final List<Map<String, Integer>> termFrequencies = new ArrayList<>();
    private final List<Integer> lengths = new ArrayList<>();
    private final List<String> ids = new ArrayList<>();
    private double averageLength = 0d;

    public Bm25Index(List<Doc> documents) {
        for (Doc document : documents) {
            List<String> tokens = Tokenizer.tokenize(document.text());
            Map<String, Integer> frequencies = new HashMap<>();
            for (String token : tokens) {
                frequencies.merge(token, 1, Integer::sum);
            }
            termFrequencies.add(frequencies);
            lengths.add(tokens.size());
            ids.add(document.id());
            for (String token : new HashSet<>(frequencies.keySet())) {
                documentFrequency.merge(token, 1, Integer::sum);
            }
        }
        this.averageLength = lengths.stream().mapToInt(Integer::intValue).average().orElse(0d);
    }

    /** 返回 docId → BM25 得分，仅包含命中至少一个查询词的文档。 */
    public Map<String, Double> score(String query) {
        Map<String, Double> scores = new HashMap<>();
        if (termFrequencies.isEmpty() || averageLength == 0d) {
            return scores;
        }
        Set<String> queryTerms = new HashSet<>(Tokenizer.tokenize(query));
        if (queryTerms.isEmpty()) {
            return scores;
        }
        int total = termFrequencies.size();
        for (String term : queryTerms) {
            Integer df = documentFrequency.get(term);
            if (df == null || df == 0) {
                continue;
            }
            double idf = Math.log(1d + (total - df + 0.5d) / (df + 0.5d));
            for (int i = 0; i < total; i++) {
                Integer tf = termFrequencies.get(i).get(term);
                if (tf == null) {
                    continue;
                }
                double denominator = tf + K1 * (1 - B + B * lengths.get(i) / averageLength);
                scores.merge(ids.get(i), idf * (tf * (K1 + 1)) / denominator, Double::sum);
            }
        }
        return scores;
    }

    public List<Map.Entry<String, Double>> topK(String query, int k) {
        return score(query).entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> entry) -> entry.getValue()).reversed())
                .limit(k)
                .toList();
    }

    public record Doc(String id, String text) {
    }
}