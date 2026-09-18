package com.trae.memind.tenant;

import com.trae.memind.domain.MemoryScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 从请求头解析多租户上下文：
 * {@code X-Tenant-Id} / {@code X-User-Id} / {@code X-Memory-Scope}。
 */
public class TenantInterceptor implements HandlerInterceptor {

    public static final String HEADER_TENANT = "X-Tenant-Id";
    public static final String HEADER_USER = "X-User-Id";
    public static final String HEADER_SCOPE = "X-Memory-Scope";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        TenantContext.set(TenantContext.of(
                request.getHeader(HEADER_TENANT),
                request.getHeader(HEADER_USER),
                MemoryScope.from(request.getHeader(HEADER_SCOPE))));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }
}