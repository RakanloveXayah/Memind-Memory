package com.trae.memind.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trae.memind.config.MemindProperties;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.llm.LlmClient;
import com.trae.memind.pipeline.ExtractionInput;
import com.trae.memind.pipeline.ExtractionOutcome;
import com.trae.memind.pipeline.ExtractionPipeline;
import com.trae.memind.retrieval.ContextCompiler;
import com.trae.memind.retrieval.RetrievalEngine;
import com.trae.memind.retrieval.RetrievalResult;
import com.trae.memind.tenant.TenantContext;
import com.trae.memind.tool.ToolDefinition;
import com.trae.memind.tool.ToolInvoker;
import com.trae.memind.tool.ToolRegistry;
import com.trae.memind.web.MemoryApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 流式聊天编排：检索记忆 → 组装上下文 → 流式生成（可自主调工具）→ 自动写回。
 *
 * <p>这是"越聊越懂你"的闭环所在：拼在 System Prompt 里的上下文全部来自长期记忆，
 * 而本轮对话与工具轨迹又会作为新的原始内容回到抽取流水线，驱动 Insight Tree 继续生长。
 *
 * <p>线程约定：本类的方法在 {@code memindExecutor} 的工作线程上执行，调用方必须已经用
 * {@link TenantContext#runWith} 绑定好租户上下文；工具与写回都在这条线程（或由它派发的
 * 工具线程）上继承同一份上下文。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RetrievalEngine retrievalEngine;
    private final ContextCompiler contextCompiler;
    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;
    private final ExtractionPipeline extractionPipeline;
    private final LlmClient llmClient;
    private final ChatPrompts prompts;
    private final ChatFallbackResponder fallbackResponder;
    private final ObjectMapper mapper;
    private final TaskExecutor executor;
    private final MemindProperties.Chat config;

    public ChatService(RetrievalEngine retrievalEngine,
                       ContextCompiler contextCompiler,
                       ToolRegistry toolRegistry,
                       ToolInvoker toolInvoker,
                       ExtractionPipeline extractionPipeline,
                       LlmClient llmClient,
                       ChatPrompts prompts,
                       ChatFallbackResponder fallbackResponder,
                       ObjectMapper mapper,
                       @Qualifier("memindExecutor") TaskExecutor executor,
                       MemindProperties properties) {
        this.retrievalEngine = retrievalEngine;
        this.contextCompiler = contextCompiler;
        this.toolRegistry = toolRegistry;
        this.toolInvoker = toolInvoker;
        this.extractionPipeline = extractionPipeline;
        this.llmClient = llmClient;
        this.prompts = prompts;
        this.fallbackResponder = fallbackResponder;
        this.mapper = mapper;
        this.executor = executor;
        this.config = properties.chat();
    }

    /** 执行一轮完整对话，事件全部推给 {@code sink}；异常被转成 {@code error} 事件，不会外抛。 */
    public void stream(MemoryApi.ChatRequest request, TenantContext context, ChatSink sink) {
        long startedAt = System.nanoTime();
        String sessionId = resolveSession(request.sessionId());
        try {
            String question = requireQuestion(request);
            boolean toolsEnabled = request.enableTools() == null || request.enableTools();

            RetrievalResult retrieval = retrievalEngine.retrieve(context, resolveScopes(request.scopes()),
                    question, request.topK());
            ContextCompiler.CompiledContext compiled = contextCompiler.compile(retrieval, request.tokenBudget());

            sink.emit("meta", new ChatEvents.Meta(
                    sessionId, llmClient.provider(), !llmClient.chatAvailable(), retrieval.strategy(),
                    compiled.memoryCount(), compiled.insightCount(), compiled.estimatedTokens(), compiled.truncated(),
                    compiled.text(), toolInfos(toolsEnabled)));

            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<ToolTrace> traces = new ArrayList<>();

            int rounds;
            if (llmClient.chatAvailable()) {
                rounds = runAgentLoop(question, compiled, toolsEnabled, sink, content, reasoning, traces);
            } else {
                rounds = 0;
                for (String chunk : fallbackResponder.compose(question, compiled)) {
                    sink.emit("delta", new ChatEvents.Delta(chunk));
                    content.append(chunk);
                }
            }

            sink.emit("done", new ChatEvents.Done(rounds, content.length(), reasoning.length(),
                    traces.size(), elapsedMs(startedAt)));

            if (writebackEnabled(request)) {
                dispatchWriteback(context, sessionId, question, content.toString(), traces, sink);
            } else {
                sink.emit("writeback", new ChatEvents.Writeback("SKIPPED", 0, "SKIPPED", 0, "本轮未开启写回"));
                sink.complete();
            }
        } catch (Exception ex) {
            log.error("流式聊天失败 sessionId={}", sessionId, ex);
            sink.emit("error", new ChatEvents.Error("chat", ex.getMessage()));
            sink.complete();
        }
    }

    // ------------------------------------------------------------------ Agent 循环

    /**
     * 模型 ↔ 工具的往返循环。
     *
     * <p>停止条件：本轮没有工具调用（即模型给出了正文）。到 {@code maxRounds} 仍未收敛时，
     * 追加一次不带 tools 的调用逼出结论——否则用户会拿到一条只有工具调用、没有回答的流。
     */
    private int runAgentLoop(String question, ContextCompiler.CompiledContext compiled, boolean toolsEnabled,
                             ChatSink sink, StringBuilder content, StringBuilder reasoning, List<ToolTrace> traces) {
        List<ObjectNode> messages = new ArrayList<>();
        messages.add(message("system", prompts.system(compiled.text())));
        messages.add(message("user", question));

        ArrayNode tools = toolsEnabled ? toolRegistry.toOpenAiTools() : mapper.createArrayNode();
        Set<String> seenCalls = new HashSet<>();
        int round = 0;

        while (round < Math.max(1, config.maxRounds())) {
            round++;
            List<LlmClient.ToolCall> calls = new ArrayList<>();
            StringBuilder roundContent = new StringBuilder();
            streamOnce(messages, tools, sink, content, reasoning, roundContent, calls);

            if (calls.isEmpty()) {
                return round;
            }

            messages.add(assistantToolCallMessage(roundContent.toString(), calls, round));
            for (LlmClient.ToolCall call : calls) {
                messages.add(executeTool(call, round, seenCalls, sink, traces));
            }
        }

        log.warn("达到最大轮次 {}，追加一次不带工具的调用以逼出结论", config.maxRounds());
        round++;
        streamOnce(messages, mapper.createArrayNode(), sink, content, reasoning, new StringBuilder(), new ArrayList<>());
        return round;
    }

    /** 一次流式调用：把 reasoning / content / tool_calls 三类增量分别归位。 */
    private void streamOnce(List<ObjectNode> messages, ArrayNode tools, ChatSink sink, StringBuilder content,
                            StringBuilder reasoning, StringBuilder roundContent, List<LlmClient.ToolCall> calls) {
        llmClient.chatStream(messages, tools, delta -> {
            if (delta.reasoning() != null && !delta.reasoning().isEmpty()) {
                reasoning.append(delta.reasoning());
                sink.emit("reasoning", new ChatEvents.Reasoning(delta.reasoning()));
            }
            if (delta.content() != null && !delta.content().isEmpty()) {
                content.append(delta.content());
                roundContent.append(delta.content());
                sink.emit("delta", new ChatEvents.Delta(delta.content()));
            }
            if (delta.hasToolCalls()) {
                calls.addAll(delta.toolCalls());
            }
        });
    }

    /** 执行一个工具调用并产出要追加回对话的 {@code role=tool} 消息。 */
    private ObjectNode executeTool(LlmClient.ToolCall call, int round, Set<String> seenCalls,
                                   ChatSink sink, List<ToolTrace> traces) {
        String callId = resolveCallId(call, round);
        String type = toolRegistry.find(call.name())
                .map(definition -> definition.type().name())
                .orElse(ToolDefinition.ToolType.TOOL.name());
        sink.emit("tool_call", new ChatEvents.ToolCall(round, callId, call.name(), type, call.arguments()));

        boolean firstTime = seenCalls.add(call.name() + "|" + call.arguments());
        ToolInvoker.ToolOutcome outcome = firstTime
                ? toolInvoker.invoke(call.name(), call.arguments())
                : duplicated(call);

        sink.emit("tool_result", new ChatEvents.ToolResult(round, callId, call.name(), outcome.ok(),
                outcome.elapsedMs(), outcome.result()));
        if (outcome.ok()) {
            traces.add(new ToolTrace(call.name(), call.arguments(), outcome.result()));
        }
        return toolMessage(callId, outcome.result());
    }

    /** 同一个 (工具, 参数) 重复调用直接回灌错误，避免模型在同一个坑里绕圈刷爆轮次。 */
    private static ToolInvoker.ToolOutcome duplicated(LlmClient.ToolCall call) {
        return new ToolInvoker.ToolOutcome(call.name(), call.arguments(), false, 0L,
                "{\"error\":\"duplicate call ignored：本次参数与之前完全相同，结果同上\"}");
    }

    // ------------------------------------------------------------------ 写回

    /**
     * 流结束后把本轮内容写回记忆。
     *
     * <p>放在 {@code memindExecutor} 上而不是同步执行：抽取要走一次模型调用，几秒起步，
     * 没必要占着 SSE 的工作线程。{@code done} 已经先发出，前端的回答展示不受影响，
     * {@code writeback} 事件随后补上"记住了什么"。
     */
    private void dispatchWriteback(TenantContext context, String sessionId, String question, String answer,
                                   List<ToolTrace> traces, ChatSink sink) {
        executor.execute(() -> TenantContext.runWith(context, () -> {
            try {
                sink.emit("writeback", writeback(context, sessionId, question, answer, traces));
            } catch (Exception ex) {
                log.error("写回记忆失败 sessionId={}", sessionId, ex);
                sink.emit("error", new ChatEvents.Error("writeback", ex.getMessage()));
            } finally {
                sink.complete();
            }
        }));
    }

    private ChatEvents.Writeback writeback(TenantContext context, String sessionId, String question,
                                           String answer, List<ToolTrace> traces) {
        ExtractionOutcome userOutcome = extractionPipeline.extractNow(context, new ExtractionInput(
                MemoryScope.USER, "CONVERSATION", sessionId,
                "user: " + question + "\nassistant: " + answer, List.of()));

        if (traces.isEmpty()) {
            return new ChatEvents.Writeback(userOutcome.status().name(), userOutcome.items().size(),
                    "SKIPPED", 0, "已写回 USER " + userOutcome.items().size() + " 条；本轮无工具调用，AGENT 侧无内容");
        }

        String transcript = traces.stream()
                .map(trace -> "tool: " + trace.name() + " " + trace.arguments() + "\nresult: " + trace.result())
                .collect(Collectors.joining("\n"));
        ExtractionOutcome agentOutcome = extractionPipeline.extractNow(context, new ExtractionInput(
                MemoryScope.AGENT, "TOOL_CALL", sessionId, transcript, List.of()));

        log.info("聊天写回完成 namespace={} USER={}条 AGENT={}条",
                context.namespace(), userOutcome.items().size(), agentOutcome.items().size());
        return new ChatEvents.Writeback(userOutcome.status().name(), userOutcome.items().size(),
                agentOutcome.status().name(), agentOutcome.items().size(),
                "已写回 USER " + userOutcome.items().size() + " 条 / AGENT " + agentOutcome.items().size() + " 条");
    }

    // ------------------------------------------------------------------ 辅助

    private List<ChatEvents.ToolInfo> toolInfos(boolean enabled) {
        if (!enabled) {
            return List.of();
        }
        return toolRegistry.all().stream()
                .map(definition -> new ChatEvents.ToolInfo(definition.name(), definition.type().name(),
                        definition.description()))
                .toList();
    }

    private ObjectNode message(String role, String text) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", text == null ? "" : text);
        return node;
    }

    /** 把模型上一轮的输出（含 tool_calls）原样追加回对话，否则协议上无法关联工具结果。 */
    private ObjectNode assistantToolCallMessage(String roundContent, List<LlmClient.ToolCall> calls, int round) {
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        message.put("content", roundContent == null ? "" : roundContent);
        ArrayNode toolCalls = message.putArray("tool_calls");
        for (LlmClient.ToolCall call : calls) {
            ObjectNode node = toolCalls.addObject();
            node.put("id", resolveCallId(call, round));
            node.put("type", "function");
            ObjectNode function = node.putObject("function");
            function.put("name", call.name());
            function.put("arguments", call.arguments());
        }
        return message;
    }

    private ObjectNode toolMessage(String callId, String result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", "tool");
        node.put("tool_call_id", callId);
        node.put("content", result);
        return node;
    }

    /** 少数供应商不回 id，而 {@code role=tool} 消息必须带 tool_call_id，这里补一个稳定的兜底值。 */
    private static String resolveCallId(LlmClient.ToolCall call, int round) {
        return call.id() == null || call.id().isBlank() ? "call-" + round + "-" + call.index() : call.id();
    }

    private static List<MemoryScope> resolveScopes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of(MemoryScope.USER, MemoryScope.AGENT);
        }
        List<MemoryScope> scopes = raw.stream().filter(Objects::nonNull).map(MemoryScope::from).distinct().toList();
        return scopes.isEmpty() ? List.of(MemoryScope.USER, MemoryScope.AGENT) : scopes;
    }

    private static String requireQuestion(MemoryApi.ChatRequest request) {
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        return message;
    }

    private static String resolveSession(String sessionId) {
        return sessionId == null || sessionId.isBlank()
                ? "chat-" + UUID.randomUUID().toString().substring(0, 8)
                : sessionId.trim();
    }

    private boolean writebackEnabled(MemoryApi.ChatRequest request) {
        return request.writeback() == null ? config.writeback() : request.writeback();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 本轮真实执行过的工具轨迹，用于 AGENT 侧写回。 */
    private record ToolTrace(String name, String arguments, String result) {
    }
}