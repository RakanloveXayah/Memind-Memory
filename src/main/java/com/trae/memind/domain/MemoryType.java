package com.trae.memind.domain;

/**
 * 记忆条目类型（对应文档「抽取流水线」中产出的 Memory Item type）。
 *
 * <p>每个类型绑定唯一的作用域：用户类记忆只能落在 USER 空间，经验类记忆只能落在 AGENT 空间，
 * 从类型层面杜绝两个作用域互相污染。
 */
public enum MemoryType {

    // ---------- USER 作用域 ----------
    PROFILE(MemoryScope.USER),
    PREFERENCE(MemoryScope.USER),
    QUERY_PATTERN(MemoryScope.USER),
    TIME_PREFERENCE(MemoryScope.USER),
    METRIC_PREFERENCE(MemoryScope.USER),
    DIMENSION_PREFERENCE(MemoryScope.USER),
    COMMUNICATION_STYLE(MemoryScope.USER),
    BUSINESS_CONTEXT(MemoryScope.USER),

    // ---------- AGENT 作用域 ----------
    TOOL_USAGE(MemoryScope.AGENT),
    DATA_SOURCE_TRAIT(MemoryScope.AGENT),
    SQL_TEMPLATE(MemoryScope.AGENT),
    BEST_PRACTICE(MemoryScope.AGENT),
    FAILURE_LESSON(MemoryScope.AGENT);

    private final MemoryScope scope;

    MemoryType(MemoryScope scope) {
        this.scope = scope;
    }

    public MemoryScope scope() {
        return scope;
    }

    /** Leaf 节点的语义分组键：同一类型的记忆归入同一个 Leaf。 */
    public String groupKey() {
        return name().toLowerCase();
    }

    /**
     * 把抽取器返回的类型字符串解析为合法类型。
     * 非法或作用域不匹配时回退到该作用域的兜底类型，避免整条记忆丢失。
     */
    public static MemoryType parse(String raw, MemoryScope scope) {
        if (raw != null && !raw.isBlank()) {
            try {
                MemoryType parsed = valueOf(raw.trim().toUpperCase().replace('-', '_'));
                if (parsed.scope == scope) {
                    return parsed;
                }
            } catch (IllegalArgumentException ignored) {
                // 落到下方兜底
            }
        }
        return scope == MemoryScope.USER ? PREFERENCE : BEST_PRACTICE;
    }

    /** 该作用域下全部合法类型，用于注入抽取提示词。 */
    public static java.util.List<MemoryType> of(MemoryScope scope) {
        return java.util.Arrays.stream(values()).filter(t -> t.scope == scope).toList();
    }
}