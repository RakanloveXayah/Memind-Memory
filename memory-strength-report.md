# Memind 记忆强度实测报告

> 由 `MemoryStrengthScenarioTest` 自动生成，全部数据来自真实 REST 调用与 PostgreSQL 查询。

| 项 | 值 |
|---|---|
| 租户 / 用户 | `acme` / `u-100` |
| 模拟跨度 | 30 天 · 5 个阶段 · 10 次会话 |
| 抽取策略 | 模型未配置时自动使用规则引擎（`rule-based`） |
| 存储 | PostgreSQL 17 + pgvector（HNSW，余弦距离） |

## 一、场景设定

三类内容混合写入，模拟真实 Agent 运行轨迹：

| 内容类型 | 落点作用域 | 场景里的样子 |
|---|---|---|
| 闲聊 | USER | 「我是林悦，负责增长运营」「沟通的时候直接说结论就行」 |
| 工具调用 | AGENT | `query_sales` 的参数、`sales_order` 表的字段特性 |
| skill 调用与执行轨迹 | AGENT | `gmv_report` skill、SQL 模板、超时失败教训 |

## 二、认知进化时间线

| 天 | 阶段 | 新增记忆(USER/AGENT) | 累计(USER/AGENT) | 用户侧 LEAF→BRANCH→ROOT | Agent 侧 LEAF→BRANCH→ROOT |
|---|---|---|---|---|---|
| D1 | 初次接入：闲聊中暴露身份与第一个查询 | 5 / 2 | 5 / 2 | 0 → 0 → 0 | 0 → 0 → 0 |
| D5 | 习惯成形：拆分维度与查询路径被记住 | 4 / 2 | 9 / 4 | 1 → 0 → 0 | 1 → 0 → 0 |
| D12 | skill 调用与失败教训进入 Agent 经验 | 2 / 3 | 11 / 7 | 2 → 1 → 1 | 2 → 1 → 1 |
| D20 | 稳定期：含一次重复陈述，用于验证去重 | 2 / 1 | 13 / 8 | 4 → 1 → 1 | 3 → 1 → 1 |
| D30 | 全新会话：只说一句话，检验记忆能否补全意图 | 0 / 1 | 13 / 9 | 4 → 1 → 1 | 3 → 1 → 1 |

## 三、Root 理解的演进

Root 是引擎对用户的最高层理解。判断它是否「活着」，关键看下层内容变化后它有没有跟着重新提炼：

**D1**（Root 0 个）：_尚未形成_

**D5**（Root 0 个）：_尚未形成_

**D12**（Root 1 个） · 下层 Branch 已变化 → **Root 已重新提炼**：跨维度理解：跨组模式：该分组（metric_preference）的稳定特征：销售额和订单量是我最关心的两个指标；我主要看GMV和转化率；该分组（time_preference）的稳定特征：最近30天是我的常用时间窗口；上周的报表也经常用到；帮我查一下最近30天的GMV

**D20**（Root 1 个） · 下层 Branch 已变化 → **Root 已重新提炼**：跨维度理解：跨组模式：该分组（metric_preference）的稳定特征：销售额和订单量是我最关心的两个指标；我主要看GMV和转化率；该分组（dimension_preference）的稳定特征：再按地区分组看一下；所有报表都要按渠道拆分

**D30**（Root 1 个） · 下层 Branch 未变化 → 沿用上阶段理解：跨维度理解：跨组模式：该分组（metric_preference）的稳定特征：销售额和订单量是我最关心的两个指标；我主要看GMV和转化率；该分组（dimension_preference）的稳定特征：再按地区分组看一下；所有报表都要按渠道拆分

## 四、记忆强度探针

每条探针都刻意省略上下文，检验引擎能否自行补全：

