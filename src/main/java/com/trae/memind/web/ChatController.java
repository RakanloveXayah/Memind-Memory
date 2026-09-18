package com.trae.memind.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trae.memind.chat.ChatService;
import com.trae.memind.chat.SseChatSink;
import com.trae.memind.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式聊天接入层：真实对话 + 工具调用 + 自动写回记忆。
 *
 * <p>用 {@link SseEmitter} 而不是 WebFlux 的 {@code Flux}：本项目的编排链路全是阻塞式的
 * （JDBC + 阻塞式 HTTP 客户端），套一层响应式只会把阻塞调用塞进事件循环。
 *
 * <p>关键约束：{@code TenantContext} 存在 ThreadLocal 里，而 SSE 的后续推送跑在线程池线程上，
 * 因此必须在<b>请求线程</b>把上下文取出来，再显式带到异步任务里——否则
 * {@code TenantInterceptor} 的 {@code afterCompletion} 一清空，工具与写回就全成了无租户调用。
 */
@RestController
@RequestMapping("/open/v1/chat")
public class ChatController {

    /** 与 spring.mvc.async.request-timeout 保持一致：思考模型首字延迟长，30s 默认值不够用。 */
    private static final long STREAM_TIMEOUT_MS = 180_000L;

    private final ChatService chatService;
    private final ObjectMapper mapper;
    private final TaskExecutor executor;

    public ChatController(ChatService chatService,
                          ObjectMapper mapper,
                          @Qualifier("memindExecutor") TaskExecutor executor) {
        this.chatService = chatService;
        this.mapper = mapper;
        this.executor = executor;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody MemoryApi.ChatRequest request) {
        TenantContext context = TenantContext.current();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        SseChatSink sink = new SseChatSink(emitter, mapper);

        emitter.onTimeout(sink::complete);
        emitter.onError(error -> sink.complete());

        executor.execute(() -> TenantContext.runWith(context, () -> chatService.stream(request, context, sink)));
        return emitter;
    }
}