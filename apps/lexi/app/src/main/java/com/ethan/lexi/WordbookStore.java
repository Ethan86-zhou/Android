package com.ethan.lexi;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 单词本单独存放在本机数据库中；词库升级、离线导出都不依赖联网。 */
public final class WordbookStore extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "lexi-wordbook.db";
    private static final String TABLE = "wordbook";

    public static final class SavedWord {
        public Entry entry = new Entry();
        public String note = "";
        public boolean learned;
        public long savedAt;
        public long updatedAt;
    }

    public WordbookStore(Context context) {
        this(context, DATABASE_NAME);
    }

    /** 独立数据库名供设备测试使用，测试不会覆盖真正的单词本。 */
    public WordbookStore(Context context, String databaseName) {
        super(context.getApplicationContext(), databaseName, null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE wordbook (word_key TEXT PRIMARY KEY NOT NULL, "
                + "dictionary_id INTEGER NOT NULL, word TEXT NOT NULL, phonetic TEXT NOT NULL, "
                + "translation TEXT NOT NULL, definition TEXT NOT NULL, tags TEXT NOT NULL, "
                + "exchange TEXT NOT NULL, frequency INTEGER NOT NULL, note TEXT NOT NULL, "
                + "learned INTEGER NOT NULL CHECK(learned IN (0,1)), "
                + "saved_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE history (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "word_key TEXT UNIQUE NOT NULL, word TEXT NOT NULL, visited_at INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 不做清库式升级，避免未来版本不兼容时悄悄丢失用户笔记。
        throw new IllegalStateException("Unsupported wordbook database version: " + oldVersion);
    }

    /** 首次保存返回 true；重复保存刷新词义快照，保留学习状态、笔记和首次收藏时间。 */
    public synchronized boolean save(Entry entry) {
        if (entry == null) throw new IllegalArgumentException("Entry is required");
        String wordKey = key(entry.word);
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = entryValues(entry);
        values.put("updated_at", System.currentTimeMillis());
        if (contains(wordKey)) {
            db.update(TABLE, values, "word_key=?", new String[]{wordKey});
            return false;
        }
        values.put("word_key", wordKey);
        values.put("note", "");
        values.put("learned", 0);
        values.put("saved_at", System.currentTimeMillis());
        db.insertOrThrow(TABLE, null, values);
        return true;
    }

    public synchronized boolean contains(String word) {
        if (word == null || word.trim().isEmpty()) return false;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM wordbook WHERE word_key=? LIMIT 1", new String[]{key(word)})) {
            return cursor.moveToFirst();
        }
    }

    public synchronized SavedWord get(String word) {
        if (word == null || word.trim().isEmpty()) return null;
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, "word_key=?",
                new String[]{key(word)}, null, null, null, "1")) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    public synchronized boolean remove(String word) {
        if (word == null || word.trim().isEmpty()) return false;
        return getWritableDatabase().delete(TABLE, "word_key=?", new String[]{key(word)}) > 0;
    }

    /** 支持单词、中文释义和笔记的文字筛选，百分号和下划线按文字处理。 */
    public synchronized List<SavedWord> list(String filter) {
        String selection = null;
        String[] args = null;
        if (filter != null && !filter.trim().isEmpty()) {
            String pattern = "%" + filter.trim().replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_") + "%";
            selection = "word LIKE ? ESCAPE '\\' OR translation LIKE ? ESCAPE '\\' "
                    + "OR note LIKE ? ESCAPE '\\'";
            args = new String[]{pattern, pattern, pattern};
        }
        List<SavedWord> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, selection, args,
                null, null, "saved_at DESC, word_key ASC")) {
            while (cursor.moveToNext()) result.add(read(cursor));
        }
        return result;
    }

    public synchronized void setNote(String word, String note) {
        String value = text(note);
        if (value.length() > WordbookExport.MAX_FIELD_CHARS) {
            throw new IllegalArgumentException("笔记过长，请控制在 64000 字以内");
        }
        ContentValues values = new ContentValues();
        values.put("note", value);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(TABLE, values, "word_key=?", new String[]{key(word)});
    }

    public synchronized void setLearned(String word, boolean learned) {
        ContentValues values = new ContentValues();
        values.put("learned", learned ? 1 : 0);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(TABLE, values, "word_key=?", new String[]{key(word)});
    }

    public synchronized int count() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM wordbook", null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    /** 查词历史只保留最近 200 个不同词；重复查词自动移到最前。 */
    public synchronized void recordLookup(String word) {
        if (word == null || word.trim().isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("word_key", key(word));
        values.put("word", word.trim());
        values.put("visited_at", System.currentTimeMillis());
        db.beginTransaction();
        try {
            db.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            db.execSQL("DELETE FROM history WHERE _id NOT IN "
                    + "(SELECT _id FROM history ORDER BY visited_at DESC, _id DESC LIMIT 200)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<String> recentHistory(int limit) {
        List<String> words = new ArrayList<>();
        int boundedLimit = Math.max(0, Math.min(200, limit));
        if (boundedLimit == 0) return words;
        try (Cursor cursor = getReadableDatabase().query("history", new String[]{"word"},
                null, null, null, null, "visited_at DESC, _id DESC", String.valueOf(boundedLimit))) {
            while (cursor.moveToNext()) words.add(cursor.getString(0));
        }
        return words;
    }

    public synchronized void clearHistory() {
        getWritableDatabase().delete("history", null, null);
    }

    /** 已完成整文件校验后，整批恢复在一个事务中完成，失败时不会留下半份备份。 */
    synchronized int importSnapshots(List<SavedWord> snapshots) {
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        db.beginTransaction();
        try {
            for (SavedWord saved : snapshots) {
                String wordKey = key(saved.entry.word);
                ContentValues values = entryValues(saved.entry);
                values.put("word_key", wordKey);
                values.put("note", text(saved.note));
                values.put("learned", saved.learned ? 1 : 0);
                values.put("saved_at", saved.savedAt);
                values.put("updated_at", saved.updatedAt);
                if (contains(wordKey)) {
                    db.update(TABLE, values, "word_key=?", new String[]{wordKey});
                } else {
                    db.insertOrThrow(TABLE, null, values);
                    added++;
                }
            }
            db.setTransactionSuccessful();
            return added;
        } finally {
            db.endTransaction();
        }
    }

    private static ContentValues entryValues(Entry entry) {
        ContentValues values = new ContentValues();
        values.put("dictionary_id", entry.id);
        values.put("word", entry.word.trim());
        values.put("phonetic", text(entry.phonetic));
        values.put("translation", text(entry.translation));
        values.put("definition", text(entry.definition));
        values.put("tags", text(entry.tags));
        values.put("exchange", text(entry.exchange));
        values.put("frequency", entry.frequency);
        return values;
    }

    private static SavedWord read(Cursor cursor) {
        SavedWord saved = new SavedWord();
        saved.entry.id = cursor.getLong(cursor.getColumnIndexOrThrow("dictionary_id"));
        saved.entry.word = cursor.getString(cursor.getColumnIndexOrThrow("word"));
        saved.entry.phonetic = cursor.getString(cursor.getColumnIndexOrThrow("phonetic"));
        saved.entry.translation = cursor.getString(cursor.getColumnIndexOrThrow("translation"));
        saved.entry.definition = cursor.getString(cursor.getColumnIndexOrThrow("definition"));
        saved.entry.tags = cursor.getString(cursor.getColumnIndexOrThrow("tags"));
        saved.entry.exchange = cursor.getString(cursor.getColumnIndexOrThrow("exchange"));
        saved.entry.frequency = cursor.getInt(cursor.getColumnIndexOrThrow("frequency"));
        saved.note = cursor.getString(cursor.getColumnIndexOrThrow("note"));
        saved.learned = cursor.getInt(cursor.getColumnIndexOrThrow("learned")) == 1;
        saved.savedAt = cursor.getLong(cursor.getColumnIndexOrThrow("saved_at"));
        saved.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"));
        return saved;
    }

    static String key(String word) {
        if (word == null || word.trim().isEmpty() || word.trim().length() > 256) {
            throw new IllegalArgumentException("Invalid word");
        }
        return word.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
