package com.trae.memind.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * pgvector 结构与索引初始化。
 *
 * <p>{@code schema.sql} 只能声明无维度的 {@code vector} 列，而 pgvector 的任何一次近邻检索
 * 都要求列维度与查询向量一致；同时 HNSW / IVFFlat 索引方法来自配置。两者都必须在
 * {@code schema.sql} 执行之后完成，因此放在这里而不是 DDL 脚本中。
 *
 * <p>维度收敛到唯一配置项 {@code memind.embedding-dimension}：它同时决定本地降级向量的维度、
 * pgvector 列维度与近邻索引。远程 embedding 模型的实际维度在首次调用时校验，不一致直接失败，
 * 避免把不同维度的向量写进同一列造成静默错配。
 */
@Component
public class PgVectorInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PgVectorInitializer.class);

    private static final String VECTOR_INDEX_NAME = "idx_memories_embedding";

    /** 需要保证向量列维度的表。 */
    private static final String[] VECTOR_TABLES = {"memind_memories", "memind_insight_nodes"};

    private final JdbcTemplate jdbcTemplate;
    private final MemindProperties properties;

    public PgVectorInitializer(DataSource dataSource,
                               MemindProperties properties,
                               ObjectProvider<DataSourceScriptDatabaseInitializer> schemaInitializer) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.properties = properties;
        // 在构造期取一次，强制 schema.sql 先执行完毕：后续 ALTER TABLE 依赖表已存在。
        schemaInitializer.getIfAvailable();
    }

    @Override
    public void afterPropertiesSet() {
        int dimension = resolveDimension();
        for (String table : VECTOR_TABLES) {
            ensureEmbeddingDimension(table, dimension);
        }
        ensureVectorIndex(dimension);
    }

    private int resolveDimension() {
        int dimension = properties.embeddingDimension();
        if (dimension <= 0) {
            throw new IllegalStateException("memind.embedding-dimension 必须为正整数，当前为 " + dimension);
        }
        return dimension;
    }

    private void ensureEmbeddingDimension(String table, int dimension) {
        String current = currentType(table);
        String expected = "vector(" + dimension + ")";
        if (expected.equalsIgnoreCase(current)) {
            log.debug("向量列维度已就绪: {}.embedding = {}", table, current);
            return;
        }
        long rows = countVectors(table);
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN embedding TYPE vector(" + dimension + ")");
            log.info("向量列维度已调整: {}.embedding {} -> {}{}", table, current, expected,
                    rows > 0 ? "（已转换 " + rows + " 条历史向量）" : "");
        } catch (Exception ex) {
            throw new IllegalStateException("调整 " + table + ".embedding 维度失败（" + current + " -> " + expected + "，"
                    + "表中已有 " + rows + " 条向量数据）。请先把历史向量迁移到目标维度，"
                    + "或清空该表后重启。原因: " + ex.getMessage(), ex);
        }
    }

    private String currentType(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                        + "WHERE attrelid = ?::regclass AND attname = 'embedding'",
                String.class, table);
    }

    private long countVectors(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE embedding IS NOT NULL", Long.class);
        return count == null ? 0L : count;
    }

    /**
     * 建立（或在索引方法变更时重建）记忆表的近邻索引。
     * 列维度变化时 PostgreSQL 会自动重建索引，因此这里只需校验索引方法。
     */
    private void ensureVectorIndex(int dimension) {
        MemindProperties.Storage.VectorIndexType type = properties.storage().vectorIndex();
        String method = switch (type) {
            case HNSW -> "hnsw";
            case IVFFLAT -> "ivfflat";
            case NONE -> null;
        };
        if (method == null) {
            log.warn("memind.storage.vector-index=NONE，向量检索将退化为全表顺序扫描（仅适合小数据量）");
            return;
        }
        String existing = existingIndexDefinition();
        if (existing != null && existing.toLowerCase().contains("using " + method)) {
            log.debug("近邻索引已就绪: {}", existing.trim());
            return;
        }
        if (existing != null) {
            jdbcTemplate.execute("DROP INDEX IF EXISTS " + VECTOR_INDEX_NAME);
        }
        String options = type == MemindProperties.Storage.VectorIndexType.IVFFLAT
                ? " WITH (lists = " + Math.max(1, properties.storage().ivfflatLists()) + ")"
                : "";
        jdbcTemplate.execute("CREATE INDEX " + VECTOR_INDEX_NAME + " ON memind_memories "
                + "USING " + method + " (embedding vector_cosine_ops)" + options);
        log.info("近邻索引已创建: {} USING {} (embedding vector_cosine_ops, 维度 {})",
                VECTOR_INDEX_NAME, method, dimension);
    }

    private String existingIndexDefinition() {
        return jdbcTemplate.query(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?",
                rs -> rs.next() ? rs.getString("indexdef") : null,
                VECTOR_INDEX_NAME);
    }
}