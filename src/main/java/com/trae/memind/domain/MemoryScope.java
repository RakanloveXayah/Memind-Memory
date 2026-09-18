package com.trae.memind.domain;

/**
 * 记忆作用域：文档第三章「双作用域记忆」的实现基础。
 *
 * <p>USER 记忆按 tenantId + userId 隔离（用户画像、偏好、业务背景）；
 * AGENT 记忆按 tenantId 隔离并在租户内共享（工具经验、SQL 模板、数据源特性）。
 */
public enum MemoryScope {

    /** 用户记忆：隔离边界 = tenant_id + user_id */
    USER,

    /** Agent 记忆：隔离边界 = tenant_id（租户内共享） */
    AGENT;

    /**
     * 计算该作用域下的存储命名空间，对应文档「改造点 1：命名空间隔离」。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @return USER → {@code tenant:user}；AGENT → {@code tenant}
     */
    public String namespace(String tenantId, String userId) {
        return this == USER ? tenantId + ":" + userId : tenantId;
    }

    public static MemoryScope from(String raw) {
        if (raw == null || raw.isBlank()) {
            return USER;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return USER;
        }
    }
}