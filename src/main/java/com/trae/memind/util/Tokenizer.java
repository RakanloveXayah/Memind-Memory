package com.trae.memind.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量分词器：英文/数字按词切分，CJK 按字符二元组切分。
 *
 * <p>BM25 的关键词召回依赖它；不引入第三方分词器以保持零额外依赖。
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        StringBuilder ascii = new StringBuilder();
        List<Character> cjkBuffer = new ArrayList<>();

        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (isCjk(ch)) {
                flushAscii(ascii, tokens);
                cjkBuffer.add(ch);
            } else if (Character.isLetterOrDigit(ch)) {
                flushCjk(cjkBuffer, tokens);
                ascii.append(Character.toLowerCase(ch));
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjkBuffer, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjkBuffer, tokens);
        return tokens;
    }

    private static void flushAscii(StringBuilder ascii, List<String> tokens) {
        if (ascii.length() > 0) {
            tokens.add(ascii.toString());
            ascii.setLength(0);
        }
    }

    private static void flushCjk(List<Character> buffer, List<String> tokens) {
        if (buffer.isEmpty()) {
            return;
        }
        if (buffer.size() == 1) {
            tokens.add(String.valueOf(buffer.get(0)));
        } else {
            for (int i = 0; i < buffer.size() - 1; i++) {
                tokens.add(new String(new char[]{buffer.get(i), buffer.get(i + 1)}));
            }
        }
        buffer.clear();
    }

    private static boolean isCjk(char ch) {
        return ch >= 0x4E00 && ch <= 0x9FFF;
    }
}