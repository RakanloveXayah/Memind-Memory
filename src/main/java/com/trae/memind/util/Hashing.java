package com.trae.memind.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 内容指纹工具，用于精确去重。 */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 归一化后取指纹，规避大小写与空白差异造成的重复记忆。 */
    public static String ofContent(String text) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase();
        return sha256(normalized);
    }
}