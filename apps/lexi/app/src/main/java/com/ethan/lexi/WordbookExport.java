package com.ethan.lexi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** 只读写调用方提供的流；系统文件选择器负责路径，不需要存储权限。 */
public final class WordbookExport {
    public static final int MAX_IMPORT_BYTES = 32 * 1024 * 1024;
    public static final int MAX_IMPORT_RECORDS = 50_000;
    public static final int MAX_FIELD_CHARS = 64_000;
    private static final String FORMAT = "lexi-wordbook";

    private WordbookExport() { }

    /** Excel 直接打开可识别中文；引号、逗号、换行按 RFC 4180 转义。 */
    public static void writeCsv(OutputStream output, List<WordbookStore.SavedWord> words)
            throws IOException {
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        writer.write('\ufeff');
        row(writer, "Word", "Phonetic", "Translation", "Definition", "Tags", "Frequency",
                "Exchange", "Note", "Learned", "SavedAt", "UpdatedAt");
        for (WordbookStore.SavedWord saved : words) {
            Entry e = saved.entry;
            row(writer, e.word, e.phonetic, e.translation, e.definition, e.tags,
                    String.valueOf(e.frequency), e.exchange, saved.note,
                    String.valueOf(saved.learned), date(saved.savedAt), date(saved.updatedAt));
        }
        writer.flush();
    }

    /** Markdown 便于离线阅读；完整无损恢复请使用 JSON 备份。 */
    public static void writeMarkdown(OutputStream output, List<WordbookStore.SavedWord> words)
            throws IOException {
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        writer.write("# 拾词 · 单词本\n\n导出时间：" + date(System.currentTimeMillis())
                + "\n\n共 " + words.size() + " 个单词。\n\n");
        for (WordbookStore.SavedWord saved : words) {
            Entry e = saved.entry;
            writer.write("## " + markdown(e.word) + "\n\n");
            if (!text(e.phonetic).isEmpty()) writer.write("音标：" + markdown(e.phonetic) + "\n\n");
            writer.write(markdown(e.translation) + "\n\n");
            if (!text(e.definition).isEmpty()) writer.write("英文释义：\n\n" + markdown(e.definition) + "\n\n");
            if (!text(e.tags).isEmpty()) writer.write("标签：" + markdown(e.tags) + "\n\n");
            if (!text(e.exchange).isEmpty()) writer.write("词形：" + markdown(e.exchange) + "\n\n");
            writer.write("状态：" + (saved.learned ? "已掌握" : "学习中") + "\n\n");
            if (!text(saved.note).isEmpty()) writer.write("笔记：\n\n" + markdown(saved.note) + "\n\n");
            writer.write("收藏时间：" + date(saved.savedAt) + "\n\n---\n\n");
        }
        writer.flush();
    }

    /** JSON 保留原始文本、学习状态和时间，可跨手机离线恢复。 */
    public static void writeJson(OutputStream output, List<WordbookStore.SavedWord> words)
            throws IOException {
        if (words.size() > MAX_IMPORT_RECORDS) throw new IOException("JSON 备份最多支持 50000 个单词");
        try {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("version", 1);
            root.put("app", "拾词 Lexi");
            root.put("exportedAt", System.currentTimeMillis());
            JSONArray entries = new JSONArray();
            for (WordbookStore.SavedWord saved : words) {
                Entry e = saved.entry;
                JSONObject object = new JSONObject();
                object.put("id", e.id);
                object.put("word", text(e.word));
                object.put("phonetic", text(e.phonetic));
                object.put("translation", text(e.translation));
                object.put("definition", text(e.definition));
                object.put("tags", text(e.tags));
                object.put("exchange", text(e.exchange));
                object.put("frequency", e.frequency);
                object.put("note", text(saved.note));
                object.put("learned", saved.learned);
                object.put("savedAt", saved.savedAt);
                object.put("updatedAt", saved.updatedAt);
                entries.put(object);
            }
            root.put("entries", entries);
            String serialized = root.toString(2);
            if (serialized.getBytes(StandardCharsets.UTF_8).length + 1 > MAX_IMPORT_BYTES) {
                throw new IOException("JSON 备份超过 32 MiB，请先用 CSV 导出或缩短过长笔记");
            }
            Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            writer.write(serialized);
            writer.write('\n');
            writer.flush();
        } catch (JSONException exception) {
            throw new IOException("无法生成单词本备份", exception);
        }
    }

