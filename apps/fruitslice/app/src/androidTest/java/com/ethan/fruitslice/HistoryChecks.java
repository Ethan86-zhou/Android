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
            require(!preferences.contains("payload_v1") && new HistoryStore(isolated).records().isEmpty(),
                    "missing payload is a stable empty installation and does not need JSON parsing"); count++;

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
            require(restored.records().size() == 2 && restored.records().get(1).score == 0,
                    "zero-score rounds are retained below higher scores when the board has room"); count++;
            HistoryStore.Record pending = restored.draft(12, 2, 1, 0, 0, 0, 3, "playing");
            restored.checkpoint(pending);
            pending.score = 40000;
            require(restored.getCheckpoint().score == 12, "checkpoint writes copy the supplied record"); count++;
            restored.clearCheckpoint();
            HistoryStore afterClear = new HistoryStore(isolated);
            require(afterClear.getCheckpoint() == null && afterClear.records().size() == 2
                            && afterClear.bestScore() == 40,
                    "explicit checkpoint clearing leaves completed history intact"); count++;

            require(preferences.edit().clear().commit(), "reset for top-ten checks"); count++;
            HistoryStore capped = new HistoryStore(isolated);
            long baseTime = 1_700_000_000_000L;
            HistoryStore.Record oldest = capped.draft(5000, 10, 4, 3, 0, 0, 10, "misses");
            oldest.timestamp = baseTime;
            capped.save(oldest);
            for (int i = 1; i <= 20; i++) {
                HistoryStore.Record record = capped.draft(21 - i, i, 1, 0, 0, 0, 60, "time");
                record.timestamp = baseTime + i;
                capped.save(record);
            }
            List<HistoryStore.Record> top = capped.records();
            require(top.size() == 10 && top.get(0).id.equals(oldest.id)
                            && top.get(0).timestamp == baseTime && top.get(9).score == 12,
                    "the oldest high score survives while newer low scores fall outside the top ten"); count++;
            for (int i = 1; i < top.size(); i++) {
                require(top.get(i - 1).score > top.get(i).score,
                        "different scores rank strictly highest first"); count++;
            }
            String tenthId = top.get(9).id;
            HistoryStore.Record low = capped.draft(11, 1, 1, 0, 0, 0, 60, "time");
            low.timestamp = baseTime + 1000;
            capped.checkpoint(low);
            capped.save(low);
            require(capped.records().size() == 10 && capped.records().get(9).id.equals(tenthId)
                            && capped.getCheckpoint() == null,
                    "a new low score does not displace tenth place and still clears its checkpoint"); count++;
            HistoryStore.Record tie = capped.draft(12, 2, 1, 0, 0, 0, 60, "time");
            tie.id = "tie-newer";
            tie.timestamp = baseTime + 100;
            capped.save(tie);
            require(capped.records().get(9).id.equals("tie-newer"),
                    "a newer equal score displaces the older tenth-place round"); count++;
            HistoryStore.Record tieB = capped.draft(12, 2, 1, 0, 0, 0, 60, "time");
            tieB.id = "tie-b";
            tieB.timestamp = baseTime + 101;
            capped.save(tieB);
            HistoryStore.Record tieA = capped.draft(12, 2, 1, 0, 0, 0, 60, "time");
            tieA.id = "tie-a";
            tieA.timestamp = tieB.timestamp;
            capped.save(tieA);
            require(capped.records().get(9).id.equals("tie-a"),
                    "equal score and timestamp use stable ascending session ID order"); count++;
            HistoryStore.Record olderTie = capped.draft(12, 2, 1, 0, 0, 0, 60, "time");
            olderTie.timestamp = baseTime;
            capped.save(olderTie);
            require(capped.records().get(9).id.equals("tie-a"),
                    "an older equal score cannot displace the newer cutoff"); count++;
            HistoryStore.Record replacement = capped.records().get(5);
            replacement.score = 60;
            replacement.timestamp = baseTime + 200;
            capped.save(replacement);
            capped.save(replacement);
            require(capped.records().size() == 10 && capped.records().get(1).id.equals(replacement.id)
                            && capped.records().get(1).score == 60,
                    "updating a saved session reranks it without duplication"); count++;
            oldest.score = 30;
            capped.save(oldest);
            HistoryStore cappedReload = new HistoryStore(isolated);
            require(cappedReload.records().size() == 10 && cappedReload.bestScore() == 5000
                            && cappedReload.records().get(0).score == 60,
                    "lifetime best remains independent of current ranked rows after replacement and reload"); count++;
            String rankedPayload = preferences.getString("payload_v1", "");
            HistoryStore rankedReload = new HistoryStore(isolated);
            require(rankedPayload.equals(preferences.getString("payload_v1", ""))
                            && rankedReload.records().get(9).id.equals("tie-a"),
                    "already-ranked top-ten history reloads without changing its payload or cutoff"); count++;

            JSONArray legacyRows = new JSONArray();
            for (int i = 99; i >= 0; i--) {
                legacyRows.put(recordJson("legacy-" + i, baseTime + i, 100 - i));
            }
            legacyRows.put(recordJson("legacy-0", baseTime, 100));
            JSONObject legacyPending = recordJson("unrelated-pending", baseTime + 200, 17)
                    .put("reason", "playing").put("duration", 14.5);
            JSONObject legacy = new JSONObject().put("version", 1).put("best", 9000)
                    .put("records", legacyRows).put("checkpoint", legacyPending);
            require(preferences.edit().putString("payload_v1", legacy.toString()).commit(),
                    "seed legacy newest-first history with over one hundred entries and a pending round"); count++;
            HistoryStore migrated = new HistoryStore(isolated);
            require(migrated.records().size() == 10 && migrated.records().get(0).id.equals("legacy-0")
                            && migrated.records().get(9).id.equals("legacy-9"),
                    "legacy migration ranks all old rows by score before retaining exactly ten"); count++;
            require(migrated.bestScore() == 9000 && migrated.getCheckpoint() != null
                            && migrated.getCheckpoint().id.equals("unrelated-pending")
                            && migrated.getCheckpoint().duration == 14.5f,
                    "legacy migration preserves lifetime best and the independent pending round"); count++;
            JSONObject migratedPayload = new JSONObject(preferences.getString("payload_v1", ""));
            require(migratedPayload.getInt("version") == 2
                            && migratedPayload.getJSONArray("records").length() == 10
                            && migratedPayload.getJSONArray("records").getJSONObject(0).getString("id").equals("legacy-0")
                            && migratedPayload.getJSONObject("checkpoint").getString("id").equals("unrelated-pending"),
                    "migration persists the ranked limit and checkpoint immediately before any new save"); count++;
            String onceMigrated = preferences.getString("payload_v1", "");
            HistoryStore migratedAgain = new HistoryStore(isolated);
            require(onceMigrated.equals(preferences.getString("payload_v1", ""))
                            && migratedAgain.records().size() == 10 && migratedAgain.bestScore() == 9000
                            && migratedAgain.getCheckpoint().id.equals("unrelated-pending"),
                    "migration is stable and idempotent on the next load"); count++;
            legacy.put("checkpoint", recordJson("legacy-99", baseTime + 99, 1));
            require(preferences.edit().putString("payload_v1", legacy.toString()).commit(),
                    "seed a pending ID already present among discarded legacy results"); count++;
            HistoryStore completedPending = new HistoryStore(isolated);
            require(completedPending.getCheckpoint() == null
                            && new JSONObject(preferences.getString("payload_v1", "")).isNull("checkpoint"),
                    "migration clears a completed pending ID even when that score is outside the top ten"); count++;

            JSONArray tiedLegacy = new JSONArray();
            tiedLegacy.put(recordJson("same-b", baseTime + 5, 100));
            tiedLegacy.put(recordJson("older", baseTime + 4, 100));
            tiedLegacy.put(recordJson("same-a", baseTime + 5, 100));
            for (int i = 0; i < 7; i++) tiedLegacy.put(recordJson("lower-" + i, baseTime + 100 + i, 90 - i));
            require(preferences.edit().putString("payload_v1", new JSONObject().put("version", 1)
                            .put("best", 100).put("records", tiedLegacy).toString()).commit(),
                    "seed a legacy file that already has ten rows in arbitrary tie order"); count++;
            HistoryStore tiedMigration = new HistoryStore(isolated);
            require(tiedMigration.records().size() == 10
                            && tiedMigration.records().get(0).id.equals("same-a")
                            && tiedMigration.records().get(1).id.equals("same-b")
                            && tiedMigration.records().get(2).id.equals("older"),
                    "already-ten legacy rows migrate with timestamp then session ID tie ordering"); count++;

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
