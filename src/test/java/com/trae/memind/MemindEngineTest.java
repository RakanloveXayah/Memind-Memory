package com.trae.memind;

import com.trae.memind.domain.ConversationLog;
import com.trae.memind.domain.ExtractionStatus;
import com.trae.memind.domain.InsightLevel;
import com.trae.memind.domain.MemoryItem;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.insight.InsightTreeEngine;
import com.trae.memind.pipeline.ExtractionInput;
import com.trae.memind.pipeline.ExtractionOutcome;
import com.trae.memind.pipeline.ExtractionPipeline;
import com.trae.memind.retrieval.ContextCompiler;
import com.trae.memind.retrieval.RetrievalEngine;
import com.trae.memind.retrieval.RetrievalResult;
import com.trae.memind.store.MemoryStore;
import com.trae.memind.tenant.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心引擎端到端验证：抽取 → 去重落库 → Insight Tree 三层提炼 → 多层级检索 → 上下文组装。
 *
 * <p>测试环境不配置任何模型 API Key，因此同时验证了「模型不可用时全链路仍可运行」的降级路径。
 *
 * <p>测试跑真实 PostgreSQL 的 memind_test 库，同一套断言可能被重复执行，因此在类级别清空业务表。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemindEngineTest {

    /** 覆盖时间、维度、指标三类稳定偏好，保证 Leaf 分组数量足以触发 Branch 与 Root。 */
    private static final List<String> USER_MESSAGES = List.of(
            "我负责增长运营这块工作",
            "销售额和订单量是我最关心的两个指标",
            "我主要看GMV和转化率",
            "所有报表都要按渠道拆分",
            "再按地区分组看一下",
            "最近30天是我的常用时间窗口",
            "上周也经常用到",
            "帮我查一下上周的数据",
            "我偏好柱状图展示结果");

    @Autowired
    private ExtractionPipeline pipeline;
    @Autowired
    private InsightTreeEngine insightTreeEngine;
    @Autowired
    private RetrievalEngine retrievalEngine;
    @Autowired
    private ContextCompiler contextCompiler;
    @Autowired
    private MemoryStore store;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 测试库是长期存在的真实实例，跑之前先清空四张业务表，保证断言不受历史数据影响。 */
    @BeforeAll
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE memind_memories, memind_insight_nodes, "
                + "memind_conversation_logs, memind_raw_contents");
    }

    @Test
    @DisplayName("完整记忆闭环：抽取 → 整合 → 检索 → 组装上下文")
    void endToEndMemoryLoop() {
        TenantContext context = TenantContext.of("t-e2e", "alice", MemoryScope.USER);

        ExtractionOutcome outcome = pipeline.extractNow(context,
                conversation(context, MemoryScope.USER, USER_MESSAGES));

        assertEquals(ExtractionStatus.SUCCESS, outcome.status(), outcome.message());
        assertFalse(outcome.items().isEmpty(), "规则抽取应当产出记忆条目");
        for (MemoryItem item : outcome.items()) {
            assertEquals("t-e2e:alice", item.namespace());
            assertEquals(MemoryScope.USER, item.scope());
            assertTrue(item.confidence() >= 0.5d);
        }

        String namespace = context.namespaceOf(MemoryScope.USER);
        insightTreeEngine.consolidate(context, MemoryScope.USER);

        List<com.trae.memind.domain.InsightNode> leaves =
                store.findInsightNodes(namespace, MemoryScope.USER, InsightLevel.LEAF);
        List<com.trae.memind.domain.InsightNode> branches =
                store.findInsightNodes(namespace, MemoryScope.USER, InsightLevel.BRANCH);
        List<com.trae.memind.domain.InsightNode> roots =
                store.findInsightNodes(namespace, MemoryScope.USER, InsightLevel.ROOT);

        assertTrue(leaves.size() >= 2, "至少两个语义分组应当各自形成 Leaf，实际 " + leaves.size());
        assertFalse(branches.isEmpty(), "多个 Leaf 应当汇聚出 Branch");
        assertEquals(1, roots.size(), "多个 Branch 应当汇聚出唯一的 Root");
        assertFalse(roots.get(0).content().isBlank());
        // 树的父指针应当可双向遍历
        assertTrue(branches.stream().allMatch(branch -> roots.get(0).id().equals(branch.parentId())));

        RetrievalResult result = retrievalEngine.retrieve(context,
                List.of(MemoryScope.USER, MemoryScope.AGENT), "帮我看看数据", 10);
        assertFalse(result.memories().isEmpty(), "检索应当召回记忆");
        assertTrue(result.insights().stream().anyMatch(insight -> insight.node().level() == InsightLevel.ROOT),
                "Root 理解应当进入检索结果（离线用例把相关性闸门设成 -1，等于关闭）");
        assertFalse(result.signals().keywords().isEmpty(), "查询分析应当产出关键词");

        ContextCompiler.CompiledContext compiled = contextCompiler.compile(result, null);
        assertTrue(compiled.text().contains("## 对该用户的理解"), compiled.text());
        assertTrue(compiled.text().contains("## 该用户的偏好与习惯"), compiled.text());
        assertTrue(compiled.estimatedTokens() > 0);
    }

    @Test
    @DisplayName("整合具备幂等性：重复整合不产生重复节点")
    void consolidationIsIdempotent() {
        TenantContext context = TenantContext.of("t-idem", "carol", MemoryScope.USER);
        pipeline.extractNow(context, conversation(context, MemoryScope.USER, USER_MESSAGES));

        insightTreeEngine.consolidate(context, MemoryScope.USER);
        String namespace = context.namespaceOf(MemoryScope.USER);
        List<String> firstLeaves = store.findInsightNodes(namespace, MemoryScope.USER, InsightLevel.LEAF)
                .stream().map(com.trae.memind.domain.InsightNode::id).sorted().toList();

        insightTreeEngine.consolidate(context, MemoryScope.USER);
        List<String> secondLeaves = store.findInsightNodes(namespace, MemoryScope.USER, InsightLevel.LEAF)
                .stream().map(com.trae.memind.domain.InsightNode::id).sorted().toList();

        assertEquals(firstLeaves, secondLeaves);
        assertEquals(1, store.findInsightNodes(namespace, MemoryScope.USER, InsightLevel.ROOT).size());
    }

    @Test
    @DisplayName("双作用域 + 多租户隔离：USER 按用户隔离，AGENT 租户内共享，跨租户不可见")
    void tenantAndScopeIsolation() {
        TenantContext alice = TenantContext.of("t-iso", "alice", MemoryScope.USER);
        TenantContext bob = TenantContext.of("t-iso", "bob", MemoryScope.USER);
        TenantContext outsider = TenantContext.of("t-other", "bob", MemoryScope.USER);

        pipeline.extractNow(alice, conversation(alice, MemoryScope.USER, List.of("我负责增长运营这块工作")));
        pipeline.extractNow(alice, conversation(alice, MemoryScope.AGENT,
                List.of("查询GMV时优先使用 sales_order 表的 order_amount 字段")));

        assertFalse(store.findMemories(alice.namespaceOf(MemoryScope.USER), MemoryScope.USER).isEmpty());
        assertTrue(store.findMemories(bob.namespaceOf(MemoryScope.USER), MemoryScope.USER).isEmpty(),
                "同租户不同用户的 USER 记忆必须物理隔离");

        assertEquals(alice.namespaceOf(MemoryScope.AGENT), bob.namespaceOf(MemoryScope.AGENT),
                "AGENT 记忆的隔离边界只到租户");
        assertFalse(store.findMemories(bob.namespaceOf(MemoryScope.AGENT), MemoryScope.AGENT).isEmpty(),
                "AGENT 记忆应当在租户内共享");
        assertTrue(store.findMemories(outsider.namespaceOf(MemoryScope.AGENT), MemoryScope.AGENT).isEmpty(),
                "跨租户不可见");

        assertTrue(retrievalEngine.retrieve(outsider, List.of(MemoryScope.USER, MemoryScope.AGENT), "GMV", 10)
                .memories().isEmpty(), "跨租户检索必须为空");
    }

    @Test
    @DisplayName("去重：重复写入同一事实不产生重复记忆")
    void deduplicationSkipsRepeatedFacts() {
        TenantContext context = TenantContext.of("t-dedup", "dave", MemoryScope.USER);
        List<String> messages = List.of("所有报表都要按渠道拆分");

        ExtractionOutcome first = pipeline.extractNow(context, conversation(context, MemoryScope.USER, messages));
        ExtractionOutcome second = pipeline.extractNow(context, conversation(context, MemoryScope.USER, messages));

        assertEquals(1, first.items().size());
        assertTrue(second.items().isEmpty(), "完全相同的事实应当被去重");
        assertEquals(1, store.findMemories(context.namespaceOf(MemoryScope.USER), MemoryScope.USER).size());
    }

    private ExtractionInput conversation(TenantContext context, MemoryScope scope, List<String> messages) {
        Instant now = Instant.now();
        StringBuilder transcript = new StringBuilder();
        List<ConversationLog> logs = new ArrayList<>();
        for (String message : messages) {
            transcript.append("user: ").append(message).append('\n');
            logs.add(new ConversationLog(
                    java.util.UUID.randomUUID().toString().replace("-", ""),
                    context.tenantId(),
                    context.userId(),
                    scope,
                    context.namespaceOf(scope),
                    "session-1",
                    "user",
                    message,
                    now));
        }
        return new ExtractionInput(scope, "CONVERSATION", "session-1", transcript.toString(), logs);
    }
}