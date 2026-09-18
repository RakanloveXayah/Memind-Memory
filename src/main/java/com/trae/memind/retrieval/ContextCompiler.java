package com.trae.memind.retrieval;

import com.trae.memind.config.MemindProperties;
import com.trae.memind.domain.InsightNode;
import com.trae.memind.domain.MemoryScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文组装器（对应文档 5.2 Step 5：compile_context → prompt-ready 文本）。
 *
 * <p>裁剪时遵循「抽象优于具体」但不再让抽象独占：Insight Tree 的整体理解优先级最高，
 * 却只允许占 {@code memind.retrieval.insight-budget-share} 比例的预算，
 * 超出的部分先让位给事实类记忆，预算还有剩再补回。
 * 早先的实现在预算不足时把原始记忆全部丢掉，结果是"注入的都是正确的废话"——
 * 抽象理解本该是事实的索引，事实被裁光了，它也就没有内容可支撑了。
 */
@Component
public class ContextCompiler {

    private final MemindProperties.Retrieval config;

    public ContextCompiler(MemindProperties properties) {
        this.config = properties.retrieval();
    }

    public CompiledContext compile(RetrievalResult result, Integer tokenBudget) {
        int budget = tokenBudget == null || tokenBudget <= 0 ? config.contextTokenBudget() : tokenBudget;

        List<Entry> entries = new ArrayList<>();
        int order = 0;
        for (RetrievalResult.ScoredInsight insight : result.insights()) {
            InsightNode node = insight.node();
            boolean userSide = node.scope() == MemoryScope.USER;
            int priority = switch (node.level()) {
                case ROOT, BRANCH -> userSide ? 1 : 2;
                case LEAF -> 5;
            };
            entries.add(new Entry(order++, priority, true,
                    userSide ? Section.USER_UNDERSTANDING : Section.AGENT_EXPERIENCE,
                    "- " + node.content()));
        }
        for (RetrievalResult.ScoredMemory scored : result.memories()) {
            boolean userSide = scored.item().scope() == MemoryScope.USER;
            entries.add(new Entry(order++, userSide ? 3 : 4, false,
                    userSide ? Section.USER_PREFERENCE : Section.AGENT_EXPERIENCE,
                    "- [" + scored.item().type().name().toLowerCase() + "] " + scored.item().content()
                            + "（置信度 " + String.format("%.2f", scored.item().confidence()) + "）"));
        }

        List<Entry> kept = new ArrayList<>();
        List<Entry> overflowInsights = new ArrayList<>();
        int insightBudget = (int) Math.ceil(budget * config.insightBudgetShare());
        int used = 0;
        int insightUsed = 0;
        for (Entry entry : entries.stream().sorted(Comparator.comparingInt(Entry::priority)).toList()) {
            int cost = estimateTokens(entry.line()) + 1;
            if (entry.insight() && insightUsed + cost > insightBudget) {
                overflowInsights.add(entry);
                continue;
            }
            if (used + cost > budget) {
                continue;
            }
            kept.add(entry);
            used += cost;
            if (entry.insight()) {
                insightUsed += cost;
            }
        }
        // 记忆放完仍有预算，说明这轮检索出来的事实不多，把刚才让位的洞察补回来
        for (Entry entry : overflowInsights) {
            int cost = estimateTokens(entry.line()) + 1;
            if (used + cost > budget) {
                continue;
            }
            kept.add(entry);
            used += cost;
        }

        Map<Section, List<String>> grouped = new LinkedHashMap<>();
        kept.stream().sorted(Comparator.comparingInt(Entry::order))
                .forEach(entry -> grouped.computeIfAbsent(entry.section(), key -> new ArrayList<>())
                        .add(entry.line()));

        StringBuilder text = new StringBuilder();
        for (Section section : Section.values()) {
            List<String> lines = grouped.get(section);
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append("## ").append(section.title).append('\n');
            lines.forEach(line -> text.append(line).append('\n'));
        }

        // 上报的条数必须是"真正进了上下文"的条数：这个数字会显示在界面上，
        // 候选数与注入数混为一谈时，用户看到的是"35 条"却只生效了 5 条。
        int insightKept = (int) kept.stream().filter(Entry::insight).count();
        return new CompiledContext(text.toString().stripTrailing(),
                estimateTokens(text.toString()),
                kept.size() - insightKept,
                insightKept,
                kept.size() < entries.size());
    }

    /** 中文字符按 1 token、其他字符按 4 字符 1 token 估算，足够用于预算裁剪。 */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0d;
        for (int index = 0; index < text.length(); index++) {
            tokens += text.charAt(index) >= 0x2E80 ? 1d : 0.25d;
        }
        return (int) Math.ceil(tokens);
    }

    private enum Section {
        USER_UNDERSTANDING("对该用户的理解"),
        USER_PREFERENCE("该用户的偏好与习惯"),
        AGENT_EXPERIENCE("可复用的 Agent 经验");

        private final String title;

        Section(String title) {
            this.title = title;
        }
    }

    /** {@code insight} 标记这条来自洞察而非事实，用于单独核算洞察占用的预算。 */
    private record Entry(int order, int priority, boolean insight, Section section, String line) {
    }

    /**
     * prompt-ready 的上下文。
     *
     * @param text            可直接注入 System Prompt 的文本
     * @param estimatedTokens 估算 Token 数
     * @param memoryCount     实际注入的记忆条目数（裁剪后，非候选数）
     * @param insightCount    实际注入的洞察节点数（裁剪后，非候选数）
     * @param truncated       是否因预算不足发生了裁剪
     */
    public record CompiledContext(String text, int estimatedTokens, int memoryCount, int insightCount,
                                  boolean truncated) {
    }
}