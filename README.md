# Memind

给对话系统加一层**长期记忆**的企业级记忆引擎：把对话沉淀成记忆，把记忆提炼成洞察，再把最相关的那一小撮注入 System Prompt。

- **写路径**：对话/文档 → 切块 → 抽取 → 去重 → 落库 → 提炼洞察
- **读路径**：问题 → 三通道召回 → RRF 融合 → 相关性闸门 → 预算裁剪 → 可直接注入的文本

技术栈：Java 17 · Spring Boot 3.2.5 · PostgreSQL 17 + pgvector · OpenAI 兼容协议（对话与向量可指向不同供应商）。

---

## 一、核心设计

### 1. 双作用域 + 命名空间

| 作用域 | 命名空间 | 内容 |
|---|---|---|
| `USER` | `{tenant}:{user}` | 用户偏好、业务背景、查询习惯 |
| `AGENT` | `{tenant}` | SQL 模板、工具用法、失败教训、数据源特性 |

`USER` 记忆人人独立，`AGENT` 记忆租户内共享。所有存储方法都强制带 `namespace + scope` 参数——**从签名上就拼不出跨租户查询**；数据库层再叠一层 `FORCE ROW LEVEL SECURITY`，租户上下文没注入时策略直接返回 0 行，即失败关闭而非失败打开。

### 2. Insight Tree：三级认知进化

| 层级 | 来源 | 含义 |
|---|---|---|
| `LEAF` | 同一语义组内 ≥ 2 条记忆 | 对某类事实的归纳 |
| `BRANCH` | 跨语义组的模式 | 业务层面的规律 |
| `ROOT` | 全局 | 对该对象的整体理解 |

写入后异步触发整合（`consolidation.immediate: true`），另有每日凌晨的批量整合。

### 3. 混合检索：三通道 + RRF + 相关性闸门

```
vector（pgvector 余弦，top 20）┐
bm25  （关键词，top 20）       ├─▶ RRF 融合(k=60) ─▶ 时间衰减 ─▶ top 15 ─▶ 上下文组装
insight（洞察节点，top 8）     ┘        ▲
                                相关性闸门（原始余弦 ≥ 0.42）
```

RRF 只用**名次**（`Σ w/(k+rank)`），绕开了三路召回分数量纲不可比的问题；代价是丢掉了"到底有多像"——一个词都没对上时各通道 top-1 照样能融合出好看的分数。因此必须补一道原始余弦闸门，否则无关记忆会稳定地占满上下文。

### 4. 上下文预算

默认 1200 token。优先级：洞察 > 事实记忆，但**洞察封顶 45%**（`insight-budget-share`），超出的洞察先让位给具体事实，事实放完还有剩余再补回来——避免"正确的废话"把具体事实全挤出去。`memoryCount` / `insightCount` 上报的是**真正进了上下文**的条数，不是候选数。

---

## 二、包结构

```
com.trae.memind
├── web          接入层：HTTP / SSE，含请求响应契约
├── chat         对话编排：Agent 工具循环 + 记忆写回
├── retrieval    读路径：召回、融合、上下文组装
├── pipeline     写路径：切块、抽取、去重
├── insight      认知进化：Insight Tree 提炼
├── store        持久化：PostgreSQL + pgvector + 内存 BM25 索引
├── llm          模型访问：对话、向量、流式解析
├── tool         工具与 skill 注册、调用与超时控制
├── tenant       租户上下文与隔离兜底
├── domain       领域模型（纯数据，无框架依赖）
├── config       配置绑定与装配
└── util         向量、分词、哈希、JSON 提取
```

---

## 三、快速开始

### 1. 环境要求

