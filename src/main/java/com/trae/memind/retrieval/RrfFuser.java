package com.trae.memind.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合排序（对应文档 5.2 Step 3）。
 *
 * <p>各召回通道的分数量纲互不可比，RRF 只依赖名次，天然规避了归一化问题：
 * {@code score(d) = Σ_channel weight / (k + rank(d))}。
 */
public class RrfFuser {

    private final int k;

    public RrfFuser(int k) {
        this.k = Math.max(1, k);
    }

    public Map<String, Fused> fuse(List<RankedList> lists) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, List<String>> channels = new LinkedHashMap<>();
        for (RankedList list : lists) {
            List<String> ids = list.ids();
            for (int rank = 0; rank < ids.size(); rank++) {
                String id = ids.get(rank);
                scores.merge(id, list.weight() / (k + rank + 1d), Double::sum);
                channels.computeIfAbsent(id, key -> new ArrayList<>()).add(list.channel());
            }
        }
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1d);
        Map<String, Fused> fused = new LinkedHashMap<>();
        scores.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> entry) -> entry.getValue()).reversed())
                .forEach(entry -> fused.put(entry.getKey(),
                        new Fused(entry.getValue() / max, channels.get(entry.getKey()))));
        return fused;
    }

    /**
     * @param channel 通道名：vector / bm25 / insight
     * @param ids     按该通道相关性降序排列的 ID
     * @param weight  通道权重
     */
    public record RankedList(String channel, List<String> ids, double weight) {
    }

    /** @param score 归一化到 (0,1] 的融合分；@param channels 命中该条目的通道 */
    public record Fused(double score, List<String> channels) {
    }
}