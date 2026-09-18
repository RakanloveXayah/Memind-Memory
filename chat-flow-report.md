# 流式聊天全链路实测报告

> 目标：验证 Web 体验台（`http://localhost:8366`）的「流式聊天」不是一次性假流式，而是**检索记忆 → 组装上下文 → 真实模型流式输出 → 模型自主调用工具/skill → 每轮自动写回记忆**的完整闭环。
>
> 实测时间：2026-09-17 16:57（探针脚本）/ 17:00（浏览器）。
> 模型：对话 `qwen3.7-flash`（百炼 MaaS，思考模型）；向量 `qllama/bge-large-zh-v1.5:latest`（本地 Ollama，1024 维）。

---

## 1. 结论速览

| 验证项 | 结果 | 关键证据 |
| :--- | :--- | :--- |
| 接口是真正的 SSE 流 | 通过 | `HTTP 200 text/event-stream`，单轮 142 个事件 |
| 思考模型的思维链与正文分流 | 通过 | `reasoning` 与 `delta` 全程两类事件，永不合流 |
| 模型自主调用工具（function calling） | 通过 | `query_sales{"metric":"gmv","time_range":"30d"}`，非规则路由 |
| 模型自主调用 skill | 通过 | `gmv_report{"group_by":"channel"}`，`type=SKILL` |
| 一轮内并发多个工具 | 通过 | 浏览器实测一轮内 `calculator` + `get_current_time` 同时返回 |
| 工具结果回灌后收敛出结论 | 通过 | `done.rounds=2`（调用 → 回灌 → 出正文） |
| 每轮自动写回记忆 | 通过 | USER 23→24→28，AGENT 24→27→30 |
| 写回严格租户隔离 | 通过 | USER 记忆命名空间恒为 `acme:u-100`，AGENT 恒为 `acme` |
| 离线回归 | 通过 | `ChatStreamParserTest` 5 例 + `ChatFlowTest` 1 例 = **6/6 通过** |
| 页面渲染 | 通过 | 气泡 / 工具卡片 / 折叠思维链 / 写回标签全部正确渲染 |

---

## 2. 场景与脚本（双轨）

### 2.1 接口契约

```
POST /open/v1/chat/stream          # produces = text/event-stream，SseEmitter 超时 180s
Header: X-Tenant-Id / X-User-Id / X-Memory-Scope
Body:   { message, sessionId?, scopes?, topK?, tokenBudget?, enableTools?, writeback? }
```

事件顺序（固定）：

```
meta（首帧） → reasoning* / delta* / tool_call / tool_result → done → writeback
                                                    ↳ 异常时插 error
```

`done` 刻意早于 `writeback`：回答先结束、写回随后补上，用户不必为一次抽取模型调用买单。

### 2.2 第一轨：JUnit 离线回归

| 用例 | 断言重点 | 结果 |
| :--- | :--- | :--- |
| `ChatStreamParserTest`（5 例） | 首帧空 delta 过滤、`reasoning_content`/`content` 分流、tool_calls 按 `index` 分片累加、多工具按序交出、错误帧抛可读异常 | 全绿 |
| `ChatFlowTest`（1 例） | SSE 契约、事件顺序、`degraded=true` 如实上报、正文非空、写回落库、命名空间隔离 | 全绿 |

