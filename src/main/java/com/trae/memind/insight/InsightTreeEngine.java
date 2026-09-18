package com.trae.memind.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trae.memind.config.MemindProperties;
import com.trae.memind.domain.InsightLevel;
import com.trae.memind.domain.InsightNode;
import com.trae.memind.domain.MemoryItem;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.llm.LlmClient;
import com.trae.memind.store.MemoryStore;
import com.trae.memind.tenant.TenantContext;
import com.trae.memind.util.Hashing;
import com.trae.memind.util.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Insight Tree 引擎——Memind 的认知层核心（对应文档第二章）。
 *
 * <p>三层递进：Leaf 在单一语义分组内提炼事实洞察，Branch 汇聚多个 Leaf 识别跨组模式，
 * Root 汇聚多个 Branch 形成跨维度理解。每层只在上层成员集合发生变化时才重新提炼，
 * 因此写入后的即时整合不会造成 LLM 调用风暴。
 */
@Service
public class InsightTreeEngine {

    private static final Logger log = LoggerFactory.getLogger(InsightTreeEngine.class);

    /** 单次提炼最多纳入的下级成员数，防止提示词无界增长。 */
    private static final int MAX_MEMBERS_PER_PROMPT = 30;
    private static final int MAX_CONTENT_CHARS = 200;

    private final MemoryStore store;
    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final MemindProperties.Consolidation config;
    private final TaskExecutor executor;

    /** 每个 namespace|scope 一把锁：并发写入时避免同一分组被并行提炼出重复节点。 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public InsightTreeEngine(MemoryStore store,
                             LlmClient llmClient,
                             ObjectMapper mapper,
                             MemindProperties properties,
                             @Qualifier("memindExecutor") TaskExecutor executor) {
        this.store = store;
        this.llmClient = llmClient;
        this.mapper = mapper;
        this.config = properties.consolidation();
        this.executor = executor;
    }

    /** 写入后触发的异步整合。 */
    public void consolidateAsync(TenantContext context, MemoryScope scope) {
        executor.execute(() -> TenantContext.runWith(context, () -> {
            try {
                consolidate(context, scope);
            } catch (Exception ex) {
                log.error("异步整合失败 namespace={} scope={}", context.namespaceOf(scope), scope, ex);
            }
        }));
    }

    /** 每日凌晨批量整合，覆盖异步整合可能遗漏的命名空间（对应文档 8.1 批量整合策略）。 */
    @Scheduled(cron = "${memind.consolidation.cron}")
    public void consolidateAll() {
        List<MemoryStore.NamespaceRef> refs = store.listNamespaces();
        log.info("开始批量整合 Insight Tree，共 {} 个命名空间", refs.size());
        for (MemoryStore.NamespaceRef ref : refs) {
            TenantContext context = TenantContext.of(ref.tenantId(), ref.userId(), ref.scope());
            consolidate(context, ref.scope());
        }
    }

    /** 完整整合一个命名空间 + 作用域下的三层节点。 */
    public void consolidate(TenantContext context, MemoryScope scope) {
        String namespace = context.namespaceOf(scope);
        Object lock = locks.computeIfAbsent(namespace + "|" + scope, key -> new Object());
        synchronized (lock) {
            List<MemoryItem> memories = store.findMemories(namespace, scope);
            if (memories.isEmpty()) {
                return;
            }
            List<InsightNode> leaves = buildLeaves(context, scope, namespace, memories);
            List<InsightNode> branches = buildBranches(context, scope, namespace, leaves);
            buildRoot(context, scope, namespace, branches);
            log.info("Insight Tree 整合完成 namespace={} scope={} Leaf={} Branch={}",
                    namespace, scope, leaves.size(), branches.size());
        }
    }

    // ------------------------------------------------------------------ Leaf：事实洞察

