package com.trae.memind.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trae.memind.config.MemindProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容协议的远程实现，DeepSeek / 通义千问 / 月之暗面等国内模型均适用。
 *
 * <p>对话与向量各持一份 {@link Endpoint}，因此可以指向不同供应商——例如对话走云端千问、
 * 向量走内网 Ollama；两个角色由 {@link #forChat} 与 {@link #forEmbedding} 构造，
 * 互不感知对方的存在。
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    /** 流式调用的最大尝试次数。上游 reset 多为一次性抖动，补一次即可覆盖绝大多数情况。 */
    private static final int STREAM_MAX_ATTEMPTS = 2;

    /** 重试前的等待：既给上游一点恢复时间，也避免立刻打回同一条刚断的连接。 */
    private static final long STREAM_RETRY_BACKOFF_MS = 500L;

    /** 一次远程调用所需的连接信息。 */
    public record Endpoint(String baseUrl, String apiKey, String model, int timeoutSeconds) {
    }

    private final Endpoint endpoint;
    private final Role role;
    private final ObjectMapper mapper;
    private final java.net.http.HttpClient httpClient;

    /** 向量维度是全局唯一配置项，同时也是 pgvector 列维度；远程返回不一致时必须立刻失败。 */
    private final int expectedDimension;

    /** 失败后的重试次数（不含首次调用）；对话能力保持 0 次以维持既有行为。 */
    private final int maxRetries;

    private final double temperature;

    private volatile boolean dimensionVerified;

    private enum Role {
        CHAT,
        EMBEDDING
    }

    private OpenAiCompatibleLlmClient(Endpoint endpoint, Role role, double temperature, int maxRetries,
                                      int expectedDimension, ObjectMapper mapper) {
        this.endpoint = endpoint;
        this.role = role;
        this.temperature = temperature;
        this.maxRetries = Math.max(0, maxRetries);
        this.expectedDimension = expectedDimension;
        this.mapper = mapper;
        this.httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, endpoint.timeoutSeconds())))
                .build();
    }

    /** 对话角色：沿用 {@code memind.llm} 下的地址与密钥。 */
    public static OpenAiCompatibleLlmClient forChat(MemindProperties.Llm config, ObjectMapper mapper) {
        return new OpenAiCompatibleLlmClient(
                new Endpoint(config.baseUrl(), config.apiKey(), config.chatModel(), config.timeoutSeconds()),
                Role.CHAT, config.temperature(), 0, 0, mapper);
    }

    /** 向量角色：地址 / 密钥 / 模型 / 超时 / 重试全部来自 {@code memind.llm.embedding}。 */
    public static OpenAiCompatibleLlmClient forEmbedding(MemindProperties.Llm.Embedding config,
                                                        int expectedDimension,
                                                        ObjectMapper mapper) {
        return new OpenAiCompatibleLlmClient(
                new Endpoint(config.baseUrl(), config.apiKey(), config.model(),
                        Math.max(1, config.timeoutMs() / 1000)),
                Role.EMBEDDING, 0d, config.maxRetries(), expectedDimension, mapper);
    }

    @Override
    public boolean chatAvailable() {
        return role == Role.CHAT;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", endpoint.model());
        body.put("temperature", temperature);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.addObject().put("role", "system").put("content", systemPrompt);
        }
        messages.addObject().put("role", "user").put("content", userPrompt);
        JsonNode response = post("/chat/completions", body);
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("LLM 返回体缺少 choices[0].message.content: " + response);
        }
        return content.asText();
    }

    @Override
    public void chatStream(List<ObjectNode> messages, ArrayNode tools, java.util.function.Consumer<ChatDelta> onDelta) {
        if (role != Role.CHAT) {
            throw new UnsupportedOperationException("当前客户端仅提供向量能力，不能用于流式对话");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", endpoint.model());
        body.put("temperature", temperature);
        body.put("stream", true);
        ArrayNode payloadMessages = body.putArray("messages");
        messages.forEach(payloadMessages::add);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }

        String endpointUrl = normalizeBaseUrl() + "/chat/completions";
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("序列化请求体失败: " + ex.getMessage(), ex);
        }

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(endpointUrl))
                .timeout(Duration.ofSeconds(Math.max(5, endpoint.timeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + endpoint.apiKey())
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        payload, java.nio.charset.StandardCharsets.UTF_8))
                .build();

        // 上游偶发的 Connection reset（实测出现过一次，重放同一请求即恢复）会让整轮对话直接失败，
        // 而这类抖动绝大多数发生在建连/首帧阶段——那时用户界面上还什么都没有，
        // 此时重试是零代价的；一旦有任意一帧交付过，重试就会让同一段内容渲染两遍，故不再重试。
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<ChatDelta> guarded = delta -> {
            delivered.set(true);
            onDelta.accept(delta);
        };

        for (int attempt = 1; ; attempt++) {
            ChatStreamParser parser = new ChatStreamParser(mapper, guarded);
            try (java.util.stream.Stream<String> lines = openStream(request, endpointUrl)) {
                lines.forEach(parser);
            } catch (RuntimeException ex) {
                if (delivered.get() || attempt >= STREAM_MAX_ATTEMPTS) {
                    throw ex;
                }
                log.warn("流式调用失败且尚未产生任何输出，重试（第 {}/{} 次）：{}",
                        attempt, STREAM_MAX_ATTEMPTS, ex.getMessage());
                try {
                    Thread.sleep(STREAM_RETRY_BACKOFF_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
                continue;
            }
            // 连接被上游截断时不会收到 finish_reason，这里兜底交出已攒下的工具调用。
            parser.finish();
            return;
        }
    }

    /** 发起请求并校验状态码：非 2xx 时消费掉响应体，把上游错误原文带给调用方。 */
    private java.util.stream.Stream<String> openStream(java.net.http.HttpRequest request, String endpointUrl) {
        try {
            java.net.http.HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() >= 300) {
                String detail;
                try (java.util.stream.Stream<String> lines = response.body()) {
                    detail = lines.collect(java.util.stream.Collectors.joining("\n"));
                }
                throw new IllegalStateException("调用 " + endpointUrl + " 失败, HTTP " + response.statusCode()
                        + ": " + detail);
            }
            return response.body();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("调用 " + endpointUrl + " 失败: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 " + endpointUrl + " 被中断", ex);
        }
    }

    @Override
    public int embeddingDimension() {
        return expectedDimension;
    }

    @Override
    public boolean embeddingRemote() {
        return role == Role.EMBEDDING;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", endpoint.model());
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        JsonNode response = post("/embeddings", body);
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() != texts.size()) {
            throw new IllegalStateException("embedding 返回体与入参数量不一致: " + response);
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (JsonNode item : data) {
            JsonNode embedding = item.path("embedding");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        if (!vectors.isEmpty() && vectors.get(0).length > 0) {
            verifyDimension(vectors.get(0).length);
        }
        return vectors;
    }

    /** 首次拿到远程向量时校验维度：与 pgvector 列维度错配会造成静默的检索失效，必须立刻失败。 */
    private void verifyDimension(int actual) {
        if (dimensionVerified) {
            return;
        }
        if (actual != expectedDimension) {
            throw new IllegalStateException("embedding 模型 " + endpoint.model() + " 返回 " + actual
                    + " 维向量，但 memind.embedding-dimension 配置为 " + expectedDimension
                    + "（同时也是 pgvector 列维度）。请把 MEMIND_EMBEDDING_DIMENSION 改为 " + actual
                    + "，并清空或迁移已落库的向量数据后重启。");
        }
        dimensionVerified = true;
    }

    @Override
    public String provider() {
        return "openai-compatible:" + endpoint.baseUrl() + "#" + endpoint.model();
    }

    private JsonNode post(String path, ObjectNode body) {
        String endpointUrl = normalizeBaseUrl() + path;
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("序列化请求体失败: " + ex.getMessage(), ex);
        }
        IllegalStateException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                backoff(attempt);
            }
            try {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(endpointUrl))
                        .timeout(Duration.ofSeconds(Math.max(5, endpoint.timeoutSeconds())))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + endpoint.apiKey())
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                payload, java.nio.charset.StandardCharsets.UTF_8))
                        .build();
                java.net.http.HttpResponse<String> response = httpClient.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                if (response.statusCode() >= 300) {
                    last = new IllegalStateException("调用 " + endpointUrl + " 失败, HTTP " + response.statusCode()
                            + ": " + response.body());
                    if (!retryable(response.statusCode())) {
                        throw last;
                    }
                    continue;
                }
                return mapper.readTree(response.body());
            } catch (java.io.IOException ex) {
                last = new IllegalStateException("调用 " + endpointUrl + " 失败: " + ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("调用 " + endpointUrl + " 被中断", ex);
            }
        }
        throw last == null ? new IllegalStateException("调用 " + endpointUrl + " 失败") : last;
    }

    /** 只有限流与服务端错误值得重试；4xx（如鉴权失败、模型不存在）重试没有意义。 */
    private static boolean retryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试等待被中断", ex);
        }
    }

    /** 统一补全 {@code /v1} 前缀：DeepSeek 等根域名与此处约定一致，通义兼容模式本身已带 /v1。 */
    private String normalizeBaseUrl() {
        String base = endpoint.baseUrl() == null ? "" : endpoint.baseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/v1") ? base : base + "/v1";
    }
}