```bash
mvn -o test -Dtest=ChatStreamParserTest,ChatFlowTest -DfailIfNoSpecifiedTests=false
# Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

离线环境没有对话模型，走的是**降级分支**（规则应答生成正文）。这不是绕过验证：降级与真实模式共用同一套编排、事件与写回链路，只有"谁生成正文"不同。真实模型的工具调用由第二轨覆盖。

### 2.3 第二轨：真实模型实测脚本

```powershell
powershell -ExecutionPolicy Bypass -File scripts\chat-flow-probe.ps1
# 可选：-BaseUrl -Tenant -User -Session -OutDir
```

脚本用 `HttpClient` + `ResponseHeadersRead` 逐行消费 SSE（刻意不用会整体缓冲的 `Invoke-WebRequest`，否则测不出首字延迟），产出：

```
chat-flow-evidence/
├── before/  after-round1/  after-round2/     # 各阶段 health / memories-user / memories-agent / insight-user / insight-agent 快照
├── round1.sse.txt  round2.sse.txt            # 原始事件流（未加工的原始字节级证据）
├── timing.txt                                # 首字延迟与条数变化对照表
└── summary.json                              # 结构化汇总
```

两轮场景设计：

| 轮次 | 提问 | 覆盖目标 |
| :--- | :--- | :--- |
| 1 | 「我是林悦，做增长运营的，以后给我数据尽量用柱状图这种图表口径。最近30天的GMV是多少？」 | 同一句里既有**闲聊**（自我介绍）又有**必须调工具才能答的问题** |
| 2 | 「帮我出一份渠道维度的日报」 | **skill** 调用 + 复用第一轮/历史沉淀的记忆 |

---

## 3. 时间线：思维链 vs 正文

| 轮次 | meta 首帧 | 首个 reasoning | 首个 delta（正文首字） | done | reasoning | content | 工具 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 415 ms | 1 174 ms | **7 897 ms** | 8 904 ms | 666 字符 / 119 帧 | 86 字符 / 18 帧 | 1 |
| 2 | 328 ms | 707 ms | **4 138 ms** | 6 624 ms | 373 字符 / 59 帧 | 273 字符 / 56 帧 | 1 |
| 3（浏览器） | — | — | — | 约 55 s（含写回等待） | — | — | 2 |

读出来的三件事：

1. **`meta` 在 400ms 内到达**，而正文首字要等 4–8 秒。先发 `meta` 正是为了让界面立刻有反馈，否则用户会以为卡死。
2. **思维链比正文多得多**（第一轮 666 : 86，约 7.7 倍），且碎片极细（119 帧承担 666 字符，平均 5.6 字符/帧）。前端只能按帧 append，攒成整段再渲染会丢失流式体感。
3. **思维链永远不混入正文**：`ChatStreamParser` 按 `delta.reasoning_content` 与 `delta.content` 分字段归位，首帧两个字段都是空串时直接丢弃，因此正文里不会出现"用户是"这类思维碎片。

---

## 4. 原始事件流摘录

首帧 `meta`（真实原文，节选）：

```
event:meta
data:{"sessionId":"probe-0917-165744","provider":"chat=openai-compatible:https://llm-le499bxihbee1gji.cn-beijing.maas.aliyuncs.com/compatible-mode/v1#qwen3.7-flash, ...",
      "degraded":false,"strategy":"SIMPLE","memoryCount":15,"insightCount":18,"estimatedTokens":1207,"truncated":true,
      "tools":[query_sales,run_sql,gmv_report,cohort_analysis,search_memory,get_current_time,calculator,weekly_summary]}
```

工具往返（第一轮，行号取自 `round1.sse.txt`）：

```
[258] event:tool_call
      data:{"round":1,"id":"call_d62f467e5a9248029ad959be","name":"query_sales","type":"TOOL",
            "arguments":"{\"metric\": \"gmv\", \"time_range\": \"30d\"}"}
[261] event:tool_result
      data:{"round":1,"id":"call_d62f467e5a9248029ad959be","name":"query_sales","ok":true,"elapsedMs":0,
            "result":"{\"metric\":\"gmv\",\"range\":\"30d\",\"total\":1284300.5}"}
```

首个思维链帧与首个正文帧（注意两者是不同字段，且都极碎）：

```
data:{"text":"用户"}          # reasoning
data:{"text":"最近30"}        # delta
```

收尾：

```
[420] event:done
      data:{"rounds":2,"contentChars":86,"reasoningChars":666,"toolCalls":1,"elapsedMs":8904}
event:writeback
      data:{"userStatus":"SUCCESS","userItems":1,"agentStatus":"SUCCESS","agentItems":3,
            "message":"已写回 USER 1 条 / AGENT 3 条"}
