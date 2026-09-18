package com.trae.memind.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trae.memind.config.MemindProperties;
import com.trae.memind.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用入口：把模型给出的「函数名 + 参数 JSON」变成一段可回灌的文本结果。
 *
 * <p>核心原则是<b>永不抛异常</b>——模型幻觉出一个不存在的工具、参数写成半个 JSON、
 * 工具内部报错，全部转成 {@code {"error":"..."}} 回灌，让模型自己纠错。
 * 一旦这里抛异常，整条 SSE 流就断了，用户只看到一次失败而不是一次自我修正。
 *
 * <p>执行体跑在独立的 {@code memind-tool-*} 线程上，只为了能真正兑现
 * {@code memind.chat.tool-timeout-ms}——同线程执无法抢占。租户上下文在派发前捕获、
 * 在任务体内用 {@link TenantContext#supplyWith} 重新绑定，因此 {@code search_memory}
 * 之类的工具仍能正确取到命名空间。
 */
@Service
public class ToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(ToolInvoker.class);

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final ToolRegistry registry;
    private final ObjectMapper mapper;
    private final int toolTimeoutMs;
    private final ExecutorService executor;

    public ToolInvoker(ToolRegistry registry, MemindProperties properties, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
        this.toolTimeoutMs = Math.max(100, properties.chat().toolTimeoutMs());
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "memind-tool-" + THREAD_SEQ.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 一次工具调用的结果。
     *
     * @param ok        是否正常返回（含模型参数错误、工具内部报错时为 false）
     * @param elapsedMs 执行耗时，直接进 SSE 的 tool_result 事件
     * @param result    回灌给模型的文本，恒为合法 JSON
     */
    public record ToolOutcome(String name, String arguments, boolean ok, long elapsedMs, String result) {
    }

    public ToolOutcome invoke(String name, String rawArguments) {
        long start = System.nanoTime();
        Optional<ToolDefinition> found = registry.find(name);
        if (found.isEmpty()) {
            List<String> available = registry.all().stream().map(ToolDefinition::name).toList();
            return failure(name, rawArguments, start, "未知工具 " + name + "，可用工具：" + available);
        }

        ObjectNode arguments;
        try {
            arguments = parse(rawArguments);
        } catch (Exception ex) {
            return failure(name, rawArguments, start, "参数不是合法 JSON 对象: " + ex.getMessage());
        }

        TenantContext context;
        try {
            context = TenantContext.current();
        } catch (IllegalStateException ex) {
            return failure(name, rawArguments, start, ex.getMessage());
        }

        ToolDefinition definition = found.get();
        Future<String> future = executor.submit(() ->
                TenantContext.supplyWith(context, () -> definition.executor().execute(arguments)));

        String result;
        try {
            result = future.get(toolTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return failure(name, rawArguments, start, "工具执行超时（上限 " + toolTimeoutMs + "ms）");
        } catch (ExecutionException ex) {
            return failure(name, rawArguments, start, rootMessage(ex));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failure(name, rawArguments, start, "工具执行被中断");
        }

        long elapsedMs = elapsedMs(start);
        log.info("工具调用完成 name={} type={} 耗时={}ms", name, definition.type(), elapsedMs);
        return new ToolOutcome(name, rawArguments, true, elapsedMs, result);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private ObjectNode parse(String rawArguments) throws Exception {
        if (rawArguments == null || rawArguments.isBlank()) {
            return mapper.createObjectNode();
        }
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(rawArguments);
        if (!node.isObject()) {
            throw new IllegalArgumentException("期望 JSON 对象，实际是 " + node.getNodeType());
        }
        return (ObjectNode) node;
    }

    private ToolOutcome failure(String name, String rawArguments, long start, String message) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("error", message);
        long elapsedMs = elapsedMs(start);
        log.warn("工具调用失败 name={} 耗时={}ms 原因={}", name, elapsedMs, message);
        return new ToolOutcome(name, rawArguments, false, elapsedMs, payload.toString());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}