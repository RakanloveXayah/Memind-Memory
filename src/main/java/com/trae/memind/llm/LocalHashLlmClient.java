package com.trae.memind.llm;

import com.trae.memind.util.Tokenizer;
import com.trae.memind.util.Vectors;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地降级实现：不依赖任何外部服务。
 *
 * <p>对话能力不可用（{@link #chatAvailable()} 返回 false），抽取流水线会自动切换到规则引擎；
 * 向量能力用「分词 → 特征哈希 → L2 归一化」生成确定性向量，保证检索链路可以离线跑通。
 */
public class LocalHashLlmClient implements LlmClient {

    private final int dimension;

    public LocalHashLlmClient(int dimension) {
        this.dimension = Math.max(64, dimension);
    }

    @Override
    public boolean chatAvailable() {
        return false;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        throw new UnsupportedOperationException("未配置对话模型，请设置 OPENAI_API_KEY 与 OPENAI_CHAT_MODEL");
    }

    @Override
    public int embeddingDimension() {
        return dimension;
    }

    @Override
    public boolean embeddingRemote() {
        return false;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(hashEmbed(text));
        }
        return result;
    }

    private float[] hashEmbed(String text) {
        float[] vector = new float[dimension];
        List<String> tokens = Tokenizer.tokenize(text);
        for (String token : tokens) {
            vector[Math.floorMod(token.hashCode(), dimension)] += 1f;
            // 叠加一次字符级特征，缓解二元分词带来的稀疏性
            for (int i = 0; i < token.length(); i++) {
                vector[Math.floorMod(31 * token.charAt(i) + i, dimension)] += 0.5f;
            }
        }
        return Vectors.normalize(vector);
    }

    @Override
    public String provider() {
        return "local-hash";
    }
}