```

第一轮事件序列统计（全部 142 个事件无一遗漏）：

```
metax1 → reasoningx119 → tool_callx1 → tool_resultx1 → deltax18 → donex1 → writebackx1
```

第二轮：`metax1 → reasoningx59 → tool_callx1 → tool_resultx1 → deltax56 → donex1 → writebackx1`。
**两轮都没有出现 `error` 事件。**

---

## 5. 工具调用与结果回灌

| 轮次 | 工具 | 类型 | 模型给出的参数 | 结果 | 耗时 | 回灌内容（前段） |
| :--- | :--- | :--- | :--- | :--- | ---: | :--- |
| 1 | `query_sales` | TOOL | `{"metric":"gmv","time_range":"30d"}` | ok | 0 ms | `{"metric":"gmv","range":"30d","total":1284300.5}` |
| 2 | `gmv_report` | **SKILL** | `{"group_by":"channel"}` | ok | 0 ms | 4 渠道 breakdown（总 128.4 万） |
| 3（浏览器） | `calculator` + `get_current_time` | TOOL | `1284300.5/4` / `{}` | ok | — | `321075.125` / ISO 时间 |

- 参数全部由模型产出，没有任何规则路由或关键词匹配参与选工具。
- 第一轮模型给出的正文：「最近30天的GMV为 **1,284,300.5**。已记录您偏好使用**柱状图**进行数据展示的诉求……」
- 回灌方式是标准协议：先追加 `role=assistant` 且带 `tool_calls` 的消息，再追加 `role=tool` 且带 `tool_call_id` 的消息。`done.rounds=2` 说明走完了一轮"调用→回灌→出结论"。
- 防御措施也在位：同一个 `(工具, 参数)` 第二次出现会被短路成 `{"error":"duplicate call ignored"}`，避免模型在同一坑里刷爆轮次；工具执行有 `memind.chat.tool-timeout-ms`（默认 3000ms）兜底，超时同样以错误 JSON 回灌而不会中断整条流。

---

## 6. 写回与洞察树

### 6.1 记忆条数变化

| 作用域 | before | after-round1 | after-round2 |
| :--- | ---: | ---: | ---: |
| USER（命名空间 `acme:u-100`） | 23 | 24（+1） | 28（+4） |
| AGENT（命名空间 `acme`） | 24 | 27（+3） | 30（+3） |

第一轮闲聊里那句「我是林悦，做增长运营的」被规则/模型抽取成 USER 侧画像与查询习惯；两轮的工具轨迹分别沉淀出 AGENT 侧经验，例如：

```
[TOOL_USAGE]     调用 query_sales 工具查询 GMV 时，需传入 metric=gmv 和 time_range=30d 参数。（0.9）
[SQL_TEMPLATE]   计算指定周期 GMV 的 SQL 模板为 select sum(order_amount) from sales_order
                 where created_at >= now() - interval '[N] days'。（0.9）
[DATA_SOURCE_TRAIT] sales_order 表使用 order_amount 表示交易金额，时间过滤依赖 created_at。（0.85）
```

这正是"下一轮 agent 更会干活"的燃料——见第 7 节。

### 6.2 洞察树

| 作用域 | 本轮快照 | LEAF | BRANCH | ROOT |
| :--- | :--- | ---: | ---: | ---: |
| USER | before / after-round2 完全一致 | 6 | 3 | 1 |
| AGENT | before / after-round2 完全一致 | 4 | 3 | 1 |

**本轮实测中洞察树没有增长**，原因是结构性的、不是 bug：

1. LEAF 需要**同一分组内至少 2 条事实**才成节点（`leaf-min-items: 2`），本轮新增的 4 条 USER 记忆分散在不同分组，没有凑够；
2. 写回触发的整合是异步的（`consolidateAsync`），快照在 `writeback` 事件后立刻采集，可能早于整合完成。

---

## 7. 第二轮"记忆补全"验证：一半成功

对比两轮实际注入的上下文（`meta.context`，各约 1 500 / 1 489 字符，两轮 `truncated=true`）：

**成功的一半（AGENT 经验复用）**：第二轮上下文里出现了第一轮及历史沉淀的工具经验——

```
- [tool_usage]    调用 gmv_report skill 生成渠道维度日报时必须指定 group_by=channel 参数。（0.90）
- [best_practice] 分析销售表现时优先使用 group_by=channel 参数，可快速获得各渠道贡献度与份额分布。（0.85）
- [data_source_trait] GMV 数据源支持按渠道分组查询，返回结果包含总 GMV 摘要及各渠道的绝对金额与占比字符串。（0.85）
```

模型据此**准确地**给出了 `{"group_by":"channel"}`，不需要额外提示。

**被削弱的一半（USER 偏好复用）**：第一轮上下文里明确带着两条柱状图偏好——

```
- [preference] 用户在查看业务数据时偏好使用柱状图等可视化图表进行展示。（0.85）
- [preference] 数据可视化偏好使用柱状图展示结果。（0.85）
```

而第二轮上下文里这两条都不在，模型最终交付的是 **Markdown 表格**而不是柱状图。原因不是模型不听话，而是**上下文预算截断**：`memind.context-token-budget=1200`，两轮都命中 `truncated=true`，压缩时优先保住 ROOT/BRANCH 洞察与高置信度记忆，"柱状图偏好"恰好被挤出了窗口。

结论：**"聊过的工具经验会被下一轮复用"已被实测证实；"聊过的呈现偏好会被下一轮复用"受限于 1200 token 的预算，需要调参才能稳定成立。**

---

## 8. 浏览器实测（页面级）

步骤：打开 `http://localhost:8366` → 顶部健康徽标由「连接中…」变为「就绪」→ 确认「流式聊天」为默认激活 tab → 输入「帮我算一下 1284300.5 除以 4 是多少？另外今天几号？」→ 发送。

