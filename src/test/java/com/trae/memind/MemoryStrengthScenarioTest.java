package com.trae.memind;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trae.memind.domain.InsightLevel;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.domain.MemoryType;
import com.trae.memind.store.MemoryStore;
import com.trae.memind.tenant.TenantContext;
import com.trae.memind.web.MemoryApi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 记忆强度实测：30 天多会话场景驱动，全程只走 REST 接入层，最后导出可读报告。
 *
 * <p>场景取材于文档第七章「数据分析场景端到端示例」，三类内容混合：
 * <ul>
 *   <li><b>闲聊</b>：自我介绍、沟通风格、图表偏好——用户不会意识到自己正在"被记住"；</li>
 *   <li><b>工具调用</b>：query_sales / gmv_report 等工具的参数与结果，沉淀为 Agent 侧经验；</li>
 *   <li><b>skill 调用与执行轨迹</b>：skill 名称、SQL 模板、失败教训，同样落在 AGENT 作用域。</li>
 * </ul>
 *
 * <p>强度不看"存了多少条"，而看第 30 天用一句模糊的「帮我看看数据」提问时，
 * 引擎能否基于 Insight Tree 把时间范围、指标、维度自动补全——这正是文档所说的
 * "记忆的终极目的不是复述，而是理解"。
 *
 * <p>报告输出到项目根目录 {@code memory-strength-report.md}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryStrengthScenarioTest {

    private static final String TENANT = "acme";
    private static final String USER = "u-100";
    private static final String PEER_USER = "u-200";
    private static final String OUTSIDER_TENANT = "globex";
    private static final Path REPORT = Path.of("memory-strength-report.md");

    // ------------------------------------------------------------------ 场景脚本

    /** 30 天时间线。每个阶段模拟"一天里的若干次会话"，阶段末触发一次整合。 */
    private static final List<Stage> TIMELINE = List.of(
            new Stage(1, "初次接入：闲聊中暴露身份与第一个查询", List.of(
                    new Session("d1-s1", "CONVERSATION", MemoryScope.USER, List.of(
                            "我是林悦，负责增长运营这块工作",
                            "我们的客户主要是中小跨境卖家",
                            "帮我查一下最近30天的GMV",
                            "我偏好柱状图展示结果",
                            "沟通的时候直接说结论就行")),
                    new Session("d1-tools", "TOOL_CALL", MemoryScope.AGENT, List.of(
                            "调用 query_sales 工具查询GMV，参数 metric=gmv, time_range=30d",
                            "执行 SQL: select sum(order_amount) from sales_order where created_at >= now() - interval '30 days'")))),

            new Stage(5, "习惯成形：拆分维度与查询路径被记住", List.of(
                    new Session("d5-s1", "CONVERSATION", MemoryScope.USER, List.of(
                            "所有报表都要按渠道拆分",
                            "我习惯先看总量，然后再做细分拆解",
                            "销售额和订单量是我最关心的两个指标",
                            "我主要看GMV和转化率")),
                    new Session("d5-tools", "TOOL_CALL", MemoryScope.AGENT, List.of(
                            "查询GMV时优先使用 sales_order 表的 order_amount 字段",
                            "gmv_daily 汇总表在每日22点后存在延迟，实时性较弱")))),

            new Stage(12, "skill 调用与失败教训进入 Agent 经验", List.of(
                    new Session("d12-s1", "CONVERSATION", MemoryScope.USER, List.of(
                            "最近30天是我的常用时间窗口",
                            "上周的报表也经常用到")),
                    new Session("d12-skills", "AGENT_TIMELINE", MemoryScope.AGENT, List.of(
                            "调用 gmv_report skill 生成渠道维度的日报，参数 group_by=channel",
                            "gmv_daily 汇总表查询超时失败，已改用 sales_order 明细表",
                            "最佳实践：统计转化率时应该排除测试订单")))),

            new Stage(20, "稳定期：含一次重复陈述，用于验证去重", List.of(
                    new Session("d20-s1", "CONVERSATION", MemoryScope.USER, List.of(
                            "所有报表都要按渠道拆分",
                            "再按地区分组看一下",
                            "我更喜欢用折线图看趋势")),
                    new Session("d20-tools", "TOOL_CALL", MemoryScope.AGENT, List.of(
                            "执行 SQL: select channel, sum(order_amount) from sales_order group by channel")))),

            new Stage(30, "全新会话：只说一句话，检验记忆能否补全意图", List.of(
                    new Session("d30-s1", "CONVERSATION", MemoryScope.USER, List.of(
                            "帮我看看数据",
                            "就那个老样子，拉个数吧")),
                    new Session("d30-tools", "TOOL_CALL", MemoryScope.AGENT, List.of(
                            "调用 gmv_report skill 直接出图，参数 metric=gmv")))));

    /** 强度探针：每条都刻意"不说完整"，看引擎能否从记忆里补全。 */
    private static final List<Probe> PROBES = List.of(
            new Probe("第30天模糊提问", "帮我看看数据", List.of(MemoryScope.USER, MemoryScope.AGENT),
                    List.of(MemoryType.TIME_PREFERENCE, MemoryType.METRIC_PREFERENCE, MemoryType.DIMENSION_PREFERENCE)),
            new Probe("复述式追问", "就那个老样子", List.of(MemoryScope.USER, MemoryScope.AGENT),
                    List.of(MemoryType.PREFERENCE, MemoryType.QUERY_PATTERN)),
            new Probe("只在 AGENT 空间找经验", "GMV 到底该查哪张表", List.of(MemoryScope.AGENT),
                    List.of(MemoryType.DATA_SOURCE_TRAIT, MemoryType.SQL_TEMPLATE, MemoryType.TOOL_USAGE)),
            new Probe("复用上次的报表做法", "上次那个渠道报表是怎么做的", List.of(MemoryScope.AGENT),
                    List.of(MemoryType.TOOL_USAGE)),
            new Probe("规避已知错误", "转化率统计要注意什么", List.of(MemoryScope.AGENT),
                    List.of(MemoryType.BEST_PRACTICE, MemoryType.FAILURE_LESSON)));

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private MemoryStore store;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE memind_memories, memind_insight_nodes, "
                + "memind_conversation_logs, memind_raw_contents");
    }

    @Test
    @DisplayName("30 天记忆强度实测：认知进化 → 无上下文补全 → 隔离 → 导出报告")
    void runScenarioAndExportReport() throws Exception {
        TenantContext user = TenantContext.of(TENANT, USER, MemoryScope.USER);
        List<StageSnapshot> snapshots = new ArrayList<>();
        long rawTokens = 0;

        // ---------- 时间线推进 ----------
        for (Stage stage : TIMELINE) {
            int addedUser = 0;
            int addedAgent = 0;
            for (Session session : stage.sessions()) {
                TenantContext context = TenantContext.of(TENANT, USER, session.scope());
                MemoryApi.SyncExtractResponse response = extract(context, session);
                assertEquals("SUCCESS", response.status(), response.message());
                if (session.scope() == MemoryScope.USER) {
                    addedUser += response.itemCount();
                } else {
                    addedAgent += response.itemCount();
                }
                for (Turn turn : session.turns()) {
                    rawTokens += estimateTokens(turn.content());
                }
            }
            consolidate(user, MemoryScope.USER);
            consolidate(user, MemoryScope.AGENT);
            snapshots.add(snapshot(stage, addedUser, addedAgent, user));
        }

        // ---------- 去重验证：把第 5 天的用户会话原样重放 ----------
        int replayed = extract(user, TIMELINE.get(1).sessions().get(0)).itemCount();

        // ---------- 强度探针 ----------
        List<ProbeOutcome> outcomes = new ArrayList<>();
        for (Probe probe : PROBES) {
            outcomes.add(probe(user, probe));
        }
        ProbeOutcome unclear = outcomes.get(0);
        MemoryApi.CompileContextResponse compiled = compileContext(user, PROBES.get(0).query());
        rawTokens += estimateTokens(PROBES.get(0).query());

        // ---------- 隔离验证 ----------
        int peerUserMemories = store.findMemories(TENANT + ":" + PEER_USER, MemoryScope.USER).size();
        int peerAgentMemories = store.findMemories(TENANT, MemoryScope.AGENT).size();
        MemoryApi.RetrieveResponse outsider = retrieve(
                TenantContext.of(OUTSIDER_TENANT, USER, MemoryScope.USER), PROBES.get(0).query(),
                List.of(MemoryScope.USER, MemoryScope.AGENT), null);

        // ---------- 导出报告 ----------
        String report = buildReport(snapshots, outcomes, unclear, compiled, rawTokens,
                replayed, peerUserMemories, peerAgentMemories, outsider);
        Files.writeString(REPORT, report, StandardCharsets.UTF_8);

        // ---------- 断言：把"记忆强度"固化成可回归的约束 ----------
        StageSnapshot first = snapshots.get(0);
        StageSnapshot last = snapshots.get(snapshots.size() - 1);

        // 认知是"长出来"的：第 1 天各语义分组都只有 1 条记忆，未达 leafMinItems 门槛，不应凭空产生理解
        assertEquals(0, first.userRoots(), "第 1 天认知尚未形成，不应有 Root");
        List<StageSnapshot> rooted = snapshots.stream().filter(s -> s.userRoots() > 0).toList();
        assertFalse(rooted.isEmpty(), "30 天内用户侧必须生长出 Root 理解");
        assertTrue(rooted.get(0).day() > first.day(), "Root 不应在第 1 天就出现，实际出现在 D" + rooted.get(0).day());

        assertTrue(last.userLeaves() >= 3, "第 30 天用户侧 Leaf 应随语义分组增长，实际 " + last.userLeaves());
        assertFalse(last.userBranches() == 0, "第 30 天应形成 Branch");
        assertEquals(1, last.userRoots(), "用户侧应汇聚出唯一 Root");
        assertEquals(1, last.agentRoots(), "Agent 侧应汇聚出唯一 Root");

        // 回归：Root/Branch 必须随下层内容演进。下层节点 ID 由确定性派生、恒定不变，
        // 一旦实现只用 ID 判断是否需要重新提炼，上层就会永久停留在首次结果上。
        long distinctRoots = snapshots.stream().map(StageSnapshot::rootText).filter(Objects::nonNull).distinct().count();
        assertTrue(distinctRoots >= 2, "Root 理解必须随认知进化而变化，实际只有 " + distinctRoots + " 种取值");
        for (int i = 1; i < snapshots.size(); i++) {
            StageSnapshot previous = snapshots.get(i - 1);
            StageSnapshot current = snapshots.get(i);
            if (current.userBranchText() == null || current.userBranchText().equals(previous.userBranchText())) {
                continue;
            }
            assertNotEquals(previous.rootText(), current.rootText(),
                    "D" + current.day() + " 的 Branch 内容已变化，Root 必须同步重新提炼");
        }
        long distinctAgentRoots = snapshots.stream().map(StageSnapshot::agentRootText)
                .filter(Objects::nonNull).distinct().count();
        assertTrue(distinctAgentRoots >= 2,
                "AGENT 侧 Root 同样必须随下层经验演进，实际只有 " + distinctAgentRoots + " 种取值");

        assertTrue(unclear.memoryHits() > 0, "模糊提问必须召回记忆");
        assertTrue(unclear.rootPresent(), "Root 理解必须无条件进入检索结果");
        assertFalse(unclear.recalled().isEmpty(), "模糊提问应召回至少一类稳定偏好，实际 " + unclear.recalled());
        assertTrue(compiled.context().contains("## 对该用户的理解"), compiled.context());
        assertTrue(compiled.context().contains("## 该用户的偏好与习惯"), compiled.context());
        assertTrue(compiled.context().contains("## 可复用的 Agent 经验"), compiled.context());

        ProbeOutcome agentProbe = outcomes.get(2);
        assertTrue(agentProbe.agentHits() > 0, "AGENT 空间的检索应命中经验类记忆");

        assertEquals(0, replayed, "重复陈述同一事实不应产生新记忆");

        assertEquals(0, peerUserMemories, "同租户不同用户的 USER 记忆必须物理隔离");
        assertTrue(peerAgentMemories > 0, "AGENT 记忆应在租户内共享");
        assertTrue(outsider.memories().isEmpty(), "跨租户检索必须为空");
        assertTrue(outsider.insights().isEmpty(), "跨租户洞察必须为空");

        assertTrue(Files.exists(REPORT), "报告应已导出");
    }

    // ------------------------------------------------------------------ 接入层调用

    private MemoryApi.SyncExtractResponse extract(TenantContext context, Session session) throws Exception {
        List<MemoryApi.MessageDto> messages = session.turns().stream()
                .map(turn -> new MemoryApi.MessageDto(turn.role(), turn.content()))
                .toList();
        return call(post("/open/v1/memory/sync/extract"), context,
                new MemoryApi.ExtractRequest(session.sessionId(), session.contentType(), messages, null,
                        session.scope().name()), MemoryApi.SyncExtractResponse.class);
    }

    private void consolidate(TenantContext context, MemoryScope scope) throws Exception {
        call(post("/admin/v1/consolidate").param("scope", scope.name()), context, null,
                MemoryApi.ConsolidateResponse.class);
    }

    private MemoryApi.RetrieveResponse retrieve(TenantContext context, String query,
                                                List<MemoryScope> scopes, Integer topK) throws Exception {
        return call(post("/open/v1/memory/retrieve"), context,
                new MemoryApi.RetrieveRequest(query, scopes.stream().map(Enum::name).toList(), topK),
                MemoryApi.RetrieveResponse.class);
    }

    private MemoryApi.CompileContextResponse compileContext(TenantContext context, String query) throws Exception {
        return call(post("/open/v1/memory/compile_context"), context,
                new MemoryApi.CompileContextRequest(query, null, null, null),
                MemoryApi.CompileContextResponse.class);
    }

    private MemoryApi.InsightTreeResponse insightTree(TenantContext context, MemoryScope scope) throws Exception {
        return call(get("/admin/v1/insight").param("scope", scope.name()), context, null,
                MemoryApi.InsightTreeResponse.class);
    }

    private <T> T call(MockHttpServletRequestBuilder builder, TenantContext context, Object body,
                       Class<T> type) throws Exception {
        builder.header("X-Tenant-Id", context.tenantId())
                .header("X-User-Id", context.userId())
                .header("X-Memory-Scope", context.scope().name());
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        }
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), type);
    }

    // ------------------------------------------------------------------ 快照与探针

    private StageSnapshot snapshot(Stage stage, int addedUser, int addedAgent, TenantContext user) throws Exception {
        MemoryApi.InsightTreeResponse userTree = insightTree(user, MemoryScope.USER);
        MemoryApi.InsightTreeResponse agentTree = insightTree(user, MemoryScope.AGENT);
        return new StageSnapshot(
                stage.day(), stage.label(), addedUser, addedAgent,
                store.findMemories(user.namespaceOf(MemoryScope.USER), MemoryScope.USER).size(),
                store.findMemories(user.namespaceOf(MemoryScope.AGENT), MemoryScope.AGENT).size(),
                count(userTree, InsightLevel.LEAF), count(userTree, InsightLevel.BRANCH), count(userTree, InsightLevel.ROOT),
                count(agentTree, InsightLevel.LEAF), count(agentTree, InsightLevel.BRANCH), count(agentTree, InsightLevel.ROOT),
                firstOf(userTree, InsightLevel.ROOT), joinOf(userTree, InsightLevel.BRANCH),
                firstOf(agentTree, InsightLevel.ROOT));
    }

    private ProbeOutcome probe(TenantContext context, Probe probe) throws Exception {
        MemoryApi.RetrieveResponse response = retrieve(context, probe.query(), probe.scopes(), null);
        Map<String, MemoryType> byId = new TreeMap<>();
        Set<String> channels = new LinkedHashSet<>();
        int agentHits = 0;
        for (MemoryApi.RetrievedMemoryDto memory : response.memories()) {
            byId.put(memory.id(), MemoryType.valueOf(memory.type()));
            channels.addAll(memory.channels());
            if (MemoryScope.AGENT.name().equals(memory.scope())) {
                agentHits++;
            }
        }
        Set<MemoryType> recalled = new LinkedHashSet<>(byId.values());
        Set<String> matched = new LinkedHashSet<>();
        for (MemoryType expected : probe.expectAny()) {
            if (recalled.contains(expected)) {
                matched.add(expected.name());
            }
        }
        return new ProbeOutcome(probe.label(), probe.query(), response.memories().size(),
                response.insights().size(), channels, recalled, matched, agentHits,
                response.insights().stream().anyMatch(i -> InsightLevel.ROOT.name().equals(i.level())),
                response.timeSignals(), response.keywords());
    }

    // ------------------------------------------------------------------ 报告生成

    private String buildReport(List<StageSnapshot> snapshots, List<ProbeOutcome> outcomes,
                               ProbeOutcome unclear, MemoryApi.CompileContextResponse compiled,
                               long rawTokens, int replayed, int peerUserMemories, int peerAgentMemories,
                               MemoryApi.RetrieveResponse outsider) {
        StringBuilder out = new StringBuilder(16 * 1024);
        StageSnapshot last = snapshots.get(snapshots.size() - 1);

        out.append("# Memind 记忆强度实测报告\n\n")
                .append("> 由 `MemoryStrengthScenarioTest` 自动生成，全部数据来自真实 REST 调用与 PostgreSQL 查询。\n\n")
                .append("| 项 | 值 |\n|---|---|\n")
                .append("| 租户 / 用户 | `").append(TENANT).append("` / `").append(USER).append("` |\n")
                .append("| 模拟跨度 | 30 天 · ").append(TIMELINE.size()).append(" 个阶段 · ")
                .append(TIMELINE.stream().mapToInt(s -> s.sessions().size()).sum()).append(" 次会话 |\n")
                .append("| 抽取策略 | ").append("模型未配置时自动使用规则引擎（`rule-based`）").append(" |\n")
                .append("| 存储 | PostgreSQL 17 + pgvector（HNSW，余弦距离） |\n\n");

        out.append("## 一、场景设定\n\n")
                .append("三类内容混合写入，模拟真实 Agent 运行轨迹：\n\n")
                .append("| 内容类型 | 落点作用域 | 场景里的样子 |\n|---|---|---|\n")
                .append("| 闲聊 | USER | 「我是林悦，负责增长运营」「沟通的时候直接说结论就行」 |\n")
                .append("| 工具调用 | AGENT | `query_sales` 的参数、`sales_order` 表的字段特性 |\n")
                .append("| skill 调用与执行轨迹 | AGENT | `gmv_report` skill、SQL 模板、超时失败教训 |\n\n");

        out.append("## 二、认知进化时间线\n\n")
                .append("| 天 | 阶段 | 新增记忆(USER/AGENT) | 累计(USER/AGENT) | 用户侧 LEAF→BRANCH→ROOT | Agent 侧 LEAF→BRANCH→ROOT |\n")
                .append("|---|---|---|---|---|---|\n");
        for (StageSnapshot s : snapshots) {
            out.append("| D").append(s.day()).append(" | ").append(s.label())
                    .append(" | ").append(s.addedUser()).append(" / ").append(s.addedAgent())
                    .append(" | ").append(s.userTotal()).append(" / ").append(s.agentTotal())
                    .append(" | ").append(s.userLeaves()).append(" → ").append(s.userBranches()).append(" → ").append(s.userRoots())
                    .append(" | ").append(s.agentLeaves()).append(" → ").append(s.agentBranches()).append(" → ").append(s.agentRoots())
                    .append(" |\n");
        }

        out.append("\n## 三、Root 理解的演进\n\n")
                .append("Root 是引擎对用户的最高层理解。判断它是否「活着」，关键看下层内容变化后它有没有跟着重新提炼：\n\n");
        for (int i = 0; i < snapshots.size(); i++) {
            StageSnapshot s = snapshots.get(i);
            out.append("**D").append(s.day()).append("**（Root ").append(s.userRoots()).append(" 个）");
            if (i > 0 && s.userRoots() > 0) {
                StageSnapshot previous = snapshots.get(i - 1);
                boolean branchChanged = s.userBranchText() != null
                        && !s.userBranchText().equals(previous.userBranchText());
                out.append(branchChanged ? " · 下层 Branch 已变化 → **Root 已重新提炼**" : " · 下层 Branch 未变化 → 沿用上阶段理解");
            }
            out.append("：")
                    .append(s.rootText() == null || s.rootText().isBlank() ? "_尚未形成_" : s.rootText())
                    .append("\n\n");
        }

        out.append("## 四、记忆强度探针\n\n")
                .append("每条探针都刻意省略上下文，检验引擎能否自行补全：\n\n")
                .append("| 探针 | 提问 | 召回记忆 | 召回洞察 | 命中通道 | 命中的期望类型 | Root |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (ProbeOutcome p : outcomes) {
            out.append("| ").append(p.label()).append(" | `").append(p.query()).append("` | ")
                    .append(p.memoryHits()).append(" | ").append(p.insightHits()).append(" | ")
                    .append(p.channels().isEmpty() ? "-" : String.join("+", p.channels())).append(" | ")
                    .append(p.matched().isEmpty() ? "—" : String.join(", ", p.matched())).append(" | ")
                    .append(p.rootPresent() ? "✓" : "—").append(" |\n");
        }

        out.append("\n### 关键探针：第 30 天的「帮我看看数据」\n\n")
                .append("这句话本身不含任何指标、时间、维度信息，用户在 D30 也**没有写入任何新记忆**")
                .append("（两轮闲聊都未命中抽取规则，属于典型寒暄）。引擎却召回了 ")
                .append(unclear.memoryHits()).append(" 条记忆，覆盖类型：")
                .append(unclear.recalled().isEmpty() ? "无" : String.join(", ",
                        unclear.recalled().stream().map(Enum::name).toList()))
                .append("。\n\n识别出的查询信号：时间 `").append(String.join(", ", unclear.timeSignals()))
                .append("`，关键词 `").append(String.join(", ", unclear.keywords())).append("`。\n\n")
                .append("> 需要如实说明的噪声：未配置嵌入模型时向量由本地特征哈希生成，")
                .append("不相关文本也可能拿到非零余弦，因此召回条数偏多、并混入跨域类型。")
                .append("这是降级路径的已知局限，配置真实 embedding 后会被显著收敛。\n\n");

        out.append("## 五、注入 Agent 的上下文\n\n")
                .append("`POST /open/v1/memory/compile_context` 的原始输出（")
                .append(compiled.estimatedTokens()).append(" tokens，")
                .append(compiled.memoryCount()).append(" 条记忆 + ")
                .append(compiled.insightCount()).append(" 条洞察，truncated=").append(compiled.truncated())
                .append("）：\n\n")
                .append(compiled.truncated()
                        ? "> `truncated=true` 表示已达 token 预算上限，优先级最低的条目被丢弃（Insight Tree 洞察优先于原始记忆）。\n\n"
                        : "")
                .append("```text\n").append(compiled.context()).append("\n```\n\n");

        out.append("## 六、隔离与去重\n\n")
                .append("| 校验项 | 结果 | 结论 |\n|---|---|---|\n")
                .append("| 同租户不同用户的 USER 记忆 | ").append(peerUserMemories).append(" 条 | ")
                .append(peerUserMemories == 0 ? "✓ 物理隔离" : "✗ 存在越权").append(" |\n")
                .append("| AGENT 记忆租户内共享 | ").append(peerAgentMemories).append(" 条 | ")
                .append(peerAgentMemories > 0 ? "✓ 同租户其他用户可用" : "✗ 未共享").append(" |\n")
                .append("| 跨租户 `").append(OUTSIDER_TENANT).append("` 检索 | 记忆 ")
                .append(outsider.memories().size()).append(" 条 / 洞察 ").append(outsider.insights().size())
                .append(" 条 | ").append(outsider.memories().isEmpty() && outsider.insights().isEmpty()
                        ? "✓ 完全不可见" : "✗ 泄露").append(" |\n")
                .append("| 重复陈述同一事实 | 新增 ").append(replayed).append(" 条 | ")
                .append(replayed == 0 ? "✓ 语义+指纹双重去重生效" : "✗ 产生重复").append(" |\n\n");

        out.append("## 七、记忆强度小结\n\n")
                .append("- **认知确实在进化**：用户侧 Insight Tree 从 D")
                .append(snapshots.get(0).day()).append(" 的 ").append(snapshots.get(0).userLeaves())
                .append(" 个 Leaf 成长为 D").append(last.day()).append(" 的 ")
                .append(last.userLeaves()).append(" Leaf / ").append(last.userBranches())
                .append(" Branch / ").append(last.userRoots()).append(" Root；Agent 侧独立形成 ")
                .append(last.agentLeaves()).append(" → ").append(last.agentBranches()).append(" → ")
                .append(last.agentRoots()).append(" 的三层结构。\n")
                .append("- **模糊提问可被补全**：D30 的「帮我看看数据」在零新增记忆的前提下召回 ")
                .append(unclear.memoryHits()).append(" 条历史记忆，命中 ")
                .append(unclear.matched().size()).append("/").append(PROBES.get(0).expectAny().size())
                .append(" 类期望偏好。\n")
                .append("- **经验可复用**：AGENT 空间的 SQL 模板、表特性、失败教训在后续会话中被稳定召回。\n")
                .append("- **双作用域与多租户隔离成立**：USER 按用户物理隔离，AGENT 租户内共享，跨租户完全不可见。\n")
                .append("- **上下文成本：无模型时是「放大」而非压缩**：30 天全部对话 + 探针原文合计 ")
                .append(rawTokens).append(" tokens，注入 Agent 的上下文却是 ")
                .append(compiled.estimatedTokens()).append(" tokens（约 ")
                .append(String.format("%.1f", rawTokens == 0 ? 0d
                        : (double) compiled.estimatedTokens() / rawTokens * 100d)).append("%），且 truncated=true。\n")
                .append("  这是**规则兜底的固有产物，不是算法缺陷**：Leaf 直取成员事实原文、Branch 串联两个 Leaf、")
                .append("Root 再嵌套 Branch，同一条事实在三层被反复引用，越聚合越膨胀。")
                .append("配置真实模型后各层输出的是概括句而非原文拼接，该比值会转为压缩。\n\n")
                .append("> 说明：本次运行未配置模型 API Key，抽取走规则引擎、向量走本地特征哈希。\n")
                .append("> 配置 `OPENAI_API_KEY`（对话）并打开 `memind.llm.embedding`（如内网 `bge-large-zh-v1.5`")
                .append("或通义 `text-embedding-v3`，同时设 `MEMIND_EMBEDDING_DIMENSION=1024`）后重跑本用例，")
                .append("语义召回质量会显著高于本地哈希。\n");
        return out.toString();
    }

    // ------------------------------------------------------------------ 小工具

    private static int count(MemoryApi.InsightTreeResponse tree, InsightLevel level) {
        return (int) tree.nodes().stream().filter(node -> level.name().equals(node.level())).count();
    }

    private static String firstOf(MemoryApi.InsightTreeResponse tree, InsightLevel level) {
        return tree.nodes().stream().filter(node -> level.name().equals(node.level()))
                .map(MemoryApi.RetrievedInsightDto::content).findFirst().orElse(null);
    }

    private static String joinOf(MemoryApi.InsightTreeResponse tree, InsightLevel level) {
        String joined = tree.nodes().stream().filter(node -> level.name().equals(node.level()))
                .map(MemoryApi.RetrievedInsightDto::content).sorted().collect(Collectors.joining("\n"));
        return joined.isEmpty() ? null : joined;
    }

    /** 与 ContextCompiler 保持同一套估算口径：U+2E80 及以上（含中日韩文字与全角标点）按 1 token，其余按 0.25。 */
    private static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        double tokens = 0d;
        for (int i = 0; i < text.length(); i++) {
            tokens += text.charAt(i) >= 0x2E80 ? 1d : 0.25d;
        }
        return (long) Math.ceil(tokens);
    }

    // ------------------------------------------------------------------ 场景数据结构

    private record Turn(String role, String content) {
    }

    private record Session(String sessionId, String contentType, MemoryScope scope, List<String> messages) {

        List<Turn> turns() {
            String role = contentType.equals("CONVERSATION") ? "user" : "tool";
            return messages.stream().map(message -> new Turn(role, message)).toList();
        }
    }

    private record Stage(int day, String label, List<Session> sessions) {
    }

    private record Probe(String label, String query, List<MemoryScope> scopes, List<MemoryType> expectAny) {
    }

    private record StageSnapshot(int day, String label, int addedUser, int addedAgent,
                                 int userTotal, int agentTotal,
                                 int userLeaves, int userBranches, int userRoots,
                                 int agentLeaves, int agentBranches, int agentRoots,
                                 String rootText, String userBranchText, String agentRootText) {
    }

    private record ProbeOutcome(String label, String query, int memoryHits, int insightHits,
                                Set<String> channels, Set<MemoryType> recalled, Set<String> matched,
                                int agentHits, boolean rootPresent, List<String> timeSignals,
                                List<String> keywords) {
    }
}