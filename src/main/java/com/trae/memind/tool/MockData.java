package com.trae.memind.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具返回的固定数据集。
 *
 * <p>全部为常量，没有任何业务逻辑：目的是让报告里的"工具调用 → 结果回灌"可以逐字比对，
 * 排除真实数据源波动带来的干扰。数值与 AGENT 记忆里沉淀的 {@code sales_order} 表口径保持一致
 * （channel + order_amount，近 30 天 GMV 128.4 万）。
 */
final class MockData {

    private MockData() {
    }

    /** 渠道维度 GMV：顺序固定，便于断言。 */
    static final List<ChannelGmv> CHANNEL_GMV = List.of(
            new ChannelGmv("amazon", 486200.0d),
            new ChannelGmv("tiktok", 372500.0d),
            new ChannelGmv("shopify", 268400.0d),
            new ChannelGmv("temu", 157200.5d));

    /** 近 30 天 GMV（各渠道之和），供 query_sales 与 calculator 交叉验证。 */
    static final double GMV_30D = 1284300.5d;

    /** 可按 metric 查询的指标口径。 */
    static final Map<String, Double> METRICS = Map.of(
            "gmv", GMV_30D,
            "orders", 8642d,
            "conversion_rate", 0.0312d,
            "uv", 276400d);

    /** 分群留存：每个 cohort 的 retention 从注册当周起算。 */
    static final List<Cohort> COHORTS = List.of(
            new Cohort("2026-W31", List.of(1.0d, 0.42d, 0.28d)),
            new Cohort("2026-W32", List.of(1.0d, 0.38d, 0.25d)));

    /** 周报要点，内容与前面的工具结果互相呼应。 */
    static final List<String> WEEKLY_HIGHLIGHTS = List.of(
            "近 30 天 GMV 128.4 万，环比上周 +12.6%",
            "TikTok 渠道增速最快（周环比 +23.4%），占比升至 29.0%",
            "2026-W31 分群首周留存 42%，较上一分群提升 4 个百分点",
            "gmv_daily 汇总表 22 点后存在同步延迟，日报已改用 sales_order 明细表");

    record ChannelGmv(String channel, double gmv) {
    }

    record Cohort(String week, List<Double> retention) {
    }
}