观察到的渲染结果：

- 用户气泡（右对齐）、Agent 气泡（左对齐）各就位；Agent 气泡内依次是：检索召回 meta 信息、折叠的思维链、工具卡片、正文、写回标签。
- 工具卡片两张：`calculator`（参数 `1284300.5/4`，结果 `321075.125`）、`get_current_time`（参数 `{}`，返回 ISO 时间），均标记成功，无 `failed` 样式。
- 最终正文：「1284300.5 除以 4 的结果是 321075.125。今天是 2026年9月17日（星期四）。」
- 写回标签：「写回 USER 0 条（SUCCESS） 写回 AGENT 3 条（SUCCESS）」——该轮是纯闲聊+工具，用户侧确实没有可沉淀的事实，0 条是正确行为而非失败。
- 全流程无红色错误文案；状态徽标在约 55 秒后由「生成中…」回到「就绪」。

唯一异常：控制台有一条 `net::ERR_ABORTED http://localhost:8366/open/v1/chat/stream`。同时**服务端日志中没有任何 ClientAbort / AsyncRequestNotUsable / 异常记录**，且前端已完整收到包括 `writeback` 在内的全部事件（写回标签正常渲染），因此判定为**服务端异步请求正常收尾时 Chrome 的客户端噪声**，不影响功能，但会让开发者控制台不干净。第 12 节对此做了完整的定位与两层缓解。

---

## 9. 已知局限

1. **工具执行线程模型与计划有一处有意偏离。** 计划写的是"工具就在 SSE 工作线程里同步执行"，但同线程无法抢占超时，`memind.chat.tool-timeout-ms` 会变成死配置。实现改为：把租户上下文捕获出来，投到独立的 `memind-tool-*` 守护线程池，用 `Future.get(timeout)` 取得结果，超时则 `cancel(true)` 并以错误 JSON 回灌。代价是多一层线程切换，收益是超时配置真实生效、且工具卡死不会拖垮整条 SSE。
2. **上下文截断会吃掉个性化偏好。** 见第 7 节。`context-token-budget` 提到 2000 或对"偏好类"记忆加保底配额，才能让"柱状图"这类要求稳定生效。
3. **写回是同步抽取。** `extractNow` 每轮都要额外走一次模型调用（本轮实测 `done` 与 `writeback` 之间约 40 秒），占用 `memindExecutor`。高并发下线程池会成为瓶颈，后续应改为队列化异步抽取。
4. **`api-key` 仍是明文默认值。** `application.yml` 里 `OPENAI_API_KEY` 带了一个可用的默认值，正式使用前必须换成环境变量注入。
5. **演示数据是 Mock。** 渠道 GMV、漏斗、留存全部来自 `MockData`，不是真实数仓；工具返回的 `elapsedMs=0` 也是这个原因。
6. **洞察树本轮未增长**，原因见 6.2，属于"新记忆还没凑够分组"的正常现象，但本轮无法作为认知进化的证据。
7. **控制台 `ERR_ABORTED` 噪声**（见第 12 节，已定位成因并做了服务端与前端两层缓解）。
8. **实测库里有历史残留。** 修复脚本 bug 期间跑过 3 次探针，`acme` / `acme:u-100` 下的记忆是累积结果；做严格 A/B 对比时应换新租户。

---

## 10. 复现步骤

