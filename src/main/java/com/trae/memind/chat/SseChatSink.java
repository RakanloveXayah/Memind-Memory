package com.trae.memind.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 把 {@link ChatSink} 落到 {@link SseEmitter} 上。
 *
 * <p>客户端中途关掉页面是常态，此时 {@code send} 会抛 IOException；这里只把通道标记为已关闭，
 * 不再向上传播——后续的工具调用与记忆写回仍然应该跑完，否则一次误关页面就丢了一轮记忆。
 *
 * <p>另外每 10 秒补一个 SSE 注释帧当心跳。这条链路存在两处长时间零字节的空档：
 * 思考模型先吐 reasoning 前的 4~8 秒静默，以及 {@code done} 之后写回记忆的几十秒。
 * 期间连接上没有任何字节流动，浏览器/中间层会把它当成闲置连接中止，
 * 控制台就报 {@code net::ERR_ABORTED}——心跳是这件事故的标准解法，且注释帧
 * 不会被任何 SSE 解析器当成事件，前端不需要配合改动。
 */
public class SseChatSink implements ChatSink {

    private static final Logger log = LoggerFactory.getLogger(SseChatSink.class);

    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    /** 所有连接共用一个调度线程，避免每个 SSE 请求都新建线程。 */
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "memind-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private final SseEmitter emitter;
    private final ObjectMapper mapper;
    private final ScheduledFuture<?> heartbeat;

    private volatile boolean closed;

    public SseChatSink(SseEmitter emitter, ObjectMapper mapper) {
        this.emitter = emitter;
        this.mapper = mapper;
        this.heartbeat = HEARTBEAT.scheduleAtFixedRate(
                this::ping, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 容器结束这次异步请求（含客户端直接断开）时兜底停掉心跳。
        // 不用 onTimeout / onError：那两个回调槽是单值的，ChatController 里已经占用了，
        // 在这里注册会把它的 complete 回调顶掉。
        emitter.onCompletion(this::cancelHeartbeat);
    }

    @Override
    public void emit(String event, Object payload) {
        if (closed) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.error("序列化 SSE 事件失败 event={}", event, ex);
            return;
        }
        try {
            // 不传 MediaType：SseEmitter 对 CharSequence 默认用 UTF-8 文本写出，中文不会乱码。
            emitter.send(SseEmitter.event().name(event).data(json));
        } catch (IOException | IllegalStateException ex) {
            markClosed("推送事件失败 event=" + event + " 原因=" + ex.getMessage());
        }
    }

    /** 只发注释帧（{@code :keep-alive}），不产生事件，纯粹为了不让连接因为「太久没动静」被掐断。 */
    private void ping() {
        if (closed) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException | IllegalStateException ex) {
            markClosed("心跳发送失败 原因=" + ex.getMessage());
        }
    }

    @Override
    public void complete() {
        if (closed) {
            return;
        }
        markClosed(null);
        try {
            emitter.complete();
        } catch (Exception ex) {
            log.debug("关闭 SSE 连接时异常: {}", ex.getMessage());
        }
    }

    private void markClosed(String reason) {
        closed = true;
        cancelHeartbeat();
        if (reason != null) {
            log.debug("SSE 通道已关闭，停止推送：{}", reason);
        }
    }

    private void cancelHeartbeat() {
        heartbeat.cancel(false);
    }
}