| 探针 | 提问 | 召回记忆 | 召回洞察 | 命中通道 | 命中的期望类型 | Root |
|---|---|---|---|---|---|---|
| 第30天模糊提问 | `帮我看看数据` | 15 | 11 | vector+bm25+insight | TIME_PREFERENCE, METRIC_PREFERENCE, DIMENSION_PREFERENCE | ✓ |
| 复述式追问 | `就那个老样子` | 15 | 11 | vector+insight | PREFERENCE | ✓ |
| 只在 AGENT 空间找经验 | `GMV 到底该查哪张表` | 9 | 5 | vector+bm25+insight | DATA_SOURCE_TRAIT, SQL_TEMPLATE, TOOL_USAGE | ✓ |
| 复用上次的报表做法 | `上次那个渠道报表是怎么做的` | 9 | 5 | vector+bm25+insight | TOOL_USAGE | ✓ |
| 规避已知错误 | `转化率统计要注意什么` | 9 | 5 | vector+bm25+insight | BEST_PRACTICE, FAILURE_LESSON | ✓ |

### 关键探针：第 30 天的「帮我看看数据」

这句话本身不含任何指标、时间、维度信息，用户在 D30 也**没有写入任何新记忆**（两轮闲聊都未命中抽取规则，属于典型寒暄）。引擎却召回了 15 条记忆，覆盖类型：DIMENSION_PREFERENCE, TIME_PREFERENCE, METRIC_PREFERENCE, TOOL_USAGE, PREFERENCE, DATA_SOURCE_TRAIT, SQL_TEMPLATE。

识别出的查询信号：时间 ``，关键词 `帮我, 我看, 看看, 看数, 数据`。

> 需要如实说明的噪声：未配置嵌入模型时向量由本地特征哈希生成，不相关文本也可能拿到非零余弦，因此召回条数偏多、并混入跨域类型。这是降级路径的已知局限，配置真实 embedding 后会被显著收敛。

## 五、注入 Agent 的上下文

`POST /open/v1/memory/compile_context` 的原始输出（1164 tokens，15 条记忆 + 10 条洞察，truncated=true）：

> `truncated=true` 表示已达 token 预算上限，优先级最低的条目被丢弃（Insight Tree 洞察优先于原始记忆）。

```text
## 对该用户的理解
- 跨维度理解：跨组模式：该分组（metric_preference）的稳定特征：销售额和订单量是我最关心的两个指标；我主要看GMV和转化率；该分组（dimension_preference）的稳定特征：再按地区分组看一下；所有报表都要按渠道拆分
- 跨组模式：该分组（metric_preference）的稳定特征：销售额和订单量是我最关心的两个指标；我主要看GMV和转化率；该分组（dimension_preference）的稳定特征：再按地区分组看一下；所有报表都要按渠道拆分
- 该分组（metric_preference）的稳定特征：销售额和订单量是我最关心的两个指标；我主要看GMV和转化率
- 该分组（dimension_preference）的稳定特征：再按地区分组看一下；所有报表都要按渠道拆分
- 该分组（time_preference）的稳定特征：最近30天是我的常用时间窗口；上周的报表也经常用到；帮我查一下最近30天的GMV
- 该分组（preference）的稳定特征：我更喜欢用折线图看趋势；我偏好柱状图展示结果

## 该用户的偏好与习惯
- [time_preference] 帮我查一下最近30天的GMV（置信度 0.63）
- [metric_preference] 我主要看GMV和转化率（置信度 0.62）
- [metric_preference] 销售额和订单量是我最关心的两个指标（置信度 0.68）
- [dimension_preference] 再按地区分组看一下（置信度 0.62）
- [dimension_preference] 所有报表都要按渠道拆分（置信度 0.62）
- [preference] 我更喜欢用折线图看趋势（置信度 0.57）
- [preference] 我偏好柱状图展示结果（置信度 0.57）
- [time_preference] 最近30天是我的常用时间窗口（置信度 0.63）

## 可复用的 Agent 经验
- 跨维度理解：跨组模式：该分组（sql_template）的稳定特征：执行 SQL: select channel, sum(order_amount) from sales_order group by channel；执行 SQL: select sum(order_amount) from sales_order where created_at >= now() - interval '30 days'；该分组（data_source_trait）的稳定特征：查询GMV时优先使用 sales_order 表的 order_amount 字段；gmv_daily 汇总表在每日22点后存在延迟，实时性较弱
- 该分组（sql_template）的稳定特征：执行 SQL: select channel, sum(order_amount) from sales_order group by channel；执行 SQL: select sum(order_amount) from sales_order where created_at >= now() - interval '30 days'
- 跨组模式：该分组（sql_template）的稳定特征：执行 SQL: select channel, sum(order_amount) from sales_order group by channel；执行 SQL: select sum(order_amount) from sales_order where created_at >= now() - interval '30 days'；该分组（data_source_trait）的稳定特征：查询GMV时优先使用 sales_order 表的 order_amount 字段；gmv_daily 汇总表在每日22点后存在延迟，实时性较弱
- 该分组（tool_usage）的稳定特征：调用 gmv_report skill 直接出图，参数 metric=gmv；调用 gmv_report skill 生成渠道维度的日报，参数 group_by=channel；调用 query_sales 工具查询GMV，参数 metric=gmv, time_range=30d
- [tool_usage] 调用 gmv_report skill 生成渠道维度的日报，参数 group_by=channel（置信度 0.70）
- [sql_template] 执行 SQL: select channel, sum(order_amount) from sales_order group by channel（置信度 0.75）
- [sql_template] 执行 SQL: select sum(order_amount) from sales_order where created_at >= now() - interval '30 days'（置信度 0.75）
- [tool_usage] 调用 gmv_report skill 直接出图，参数 metric=gmv（置信度 0.68）
- [tool_usage] 调用 query_sales 工具查询GMV，参数 metric=gmv, time_range=30d（置信度 0.75）
- [data_source_trait] 查询GMV时优先使用 sales_order 表的 order_amount 字段（置信度 0.68）
- [data_source_trait] gmv_daily 汇总表在每日22点后存在延迟，实时性较弱（置信度 0.75）
```

