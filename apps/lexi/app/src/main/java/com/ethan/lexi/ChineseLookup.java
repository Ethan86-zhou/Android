package com.ethan.lexi;

import java.util.LinkedHashSet;
import java.util.Set;

/** 与内置词库的汉字倒排索引共用的编码规则；不依赖系统分词或联网服务。 */
final class ChineseLookup {
    static final String TOKEN_FORMAT = "unique-han-codepoint-h-lowercase-hex";
    static final String MEANING_FORMAT = "lower-no-ascii-space-segments-v1";

    private ChineseLookup() { }

    static boolean containsChinese(String value) {
        if (value == null) return false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (isChinese(codePoint)) return true;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    /** ASCII token 避免 SQLite simple tokenizer 对中文整句不切词；空格表示 AND。 */
    static String matchExpression(String query) {
        Set<Integer> characters = new LinkedHashSet<>();
        for (int offset = 0; offset < query.length();) {
            int codePoint = query.codePointAt(offset);
            if (isChinese(codePoint)) characters.add(codePoint);
            offset += Character.charCount(codePoint);
        }
        StringBuilder match = new StringBuilder();
        for (int codePoint : characters) {
            if (match.length() > 0) match.append(' ');
            match.append('h').append(Integer.toHexString(codePoint));
        }
        return match.toString();
    }

    private static boolean isChinese(int codePoint) {
        return codePoint >= 0x3400 && codePoint <= 0x4dbf
                || codePoint >= 0x4e00 && codePoint <= 0x9fff
                || codePoint >= 0xf900 && codePoint <= 0xfaff
                || codePoint >= 0x20000 && codePoint <= 0x323af;
    }

    /** 仅用于完整词义索引；后续 instr 仍核对保留空格、标点的原始查询。 */
    static String meaningKey(String normalizedQuery) {
        return normalizedQuery.replace(" ", "").replace("\r", "");
    }

    static String exactMeaningsSql(String columns) {
        return "SELECT " + columns
                + " FROM chinese_meanings m JOIN entries e ON e.id=m.entry_id"
                + " WHERE m.meaning=? AND instr(lower(e.translation),?)>0"
                + " ORDER BY e.frequency,length(e.translation),length(e.lookup),e.lookup,e.id LIMIT ?";
    }

    static String partialMeaningsSql(String columns) {
        return "SELECT " + columns
                + " FROM chinese_search JOIN entries e ON e.id=chinese_search.docid"
                + " WHERE chinese_search MATCH ? AND instr(lower(e.translation),?)>0"
                + " ORDER BY e.frequency,length(e.translation),length(e.lookup),e.lookup,e.id LIMIT ?";
    }
}
