package com.trae.memind.util;

/**
 * 从 LLM 自由文本输出中抠出 JSON 片段。
 *
 * <p>模型经常在 JSON 外包一层 markdown 代码块或解释性文字，这里做一次容错截取。
 */
public final class JsonExtractor {

    private JsonExtractor() {
    }

    public static String extractArray(String raw) {
        return slice(raw, '[', ']');
    }

    public static String extractObject(String raw) {
        return slice(raw, '{', '}');
    }

    private static String slice(String raw, char open, char close) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf(open);
        int end = raw.lastIndexOf(close);
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }
}