```bash
# 1. 启动应用（新代码含 /open/v1/chat/stream）
mvn -o spring-boot:run                     # → http://localhost:8366

# 2. 离线回归
mvn -o test -Dtest=ChatStreamParserTest,ChatFlowTest -DfailIfNoSpecifiedTests=false

# 3. 真实模型实测（产出 chat-flow-evidence/）
powershell -ExecutionPolicy Bypass -File scripts\chat-flow-probe.ps1

# 4. 页面实测：浏览器打开 http://localhost:8366，默认即为「流式聊天」tab

# 5. 联网工具实测（产出 chat-flow-evidence/web-tools/）
powershell -ExecutionPolicy Bypass -File scripts\web-tools-probe.ps1
```

证据与结论的对应关系：第 3 节 ← `timing.txt` / `summary.json`；第 4 节 ← `round1.sse.txt` / `round2.sse.txt`；第 5 节 ← `summary.json` 的 `calls`；第 6 节 ← `before/`、`after-round1/`、`after-round2/` 与 `/admin/v1/insight`；第 7 节 ← 两轮 `meta.context`；第 11 节 ← `web-tools/1.sse.txt`、`web-tools/2.sse.txt`、`web-tools/summary.json`。

---

## 11. 联网工具：get_weather / search_web

### 11.1 为什么加、加完之后的能力面

原来的 8 个能力全部落在"模型本来就会，只是需要精准数据"的范围内（业务指标、SQL、报表、留存、记忆检索、时间、算术、周报）。它们有一个共同的边界：**回答不了训练数据里没有的事**。「郑州今天天气怎么样」正是这类问题——模型只能凭概率编一个温度出来，而这恰恰是最容易被用户一眼识破的幻觉。

新增两个 `TOOL`（都是 `ToolDefinition.ToolType.TOOL`，非 skill），能力面从 8 个变成 10 个、其中 8 TOOL / 2 SKILL：

| 名称 | 类型 | 数据源 | 关键实现 |
| --- | --- | --- | --- |
| `get_weather` | TOOL | Open-Meteo（geocoding + forecast） | 城市名 → 经纬度 → 当前天气 + 未来 3 天；WMO 天气码映射中文；额外返回一句可直接抄进正文的中文 `summary` |
| `search_web` | TOOL | 360 搜索（`www.so.com`） | SERP HTML 解析出标题 / 链接 / 摘要，最多 5 条 |

两个都不需要 API Key，这一点是有意为之：本模块是给别人在自己机器上跑的记忆演示，多一个 key 就多一道跑不起来的门槛。

### 11.2 实测结果（真实模型 qwen3.7-flash，两轮）

脚本 `scripts/web-tools-probe.ps1`，原始报文见 `chat-flow-evidence/web-tools/`。

**第 1 轮 · 「郑州今天天气怎么样？」**（HTTP 200，整轮 10940ms，`meta` 2501ms）

模型自主调用 `get_weather`，参数 `{"city": "郑州"}`——把口语里的城市名正确归一成了 `city` 参数：

```json
{"city":"郑州","admin":"河南","country":"中国","timezone":"Asia/Shanghai","source":"Open-Meteo",
 "current":{"time":"2026-09-17T17:30","temperature":24.9,"feelsLike":24.5,"humidity":49.0,
            "precipitation":0.0,"windSpeed":10.4,"condition":"晴间多云"},
 "daily":[{"date":"2026-09-17","condition":"阴","minTemperature":20.1,"maxTemperature":26.9,
           "precipitationProbability":14.0}, …]}
```

工具耗时 2708ms（geocoding 约 0.9s + forecast 约 1.8s），正文 74 字：

> 郑州当前晴间多云，气温 24.9℃，体感 24.5℃，湿度 49%，风速 10.4 km/h；今天阴，气温 20.1~26.9℃，降水概率 14%。

数字与工具返回逐项对得上，没有二次加工失真——这正是把 `summary` 直接放进工具结果的价值。

**第 2 轮 · 「帮我搜一下郑州最近有什么新闻」**（HTTP 200，整轮 17141ms，`meta` 388ms）

模型调用 `search_web`，参数 `{"query": "郑州最近有什么新闻"}`，耗时 925ms，返回 5 条：

```json
{"query":"郑州最近有什么新闻","source":"www.so.com","count":5,
 "results":[{"title":"郑州新闻 - 郑州市人民政府","url":"…","snippet":"…"}, …]}
```

