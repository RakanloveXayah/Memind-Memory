package com.trae.memind.chat;

import com.trae.memind.retrieval.ContextCompiler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 无对话模型时的确定性应答。
 *
 * <p>关键点是<b>不抛异常、照常走完事件序列</b>：离线环境下接口契约依然完整，
 * 前端只需要一条渲染路径，离线用例也才能真正断言"事件顺序 + 写回"这些编排行为。
 *
 * <p>应答按固定切片返回，由调用方逐片作为 {@code delta} 推送——这样降级模式与真实模型模式
 * 在传输层上是同一条路径，不会出现"只有真实模型才走流式"的分叉。
 */
@Component
public class ChatFallbackResponder {

    /** @return 依次推送的正文切片 */
    public List<String> compose(String question, ContextCompiler.CompiledContext compiled) {
        List<String> chunks = new ArrayList<>();
        chunks.add("【离线降级】未配置对话模型（OPENAI_API_KEY），本条回答由规则引擎生成。\n\n");
        chunks.add("你说的是：" + question + "\n\n");
        if (compiled != null && (compiled.insightCount() > 0 || compiled.memoryCount() > 0)) {
            chunks.add("从长期记忆里召回了 " + compiled.insightCount() + " 条洞察、"
                    + compiled.memoryCount() + " 条相关记忆：\n" + compiled.text() + "\n\n");
        } else {
            chunks.add("没有检索到与你相关的长期记忆，多聊几轮就会积累起来。\n\n");
        }
        chunks.add("配置 OPENAI_API_KEY 后，这里会换成真实模型的流式回答，并由模型自主决定调用哪些工具。");
        return chunks;
    }
}