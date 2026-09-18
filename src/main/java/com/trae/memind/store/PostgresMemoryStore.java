package com.trae.memind.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trae.memind.config.MemindProperties;
import com.trae.memind.domain.ConversationLog;
import com.trae.memind.domain.InsightLevel;
import com.trae.memind.domain.InsightNode;
import com.trae.memind.domain.MemoryItem;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.domain.MemoryType;
import com.trae.memind.domain.RawContent;
import com.trae.memind.util.Vectors;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * PostgreSQL + pgvector 存储实现。
 *
 * <p>三点设计要点（相对直接照搬内存表结构而言）：
 * <ol>
 *   <li>写入用 {@code INSERT ... ON CONFLICT (id) DO UPDATE}：语义等价于幂等 upsert，且不需要锁表；</li>
 *   <li>每个公开方法都在自己的事务内先用 {@code set_config(..., true)} 注入租户上下文，
 *       让 RLS 策略生效——未注入时策略返回 0 行（失败关闭），不会泄露数据；</li>
 *   <li>向量召回下推给 pgvector 的 {@code <=>} 余弦距离算子，应用层不再做全表暴力扫描。</li>
 * </ol>
 */
@Repository
public class PostgresMemoryStore implements MemoryStore {

    /** 常规读取不携带向量列，避免整合流程传输大量无用的 float 数据。 */
    private static final String MEMORY_COLUMNS =
            "id, tenant_id, user_id, scope, namespace, mem_type, content_text, content_hash, "
                    + "confidence, metadata, source_id, created_at, updated_at";

    private static final String MEMORY_COLUMNS_WITH_VECTOR = MEMORY_COLUMNS + ", embedding";

    private static final String INSIGHT_COLUMNS =
            "id, tenant_id, user_id, scope, namespace, insight_level, group_key, content_text, "
                    + "parent_id, member_ids, embedding, confidence, node_version, created_at, updated_at";

    private static final String SAVE_MEMORY_SQL = """
            INSERT INTO memind_memories
                (id, tenant_id, user_id, scope, namespace, mem_type, content_text, content_hash,
                 confidence, embedding, metadata, source_id, created_at, updated_at, active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
            ON CONFLICT (id) DO UPDATE SET
                content_text = EXCLUDED.content_text,
                content_hash = EXCLUDED.content_hash,
                confidence   = EXCLUDED.confidence,
                embedding    = EXCLUDED.embedding,
                metadata     = EXCLUDED.metadata,
                updated_at   = EXCLUDED.updated_at,
                active       = TRUE
            """;

