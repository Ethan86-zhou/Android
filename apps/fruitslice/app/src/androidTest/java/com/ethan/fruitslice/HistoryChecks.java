package com.ethan.fruitslice;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exercises real Android preferences in a private, disposable test namespace. */
public final class HistoryChecks {
    private HistoryChecks() { }

    public static int run(Context context) throws Exception {
        String namespace = "fruit_slice_history_test_" + UUID.randomUUID().toString();
        Context isolated = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }

            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(namespace + "_" + name, mode);
            }

            @Override public boolean deleteSharedPreferences(String name) {
                return super.deleteSharedPreferences(namespace + "_" + name);
            }
        };
        SharedPreferences preferences = isolated.getSharedPreferences("fruit_slice_history", Context.MODE_PRIVATE);
        int count = 0;
        try {
            require(preferences.edit().clear().commit(), "test preferences can be cleared"); count++;
            HistoryStore store = new HistoryStore(isolated);
            require(store.records().isEmpty() && store.bestScore() == 0 && store.getCheckpoint() == null,
                    "new store is empty"); count++;

            Set<String> ids = new HashSet<>();
            for (int i = 0; i < 250; i++) {
                HistoryStore.Record draft = store.draft(0, 0, 0, 0, 0, 0, 0, "playing");
                require(draft.id != null && !draft.id.isEmpty() && ids.add(draft.id), "draft IDs are unique"); count++;
            }

            HistoryStore.Record round = store.draft(0, 0, 0, 0, 0, 0, 0, "playing");
            String roundId = round.id;
            store.checkpoint(round);
            require(roundId.equals(new HistoryStore(isolated).getCheckpoint().id),
                    "zero-score checkpoint survives reload"); count++;
            round.score = 35;
            round.sliced = 7;
            round.maxCombo = 4;
            round.misses = 2;
            round.bombs = 1;
            round.switches = 2;
            round.duration = 23.5f;
            store.checkpoint(round);
            require(roundId.equals(store.getCheckpoint().id) && store.getCheckpoint().score == 35,
                    "repeated checkpoints retain session identity and latest score"); count++;
            HistoryStore.Record mutableCheckpoint = store.getCheckpoint();
            mutableCheckpoint.id = "edited externally";
            mutableCheckpoint.score = 9000;
            require(roundId.equals(store.getCheckpoint().id) && store.getCheckpoint().score == 35,
                    "checkpoint reads are defensive copies"); count++;
            round.reason = "interrupted";
            store.save(round);
            JSONObject payload = new JSONObject(preferences.getString("payload_v1", ""));
            require(payload.isNull("checkpoint") && payload.getJSONArray("records").length() == 1,
                    "save stores result and clears its pending checkpoint in one payload"); count++;
            require(preferences.getAll().size() == 1,
                    "all history state is in a single preference payload"); count++;
            HistoryStore restored = new HistoryStore(isolated);
            require(restored.getCheckpoint() == null && restored.records().size() == 1
                            && restored.bestScore() == 35,
                    "result, best, and checkpoint removal survive reload"); count++;
            HistoryStore.Record restoredRound = restored.records().get(0);
            require(roundId.equals(restoredRound.id) && restoredRound.timestamp == round.timestamp
                            && restoredRound.sliced == 7 && restoredRound.maxCombo == 4
                            && restoredRound.misses == 2 && restoredRound.bombs == 1
                            && restoredRound.switches == 2 && restoredRound.duration == 23.5f
                            && "interrupted".equals(restoredRound.reason),
                    "all result fields survive persistence"); count++;

            round.score = 40;
            restored.save(round);
            restored.save(round);
            require(restored.records().size() == 1 && restored.records().get(0).score == 40,
                    "repeated session save updates without duplicates"); count++;
            List<HistoryStore.Record> exposed = restored.records();
            exposed.get(0).score = 99999;
            exposed.clear();
            require(restored.records().size() == 1 && restored.records().get(0).score == 40,
                    "history list and record reads are defensive copies"); count++;
            restored.checkpoint(round);
            require(restored.getCheckpoint() == null, "completed session cannot become pending again"); count++;

            HistoryStore.Record zero = restored.draft(0, 0, 0, 0, 0, 0, 0f, "time");
            zero.timestamp = round.timestamp + 1;
            restored.save(zero);
            require(restored.records().size() == 2 && restored.records().get(0).score == 0,
                    "zero-score completed rounds are retained"); count++;
            HistoryStore.Record pending = restored.draft(12, 2, 1, 0, 0, 0, 3, "playing");
            restored.checkpoint(pending);
            pending.score = 40000;
            require(restored.getCheckpoint().score == 12, "checkpoint writes copy the supplied record"); count++;
            restored.clearCheckpoint();
            HistoryStore afterClear = new HistoryStore(isolated);
            require(afterClear.getCheckpoint() == null && afterClear.records().size() == 2
                            && afterClear.bestScore() == 40,
                    "explicit checkpoint clearing leaves completed history intact"); count++;

            require(preferences.edit().clear().commit(), "reset for cap checks"); count++;
            HistoryStore capped = new HistoryStore(isolated);
            long baseTime = 1_700_000_000_000L;
            HistoryStore.Record oldest = capped.draft(5000, 10, 4, 3, 0, 0, 10, "misses");
            oldest.timestamp = baseTime;
            capped.save(oldest);
            for (int i = 1; i <= 101; i++) {
                HistoryStore.Record record = capped.draft(i, i, 1, 0, 0, 0, 60, "time");
                record.timestamp = baseTime + i;
                capped.save(record);
            }
            List<HistoryStore.Record> recent = capped.records();
            require(recent.size() == 100 && recent.get(0).timestamp == baseTime + 101
                            && recent.get(99).timestamp == baseTime + 2,
                    "only the 100 newest timestamps remain"); count++;
            for (int i = 1; i < recent.size(); i++) {
                require(recent.get(i - 1).timestamp >= recent.get(i).timestamp,
                        "history is newest first"); count++;
            }
            HistoryStore cappedReload = new HistoryStore(isolated);
            require(cappedReload.records().size() == 100 && cappedReload.bestScore() == 5000,
                    "lifetime best survives removal of its oldest round and reload"); count++;

            require(preferences.edit().clear().putString("payload_v1", "{broken").commit(),
                    "write deliberately corrupt test JSON"); count++;
            HistoryStore broken = new HistoryStore(isolated);
            require(broken.records().isEmpty() && broken.bestScore() == 0 && broken.getCheckpoint() == null,
                    "malformed JSON opens with an empty usable store"); count++;
            broken.save(broken.draft(0, 0, 0, 0, 0, 0, 0, "time"));
            require(new HistoryStore(isolated).records().size() == 1,
                    "normal persistence recovers after malformed JSON"); count++;

            JSONObject valid = recordJson("valid", baseTime + 3, 19).put("duration", "NaN");
            JSONObject duplicate = recordJson("valid", baseTime + 3, 19);
            JSONObject infinity = recordJson("infinity", baseTime + 2, 3).put("duration", "Infinity");
            JSONObject negative = recordJson("negative", baseTime + 1, -2)
                    .put("sliced", -1).put("maxCombo", -2).put("misses", -3)
                    .put("bombs", -4).put("switches", -5).put("duration", -4.5);
            JSONArray damagedRows = new JSONArray().put(JSONObject.NULL).put("invalid entry")
                    .put(new JSONObject().put("id", "missing timestamp"))
                    .put(recordJson("", baseTime, 99)).put(valid).put(duplicate).put(infinity).put(negative);
            JSONObject damaged = new JSONObject().put("best", -10).put("records", damagedRows)
                    .put("checkpoint", valid);
            require(preferences.edit().putString("payload_v1", damaged.toString()).commit(),
                    "write mixed valid and corrupt test entries"); count++;
            HistoryStore repaired = new HistoryStore(isolated);
            require(repaired.records().size() == 3 && repaired.bestScore() == 19,
                    "invalid entries are skipped and valid session IDs are deduplicated"); count++;
            require(repaired.getCheckpoint() == null, "already completed pending ID is ignored on reload"); count++;
            List<HistoryStore.Record> safeRows = repaired.records();
            require(safeRows.get(0).duration == 0f && safeRows.get(1).duration == 0f,
                    "NaN and infinite durations normalize to zero"); count++;
            HistoryStore.Record safeNegative = safeRows.get(2);
            require(safeNegative.score == 0 && safeNegative.sliced == 0 && safeNegative.maxCombo == 0
                            && safeNegative.misses == 0 && safeNegative.bombs == 0
                            && safeNegative.switches == 0 && safeNegative.duration == 0f,
                    "corrupt negative counters and duration normalize safely"); count++;

            require(preferences.edit().clear().putInt("payload_v1", 7).commit(),
                    "write wrong-typed preference payload"); count++;
            require(new HistoryStore(isolated).records().isEmpty(),
                    "wrong-typed preference payload does not crash opening"); count++;
            return count;
        } finally {
            preferences.edit().clear().commit();
            isolated.deleteSharedPreferences("fruit_slice_history");
        }
    }

    private static JSONObject recordJson(String id, long timestamp, int score) throws Exception {
        return new JSONObject().put("id", id).put("timestamp", timestamp).put("score", score)
                .put("sliced", 0).put("maxCombo", 0).put("misses", 0).put("bombs", 0)
                .put("switches", 0).put("duration", 1).put("reason", "time");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
