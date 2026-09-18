-- Memind 存储层表结构（PostgreSQL 17 + pgvector）
--
-- 说明：
--   1. embedding 列声明为无维度 vector，真实维度由 PgVectorInitializer 在启动时按
--      memind.embedding-dimension 补齐（远程 embedding 模型维度只在调用后才可知，
--      因此维度必须收敛到单一配置项）。
--   2. 向量近邻索引（HNSW / IVFFlat）也由 PgVectorInitializer 创建，因为索引方法来自配置。
--   3. 四张业务表全部启用并强制 RLS：租户上下文由存储层在事务内通过
--      set_config('app.tenant_id', ?, true) 注入。FORCE 让表 owner 同样受策略约束，
--      防止应用账号绕过隔离。
--   4. USING 子句保留了跨租户读取的逃生口（app.cross_tenant='on'），仅供存储层
--      listNamespaces() 供定时批量整合枚举命名空间使用；WITH CHECK 保持严格，
--      任何写入路径都不能跨租户。

CREATE TABLE IF NOT EXISTS memind_memories (
    id           VARCHAR(64)      PRIMARY KEY,
    tenant_id    VARCHAR(64)      NOT NULL,
    user_id      VARCHAR(64)      NOT NULL,
    scope        VARCHAR(16)      NOT NULL,
    namespace    VARCHAR(160)     NOT NULL,
    mem_type     VARCHAR(48)      NOT NULL,
    content_text TEXT             NOT NULL,
    content_hash VARCHAR(64)      NOT NULL,
    confidence   DOUBLE PRECISION NOT NULL,
    embedding    vector,
    metadata     TEXT,
    source_id    VARCHAR(64),
    created_at   TIMESTAMPTZ      NOT NULL,
    updated_at   TIMESTAMPTZ      NOT NULL,
    active       BOOLEAN          NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_memories_ns ON memind_memories(namespace, scope, active);
CREATE INDEX IF NOT EXISTS idx_memories_tenant_user ON memind_memories(tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_memories_hash ON memind_memories(namespace, content_hash);

CREATE TABLE IF NOT EXISTS memind_insight_nodes (
    id            VARCHAR(64)      PRIMARY KEY,
    tenant_id     VARCHAR(64)      NOT NULL,
    user_id       VARCHAR(64)      NOT NULL,
    scope         VARCHAR(16)      NOT NULL,
    namespace     VARCHAR(160)     NOT NULL,
    insight_level VARCHAR(16)      NOT NULL,
    group_key     VARCHAR(64),
    content_text  TEXT             NOT NULL,
    parent_id     VARCHAR(64),
    member_ids    TEXT,
    embedding     vector,
    confidence    DOUBLE PRECISION NOT NULL,
    node_version  INTEGER          NOT NULL,
    created_at    TIMESTAMPTZ      NOT NULL,
    updated_at    TIMESTAMPTZ      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_insight_ns ON memind_insight_nodes(namespace, scope, insight_level);

CREATE TABLE IF NOT EXISTS memind_conversation_logs (
    id           VARCHAR(64)  PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    user_id      VARCHAR(64)  NOT NULL,
    scope        VARCHAR(16)  NOT NULL,
    namespace    VARCHAR(160) NOT NULL,
    session_id   VARCHAR(64),
    role         VARCHAR(16)  NOT NULL,
    content_text TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_logs_ns ON memind_conversation_logs(namespace, created_at);

CREATE TABLE IF NOT EXISTS memind_raw_contents (
    id           VARCHAR(64)  PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    user_id      VARCHAR(64)  NOT NULL,
    scope        VARCHAR(16)  NOT NULL,
    namespace    VARCHAR(160) NOT NULL,
    content_type VARCHAR(32)  NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_raw_ns ON memind_raw_contents(namespace, created_at);

-- ------------------------------------------------------------------ 行级安全（RLS）

ALTER TABLE memind_memories          ENABLE ROW LEVEL SECURITY;
ALTER TABLE memind_memories          FORCE ROW LEVEL SECURITY;
ALTER TABLE memind_insight_nodes     ENABLE ROW LEVEL SECURITY;
ALTER TABLE memind_insight_nodes     FORCE ROW LEVEL SECURITY;
ALTER TABLE memind_conversation_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE memind_conversation_logs FORCE ROW LEVEL SECURITY;
ALTER TABLE memind_raw_contents      ENABLE ROW LEVEL SECURITY;
ALTER TABLE memind_raw_contents      FORCE ROW LEVEL SECURITY;

-- PostgreSQL 不支持 CREATE POLICY IF NOT EXISTS，只能用 DROP + CREATE 保证脚本可重复执行
DROP POLICY IF EXISTS tenant_isolation ON memind_memories;
CREATE POLICY tenant_isolation ON memind_memories
    USING (tenant_id = current_setting('app.tenant_id', true)
           OR current_setting('app.cross_tenant', true) = 'on')
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

DROP POLICY IF EXISTS tenant_isolation ON memind_insight_nodes;
CREATE POLICY tenant_isolation ON memind_insight_nodes
    USING (tenant_id = current_setting('app.tenant_id', true)
           OR current_setting('app.cross_tenant', true) = 'on')
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

DROP POLICY IF EXISTS tenant_isolation ON memind_conversation_logs;
CREATE POLICY tenant_isolation ON memind_conversation_logs
    USING (tenant_id = current_setting('app.tenant_id', true)
           OR current_setting('app.cross_tenant', true) = 'on')
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

DROP POLICY IF EXISTS tenant_isolation ON memind_raw_contents;
CREATE POLICY tenant_isolation ON memind_raw_contents
    USING (tenant_id = current_setting('app.tenant_id', true)
           OR current_setting('app.cross_tenant', true) = 'on')
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));