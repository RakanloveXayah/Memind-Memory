package com.trae.memind.domain;

/** Insight Tree 的三层节点层级：Leaf → Branch → Root。 */
public enum InsightLevel {

    /** 叶子节点：单一语义分组内的事实洞察 */
    LEAF,

    /** 分支节点：多个 Leaf 汇聚出的跨组模式 */
    BRANCH,

    /** 根节点：多个 Branch 汇聚出的跨维度理解 */
    ROOT
}