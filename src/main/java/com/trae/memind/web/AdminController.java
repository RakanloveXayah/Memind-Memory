package com.trae.memind.web;

import com.trae.memind.domain.InsightLevel;
import com.trae.memind.domain.InsightNode;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.insight.InsightTreeEngine;
import com.trae.memind.store.MemoryStore;
import com.trae.memind.tenant.TenantContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 运维接口：手动触发整合、查看 Insight Tree 结构，用于验证认知进化效果。 */
@RestController
@RequestMapping("/admin/v1")
public class AdminController {

    private final InsightTreeEngine insightTreeEngine;
    private final MemoryStore store;

    public AdminController(InsightTreeEngine insightTreeEngine, MemoryStore store) {
        this.insightTreeEngine = insightTreeEngine;
        this.store = store;
    }

    /** 手动触发当前命名空间与作用域的整合，无需等待定时任务。 */
    @PostMapping("/consolidate")
    public MemoryApi.ConsolidateResponse consolidate(@RequestParam(name = "scope", required = false) String scope) {
        TenantContext context = TenantContext.current();
        MemoryScope target = scope == null || scope.isBlank() ? context.scope() : MemoryScope.from(scope);
        insightTreeEngine.consolidate(context, target);
        return new MemoryApi.ConsolidateResponse("SUCCESS",
                "已完成 " + context.namespaceOf(target) + " 的 Insight Tree 整合");
    }

    /** 按 Root → Branch → Leaf 顺序返回当前命名空间的认知结构。 */
    @GetMapping("/insight")
    public MemoryApi.InsightTreeResponse insightTree(@RequestParam(name = "scope", required = false) String scope) {
        TenantContext context = TenantContext.current();
        MemoryScope target = scope == null || scope.isBlank() ? context.scope() : MemoryScope.from(scope);
        String namespace = context.namespaceOf(target);
        List<MemoryApi.RetrievedInsightDto> nodes = new ArrayList<>();
        for (InsightLevel level : List.of(InsightLevel.ROOT, InsightLevel.BRANCH, InsightLevel.LEAF)) {
            for (InsightNode node : store.findInsightNodes(namespace, target, level)) {
                nodes.add(new MemoryApi.RetrievedInsightDto(
                        node.id(), node.level().name(), node.scope().name(), node.groupKey(),
                        node.content(), node.confidence(), node.confidence()));
            }
        }
        return new MemoryApi.InsightTreeResponse(namespace, target.name(), nodes);
    }

    /** 列出当前命名空间某个作用域下的全部记忆，用于观察"引擎到底记住了什么"。 */
    @GetMapping("/memories")
    public List<MemoryApi.MemoryItemDto> memories(@RequestParam(name = "scope", required = false) String scope) {
        TenantContext context = TenantContext.current();
        MemoryScope target = scope == null || scope.isBlank() ? context.scope() : MemoryScope.from(scope);
        return store.findMemories(context.namespaceOf(target), target).stream()
                .map(MemoryController::toDto)
                .toList();
    }

    /**
     * 清空当前命名空间，用于重置演示数据。
     *
     * <p>洞察节点必须一起清掉：它们是从记忆里提炼出来的，记忆没了还留着的话，
     * 检索时会继续把"对一批已经不存在的记忆的理解"注入上下文。
     */
    @DeleteMapping("/memories")
    public MemoryApi.ConsolidateResponse clearMemories(@RequestParam(name = "scope", required = false) String scope) {
        TenantContext context = TenantContext.current();
        MemoryScope target = scope == null || scope.isBlank() ? context.scope() : MemoryScope.from(scope);
        String namespace = context.namespaceOf(target);
        int removed = store.deleteMemories(namespace, target);
        for (InsightLevel level : InsightLevel.values()) {
            store.deleteInsightNodes(namespace, target, level);
        }
        return new MemoryApi.ConsolidateResponse("SUCCESS",
                "已清空 " + namespace + " 的 " + removed + " 条记忆与全部洞察节点");
    }
}