    private List<InsightNode> buildLeaves(TenantContext context, MemoryScope scope, String namespace,
                                          List<MemoryItem> memories) {
        Map<String, InsightNode> existingByGroup = store.findInsightNodes(namespace, scope, InsightLevel.LEAF)
                .stream()
                .filter(node -> node.groupKey() != null)
                .collect(Collectors.toMap(InsightNode::groupKey, Function.identity(), (a, b) -> a));

        Map<String, List<MemoryItem>> grouped = memories.stream()
                .collect(Collectors.groupingBy(item -> item.type().groupKey(), LinkedHashMap::new, Collectors.toList()));

        List<InsightNode> leaves = new ArrayList<>();
        Instant now = Instant.now();
        for (Map.Entry<String, List<MemoryItem>> entry : grouped.entrySet()) {
            String groupKey = entry.getKey();
            List<MemoryItem> members = entry.getValue().stream()
                    .sorted(Comparator.comparing(MemoryItem::createdAt).reversed())
                    .limit(MAX_MEMBERS_PER_PROMPT)
                    .toList();
            if (members.size() < config.leafMinItems()) {
                continue;
            }
            List<String> memberIds = memberIds(members);
            String nodeId = deterministicId("LEAF", namespace, scope, groupKey);
            InsightNode existing = existingByGroup.get(groupKey);

            if (existing != null && Objects.equals(existing.memberIds(), memberIds)) {
                leaves.add(existing);
                continue;
            }
            InsightDraft draft = distillLeaf(groupKey, members);
            float[] embedding = llmClient.embedOne(draft.content());
            leaves.add(new InsightNode(
                    nodeId,
                    context.tenantId(),
                    context.userId(),
                    scope,
                    namespace,
                    InsightLevel.LEAF,
                    groupKey,
                    draft.content(),
                    existing == null ? null : existing.parentId(),
                    memberIds,
                    embedding,
                    draft.confidence(),
                    versionOf(memberIds),
                    existing == null ? now : existing.createdAt(),
                    now));
        }

        // 分组消失（记忆被遗忘或类型迁移）时，该 Leaf 必须一并消失，否则树会残留过期理解
        store.deleteInsightNodes(namespace, scope, InsightLevel.LEAF);
        store.saveInsightNodes(leaves);
        return leaves;
    }

    // ------------------------------------------------------------------ Branch：跨组模式

