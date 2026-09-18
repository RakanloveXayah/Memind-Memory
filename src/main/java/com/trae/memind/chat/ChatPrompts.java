package com.trae.memind.chat;

import com.trae.memind.retrieval.ContextCompiler;
import org.springframework.stereotype.Component;

/**
 * System Prompt 组装。
 *
 * <p>两件事：把 {@link ContextCompiler} 产出的上下文作为「已知记忆」注入，以及用显式指令
 * 对齐历史记忆里已经沉淀的用户偏好（先结论后细节、简短直接），
 * 否则模型会一边"记得"用户要简短、一边输出长篇分点分析。
 */
@Component
public class ChatPrompts {

    private static final String PERSONA = """
            你是 Memind 数据分析助手，服务于跨境电商场景。
            你有一套长期记忆系统：「已知记忆」是从历史对话与工具轨迹中沉淀出来的、
            关于当前用户与你自己（Agent）的理解，而不是本轮对话内容。""";

    private static final String REQUIREMENTS = """
            ## 回答要求
            1. 先给结论，再给支撑细节。用户明确偏好简短直接的沟通方式，不要寒暄与铺垫。
            2. 涉及具体数字（GMV、订单量、留存率、环比等）时必须调用工具获取，禁止凭空编造。
            3. 需要确认用户以往的要求、口径或偏好时，调用 search_memory 检索记忆。
            4. 时间口径含糊（如「上周」「最近」）时，先用 get_current_time 确认当天日期再换算。
            5. 工具返回的内容直接引用，不要复述调用过程，也不要输出你的思考过程。
            6. 全程用中文回答。""";

    private static final String EMPTY_MEMORY = "（暂无历史记忆，这是与用户的第一次交互）";

    /** 组装 System Prompt。{@code compiledContext} 为空时按"首次交互"处理。 */
    public String system(String compiledContext) {
        String memory = compiledContext == null || compiledContext.isBlank() ? EMPTY_MEMORY : compiledContext;
        return PERSONA + "\n\n## 已知记忆\n" + memory + "\n\n" + REQUIREMENTS;
    }
}