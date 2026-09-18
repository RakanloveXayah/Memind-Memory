package com.trae.memind.retrieval;

import java.util.List;

/**
 * 查询分析结果（对应文档 5.2 检索路径 Step 1：提取查询中的实体、意图、时间信号）。
 *
 * @param keywords    关键词，用于 BM25 通道与可解释性输出
 * @param timeSignals 时间信号，例如「最近30天」「上周」
 * @param entities    实体（英文标识、数字、引号内的短语）
 */
public record QuerySignals(List<String> keywords, List<String> timeSignals, List<String> entities) {
}