    private List<InsightNode> buildBranches(TenantContext context, MemoryScope scope, String namespace,
                                            List<InsightNode> leaves) {
        if (leaves.size() < 2) {
            store.deleteInsightNodes(namespace, scope, InsightLevel.BRANCH);
            return List.of();
        }
        List<String> leafIds = leaves.stream().map(InsightNode::id).sorted().toList();
        int expectedVersion = versionOfNodes(leaves);
        List<InsightNode> existing = store.findInsightNodes(namespace, scope, InsightLevel.BRANCH);

        List<String> existingCovered = existing.stream()
                .flatMap(node -> node.memberIds().stream())
                .distinct()
                .sorted()
                .toList();
        // 只比对 Leaf 的 ID 是不够的：ID 由分组名确定性派生，成员变化时 ID 不变而内容已变，
        // 必须同时比对 Leaf 的版本号，否则 Branch 会保留旧的 Leaf 内容。
        if (!existing.isEmpty() && existingCovered.equals(leafIds)
                && existing.stream().allMatch(node -> node.version() == expectedVersion)) {
            return existing;
        }

        List<InsightDraft> drafts = distillBranches(leaves);
        Instant now = Instant.now();
        List<InsightNode> branches = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            InsightDraft draft = drafts.get(index);
            branches.add(new InsightNode(
                    deterministicId("BRANCH", namespace, scope, String.valueOf(index)),
                    context.tenantId(),
                    context.userId(),
                    scope,
                    namespace,
                    InsightLevel.BRANCH,
                    null,
                    draft.content(),
                    null,
                    leafIds,
                    llmClient.embedOne(draft.content()),
                    draft.confidence(),
                    expectedVersion,
                    now,
                    now));
        }
        store.deleteInsightNodes(namespace, scope, InsightLevel.BRANCH);
        store.saveInsightNodes(branches);
        return branches;
    }

    // ------------------------------------------------------------------ Root：跨维度理解

    private void buildRoot(TenantContext context, MemoryScope scope, String namespace, List<InsightNode> branches) {
        if (branches.isEmpty()) {
            store.deleteInsightNodes(namespace, scope, InsightLevel.ROOT);
            return;
        }
        List<String> branchIds = branches.stream().map(InsightNode::id).sorted().toList();
        int expectedVersion = versionOfNodes(branches);
        List<InsightNode> existing = store.findInsightNodes(namespace, scope, InsightLevel.ROOT);
        InsightNode current = existing.isEmpty() ? null : existing.get(0);
        Instant now = Instant.now();
        String nodeId = deterministicId("ROOT", namespace, scope, "root");

        InsightNode root;
        // 与 Branch 同理：Branch 的 ID 由序号确定性派生、恒定不变，因此必须连同版本号一起比对，
        // 否则下层内容已更新而 Root 仍停留在首次提炼的结果上。
        if (current != null && Objects.equals(current.memberIds(), branchIds)
                && current.version() == expectedVersion) {
            root = current;
        } else {
            InsightDraft draft = distillRoot(branches);
            root = new InsightNode(
                    nodeId,
                    context.tenantId(),
                    context.userId(),
                    scope,
                    namespace,
                    InsightLevel.ROOT,
                    null,
                    draft.content(),
                    null,
                    branchIds,
                    llmClient.embedOne(draft.content()),
                    draft.confidence(),
                    expectedVersion,
                    current == null ? now : current.createdAt(),
                    now);
            store.deleteInsightNodes(namespace, scope, InsightLevel.ROOT);
            store.saveInsightNodes(List.of(root));
        }

        // 回填父指针，让树可以从任意方向遍历
        List<InsightNode> reparentedBranches = branches.stream().map(branch -> branch.withParent(root.id())).toList();
        store.saveInsightNodes(reparentedBranches);
    }

    // ------------------------------------------------------------------ 提炼（LLM 优先，规则兜底）

    private InsightDraft distillLeaf(String groupKey, List<MemoryItem> members) {
        List<String> facts = members.stream().map(MemoryItem::content).toList();
        if (llmClient.chatAvailable()) {
            String prompt = """
                    以下是同一个语义分组（%s）下的多条记忆事实：
                    %s

                    请提炼出一句更高层次的事实洞察：它应当归纳这些事实的共同特征，
                    而不是简单罗列，也不要编造输入中不存在的信息。
                    严格输出 JSON 对象：{"content":"洞察文本","confidence":0.8}
                    禁止输出解释文字与 markdown 代码块。
                    """.formatted(groupKey, bullet(facts));
            InsightDraft draft = askForObject(prompt);
            if (draft != null) {
                return draft;
            }
        }
        return new InsightDraft("该分组（" + groupKey + "）的稳定特征：" + String.join("；", head(facts, 3)),
                averageConfidence(members));
    }

    private List<InsightDraft> distillBranches(List<InsightNode> leaves) {
        List<String> insights = leaves.stream().map(node -> node.groupKey() + ": " + node.content()).toList();
        if (llmClient.chatAvailable()) {
            String prompt = """
                    以下是同一作用域下各个语义分组（Leaf）的洞察：
                    %s

                    请识别跨分组的模式：把彼此印证、能推出更深结论的 Leaf 组合起来，
                    形成不超过 %d 条模式洞察。每条洞察都必须跨越至少两个分组。
                    严格输出 JSON 对象：{"insights":[{"content":"洞察文本","confidence":0.8}]}
                    禁止输出解释文字与 markdown 代码块。
                    """.formatted(bullet(insights), config.branchMaxInsights());
            List<InsightDraft> drafts = askForArray(prompt);
            if (!drafts.isEmpty()) {
                return drafts.stream().limit(config.branchMaxInsights()).toList();
            }
        }
        List<InsightNode> strongest = leaves.stream()
                .sorted(Comparator.comparingDouble(InsightNode::confidence).reversed())
                .limit(2)
                .toList();
        return List.of(new InsightDraft(
                "跨组模式：" + String.join("；", strongest.stream().map(InsightNode::content).toList()),
                averageConfidence(strongest)));
    }

    private InsightDraft distillRoot(List<InsightNode> branches) {
        List<String> insights = branches.stream().map(InsightNode::content).toList();
        if (llmClient.chatAvailable()) {
            String prompt = """
                    以下是同一作用域下各个维度的模式洞察（Branch）：
                    %s

                    请形成一条跨维度的整体理解：说明这些维度共同揭示了什么样的对象特征与行为倾向。
                    严格输出 JSON 对象：{"content":"整体理解","confidence":0.8}
                    禁止输出解释文字与 markdown 代码块。
                    """.formatted(bullet(insights));
            InsightDraft draft = askForObject(prompt);
            if (draft != null) {
                return draft;
            }
        }
        return new InsightDraft("跨维度理解：" + String.join("；", insights), averageConfidence(branches));
    }

    private InsightDraft askForObject(String prompt) {
        try {
            String response = llmClient.chat("你是记忆洞察提炼器，只输出严格 JSON。", prompt);
            String json = JsonExtractor.extractObject(response);
            if (json == null) {
                return null;
            }
            JsonNode node = mapper.readTree(json);
            String content = node.path("content").asText("").trim();
            if (content.isEmpty()) {
                return null;
            }
            return new InsightDraft(content, clamp(node.path("confidence").asDouble(0.7d)));
        } catch (Exception ex) {
            log.warn("洞察提炼失败，使用规则兜底: {}", ex.getMessage());
            return null;
        }
    }

    private List<InsightDraft> askForArray(String prompt) {
        try {
            String response = llmClient.chat("你是记忆洞察提炼器，只输出严格 JSON。", prompt);
            String json = JsonExtractor.extractObject(response);
            if (json == null) {
                return List.of();
            }
            JsonNode insights = mapper.readTree(json).path("insights");
            if (!insights.isArray()) {
                return List.of();
            }
            List<InsightDraft> drafts = new ArrayList<>();
            for (JsonNode node : insights) {
                String content = node.path("content").asText("").trim();
                if (!content.isEmpty()) {
                    drafts.add(new InsightDraft(content, clamp(node.path("confidence").asDouble(0.7d))));
                }
            }
            return drafts;
        } catch (Exception ex) {
            log.warn("跨组模式提炼失败，使用规则兜底: {}", ex.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ 工具方法

    private static List<String> memberIds(List<MemoryItem> members) {
        return members.stream().map(MemoryItem::id).sorted().toList();
    }

    private static String bullet(List<String> lines) {
        return lines.stream()
                .map(line -> "- " + truncate(line))
                .collect(Collectors.joining("\n"));
    }

    private static String truncate(String text) {
        return text.length() <= MAX_CONTENT_CHARS ? text : text.substring(0, MAX_CONTENT_CHARS) + "…";
    }

    private static List<String> head(List<String> lines, int limit) {
        return lines.stream().limit(limit).toList();
    }

    private static double averageConfidence(List<? extends Object> sources) {
        return sources.stream().mapToDouble(source -> {
            if (source instanceof MemoryItem item) {
                return item.confidence();
            }
            if (source instanceof InsightNode node) {
                return node.confidence();
            }
            return 0.6d;
        }).average().orElse(0.6d);
    }

    private static double clamp(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    private static int versionOf(List<String> memberIds) {
        return Math.abs(Hashing.sha256(String.join(",", memberIds)).hashCode());
    }

    /**
     * 由下级节点的「ID + 版本号」派生的父节点版本号。
     *
     * <p>下级节点的 ID 往往是确定性派生的（Leaf 用分组名、Branch 用序号），成员集合变化时 ID 不变，
     * 只有版本号会变；因此父节点必须基于「ID + 版本号」判断是否需要重新提炼。
     */
    private static int versionOfNodes(List<InsightNode> nodes) {
        return versionOf(nodes.stream().map(node -> node.id() + "#" + node.version()).sorted().toList());
    }

    /** 节点 ID 由「层级 + 命名空间 + 作用域 + 分组」确定性派生，重新提炼时原地更新而非堆积副本。 */
    private static String deterministicId(String level, String namespace, MemoryScope scope, String discriminator) {
        return Hashing.sha256(level + "|" + namespace + "|" + scope + "|" + discriminator).substring(0, 32);
    }

    private record InsightDraft(String content, double confidence) {
    }
}