正文 297 字，把检索结果归纳成五类（教育招生 / 民生服务 / 经济物价 / 天气出行 / 政务发展），条条能对上返回的条目，并且**没有把原始链接堆进正文**（工具描述里明确要求了这一点）。

### 11.3 搜索源的取舍：实测出来的，不是偏好

选 360 之前，逐一实测了本机网络下各家的可达性与结果质量（同一个查询「郑州天气」）：

| 源 | 结果 | 判定 |
| --- | --- | --- |
| 360 搜索 `www.so.com` | 429KB，标题「郑州天气_360搜索」，`res-list` × 9，结果全部与郑州天气相关 | **采用** |
| cn.bing.com | 200，96KB，`b_algo` × 10，**但中文查询返回的是「健身动作」「阿萨姆神庙」「Word 对齐技巧」等完全无关条目** | 弃用 |
| DuckDuckGo（html / lite / instant API） | 连接超时或 0 字节 | 弃用 |
| 百度 `www.baidu.com/s`、`m.baidu.com` | 479 / 508 字节的壳页 | 弃用 |
| 搜狗 | 2.5KB，只有 JS 配置，无结果 | 弃用 |
| mojeek / searx | 325 字节 / 0 字节 | 弃用 |
| Wikipedia API | 超时 | 弃用 |

Bing 那一条值得特别记一笔：它**不是连不上，而是响应一个看起来完全正常、内容却与查询无关的 SERP**（RSS 形式同样如此，`<title>必应：郑州新闻</title>` 而条目是法语网站）。这类"成功但错误"的失败模式比超时危险得多——静默地把幻觉喂给模型。因此代码里最终没有保留任何 Bing 分支，而不是"加个 fallback 保险"。

### 11.4 配套改动

- **`memind.chat.tool-timeout-ms`：3000 → 15000。** 老默认值是按"纯内存 Mock 工具"给的（Mock 工具实测 `elapsedMs=0`）；联网工具单次 geocoding + forecast 就要约 2.7~5.4s，3 秒上限会让它们**必然超时**，表现为模型收到一条 `工具执行超时` 后改口"我查不到"。`application.yml` 与测试用的 `application.yml` 同步调整。
- **`ToolRegistry.error()` 由 `private static` 改为包级可见**，`WebTools` 复用同一套 JSON 转义，避免两处各写一份。
- **`ToolRegistry` 类注释同步更新**：10 个能力、8 TOOL / 2 SKILL，并写明哪两个会真的访问互联网。
- 新增可复用探针 `scripts/web-tools-probe.ps1`（UTF-8 with BOM，PS 5.1 中文脚本的硬要求），产出物固定落在 `chat-flow-evidence/web-tools/`。

### 11.5 这一节的局限

1. **SERP 解析天然脆弱。** 360 的 `res-list` / `res-title` / `res-desc` 是私有 class，站点改版即失效。这是"不引入 Key"的代价，换来的是零配置可跑。生产环境应换成带 SLA 的搜索 API。
2. **部分结果链接是 `so.com/link?m=…` 跳转串**，参数加密无法静态还原，已如实原样返回（`normalizeUrl`），没有为每条结果多发一次解析请求。
3. **天气依赖 Open-Meteo 的可用性。** 两个端点各 8s 请求超时、5s 连接超时，叠加后最坏情况仍在 15s 工具上限内；失败时返回 `{"error":"调用天气服务失败：…"}` 交给模型解释，不会中断整条 SSE。

---

## 12. `net::ERR_ABORTED` 的定位与两层缓解

### 12.1 现象

用户报的现场是控制台里这条错误，堆栈落在 `index.html:497` 的 `fetch("/open/v1/chat/stream", …)` 上，外层帧是 `node:electron/js2c/sandbox_bundle`——即 Trae 内置浏览器（Electron webview）的网络层，不是页面自己抛的异常。

用自动化浏览器复现，控制台一共两条 error：

```
net::ERR_ABORTED http://localhost:8366/open/v1/chat/stream
net::ERR_ABORTED http://localhost:8366/
```

网络面板里 `/open/v1/chat/stream` 被记为 `failed`。但同一时刻页面渲染是完整的：`get_weather` 卡片「成功 · 2413ms」、正文 94 字、标签「真实模型 / 召回 15 记忆 / 19 洞察 / 2 轮 / 正文 94 字 / 思考 142 字 / 工具 1 次 / 6264ms」，**没有任何红色错误文案**。

