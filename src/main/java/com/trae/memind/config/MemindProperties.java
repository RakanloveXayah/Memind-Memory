package com.trae.memind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Memind 全部可调参数（对齐文档第八章工程落地要点）。 */
@ConfigurationProperties(prefix = "memind")
public record MemindProperties(
        int embeddingDimension,
        Llm llm,
        Chat chat,
        Extraction extraction,
        Consolidation consolidation,
        Retrieval retrieval,
        Storage storage) {

    /**
     * 流式聊天编排参数。
     *
     * @param maxRounds    单轮对话内最多几次「模型 → 工具 → 模型」往返，防止模型绕圈
     * @param toolTimeoutMs 单个工具的执行上限；超时以可读错误回灌给模型，而不是打断整条流
     * @param writeback    是否在流结束后自动把本轮对话与工具轨迹写回记忆
     */
    public record Chat(int maxRounds, int toolTimeoutMs, boolean writeback) {
    }

    public record Llm(
            String baseUrl,
            String apiKey,
            String chatModel,
            int timeoutSeconds,
            double temperature,
            Embedding embedding) {

        public boolean chatConfigured() {
            return apiKey != null && !apiKey.isBlank() && chatModel != null && !chatModel.isBlank();
        }

        /**
         * 向量能力配置块：地址 / 密钥 / 模型独立于对话能力，可指向本地推理服务（如 Ollama）。
         *
         * <p>{@code dimension} 是该远程模型的期望维度，必须与全局的 {@code memind.embedding-dimension}
         * 一致——后者才是 pgvector 列维度与本地降级向量的唯一来源。
         */
        public record Embedding(
                Mode mode,
                String baseUrl,
                String apiKey,
                String model,
                int dimension,
                int timeoutMs,
                int maxRetries) {

            public enum Mode {
                /** 强制远程：缺少必要配置时启动即失败，避免静默降级成哈希向量。 */
                REMOTE,
                /** 强制本地：离线测试用，忽略远程配置。 */
                LOCAL,
                /** 有 api-key 与模型名就走远程，否则本地。 */
                AUTO
            }

            /** 远程向量能力是否已配置齐全。 */
            public boolean modelConfigured() {
                return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
            }
        }
    }

    public record Extraction(int chunkSize, double minConfidence, int maxItemsPerChunk) {
    }

    public record Consolidation(int leafMinItems, int branchMaxInsights, boolean immediate, String cron) {
    }

    /**
     * 检索与上下文组装参数。
     *
     * @param minConfidence      记忆自身的置信度下限，低于此值的条目不进候选
     * @param minRelevance       <b>与本轮问题的相关性下限</b>（原始余弦相似度）。RRF 只记录"排第几"、
     *                           不记录"有多像"，因此一个词都没对上的时候，各通道的 top-1 依然会融合出
     *                           一个看起来很正常的分数——这就是无关记忆稳定挤进上下文的原因。
     *                           闸门只在向量具备真实语义时生效（本地特征哈希向量没有语义，此时自动关闭）
     * @param insightBudgetShare 洞察（Insight Tree）最多占用的上下文预算比例；超出部分先让位给
     *                           事实类记忆，预算还有剩再补回。避免"抽象理解"把具体事实全部挤出去
     */
    public record Retrieval(
            int vectorTopK,
            int bm25TopK,
            int insightTopK,
            int finalTopK,
            int rrfK,
            int timeDecayHalfLifeDays,
            double minConfidence,
            double minRelevance,
            double insightBudgetShare,
            int contextTokenBudget,
            String strategy) {
    }

    /** 存储层参数：向量索引类型与检索精度。 */
    public record Storage(VectorIndexType vectorIndex, int ivfflatLists, int hnswEfSearch) {

        public enum VectorIndexType {
            /** pgvector HNSW：召回精度高，适合在线增量写入。 */
            HNSW,
            /** pgvector IVFFlat：索引体积小、构建快，适合数据量大且批量导入的场景。 */
            IVFFLAT,
            /** 不建近邻索引，退化为全表扫描（仅用于小数据量调试）。 */
            NONE
        }
    }
}