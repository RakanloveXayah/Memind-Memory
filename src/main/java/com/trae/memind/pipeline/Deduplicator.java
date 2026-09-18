package com.trae.memind.pipeline;

import com.trae.memind.domain.MemoryItem;
import com.trae.memind.domain.MemoryScope;
import com.trae.memind.store.MemoryStore;
import com.trae.memind.util.Vectors;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 去重器（对应文档写入路径中的「去重（语义相似度 + Hash）」环节）。
 *
 * <p>两道防线：内容指纹做精确去重，向量余弦相似度做语义去重。
 * 语义比较分两段——与本批尚未落库的候选在内存里互相比较，
 * 与已落库的历史记忆则下推给 pgvector 的近邻检索（top-1 即最高相似度）。
 */
@Component
public class Deduplicator {

    /** 语义去重阈值：过高会放过同义复述，过低会误杀细节差异。 */
    private static final double SEMANTIC_THRESHOLD = 0.92d;

    private final MemoryStore store;

    public Deduplicator(MemoryStore store) {
        this.store = store;
    }

    public List<MemoryItem> filter(List<MemoryItem> candidates, String namespace, MemoryScope scope) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> knownHashes = new HashSet<>(store.findContentHashes(namespace, scope));

        List<MemoryItem> kept = new ArrayList<>(candidates.size());
        for (MemoryItem candidate : candidates) {
            if (knownHashes.contains(candidate.contentHash())) {
                continue;
            }
            if (isDuplicateInBatch(candidate, kept) || isDuplicateInStore(candidate, namespace, scope)) {
                continue;
            }
            kept.add(candidate);
            knownHashes.add(candidate.contentHash());
        }
        return kept;
    }

    private boolean isDuplicateInBatch(MemoryItem candidate, List<MemoryItem> kept) {
        if (candidate.embedding() == null) {
            return false;
        }
        for (MemoryItem existing : kept) {
            if (Vectors.cosine(candidate.embedding(), existing.embedding()) >= SEMANTIC_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private boolean isDuplicateInStore(MemoryItem candidate, String namespace, MemoryScope scope) {
        if (candidate.embedding() == null) {
            return false;
        }
        return !store.searchByVector(namespace, scope, candidate.embedding(), 1, SEMANTIC_THRESHOLD).isEmpty();
    }
}