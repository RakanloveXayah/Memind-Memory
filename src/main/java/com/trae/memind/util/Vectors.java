package com.trae.memind.util;

/**
 * 向量工具：pgvector 字面量序列化与本地相似度计算。
 *
 * <p>向量以 pgvector 的文本形式（{@code [0.1,0.2]}）与数据库交换，不依赖额外的驱动依赖；
 * 余弦相似度仅用于洞察节点等小集合的本地排序，大规模记忆的语义召回已下推到 pgvector。
 */
public final class Vectors {

    private Vectors() {
    }

    /** 序列化为 pgvector 字面量，例如 {@code [0.1,0.2]}；空向量返回 null。 */
    public static String toLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder(vector.length * 8 + 2);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }

    /** 解析 pgvector 的文本形式；无法解析时返回 null，保证脏数据不会击穿检索。 */
    public static float[] parse(String literal) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        String body = literal.trim();
        if (body.startsWith("[")) {
            body = body.substring(1, body.length() - 1);
        }
        if (body.isBlank()) {
            return null;
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                vector[i] = Float.parseFloat(parts[i].trim());
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return vector;
    }

    /** 余弦相似度；维度不一致或含 NaN 时返回 0，保证检索不会被脏数据击穿。 */
    public static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0d;
        }
        double dot = 0d;
        double normLeft = 0d;
        double normRight = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            normLeft += (double) left[i] * left[i];
            normRight += (double) right[i] * right[i];
        }
        if (normLeft == 0d || normRight == 0d) {
            return 0d;
        }
        double similarity = dot / (Math.sqrt(normLeft) * Math.sqrt(normRight));
        return Double.isNaN(similarity) ? 0d : similarity;
    }

    public static float[] normalize(float[] vector) {
        double norm = 0d;
        for (float value : vector) {
            norm += (double) value * value;
        }
        if (norm == 0d) {
            return vector;
        }
        double scale = Math.sqrt(norm);
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / scale);
        }
        return result;
    }
}