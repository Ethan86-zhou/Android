package com.ethan.lexi;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 在三星真机上验证数据库及导出格式，所有数据都写入独立临时数据库。 */
public final class WordbookChecks {
    private int checks;

    private WordbookChecks() { }

    public static int run(Context context) throws Exception {
        return new WordbookChecks().runChecks(context);
    }

    private int runChecks(Context context) throws Exception {
        String originalName = "lexi-wordbook-checks-" + System.nanoTime() + ".db";
        String restoreName = "lexi-wordbook-restore-checks-" + System.nanoTime() + ".db";
        WordbookStore store = new WordbookStore(context, originalName);
        WordbookStore restored = new WordbookStore(context, restoreName);
        try {
            equal(0, store.count(), "empty database");
            check(!store.contains(null) && !store.contains(" "), "empty lookup is absent");
            check(store.get("missing") == null, "missing saved word");
            Entry apple = entry("apple", "n. 苹果,\"果实\"\n第二行中文 🍎");
            apple.id = 42;
            apple.phonetic = "ˈæpəl";
            apple.definition = "A fruit, usually \"round\".\r\nEnglish line two.";
            apple.tags = "cet4 cet6";
            apple.exchange = "s:apples";
            apple.frequency = 123;
            check(store.save(apple), "first save inserted");
            check(store.contains("  APPLE  "), "word key case and space normalization");
            long savedAt = store.get("apple").savedAt;
            store.setNote("apple", "记忆,\"苹果\"\n笔记 🍏");
            store.setLearned("APPLE", true);
            apple.word = "APPLE";
            check(!store.save(apple), "duplicate save reports existing");
            equal(1, store.count(), "duplicate save has one row");
            WordbookStore.SavedWord beforeClose = store.get("apple");
            check(beforeClose.learned, "duplicate preserves learned state");
            equal("记忆,\"苹果\"\n笔记 🍏", beforeClose.note, "duplicate preserves note");
            equal(savedAt, beforeClose.savedAt, "duplicate preserves first save time");
            store.close();
            store = new WordbookStore(context, originalName);
            equal(1, store.count(), "saved row survives database reopen");
            equal(beforeClose.note, store.get("apple").note, "note survives reopen");
            check(store.get("apple").learned, "learned state survives reopen");
            equal("ˈæpəl", store.get("apple").entry.phonetic, "phonetic survives reopen");
            equal(123, store.get("apple").entry.frequency, "frequency survives reopen");
            equal(42L, store.get("apple").entry.id, "dictionary id snapshot survives reopen");
            equal(1, store.list("苹果").size(), "Chinese translation filter");
            equal(1, store.list("记忆").size(), "note filter");
            equal(1, store.list("app").size(), "case insensitive word filter");
            equal(0, store.list("absent").size(), "nonmatching filter");
            store.get("apple").note = "not persisted";
            equal(beforeClose.note, store.get("apple").note, "returned snapshot is detached");

            Entry special = entry("literal_%", "文字下划线和百分号");
            store.save(special);
            equal(1, store.list("%").size(), "LIKE percent remains literal");
            equal(1, store.list("_").size(), "LIKE underscore remains literal");
            equal(0, store.list("x' OR 1=1 --").size(), "filter is parameterized");
            check(store.remove("literal_%"), "remove saved word");
            check(!store.remove("literal_%"), "remove missing word reports false");
            equal(1, store.count(), "remove changes count");
            check(!store.contains("literal_%"), "removed word absent");
            boolean rejected = false;
            try { store.save(entry("  ", "bad")); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && store.count() == 1, "blank word does not mutate database");

            store.recordLookup("apple");
            store.recordLookup("banana");
            store.recordLookup("APPLE");
            equal(2, store.recentHistory(200).size(), "history deduplicates same word");
            equal("APPLE", store.recentHistory(1).get(0), "repeated lookup is most recent");
            equal(0, store.recentHistory(-1).size(), "negative history limit is bounded");
            for (int i = 0; i < 205; i++) store.recordLookup("history-" + i);
            equal(200, store.recentHistory(500).size(), "history capped to 200");
            equal("history-204", store.recentHistory(1).get(0), "history deterministic newest first");
            check(!store.recentHistory(200).contains("history-0"), "history prunes oldest");
            store.clearHistory();
            equal(0, store.recentHistory(200).size(), "history clear");

            Entry formula = entry("=1+1", "  =HYPERLINK(\"file:test\")");
            formula.definition = "+SUM(1,2)";
            formula.tags = "-123";
            formula.exchange = "@SUM(1,2)";
            store.save(formula);
            store.setNote(formula.word, "\t=cmd\n中文");
            ByteArrayOutputStream csvBytes = new ByteArrayOutputStream();
            WordbookExport.writeCsv(csvBytes, store.list(null));
            byte[] csvRaw = csvBytes.toByteArray();
            check(csvRaw[0] == (byte) 0xef && csvRaw[1] == (byte) 0xbb
                    && csvRaw[2] == (byte) 0xbf, "CSV UTF-8 BOM");
            String csv = new String(csvRaw, StandardCharsets.UTF_8);
            check(csv.endsWith("\r\n"), "CSV CRLF record terminator");
            List<List<String>> rows = parseCsv(csv.substring(1));
            equal(3, rows.size(), "CSV header and two records");
            equal(11, rows.get(0).size(), "CSV column count");
            equal("Word", rows.get(0).get(0), "CSV header");
            List<String> appleRow = findCsvWord(rows, "APPLE");
            equal(apple.translation, appleRow.get(2), "CSV Chinese comma quote LF roundtrip");
            equal(apple.definition, appleRow.get(3), "CSV CRLF quoted content roundtrip");
            equal(beforeClose.note, appleRow.get(7), "CSV Unicode notes roundtrip");
            equal("true", appleRow.get(8), "CSV learned value");
            check(appleRow.get(9).endsWith("Z"), "CSV UTC portable timestamp");
            List<String> formulaRow = findCsvWord(rows, "'=1+1");
            equal("'  =HYPERLINK(\"file:test\")", formulaRow.get(2), "CSV formula after whitespace guarded");
            equal("'+SUM(1,2)", formulaRow.get(3), "CSV plus formula guarded");
            equal("'-123", formulaRow.get(4), "CSV minus formula guarded");
            equal("'@SUM(1,2)", formulaRow.get(6), "CSV at formula guarded");
            equal("'\t=cmd\n中文", formulaRow.get(7), "CSV control prefix guarded");

            store.setNote("apple", "<script>alert('x')</script> [链接](evil) # 标题");
            ByteArrayOutputStream mdBytes = new ByteArrayOutputStream();
            WordbookExport.writeMarkdown(mdBytes, store.list(null));
            String markdown = mdBytes.toString("UTF-8");
            check(markdown.contains("共 2 个单词"), "Markdown count");
            check(markdown.contains("状态：已掌握"), "Markdown learned state");
            check(markdown.contains("&lt;script&gt;"), "Markdown HTML escaped");
            check(markdown.contains("\\[链接\\]\\(evil\\) \\#"), "Markdown markup escaped");
            check(markdown.contains("苹果"), "Markdown Chinese preserved");
            store.setNote("apple", beforeClose.note);

            ByteArrayOutputStream jsonBytes = new ByteArrayOutputStream();
            WordbookExport.writeJson(jsonBytes, store.list(null));
            byte[] backup = jsonBytes.toByteArray();
            equal(2, WordbookExport.importJson(new ByteArrayInputStream(backup), restored), "JSON first import count");
            equal(2, restored.count(), "JSON restore row count");
            for (WordbookStore.SavedWord original : store.list(null)) {
                sameSnapshot(original, restored.get(original.entry.word));
            }
            equal(0, WordbookExport.importJson(new ByteArrayInputStream(backup), restored), "JSON repeat import idempotent count");
            equal(2, restored.count(), "JSON repeat import does not duplicate");
            byte[] bomBackup = ("\ufeff" + new String(backup, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            equal(0, WordbookExport.importJson(new ByteArrayInputStream(bomBackup), restored), "UTF-8 BOM backup supported");
            restored.save(entry("keep-me", "保留原有单词"));
            restored.setNote("apple", "obsolete");
            restored.setLearned("apple", false);
            equal(0, WordbookExport.importJson(new ByteArrayInputStream(backup), restored), "JSON merge replaces existing only");
            equal(3, restored.count(), "JSON merge retains unrelated entries");
            equal(beforeClose.note, restored.get("apple").note, "JSON merge restores notes");
            check(restored.get("apple").learned, "JSON merge restores learned");

            String backupText = new String(backup, StandardCharsets.UTF_8);
            JSONObject malformed = new JSONObject(backupText);
            JSONArray records = malformed.getJSONArray("entries");
            records.getJSONObject(0).put("word", "should-not-be-added");
            records.getJSONObject(1).put("learned", "false");
            rejectImport(malformed.toString().getBytes(StandardCharsets.UTF_8), restored,
                    "malformed later record rejects complete import");
            check(!restored.contains("should-not-be-added"), "invalid import has no partial insertion");
            rejectImport((backupText + " trailing").getBytes(StandardCharsets.UTF_8), restored, "trailing JSON rejected");
            rejectImport(backupText.replace("\"version\": 1", "\"version\": 2")
                    .getBytes(StandardCharsets.UTF_8), restored, "unsupported version rejected");
            rejectImport(new byte[]{(byte) 0xc3, 0x28}, restored, "invalid UTF-8 rejected");
            rejectImport("[]".getBytes(StandardCharsets.UTF_8), restored, "nonobject root rejected");
            rejectImport("{/*comment*/\"format\":\"lexi-wordbook\"}".getBytes(StandardCharsets.UTF_8),
                    restored, "nonstandard JSON comments rejected");
            rejectImport("{format:\"lexi-wordbook\"}".getBytes(StandardCharsets.UTF_8),
                    restored, "unquoted JSON key rejected");
            rejectImport("{'format':'lexi-wordbook'}".getBytes(StandardCharsets.UTF_8),
                    restored, "single quoted JSON rejected");
            rejectImport("{\"x\":[1,]}".getBytes(StandardCharsets.UTF_8), restored, "trailing JSON comma rejected");
            rejectImport("{\"x\":01}".getBytes(StandardCharsets.UTF_8), restored, "leading zero JSON number rejected");
            rejectImport((repeat('[', 1000) + repeat(']', 1000)).getBytes(StandardCharsets.UTF_8),
                    restored, "deep nested JSON rejected without recursive stack overflow");
            JSONObject overlong = new JSONObject(backupText);
            overlong.getJSONArray("entries").getJSONObject(0).put("note", repeat('a', WordbookExport.MAX_FIELD_CHARS + 1));
            rejectImport(overlong.toString().getBytes(StandardCharsets.UTF_8), restored, "overlong note rejected");
            JSONObject badInteger = new JSONObject(backupText);
            badInteger.getJSONArray("entries").getJSONObject(0).put("savedAt", -1);
            rejectImport(badInteger.toString().getBytes(StandardCharsets.UTF_8), restored, "negative timestamp rejected");
            badInteger.getJSONArray("entries").getJSONObject(0).put("savedAt", 1.5);
            rejectImport(badInteger.toString().getBytes(StandardCharsets.UTF_8), restored, "fractional timestamp rejected");
            JSONObject missing = new JSONObject(backupText);
            missing.getJSONArray("entries").getJSONObject(0).remove("translation");
            rejectImport(missing.toString().getBytes(StandardCharsets.UTF_8), restored, "missing snapshot field rejected");
            JSONArray tooMany = new JSONArray();
            for (int i = 0; i <= WordbookExport.MAX_IMPORT_RECORDS; i++) tooMany.put(new JSONObject());
            JSONObject largeRecords = new JSONObject();
            largeRecords.put("format", "lexi-wordbook");
            largeRecords.put("version", 1);
            largeRecords.put("entries", tooMany);
            rejectImport(largeRecords.toString().getBytes(StandardCharsets.UTF_8), restored, "record limit rejected");
            boolean sizeRejected = false;
            try {
                WordbookExport.importJson(new InputStream() {
                    private int remaining = WordbookExport.MAX_IMPORT_BYTES + 1;
                    @Override public int read() { return remaining-- > 0 ? ' ' : -1; }
                    @Override public int read(byte[] bytes, int offset, int length) {
                        if (remaining <= 0) return -1;
                        int count = Math.min(remaining, length);
                        java.util.Arrays.fill(bytes, offset, offset + count, (byte) ' ');
                        remaining -= count;
                        return count;
                    }
                }, restored);
            } catch (IOException expected) {
                sizeRejected = expected.getMessage().contains("32 MiB");
            }
            check(sizeRejected, "input byte size rejected before parsing");
            equal(3, restored.count(), "all invalid imports preserve database");
            check(restored.get("apple").learned, "invalid imports preserve learned state");
            return checks;
        } finally {
            store.close();
            restored.close();
            context.deleteDatabase(originalName);
            context.deleteDatabase(restoreName);
        }
    }

    private void sameSnapshot(WordbookStore.SavedWord expected, WordbookStore.SavedWord actual) {
        check(actual != null, "restored snapshot exists: " + expected.entry.word);
        equal(expected.entry.id, actual.entry.id, "JSON id");
        equal(expected.entry.word, actual.entry.word, "JSON word");
        equal(expected.entry.phonetic, actual.entry.phonetic, "JSON phonetic");
        equal(expected.entry.translation, actual.entry.translation, "JSON translation");
        equal(expected.entry.definition, actual.entry.definition, "JSON definition");
        equal(expected.entry.tags, actual.entry.tags, "JSON tags");
        equal(expected.entry.exchange, actual.entry.exchange, "JSON exchange");
        equal(expected.entry.frequency, actual.entry.frequency, "JSON frequency");
        equal(expected.note, actual.note, "JSON exact unguarded note");
        check(expected.learned == actual.learned, "JSON learned");
        equal(expected.savedAt, actual.savedAt, "JSON savedAt");
        equal(expected.updatedAt, actual.updatedAt, "JSON updatedAt");
    }

    private void rejectImport(byte[] data, WordbookStore store, String description) throws Exception {
        int before = store.count();
        boolean rejected = false;
        try { WordbookExport.importJson(new ByteArrayInputStream(data), store); }
        catch (IOException expected) { rejected = true; }
        check(rejected, description);
        equal(before, store.count(), description + " preserves count");
    }

    private static Entry entry(String word, String translation) {
        Entry entry = new Entry();
        entry.word = word;
        entry.translation = translation;
        return entry;
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }

    /** 独立 CSV 解析器验证实际导出单元格内容，避免只对生成文本做片段匹配。 */
    private static List<List<String>> parseCsv(String source) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (value == '"') {
                if (quoted && i + 1 < source.length() && source.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (value == ',' && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (value == '\r' && !quoted && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                i++;
            } else {
                cell.append(value);
            }
        }
        if (quoted || cell.length() != 0 || !row.isEmpty()) throw new AssertionError("Malformed CSV output");
        return rows;
    }

    private static List<String> findCsvWord(List<List<String>> rows, String word) {
        for (List<String> row : rows) if (word.equals(row.get(0))) return row;
        throw new AssertionError("CSV word missing: " + word);
    }

    private void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + ": expected " + expected + ", actual " + actual);
    }
}