    /** 返回新增词条数；同名词条以备份内容为准，未出现在备份的现有词条保留。 */
    public static int importJson(InputStream input, WordbookStore store) throws IOException {
        String data = readBoundedUtf8(input);
        new StrictJson(data).validate();
        List<WordbookStore.SavedWord> snapshots = new ArrayList<>();
        try {
            JSONTokener tokener = new JSONTokener(data);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0) {
                throw new JSONException("Invalid JSON root or trailing content");
            }
            JSONObject root = (JSONObject) parsed;
            if (!FORMAT.equals(string(root, "format", 64)) || integer(root, "version") != 1) {
                throw new JSONException("Unsupported backup format/version");
            }
            Object entriesValue = root.get("entries");
            if (!(entriesValue instanceof JSONArray)) throw new JSONException("entries must be an array");
            JSONArray entries = (JSONArray) entriesValue;
            if (entries.length() > MAX_IMPORT_RECORDS) throw new JSONException("Too many records");
            // 先校验所有记录，再开始写数据库，坏文件不会改动原有单词本。
            for (int i = 0; i < entries.length(); i++) {
                Object record = entries.get(i);
                if (!(record instanceof JSONObject)) throw new JSONException("Invalid record at " + i);
                JSONObject object = (JSONObject) record;
                WordbookStore.SavedWord saved = new WordbookStore.SavedWord();
                saved.entry.word = string(object, "word", 256);
                WordbookStore.key(saved.entry.word);
                saved.entry.id = nonNegativeLong(object, "id");
                saved.entry.phonetic = string(object, "phonetic", MAX_FIELD_CHARS);
                saved.entry.translation = string(object, "translation", MAX_FIELD_CHARS);
                saved.entry.definition = string(object, "definition", MAX_FIELD_CHARS);
                saved.entry.tags = string(object, "tags", MAX_FIELD_CHARS);
                saved.entry.exchange = string(object, "exchange", MAX_FIELD_CHARS);
                saved.entry.frequency = integer(object, "frequency");
                saved.note = string(object, "note", MAX_FIELD_CHARS);
                Object learned = object.get("learned");
                if (!(learned instanceof Boolean)) throw new JSONException("learned must be boolean");
                saved.learned = (Boolean) learned;
                saved.savedAt = nonNegativeLong(object, "savedAt");
                saved.updatedAt = nonNegativeLong(object, "updatedAt");
                snapshots.add(saved);
            }
        } catch (JSONException | IllegalArgumentException exception) {
            throw new IOException("备份文件格式不正确或超过容量限制，未导入任何词条", exception);
        }
        return store.importSnapshots(snapshots);
    }

    private static String readBoundedUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (buffer.size() > MAX_IMPORT_BYTES - count) {
                throw new IOException("备份文件超过 32 MiB，未导入任何词条");
            }
            buffer.write(chunk, 0, count);
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buffer.toByteArray())).toString();
            return value.startsWith("\ufeff") ? value.substring(1) : value;
        } catch (CharacterCodingException exception) {
            throw new IOException("备份文件必须使用 UTF-8 编码", exception);
        }
    }

    /** 原生 JSONTokener 容忍注释和未加引号的键；备份只接受标准 JSON 并限制嵌套。 */
    private static final class StrictJson {
        private final String data;
        private int position;

        StrictJson(String data) { this.data = data; }

        void validate() throws IOException {
            value(0);
            space();
            if (position != data.length()) invalid();
        }

        private void value(int depth) throws IOException {
            if (depth > 32) throw new IOException("备份文件嵌套过深，未导入任何词条");
            space();
            if (position >= data.length()) invalid();
            char next = data.charAt(position);
            if (next == '{') {
                position++;
                if (take('}')) return;
                do {
                    space();
                    string();
                    if (!take(':')) invalid();
                    value(depth + 1);
                } while (take(','));
                if (!take('}')) invalid();
            } else if (next == '[') {
                position++;
                if (take(']')) return;
                do { value(depth + 1); } while (take(','));
                if (!take(']')) invalid();
            } else if (next == '"') {
                string();
            } else if (next == 't') {
                literal("true");
            } else if (next == 'f') {
                literal("false");
            } else if (next == 'n') {
                literal("null");
            } else {
                number();
            }
        }

        private void string() throws IOException {
            if (position >= data.length() || data.charAt(position++) != '"') invalid();
            while (position < data.length()) {
                char next = data.charAt(position++);
                if (next == '"') return;
                if (next < 0x20) invalid();
                if (next == '\\') {
                    if (position >= data.length()) invalid();
                    char escaped = data.charAt(position++);
                    if (escaped == 'u') {
                        for (int i = 0; i < 4; i++) {
                            if (position >= data.length()
                                    || Character.digit(data.charAt(position++), 16) < 0) invalid();
                        }
                    } else if ("\"\\/bfnrt".indexOf(escaped) < 0) {
                        invalid();
                    }
                }
            }
            invalid();
        }

        private void number() throws IOException {
            if (position < data.length() && data.charAt(position) == '-') position++;
            if (position >= data.length()) invalid();
            if (data.charAt(position) == '0') position++;
            else {
                if (data.charAt(position) < '1' || data.charAt(position) > '9') invalid();
                digits();
            }
            if (position < data.length() && data.charAt(position) == '.') {
                position++;
                int start = position;
                digits();
                if (start == position) invalid();
            }
            if (position < data.length() && (data.charAt(position) == 'e' || data.charAt(position) == 'E')) {
                position++;
                if (position < data.length() && (data.charAt(position) == '+' || data.charAt(position) == '-')) position++;
                int start = position;
                digits();
                if (start == position) invalid();
            }
        }

        private void digits() {
            while (position < data.length() && data.charAt(position) >= '0' && data.charAt(position) <= '9') position++;
        }

        private void literal(String expected) throws IOException {
            if (!data.startsWith(expected, position)) invalid();
            position += expected.length();
        }

        private boolean take(char expected) {
            space();
            if (position < data.length() && data.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void space() {
            while (position < data.length() && " \t\r\n".indexOf(data.charAt(position)) >= 0) position++;
        }

        private void invalid() throws IOException {
            throw new IOException("备份文件不是有效 JSON，未导入任何词条");
        }
    }

    private static String string(JSONObject object, String name, int maximum) throws JSONException {
        Object value = object.get(name);
        if (!(value instanceof String) || ((String) value).length() > maximum) {
            throw new JSONException("Invalid text field: " + name);
        }
        return (String) value;
    }

    private static long nonNegativeLong(JSONObject object, String name) throws JSONException {
        Object value = object.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new JSONException("Invalid integer field: " + name);
        }
        long result = ((Number) value).longValue();
        if (result < 0) throw new JSONException("Negative integer field: " + name);
        return result;
    }

    private static int integer(JSONObject object, String name) throws JSONException {
        long value = nonNegativeLong(object, name);
        if (value > Integer.MAX_VALUE) throw new JSONException("Integer field too large: " + name);
        return (int) value;
    }

    private static void row(Writer writer, String... fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) writer.write(',');
            writer.write('"');
            writer.write(csvSafe(fields[i]).replace("\"", "\"\""));
            writer.write('"');
        }
        writer.write("\r\n");
    }

    private static String csvSafe(String value) {
        String safe = text(value);
        // 表格软件可能把 =、+、-、@ 开头的文本执行成公式；前置单引号保留文本显示。
        // JSON 备份不添加该保护字符，负责无损备份和恢复。
        int first = 0;
        while (first < safe.length() && Character.isWhitespace(safe.charAt(first))) first++;
        if (!safe.isEmpty() && (safe.charAt(0) == '\t' || safe.charAt(0) == '\r' || safe.charAt(0) == '\n'
                || (first < safe.length() && "=+-@".indexOf(safe.charAt(first)) >= 0))) {
            return "'" + safe;
        }
        return safe;
    }

    private static String markdown(String value) {
        String result = text(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        for (String mark : new String[]{"\\", "`", "*", "_", "{", "}", "[", "]", "(", ")", "#", "+", "-", "!", "|"}) {
            result = result.replace(mark, "\\" + mark);
        }
        return result.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "  \n");
    }

    private static String date(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
