package com.trae.memind.chat;

/**
 * 聊天输出的下行通道。
 *
 * <p>把编排逻辑与传输层隔开：{@link ChatService} 只负责"发生什么"，是否走 SSE、
 * 还是被某个用例收集成列表，由实现决定。
 */
public interface ChatSink {

    /** 推送一个命名事件。payload 会被序列化成 JSON。 */
    void emit(String event, Object payload);

    /** 正常结束本轮输出。 */
    void complete();
}