package com.trae.memind.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 一个可被模型调用的能力：既包含给模型看的声明（name / description / parameters），
 * 也包含服务端的真实实现（executor）。
 *
 * @param name        函数名，必须与 OpenAI tools schema 中的 name 一致
 * @param description 给模型看的能力说明，直接决定模型会不会选它
 * @param parameters  JSON Schema 形式的入参声明
 * @param type        TOOL（原子工具）或 SKILL（多步编排能力）
 * @param executor    实际执行体，入参是模型给出的 JSON，返回可直接回灌给模型的文本
 */
public record ToolDefinition(
        String name,
        String description,
        ObjectNode parameters,
        ToolType type,
        ToolExecutor executor) {

    public enum ToolType {
        /** 原子工具：一次调用完成一件事。 */
        TOOL,
        /** 技能：内部编排多步（如聚合、分组、成文），对外仍只暴露一个函数名。 */
        SKILL
    }

    /** 工具执行体。返回 JSON 文本最便于模型理解，失败时返回 {@code {"error":"..."}}。 */
    @FunctionalInterface
    public interface ToolExecutor {
        String execute(ObjectNode arguments);
    }
}