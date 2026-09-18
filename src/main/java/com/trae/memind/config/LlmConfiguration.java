package com.trae.memind.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trae.memind.llm.LlmClient;
import com.trae.memind.llm.LocalHashLlmClient;
import com.trae.memind.llm.OpenAiCompatibleLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 装配：对话能力与向量能力分别选型。
 *
 * <p>两者可以来自不同的提供方——例如对话用云端千问、向量用内网 Ollama；
 * 对话能力未配置时自动下沉到本地实现，向量能力的降级策略由 {@code memind.llm.embedding.mode} 决定
 * （remote 强制远程、失败即报错；local 强制本地；auto 视配置而定）。
 */
@Configuration
@EnableConfigurationProperties(MemindProperties.class)
public class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    @Bean
    public LlmClient llmClient(MemindProperties properties, ObjectMapper mapper) {
        MemindProperties.Llm config = properties.llm();
        int dimension = properties.embeddingDimension();
        LocalHashLlmClient local = new LocalHashLlmClient(dimension);

        LlmClient chatDelegate = config.chatConfigured()
                ? OpenAiCompatibleLlmClient.forChat(config, mapper)
                : local;
        LlmClient embeddingDelegate = resolveEmbeddingDelegate(config.embedding(), dimension, local, mapper);

        log.info("Memind LLM 装配完成: chat={}, embedding={}, 向量维度={}",
                chatDelegate.provider(), embeddingDelegate.provider(), dimension);
        if (!config.chatConfigured()) {
            log.warn("未配置 OPENAI_API_KEY / OPENAI_CHAT_MODEL，记忆抽取将降级为规则引擎");
        }
        return new DelegatingLlmClient(chatDelegate, embeddingDelegate);
    }

    /**
     * 按 mode 决定向量能力走向远程还是本地。
     *
     * <p>remote 模式下配置缺失直接失败：静默降级成哈希向量会让检索质量悄无声息地劣化，
     * 这类问题在数据攒起来之后极难定位。
     */
    private LlmClient resolveEmbeddingDelegate(MemindProperties.Llm.Embedding embedding,
                                               int dimension,
                                               LlmClient local,
                                               ObjectMapper mapper) {
        MemindProperties.Llm.Embedding.Mode mode =
                embedding.mode() == null ? MemindProperties.Llm.Embedding.Mode.AUTO : embedding.mode();
        if (mode == MemindProperties.Llm.Embedding.Mode.LOCAL) {
            log.info("embedding mode=local，向量能力使用本地特征哈希（维度 {}）", dimension);
            return local;
        }
        if (!embedding.modelConfigured()) {
            if (mode == MemindProperties.Llm.Embedding.Mode.REMOTE) {
                throw new IllegalStateException("memind.llm.embedding.mode=remote 但缺少必要配置："
                        + "请设置 EMBED_BASE_URL / EMBED_API_KEY / EMBED_MODEL，"
                        + "或改为 mode=local 显式接受本地哈希降级");
            }
            log.warn("未配置 EMBED_MODEL / EMBED_API_KEY，向量能力使用本地特征哈希（维度 {}）", dimension);
            return local;
        }
        if (embedding.dimension() != dimension) {
            throw new IllegalStateException("memind.llm.embedding.dimension=" + embedding.dimension()
                    + " 与 memind.embedding-dimension=" + dimension + " 不一致。后者是 pgvector 列维度，"
                    + "请把两者对齐（统一通过 MEMIND_EMBEDDING_DIMENSION 与 EMBED_DIM 设置为相同值）");
        }
        return OpenAiCompatibleLlmClient.forEmbedding(embedding, dimension, mapper);
    }

    /** 对话与向量分派到不同实现。 */
    private record DelegatingLlmClient(LlmClient chatDelegate, LlmClient embeddingDelegate) implements LlmClient {

        @Override
        public boolean chatAvailable() {
            return chatDelegate.chatAvailable();
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            return chatDelegate.chat(systemPrompt, userPrompt);
        }

        @Override
        public void chatStream(List<ObjectNode> messages, ArrayNode tools, Consumer<ChatDelta> onDelta) {
            chatDelegate.chatStream(messages, tools, onDelta);
        }

        @Override
        public int embeddingDimension() {
            return embeddingDelegate.embeddingDimension();
        }

        @Override
        public boolean embeddingRemote() {
            return embeddingDelegate.embeddingRemote();
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return embeddingDelegate.embed(texts);
        }

        @Override
        public String provider() {
            return "chat=" + chatDelegate.provider() + ", embedding=" + embeddingDelegate.provider();
        }
    }
}