### 12.2 为什么判定是宿主噪声，而不是服务端缺陷

四条互相独立的判据：

1. **同一个网络面板里，顶层文档 `/` 也被标成 `ERR_ABORTED`。** 顶层文档只有在导航被取代、页面被销毁时才会出现这个状态，一次正常的聊天不可能让文档请求中止。这条直接把"网络面板里的 failed"和"我们的流有没有坏"解耦开了：它是宿主在会话收尾时，对仍在跟踪中的请求统一打的一笔。
2. **服务端日志干净。** 整轮没有任何 `ClientAbortException` / `AsyncRequestNotUsableException` / 栈，唯一的 ERROR 是无关的 `NoResourceFoundException: No static resource @vite/client`（某个前端注入请求 404）。
3. **写回在服务端照常完成**：日志 `写回完成 namespace=acme:u-100 USER=0条 AGENT=4条`。客户端断连不会让服务端丢记忆，这正是 `SseChatSink` 的既定设计（`send` 失败只标记通道关闭、不中断编排）。
4. **换一个非宿主客户端，同一份代码一切正常**：探针从头读到 EOF，两轮都收到了 `writeback` 事件，且 57s / 82s 的静默期被心跳帧填满（见 12.4 的统计）。

### 12.3 成因：`done` 之后那段 1~1.5 分钟的零字节空档

`done` 之后服务端还要跑两轮抽取（USER 侧 + AGENT 侧各一次模型调用）才会发 `writeback`，这段空档实测 **57s（第 1 轮）/ 82s（第 2 轮）**。期间这条 TCP 连接上一个字节都没有，是"长连接被当成闲置连接中止"最典型的窗口；而宿主只要认为这笔请求还挂着，收尾时就会把它记成中止。

对照实验也印证了这一点：**总耗时短的轮次不报错**。浏览器那轮 `done` 在 6264ms 就发完了，之后连接还要挂 30 多秒等写回，报错就出现在这个窗口里。

### 12.4 两层缓解（已落地）

**第一层 · 服务端心跳。** `SseChatSink` 每 10 秒补一个 SSE 注释帧 `:keep-alive`：静态守护调度器（线程名 `memind-sse-heartbeat`，所有连接共用一条线程）在构造时按固定频率起任务，`complete()` / `onCompletion` 时取消。注释帧不产生事件，前端 `parseSseBlock` 只认 `event:` / `data:` 前缀、探针也只认这两者，因此**不需要任何前端配合**就成立了。

实测证据（`chat-flow-evidence/web-tools/`）：

| 文件 | 整轮耗时 | `:keep-alive` 帧数 | `event:writeback` |
| --- | --- | --- | --- |
| `1.sse.txt` | 70031ms | 6 | 1 |
| `2.sse.txt` | 94161ms | 9 | 1 |

帧数与"整轮时长 ÷ 10s"吻合，且两轮都收到了 `writeback`——说明长静默期全程有字节流动，连接没有再被当作闲置连接掐断。

**第二层 · 前端不再把它渲染成失败。** `sendChat` 的 `catch` 分支改为按"是否已经收到 `done`"分流：

- 已收到 `done`：正文与工具结果都已完整拿到，此时的中止只发生在收尾阶段，显示中性提示「连接在收尾阶段被中止（AbortError），本轮结果不受影响」，并顺手 `loadMemories()` / `loadInsight()` 刷新（写回在服务端会继续跑完）。
- 未收到 `done`：仍然显示原来的红色「连接失败：…」。

这样即使宿主继续打这笔标记，用户也不会再看到"这轮白聊了"的误导性文案。

### 12.5 仍然存在的部分，以及一个结构性选项

只要 `writeback` 走同一条 SSE，连接就必须在 `done` 之后继续开着 1~1.5 分钟，宿主仍有理由把这笔"还挂着"的请求记成中止。**要让控制台绝对干净，只有结构性改法**：`done` 之后立即 `complete()`，写回降级为后台任务，页面改为轮询 `/admin/v1/memories` 取结果。

本次**没有**做这个改动，理由是这样会删掉已被验证的 `writeback` 事件与页面上的写回标签（用户可见的功能收益为负），而这条报错本身不影响任何行为——它是一条网络日志记录，不是异常。这是一个取舍，不是遗漏。