## 六、隔离与去重

| 校验项 | 结果 | 结论 |
|---|---|---|
| 同租户不同用户的 USER 记忆 | 0 条 | ✓ 物理隔离 |
| AGENT 记忆租户内共享 | 9 条 | ✓ 同租户其他用户可用 |
| 跨租户 `globex` 检索 | 记忆 0 条 / 洞察 0 条 | ✓ 完全不可见 |
| 重复陈述同一事实 | 新增 0 条 | ✓ 语义+指纹双重去重生效 |

## 七、记忆强度小结

- **认知确实在进化**：用户侧 Insight Tree 从 D1 的 0 个 Leaf 成长为 D30 的 4 Leaf / 1 Branch / 1 Root；Agent 侧独立形成 3 → 1 → 1 的三层结构。
- **模糊提问可被补全**：D30 的「帮我看看数据」在零新增记忆的前提下召回 15 条历史记忆，命中 3/3 类期望偏好。
- **经验可复用**：AGENT 空间的 SQL 模板、表特性、失败教训在后续会话中被稳定召回。
- **双作用域与多租户隔离成立**：USER 按用户物理隔离，AGENT 租户内共享，跨租户完全不可见。
- **上下文成本：无模型时是「放大」而非压缩**：30 天全部对话 + 探针原文合计 380 tokens，注入 Agent 的上下文却是 1164 tokens（约 306.3%），且 truncated=true。
  这是**规则兜底的固有产物，不是算法缺陷**：Leaf 直取成员事实原文、Branch 串联两个 Leaf、Root 再嵌套 Branch，同一条事实在三层被反复引用，越聚合越膨胀。配置真实模型后各层输出的是概括句而非原文拼接，该比值会转为压缩。

> 说明：本次运行未配置模型 API Key，抽取走规则引擎、向量走本地特征哈希。
> 配置 `OPENAI_API_KEY`（对话）并打开 `memind.llm.embedding`（如内网 `bge-large-zh-v1.5`或通义 `text-embedding-v3`，同时设 `MEMIND_EMBEDDING_DIMENSION=1024`）后重跑本用例，语义召回质量会显著高于本地哈希。
