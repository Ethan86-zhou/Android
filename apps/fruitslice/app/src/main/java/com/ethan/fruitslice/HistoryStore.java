package com.ethan.fruitslice;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Device-local results and the last paused round, written together as one payload. */
public final class HistoryStore {
    private static final String PREFERENCES = "fruit_slice_history";
    private static final String PAYLOAD = "payload_v1";
    private static final int LIMIT = 100;
    private static final int MAX_PAYLOAD_LENGTH = 524288;
    private final SharedPreferences preferences;
    private final ArrayList<Record> history = new ArrayList<>();
    private Record checkpoint;
    private int best;

    public static final class Record {
        public String id;
        public long timestamp;
        public int score;
        public int sliced;
        public int maxCombo;
        public int misses;
        public int bombs;
        public int switches;
        public float duration;
        public String reason;
    }

    public HistoryStore(Context context) {
        Context application = context.getApplicationContext();
        preferences = (application == null ? context : application)
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        load();
    }

    /** Returns independent copies; callers cannot mutate saved history accidentally. */
    public synchronized List<Record> records() {
        ArrayList<Record> result = new ArrayList<>(history.size());
        for (Record record : history) result.add(copy(record));
        return result;
    }

    public synchronized int bestScore() {
        return best;
    }

    /** Make this once when a round starts, then update its fields for checkpoints. */
    public Record draft(int score, int sliced, int maxCombo, int misses, int bombs,
            int switches, float duration, String reason) {
        Record record = new Record();
        record.id = UUID.randomUUID().toString();
        record.timestamp = System.currentTimeMillis();
        record.score = score;
        record.sliced = sliced;
        record.maxCombo = maxCombo;
        record.misses = misses;
        record.bombs = bombs;
        record.switches = switches;
        record.duration = duration;
        record.reason = reason;
        return normalize(record);
    }

    /** A repeated save for the same round updates it instead of adding another result. */
    public synchronized void save(Record record) {
        if (record == null) return;
        ensureIdentity(record);
        Record saved = normalize(record);
        for (int i = history.size() - 1; i >= 0; i--) {
            if (saved.id.equals(history.get(i).id)) history.remove(i);
        }
        history.add(saved);
        best = Math.max(best, saved.score);
        trimAndSort();
        if (checkpoint != null && saved.id.equals(checkpoint.id)) checkpoint = null;
        persist();
    }

    /** Synchronous small write for Activity.onPause, including a zero-score round. */
    public synchronized void checkpoint(Record record) {
        if (record == null) return;
        ensureIdentity(record);
        for (Record saved : history) {
            if (saved.id.equals(record.id)) return;
        }
        checkpoint = normalize(record);
        persist();
    }

    public synchronized void clearCheckpoint() {
        if (checkpoint == null) return;
        checkpoint = null;
        persist();
    }

    public synchronized Record getCheckpoint() {
        return checkpoint == null ? null : copy(checkpoint);
    }

    private void load() {
        String raw;
        try {
            raw = preferences.getString(PAYLOAD, null);
        } catch (ClassCastException ignored) {
            return;
        }
        if (raw == null || raw.length() > MAX_PAYLOAD_LENGTH) return;
        try {
            JSONObject payload = new JSONObject(raw);
            best = Math.max(0, payload.optInt("best", 0));
            JSONArray records = payload.optJSONArray("records");
            Set<String> ids = new HashSet<>();
            if (records != null) {
                for (int i = 0; i < records.length(); i++) {
                    Record record = decode(records.optJSONObject(i));
                    if (record == null) continue;
                    best = Math.max(best, record.score);
                    if (ids.add(record.id)) history.add(record);
                }
            }
            trimAndSort();
            checkpoint = decode(payload.optJSONObject("checkpoint"));
            if (checkpoint != null && ids.contains(checkpoint.id)) checkpoint = null;
        } catch (JSONException ignored) {
            // A malformed payload must never prevent the game from opening.
        }
    }