- JDK 17、Maven
- PostgreSQL 17 + [pgvector](https://github.com/pgvector/pgvector)
- 一个 OpenAI 兼容的对话模型；向量模型可选（但生产建议用真实的 embedding 服务）

### 2. 建库（一次性，需超级用户）

```bash
psql -U postgres -h 127.0.0.1 -p 5432 -d postgres \
     -v password='memind' -f src/main/resources/db/setup.sql
```

脚本可重复执行，只创建独立的角色 `memind` 与两个库（`memind` / `memind_test`），不触碰实例上已有数据。表结构、向量列维度、近邻索引与 RLS 策略由应用启动时执行 `schema.sql` + `PgVectorInitializer` 完成。

### 3. 配置模型

```bash
# 对话能力（示例：通义千问）
export OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export OPENAI_API_KEY=sk-xxx
export OPENAI_CHAT_MODEL=qwen-plus

# 向量能力（与对话能力解耦，可指向内网推理服务）
export MEMORY_EMBED_MODE=remote
export EMBED_BASE_URL=http://127.0.0.1:21434/v1
export EMBED_API_KEY=ollama
export EMBED_MODEL=qllama/bge-large-zh-v1.5:latest
```

> ⚠️ 密钥只从环境变量读取（`OPENAI_API_KEY` / `EMBED_API_KEY`），仓库里不落任何真实密钥。

### 4. 启动

```bash
mvn spring-boot:run          # http://localhost:8366
curl http://localhost:8366/open/v1/health
```

打开 `http://localhost:8366/` 是内置体验台，四个面板：写入记忆、记忆清单、Insight Tree、检索召回 / 上下文注入。

### 5. 跑测试

```bash
mvn test
```

全部离线可跑（不需要数据库和模型），测试配置把相关性闸门关成 `-1.0`——本地特征哈希向量没有语义，余弦不可解释。

---

## 四、接口

所有接口通过请求头传递租户身份：

| 请求头 | 说明 |
|---|---|
| `X-Tenant-Id` | 租户 ID，必填 |
| `X-User-Id` | 用户 ID，决定 `USER` 命名空间 |
| `X-Memory-Scope` | 缺省作用域：`USER` / `AGENT` |

### 记忆写入

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/open/v1/memory/extract` | 异步写入，HTTP 成功仅代表任务已派发 |
| POST | `/open/v1/memory/sync/extract` | 同步写入，仅 `status=SUCCESS` 代表已落库 |

### 检索与上下文

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/open/v1/memory/retrieve` | 返回融合排序后的记忆与洞察，含命中通道，便于调参 |
| POST | `/open/v1/memory/compile_context` | 返回可直接注入 System Prompt 的文本 |

### 对话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/open/v1/chat/stream` | SSE 流式对话，自动写回记忆 |

事件序列：`meta → reasoning* → (delta | tool_call → tool_result)* → done → writeback`，异常走 `error`。`reasoning` 与 `delta` 永不合流。SSE 带 10 秒注释帧心跳，防止长耗时工具调用期间被代理掐断。

### 运维

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/open/v1/health` | 健康检查，含模型可用性 |
| POST | `/admin/v1/consolidate` | 手动触发整合，不用等定时任务 |
| GET | `/admin/v1/insight` | 按 Root → Branch → Leaf 返回认知结构 |
| GET | `/admin/v1/memories?scope=` | 列出命名空间下的全部记忆 |
| DELETE | `/admin/v1/memories?scope=` | 清空命名空间（软删除记忆 + 丢弃洞察节点） |

---

## 五、关键配置

| 配置项 | 默认 | 说明 |
|---|---|---|
| `memind.embedding-dimension` | `1024` | 向量维度单一来源，同时驱动降级向量、pgvector 列与近邻索引；必须与 `llm.embedding.dimension` 一致，否则启动即失败 |
| `memind.llm.embedding.mode` | `remote` | `remote` 强制远程、失败即报错；`local` 离线；`auto` 有 key 走远程 |
| `memind.chat.max-rounds` | `4` | 单轮对话内最多的模型↔工具往返次数，到顶强制一次不带 tools 的调用收尾 |
| `memind.chat.tool-timeout-ms` | `15000` | 联网工具要跑真实 HTTP，默认值曾因 3s 而必然超时 |
| `memind.extraction.min-confidence` | `0.5` | 抽取结果的置信度下限 |
| `memind.retrieval.min-relevance` | `0.42` | **相关性闸门**，见下文标定 |
| `memind.retrieval.insight-budget-share` | `0.45` | 洞察最多占用的上下文预算比例 |
| `memind.retrieval.time-decay-half-life-days` | `30` | 时间衰减半衰期 |
| `memind.storage.vector-index` | `HNSW` | `HNSW` 在线增量写入更稳；`IVFFlat` 索引更小；`NONE` 退化顺序扫描 |

---

## 六、踩坑记录

这些是实测踩出来的，改动代码前建议先读：

**1. `min-relevance = 0.42` 是标定值，不是拍的**
拿 bge-large-zh-v1.5 实测：同一问题的相关条目 0.44~0.69，无关条目 0.12~0.39（中文句对本身有约 0.3 的底噪）。调高更干净但漏弱相关，调低噪声回潮。

**2. RRF 天生看不见相关性**
只用名次融合，必然出现"各通道 top-1 都不是但融合前排"的情况。改动融合逻辑时，闸门不能省。

**3. 洞察树曾经独占上下文**
早期实现里 ROOT/BRANCH 走 `mustInclude` 且优先级最高，结果抽象理解吃光预算，用户看到的是"每条都对但都没用"。

**4. 抽取入口必须挡疑问句**
模型把助手的反问「请问今天需要监控哪些核心指标？」当成用户偏好存了下来，置信度 0.58 正好在阈值之上——指望下游置信度过滤是挡不住的，必须在入口挡。

**5. 流式重试只在"尚未产出任何输出"时**
上游偶发 `Connection reset` 多数发生在建连/首帧阶段，那时重试零代价；一旦有任意一帧交付过还重试，同一段内容会渲染两遍。最多 2 次尝试，500ms 退避。

**6. 向量能力不要静默降级**
`mode: remote` 下缺 key 或缺模型直接启动失败。降级成本地特征哈希向量后，整套语义检索会失去意义，而且不会报错——这比启动失败危险得多。

**7. 思考模型的 SSE 超时**
思考模型先吐 `reasoning_content` 再吐 `content`，首字延迟很长，Spring MVC 默认 30s 会掐断连接，需把 `spring.mvc.async.request-timeout` 放宽到 180s。

---

## 七、测试与证据

| 测试类 | 覆盖 |
|---|---|
| `MemindEngineTest` | 引擎主链路（抽取 → 检索 → 编译） |
| `MemoryApiTest` | REST 契约与异常路径 |
| `ChatFlowTest` | 对话事件序列 |
| `ChatStreamParserTest` | 流式分片解析、工具调用增量拼接 |
| `OpenAiCompatibleLlmClientStreamRetryTest` | 流式重试的三种分支 |
| `HybridExtractionStrategyTest` | 抽取入口闸门 |
| `MemoryStrengthScenarioTest` | 30 天 5 阶段 10 次会话的记忆强度场景 |

真实模型联调的证据与报告：

- [chat-flow-report.md](chat-flow-report.md) — 流式对话全链路（含工具调用、skills、联网工具）
- [memory-strength-report.md](memory-strength-report.md) — 记忆强度场景测试
- [scripts/](scripts/) — SSE 探针脚本，可直接复跑
- [chat-flow-evidence/](chat-flow-evidence/) — 原始证据（SSE 日志、记忆快照、耗时）