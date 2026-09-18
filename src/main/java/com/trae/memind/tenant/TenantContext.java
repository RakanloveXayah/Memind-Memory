package com.trae.memind.tenant;

import com.trae.memind.domain.MemoryScope;

/**
 * 租户上下文：承载一次请求的 tenant / user / scope。
 *
 * <p>所有存储与检索操作都必须从上下文取命名空间，禁止由调用方自由传入，
 * 这是「多租户硬隔离」在应用层的强制点。
 */
public final class TenantContext {

    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

    private final String tenantId;
    private final String userId;
    private final MemoryScope scope;

    private TenantContext(String tenantId, String userId, MemoryScope scope) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.scope = scope;
    }

    public static TenantContext of(String tenantId, String userId, MemoryScope scope) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (scope == MemoryScope.USER && (userId == null || userId.isBlank())) {
            throw new IllegalArgumentException("USER 作用域必须提供 userId");
        }
        return new TenantContext(tenantId.trim(),
                userId == null ? "" : userId.trim(),
                scope == null ? MemoryScope.USER : scope);
    }

    public static void set(TenantContext context) {
        HOLDER.set(context);
    }

    /** 供异步 / 批量场景显式绑定上下文。 */
    public static void runWith(TenantContext context, Runnable action) {
        TenantContext previous = HOLDER.get();
        HOLDER.set(context);
        try {
            action.run();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    /** 与 {@link #runWith} 同理，但任务有返回值（如工具执行）。 */
    public static <T> T supplyWith(TenantContext context, java.util.function.Supplier<T> action) {
        TenantContext previous = HOLDER.get();
        HOLDER.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    public static TenantContext current() {
        TenantContext context = HOLDER.get();
        if (context == null) {
            throw new IllegalStateException("缺少租户上下文，请通过 X-Tenant-Id / X-User-Id 请求头提供");
        }
        return context;
    }

    public static void clear() {
        HOLDER.remove();
    }

    public String tenantId() {
        return tenantId;
    }

    public String userId() {
        return userId;
    }

    public MemoryScope scope() {
        return scope;
    }

    /** 当前上下文的存储命名空间。 */
    public String namespace() {
        return scope.namespace(tenantId, userId);
    }

    /** 指定作用域的命名空间，用于一次请求中同时读写 USER 与 AGENT 空间。 */
    public String namespaceOf(MemoryScope target) {
        return target.namespace(tenantId, userId);
    }
}