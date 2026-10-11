package com.ethan.lexi;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** 完全本地的英汉词库。首次解压、校验和所有查询均由调用方放到后台线程。 */
public final class DictionaryRepository implements Closeable {
    // 屏幕旋转可能同时存在新旧 Activity；不同 repository 也必须串行安装同一份词库。
    private static final Object INSTALL_LOCK = new Object();
    private static final String COLUMNS = "id,word,phonetic,translation,definition,tags,exchange,frequency";
    private final Context context;
    private SQLiteDatabase database;
    private int entryCount;

    public DictionaryRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 第一次使用从 APK 内置压缩包解压；写入临时文件并验 SHA-256 后原子改名。
     * 中途退出不会把半个数据库当作可用词库。生词本使用另一个数据库，不受影响。
     */
    public synchronized void prepare() throws IOException {
        synchronized (INSTALL_LOCK) {
            prepareLocked();
        }
    }

    private void prepareLocked() throws IOException {
        if (database != null && database.isOpen()) return;
        try {
            JSONObject manifest = readManifest();
            JSONObject dbInfo = manifest.getJSONObject("database");
            String expectedHash = dbInfo.getString("sha256");
            String sourceId = manifest.getString("source_id");
            long expectedBytes = dbInfo.getLong("bytes");
            int expectedCount = manifest.getJSONObject("counts").getInt("entries");
            File target = context.getDatabasePath("dictionary-" + expectedHash.substring(0, 16) + ".db");
            File directory = target.getParentFile();
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
                throw new IOException("无法创建离线词库目录");
            }
            // 正常启动只查数据库身份和数量，不反复解压 76 万条词库。
            if (target.isFile() && target.length() == expectedBytes) {
                try {
                    openAndValidate(target, sourceId, expectedCount);
                    return;
                } catch (SQLiteException | IOException invalidCopy) {
                    close();
                }
            }
            File temporary = new File(directory, target.getName() + ".tmp");
            if (temporary.exists() && !temporary.delete()) throw new IOException("无法清理未完成的词库安装");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                String asset = manifest.getJSONObject("asset").getString("filename");
                long total = 0;
                try (InputStream input = new GZIPInputStream(context.getAssets().open(asset), 131072);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[131072];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        output.write(buffer, 0, size);
                        digest.update(buffer, 0, size);
                        total += size;
                    }
                    output.getFD().sync();
                }
                if (total != expectedBytes || !hex(digest.digest()).equals(expectedHash)) {
                    throw new IOException("内置词库校验未通过，请重新安装此安装包");
                }
                if (target.exists() && !target.delete()) throw new IOException("无法替换损坏的词库副本");
                if (!temporary.renameTo(target)) throw new IOException("无法完成离线词库安装");
                openAndValidate(target, sourceId, expectedCount);
            } finally {
                if (temporary.exists()) temporary.delete();
            }
        } catch (JSONException | NoSuchAlgorithmException | SQLiteException error) {
            close();
            throw new IOException("离线词库准备失败", error);
        }
    }

    private void openAndValidate(File file, String sourceId, int expectedCount) throws IOException {
        SQLiteDatabase opened = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        try {
            if (opened.getVersion() != 1) throw new IOException("离线词库版本不匹配");
            try (Cursor cursor = opened.rawQuery("SELECT value FROM metadata WHERE name='source_id'", null)) {
                if (!cursor.moveToFirst() || !sourceId.equals(cursor.getString(0))) {
                    throw new IOException("离线词库来源不匹配");
                }
            }
            try (Cursor cursor = opened.rawQuery("SELECT value FROM metadata WHERE name='entry_count'", null)) {
                if (!cursor.moveToFirst() || cursor.getInt(0) != expectedCount) {
                    throw new IOException("离线词库条目数不匹配");
                }
            }
            database = opened;
            entryCount = expectedCount;
        } catch (IOException | RuntimeException error) {
            opened.close();
            throw error;
        }
    }

    private JSONObject readManifest() throws IOException, JSONException {
        try (InputStream input = context.getAssets().open("dictionary-source.json");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
    }

    public synchronized int count() {
        requireReady();
        return entryCount;
    }

    public synchronized Entry get(long id) {
        requireReady();
        try (Cursor cursor = database.rawQuery("SELECT " + COLUMNS + " FROM entries WHERE id=?",
                new String[]{Long.toString(id)})) {
            return cursor.moveToFirst() ? entry(cursor) : null;
        }
    }

    /** 忽略大小写、首尾空格并兼容弯引号；不存在时返回 null。 */
    public synchronized Entry exact(String query) {
        requireReady();
        String normalized = normalize(query);
        if (normalized.isEmpty()) return null;
        try (Cursor cursor = database.rawQuery("SELECT " + COLUMNS
                + " FROM entries WHERE lookup=? ORDER BY frequency,id LIMIT 1", new String[]{normalized})) {
            return cursor.moveToFirst() ? entry(cursor) : null;
        }
    }

    /** 精确词条优先，其次词形还原，再按语料词频显示前缀匹配；始终走索引。 */
    public synchronized List<Entry> search(String query, int limit) {
        requireReady();
        String normalized = normalize(query);
        int safeLimit = Math.max(0, Math.min(limit, 100));
        LinkedHashMap<Long, Entry> found = new LinkedHashMap<>();
        if (normalized.isEmpty() || safeLimit == 0) return new ArrayList<>();
        addRows(found, "SELECT " + COLUMNS
                + " FROM entries WHERE lookup=? ORDER BY frequency,id LIMIT ?",
                new String[]{normalized, Integer.toString(safeLimit)}, safeLimit);
        if (found.size() < safeLimit) {
            addRows(found, "SELECT " + prefixedColumns("e")
                    + " FROM aliases a JOIN entries e ON e.id=a.entry_id WHERE a.form=?"
                    + " ORDER BY e.frequency,e.lookup,e.id LIMIT ?",
                    new String[]{normalized, Integer.toString(safeLimit)}, safeLimit);
        }
        if (found.size() < safeLimit) {
            // 先用覆盖索引选出 ID，再读少量释义，避免为短前缀读取几万行长文本。
            addRows(found, "SELECT " + prefixedColumns("e")
                    + " FROM entries e JOIN (SELECT id FROM entries INDEXED BY entries_lookup"
                    + " WHERE lookup>=? AND lookup<? AND lookup!=?"
                    + " ORDER BY frequency,length(lookup),lookup,id LIMIT ?) p ON e.id=p.id"
                    + " ORDER BY e.frequency,length(e.lookup),e.lookup,e.id",
                    new String[]{normalized, normalized + "\uffff", normalized, Integer.toString(safeLimit)}, safeLimit);
        }
        return new ArrayList<>(found.values());
    }

    private void addRows(LinkedHashMap<Long, Entry> result, String sql, String[] arguments, int limit) {
        try (Cursor cursor = database.rawQuery(sql, arguments)) {
            while (cursor.moveToNext() && result.size() < limit) {
                Entry value = entry(cursor);
                result.put(value.id, value);
            }
        }
    }

    private static String prefixedColumns(String prefix) {
        return prefix + "." + COLUMNS.replace(",", "," + prefix + ".");
    }

    private static Entry entry(Cursor cursor) {
        return new Entry(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getInt(7));
    }

    static String normalize(String query) {
        if (query == null) return "";
        String value = query.trim().toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'').replace('\u2018', '\'')
                .replace('\u2013', '-').replace('\u2014', '-');
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    private void requireReady() {
        if (database == null || !database.isOpen()) throw new IllegalStateException("请先在后台准备离线词库");
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    @Override public synchronized void close() {
        if (database != null) database.close();
        database = null;
        entryCount = 0;
    }
}