    private static final String SAVE_INSIGHT_SQL = """
            INSERT INTO memind_insight_nodes
                (id, tenant_id, user_id, scope, namespace, insight_level, group_key, content_text,
                 parent_id, member_ids, embedding, confidence, node_version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                group_key    = EXCLUDED.group_key,
                content_text = EXCLUDED.content_text,
                parent_id    = EXCLUDED.parent_id,
                member_ids   = EXCLUDED.member_ids,
                embedding    = EXCLUDED.embedding,
                confidence   = EXCLUDED.confidence,
                node_version = EXCLUDED.node_version,
                updated_at   = EXCLUDED.updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final String hnswEfSearch;

    public PostgresMemoryStore(JdbcTemplate jdbcTemplate,
                               ObjectMapper mapper,
                               PlatformTransactionManager transactionManager,
                               MemindProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.hnswEfSearch = String.valueOf(Math.max(1, properties.storage().hnswEfSearch()));
    }

    // ------------------------------------------------------------------ 记忆条目

    @Override
    public void saveMemories(List<MemoryItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        runInTenant(items.get(0).tenantId(), false, () ->
                jdbcTemplate.batchUpdate(SAVE_MEMORY_SQL, items, items.size(), (statement, item) -> {
                    statement.setString(1, item.id());
                    statement.setString(2, item.tenantId());
                    statement.setString(3, item.userId());
                    statement.setString(4, item.scope().name());
                    statement.setString(5, item.namespace());
                    statement.setString(6, item.type().name());
                    statement.setString(7, item.content());
                    statement.setString(8, item.contentHash());
                    statement.setDouble(9, item.confidence());
                    statement.setObject(10, vectorParam(item.embedding()));
                    statement.setString(11, writeMetadata(item.metadata()));
                    statement.setString(12, item.sourceId());
                    statement.setObject(13, timestamp(item.createdAt()));
                    statement.setObject(14, timestamp(item.updatedAt()));
                }));
    }

    @Override
    public List<MemoryItem> findMemories(String namespace, MemoryScope scope) {
        return callInTenant(tenantOf(namespace), false, () -> jdbcTemplate.query(
                "SELECT " + MEMORY_COLUMNS + " FROM memind_memories "
                        + "WHERE namespace = ? AND scope = ? AND active = TRUE ORDER BY created_at DESC",
                (rs, rowNum) -> mapMemory(rs, null), namespace, scope.name()));
    }

    @Override
    public List<MemoryText> findMemoryTexts(String namespace, MemoryScope scope) {
        return callInTenant(tenantOf(namespace), false, () -> jdbcTemplate.query(
                "SELECT id, scope, mem_type, content_text FROM memind_memories "
                        + "WHERE namespace = ? AND scope = ? AND active = TRUE",
                (rs, rowNum) -> new MemoryText(
                        rs.getString("id"),
                        MemoryType.parse(rs.getString("mem_type"), MemoryScope.from(rs.getString("scope"))),
                        rs.getString("content_text")),
                namespace, scope.name()));
    }

    @Override
    public List<VectorHit> searchByVector(String namespace, MemoryScope scope, float[] queryEmbedding,
                                          int topK, double minSimilarity) {
        if (queryEmbedding == null || queryEmbedding.length == 0 || topK <= 0) {
            return List.of();
        }
        PGobject probe = vectorParam(queryEmbedding);
        List<VectorHit> hits = callInTenant(tenantOf(namespace), false, () -> jdbcTemplate.query("""
                        SELECT id, 1 - (embedding <=> ?) AS similarity
                        FROM memind_memories
                        WHERE namespace = ? AND scope = ? AND active = TRUE AND embedding IS NOT NULL
                        ORDER BY embedding <=> ?
                        LIMIT ?
                        """,
                (rs, rowNum) -> new VectorHit(rs.getString("id"), rs.getDouble("similarity")),
                probe, namespace, scope.name(), probe, topK));
        return hits.stream().filter(hit -> hit.similarity() > minSimilarity).toList();
    }

    @Override
    public List<MemoryItem> findMemoriesByIds(String namespace, MemoryScope scope, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        String placeholders = distinct.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] args = new Object[distinct.size() + 2];
        args[0] = namespace;
        args[1] = scope.name();
        for (int i = 0; i < distinct.size(); i++) {
            args[i + 2] = distinct.get(i);
        }
        return callInTenant(tenantOf(namespace), false, () -> jdbcTemplate.query(
                "SELECT " + MEMORY_COLUMNS_WITH_VECTOR + " FROM memind_memories "
                        + "WHERE namespace = ? AND scope = ? AND id IN (" + placeholders + ")",
                (rs, rowNum) -> mapMemory(rs, Vectors.parse(rs.getString("embedding"))), args));
    }

    @Override
    public Set<String> findContentHashes(String namespace, MemoryScope scope) {
        return callInTenant(tenantOf(namespace), false, () -> new HashSet<>(jdbcTemplate.queryForList(
                "SELECT content_hash FROM memind_memories WHERE namespace = ? AND scope = ? AND active = TRUE",
                String.class, namespace, scope.name())));
    }

    @Override
    public int deleteMemories(String namespace, MemoryScope scope) {
        return callInTenant(tenantOf(namespace), false, () -> jdbcTemplate.update(
                "UPDATE memind_memories SET active = FALSE, updated_at = now() "
                        + "WHERE namespace = ? AND scope = ? AND active = TRUE",
                namespace, scope.name()));
    }

    // ------------------------------------------------------------------ 洞察节点

    @Override
    public void saveInsightNodes(List<InsightNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        runInTenant(nodes.get(0).tenantId(), false, () ->
                jdbcTemplate.batchUpdate(SAVE_INSIGHT_SQL, nodes, nodes.size(), (statement, node) -> {
                    statement.setString(1, node.id());
                    statement.setString(2, node.tenantId());
                    statement.setString(3, node.userId());
                    statement.setString(4, node.scope().name());
                    statement.setString(5, node.namespace());
                    statement.setString(6, node.level().name());
                    statement.setString(7, node.groupKey());
                    statement.setString(8, node.content());
                    statement.setString(9, node.parentId());
                    statement.setString(10, writeIds(node.memberIds()));
                    statement.setObject(11, vectorParam(node.embedding()));
                    statement.setDouble(12, node.confidence());
                    statement.setInt(13, node.version());
                    statement.setObject(14, timestamp(node.createdAt()));
                    statement.setObject(15, timestamp(node.updatedAt()));
                }));
    }

    @Override
    public List<InsightNode> findInsightNodes(String namespace, MemoryScope scope, InsightLevel level) {
        return callInTenant(tenantOf(namespace), false, () -> jdbcTemplate.query(
                "SELECT " + INSIGHT_COLUMNS + " FROM memind_insight_nodes "
                        + "WHERE namespace = ? AND scope = ? AND insight_level = ? ORDER BY confidence DESC",
                this::mapInsight, namespace, scope.name(), level.name()));
    }

    @Override
    public void deleteInsightNodes(String namespace, MemoryScope scope, InsightLevel level) {
        runInTenant(tenantOf(namespace), false, () -> jdbcTemplate.update(
                "DELETE FROM memind_insight_nodes WHERE namespace = ? AND scope = ? AND insight_level = ?",
                namespace, scope.name(), level.name()));
    }

    // ------------------------------------------------------------------ 对话日志与原始内容

    @Override
    public void saveConversationLog(ConversationLog log) {
        runInTenant(log.tenantId(), false, () -> jdbcTemplate.update("""
                INSERT INTO memind_conversation_logs
                    (id, tenant_id, user_id, scope, namespace, session_id, role, content_text, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, log.id(), log.tenantId(), log.userId(), log.scope().name(), log.namespace(),
                log.sessionId(), log.role(), log.content(), timestamp(log.createdAt())));
    }

    @Override
    public void saveRawContent(RawContent rawContent) {
        runInTenant(rawContent.tenantId(), false, () -> jdbcTemplate.update("""
                INSERT INTO memind_raw_contents
                    (id, tenant_id, user_id, scope, namespace, content_type, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET payload = EXCLUDED.payload
                """, rawContent.id(), rawContent.tenantId(), rawContent.userId(), rawContent.scope().name(),
                rawContent.namespace(), rawContent.contentType(), rawContent.payload(),
                timestamp(rawContent.createdAt())));
    }

    @Override
    public List<NamespaceRef> listNamespaces() {
        // 唯一允许跨租户读取的路径：定时批量整合需要枚举全部命名空间，
        // 逃生口只在存储层内部开启，调用方无法自行拼出跨租户查询。
        return callInTenant("", true, () -> jdbcTemplate.query(
                "SELECT DISTINCT namespace, scope, tenant_id, user_id FROM memind_memories WHERE active = TRUE",
                (rs, rowNum) -> new NamespaceRef(
                        rs.getString("namespace"),
                        MemoryScope.from(rs.getString("scope")),
                        rs.getString("tenant_id"),
                        rs.getString("user_id"))));
    }

    // ------------------------------------------------------------------ 租户上下文

    private void runInTenant(String tenantId, boolean crossTenant, Runnable action) {
        transactionTemplate.execute(status -> {
            applyTenantContext(tenantId, crossTenant);
            action.run();
            return null;
        });
    }

    private <T> T callInTenant(String tenantId, boolean crossTenant, Supplier<T> action) {
        return transactionTemplate.execute(status -> {
            applyTenantContext(tenantId, crossTenant);
            return action.get();
        });
    }

    /**
     * 事务级注入租户上下文（{@code is_local = true}，提交后自动失效，不会污染连接池里的连接）。
     * set_config 返回 text，用一个字符串拼接表达式把三次调用收敛到单列单行，便于 queryForObject 读取。
     */
    private void applyTenantContext(String tenantId, boolean crossTenant) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.tenant_id', ?, true) "
                        + "|| set_config('app.cross_tenant', ?, true) "
                        + "|| set_config('hnsw.ef_search', ?, true)",
                String.class, tenantId == null ? "" : tenantId, crossTenant ? "on" : "off", hnswEfSearch);
    }

    /** 命名空间形如 {@code tenant:user}（USER）或 {@code tenant}（AGENT），租户即首段。 */
    private static String tenantOf(String namespace) {
        int index = namespace.indexOf(':');
        return index < 0 ? namespace : namespace.substring(0, index);
    }

    // ------------------------------------------------------------------ 行映射与序列化

    private MemoryItem mapMemory(ResultSet rs, float[] embedding) throws SQLException {
        MemoryScope scope = MemoryScope.from(rs.getString("scope"));
        return new MemoryItem(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                scope,
                rs.getString("namespace"),
                MemoryType.parse(rs.getString("mem_type"), scope),
                rs.getString("content_text"),
                rs.getString("content_hash"),
                rs.getDouble("confidence"),
                embedding,
                readMetadata(rs.getString("metadata")),
                rs.getString("source_id"),
                instantOf(rs, "created_at"),
                instantOf(rs, "updated_at"));
    }

    private InsightNode mapInsight(ResultSet rs, int rowNum) throws SQLException {
        return new InsightNode(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                MemoryScope.from(rs.getString("scope")),
                rs.getString("namespace"),
                InsightLevel.valueOf(rs.getString("insight_level")),
                rs.getString("group_key"),
                rs.getString("content_text"),
                rs.getString("parent_id"),
                readIds(rs.getString("member_ids")),
                Vectors.parse(rs.getString("embedding")),
                rs.getDouble("confidence"),
                rs.getInt("node_version"),
                instantOf(rs, "created_at"),
                instantOf(rs, "updated_at"));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant == null ? Instant.now() : instant, ZoneOffset.UTC);
    }

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? Instant.now() : value.toInstant();
    }

    /** pgvector 参数必须以 vector 类型绑定，否则 PostgreSQL 无法把文本字面量隐式转换为 vector。 */
    private static PGobject vectorParam(float[] vector) {
        String literal = Vectors.toLiteral(vector);
        if (literal == null) {
            return null;
        }
        try {
            PGobject object = new PGobject();
            object.setType("vector");
            object.setValue(literal);
            return object;
        } catch (SQLException ex) {
            throw new IllegalStateException("构造 pgvector 参数失败: " + ex.getMessage(), ex);
        }
    }

    private String writeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Map<String, String> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String writeIds(List<String> memberIds) {
        return memberIds == null ? "[]" : memberIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private List<String> readIds(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return Arrays.stream(json.replaceAll("[\\[\\]\"]", "").split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
    }
}