    private void trimAndSort() {
        Collections.sort(history, new Comparator<Record>() {
            @Override public int compare(Record first, Record second) {
                int time = Long.compare(second.timestamp, first.timestamp);
                return time != 0 ? time : first.id.compareTo(second.id);
            }
        });
        while (history.size() > LIMIT) history.remove(history.size() - 1);
    }

    @android.annotation.SuppressLint("ApplySharedPref") // A small synchronous checkpoint must survive process death after onPause.
    private void persist() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("version", 1);
            payload.put("best", best);
            JSONArray records = new JSONArray();
            for (Record record : history) records.put(encode(record));
            payload.put("records", records);
            payload.put("checkpoint", checkpoint == null ? JSONObject.NULL : encode(checkpoint));
            // One commit prevents history, best score, and recovery state from disagreeing.
            preferences.edit().putString(PAYLOAD, payload.toString()).commit();
        } catch (JSONException ignored) {
            // normalize() guarantees finite numbers and non-null identity fields.
        }
    }

    private static JSONObject encode(Record record) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", record.id);
        json.put("timestamp", record.timestamp);
        json.put("score", record.score);
        json.put("sliced", record.sliced);
        json.put("maxCombo", record.maxCombo);
        json.put("misses", record.misses);
        json.put("bombs", record.bombs);
        json.put("switches", record.switches);
        json.put("duration", record.duration);
        json.put("reason", record.reason);
        return json;
    }

    private static Record decode(JSONObject json) {
        if (json == null) return null;
        String id = json.optString("id", "");
        long timestamp = json.optLong("timestamp", 0L);
        // Missing identity denotes a damaged entry, not a new session on every launch.
        if (id.trim().isEmpty() || id.length() > 128 || timestamp <= 0) return null;
        Record record = new Record();
        record.id = id;
        record.timestamp = timestamp;
        record.score = json.optInt("score", 0);
        record.sliced = json.optInt("sliced", 0);
        record.maxCombo = json.optInt("maxCombo", 0);
        record.misses = json.optInt("misses", 0);
        record.bombs = json.optInt("bombs", 0);
        record.switches = json.optInt("switches", 0);
        record.duration = (float) json.optDouble("duration", 0.0);
        record.reason = json.optString("reason", "finished");
        return normalize(record);
    }

    private static void ensureIdentity(Record record) {
        if (record.id == null || record.id.trim().isEmpty() || record.id.length() > 128) {
            record.id = UUID.randomUUID().toString();
        }
        if (record.timestamp <= 0) record.timestamp = System.currentTimeMillis();
    }

    private static Record normalize(Record record) {
        Record normalized = copy(record);
        normalized.score = Math.max(0, normalized.score);
        normalized.sliced = Math.max(0, normalized.sliced);
        normalized.maxCombo = Math.max(0, normalized.maxCombo);
        normalized.misses = Math.max(0, normalized.misses);
        normalized.bombs = Math.max(0, normalized.bombs);
        normalized.switches = Math.max(0, normalized.switches);
        if (Float.isNaN(normalized.duration) || Float.isInfinite(normalized.duration)) {
            normalized.duration = 0f;
        }
        normalized.duration = Math.max(0f, normalized.duration);
        if (normalized.reason == null || normalized.reason.trim().isEmpty()) {
            normalized.reason = "finished";
        } else if (normalized.reason.length() > 80) {
            normalized.reason = normalized.reason.substring(0, 80);
        }
        return normalized;
    }

    private static Record copy(Record source) {
        Record result = new Record();
        result.id = source.id;
        result.timestamp = source.timestamp;
        result.score = source.score;
        result.sliced = source.sliced;
        result.maxCombo = source.maxCombo;
        result.misses = source.misses;
        result.bombs = source.bombs;
        result.switches = source.switches;
        result.duration = source.duration;
        result.reason = source.reason;
        return result;
    }
}
