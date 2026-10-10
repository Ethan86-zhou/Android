package com.ethan.fruitslice;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Device acceptance with actual MotionEvents; controlled scenes exist only in this test APK. */
public final class GameInstrumentation extends Instrumentation {
    private MainActivity activity;
    private GameView view;
    private int checks;
    private File evidence;
    private SharedPreferences production;
    private String originalPayload;
    private boolean historyOnly;
    private final JSONArray results = new JSONArray();
    private final JSONArray screenshots = new JSONArray();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        historyOnly = arguments != null && "true".equals(arguments.getString("history_only"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            production = getTargetContext().getSharedPreferences("fruit_slice_history", 0);
            originalPayload = production.getString("payload_v1", null);
            evidence = new File(getTargetContext().getFilesDir(), "device-tests");
            if (!evidence.exists() && !evidence.mkdirs()) throw new AssertionError("Cannot create evidence");
            int storage = HistoryChecks.run(getTargetContext());
            checks += storage;
            results.put("Local persistence: " + storage + " checks passed in isolated preferences");
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            runOnMainSync(() -> view = activity.gameView);
            if (historyOnly) {
                topTenAcceptance();
                result.putString("stream", "PASS: " + checks + " device checks\n");
                result.putInt("checks", checks);
                finish(Activity.RESULT_OK, result);
                return;
            }
            expect(view.art.usesRealisticFruitAssets(), "All six realistic whole fruits and six cut faces load offline");
            JSONObject artwork = new JSONObject(view.art.assetDiagnostics());
            expect(artwork.getInt("wholeAssets") == 6 && artwork.getInt("cutAssets") == 6,
                    "Whole fruit and cut-face caches contain every species");
            artPreview();
            SystemClock.sleep(750);
            expect(read(() -> view.overlay == GameView.Overlay.NONE), "Open starts a round immediately");
            expect(read(() -> view.engine.elapsed > .2f && view.engine.remaining < 60), "Timer advances in foreground");
            for (int i = 0; i < 24 && !read(() -> view.engine.fruits.stream().anyMatch(f -> f.y > 150 && f.y < 330)); i++) SystemClock.sleep(40);
            expect(read(() -> !view.engine.fruits.isEmpty()), "Natural opening waves launch fruit");
            screenshot("01-opening.png");
            naturalSwipe();
            sizeAndScoreChecks();
            fixture(() -> {
                view.engine.restart(); view.engine.fruits.clear();
                view.engine.spawnFruit(0, 430, 250, 0, 0, 14);
                view.engine.spawnFruit(1, 500, 250, 0, 0, 28);
                view.engine.spawnFruit(2, 590, 250, 0, 0, 56);
            });
            swipe(365, 250, 655, 250, 80);
            expect(read(() -> view.engine.sliced == 3), "Continuous swipe slices three fruits");
            expect(read(() -> view.engine.score == 43 && view.engine.maxCombo == 3), "Mixed-size combo earns 20+12+5 plus six bonus points");
            screenshot("02-combo.png");
            fixture(() -> { view.engine.fruits.clear(); view.engine.score = 50; view.engine.remaining = 50;
                view.engine.spawnFruit(7, 500, 250, 0, 0, 28); });
            swipe(445, 250, 555, 250, 70);
            expect(read(() -> view.engine.score == 40 && view.engine.bombsHit == 1), "Bomb deducts ten points");
            expect(read(() -> view.engine.remaining > 46 && view.engine.remaining <= 47), "Bomb deducts three seconds");
            screenshot("03-bomb.png");

            Set<GameEngine.Mode> modes = new HashSet<>();
            GameEngine.Mode[] previous = {GameEngine.Mode.CLASSIC};
            for (int i = 0; i < 32 && modes.size() < 4; i++) {
                fixture(() -> { view.engine.fruits.clear(); view.engine.remaining = 50; view.engine.misses = 0;
                    view.engine.spawnFruit(6, 500, 250, 0, 0, 31); });
                swipe(445, 250, 555, 250, 65);
                GameEngine.Mode current = read(() -> view.engine.mode);
                expect(current != GameEngine.Mode.CLASSIC && current != previous[0], "Rainbow randomly chooses a different special mode");
                previous[0] = current;
                if (modes.add(current)) screenshot("04-mode-" + current.name().toLowerCase() + ".png");
                expect(read(() -> view.engine.modeRemaining > 7 && view.engine.modeRemaining <= 12), "Special mode has bounded duration");
            }
            expect(modes.size() == 4, "All four special modes reached through real swipe input");
            fixture(() -> { view.engine.fruits.clear(); view.engine.modeRemaining = .08f; });
            SystemClock.sleep(300);
            expect(read(() -> view.engine.mode == GameEngine.Mode.CLASSIC), "Special mode automatically returns to classic");

            tap(950, 46);
            expect(read(() -> view.overlay == GameView.Overlay.PAUSE), "Pause button opens pause card");
            float pausedTime = read(() -> view.engine.remaining);
            SystemClock.sleep(500);
            expect(Math.abs(read(() -> view.engine.remaining) - pausedTime) < .001f, "Pause freezes round timer");
            screenshot("05-pause.png");
            float cardTop = read(() -> Math.max(12, (view.getHeight() / screenScale() - 360) / 2));
            tap(500, cardTop + 319);
            expect(read(() -> view.overlay == GameView.Overlay.HISTORY), "History opens from paused round");
            tap(725, cardTop + 33);
            expect(read(() -> view.overlay == GameView.Overlay.PAUSE), "Closing history preserves pause");
            expect(Math.abs(read(() -> view.engine.remaining) - pausedTime) < .001f, "History never consumes play time");
            tap(500, cardTop + 256);
            expect(read(() -> view.overlay == GameView.Overlay.NONE), "Continue resumes existing round");

            shell("cmd statusbar expand-notifications");
            for (int i = 0; i < 30 && !read(() -> view.overlay == GameView.Overlay.PAUSE); i++) SystemClock.sleep(50);
            expect(read(() -> view.overlay == GameView.Overlay.PAUSE), "Notification shade pauses covered gameplay");
            shell("cmd statusbar collapse");
            SystemClock.sleep(600);
            pausedTime = read(() -> view.engine.remaining);
            shell("input keyevent KEYCODE_HOME");
            SystemClock.sleep(500);
            expect(Math.abs(read(() -> view.engine.remaining) - pausedTime) < .001f, "Home/background freezes timer");
            getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(500);
            expect(read(() -> view.overlay == GameView.Overlay.PAUSE), "Returning from background requires deliberate resume");
            tap(500, cardTop + 256);

            int initialHistory = read(() -> view.history.records().size());
            int expectedHistory = Math.min(10, initialHistory + 1);
            int fixtureScore = read(() -> view.history.bestScore()) + 321;
            fixture(() -> { view.engine.fruits.clear(); view.engine.remaining = .08f; view.engine.misses = 0;
                view.engine.score = fixtureScore; view.engine.maxCombo = 7; });
            SystemClock.sleep(300);
            expect(read(() -> view.overlay == GameView.Overlay.RESULT && view.engine.gameOver), "Timeout produces result card");
            expect(read(() -> view.history.records().size() == expectedHistory), "Result saved once within ten-round limit");
            SystemClock.sleep(300);
            expect(read(() -> view.history.records().size() == expectedHistory), "Repeated frames cannot duplicate results");
            expect(new HistoryStore(getTargetContext()).records().get(0).score == fixtureScore, "Result reloads from actual device storage");
            screenshot("06-result.png");
            tap(500, cardTop + 330);
            expect(read(() -> view.overlay == GameView.Overlay.HISTORY), "History opens after result");
            screenshot("07-history.png");
            tap(725, cardTop + 33);
            tap(500, cardTop + 275);
            expect(read(() -> view.engine.score == 0 && !view.engine.gameOver && view.overlay == GameView.Overlay.NONE), "Play again resets round without deleting history");
            expect(read(() -> view.history.records().size() == expectedHistory), "New round retains history");

            JSONObject report = new JSONObject().put("passed", true).put("checks", checks)
                    .put("results", results).put("screenshots", screenshots).put("artwork", artwork)
                    .put("method", "Real touchscreen MotionEvents plus explicit test-only scene fixtures; storage uses isolated preferences. The result score is a test fixture.");
            write("device-checks.json", report.toString(2));
            result.putString("stream", "PASS: " + checks + " device checks\n");
            restoreHistory();
            result.putInt("checks", checks);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            try { write("device-failure.txt", android.util.Log.getStackTraceString(failure)); } catch (Exception ignored) { }
            try { restoreHistory(); } catch (Exception ignored) { }
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void sizeAndScoreChecks() throws Exception {
        expect(GameEngine.MIN_FRUIT_RADIUS * 4 == GameEngine.MAX_FRUIT_RADIUS,
                "Smallest fruit diameter is exactly one quarter of largest");
        int[] points = {20, 12, 8, 5};
        float[] radii = {14, 28, 42, 56};
        for (int i = 0; i < radii.length; i++) {
            final float radius = radii[i];
            final int type = i;
            fixture(() -> {
                view.engine.restart();
                view.engine.spawnFruit(type, 500, 250, 0, 0, radius);
            });
            if (i == 0) {
                swipe(430, 230, 570, 230, 30);
                expect(read(() -> view.engine.sliced == 0 && view.engine.score == 0),
                        "Touch outside a tiny fruit does not use an inflated hit area");
            }
            float targetY = read(() -> view.engine.fruits.get(0).y);
            swipe(500, targetY - 90, 500, targetY + 90, 50);
            expect(read(() -> view.engine.sliced == 1) && read(() -> view.engine.score) == points[i],
                    "Real touch on radius " + radius + " awards " + points[i] + " points");
        }
        fixture(() -> {
            view.engine.restart();
            view.engine.mode = GameEngine.Mode.DOUBLE;
            view.engine.modeRemaining = 10;
            view.engine.spawnFruit(0, 430, 250, 0, 0, 14);
            view.engine.spawnFruit(3, 590, 250, 0, 0, 56);
        });
        swipe(365, 250, 655, 250, 60);
        expect(read(() -> view.engine.score == 50 && view.engine.sliced == 2),
                "Double mode doubles different size rewards to 40 and 10");
    }

    private void topTenAcceptance() throws Exception {
        JSONObject before = originalPayload == null ? new JSONObject() : new JSONObject(originalPayload);
        JSONArray previous = before.optJSONArray("records");
        ArrayList<JSONObject> expected = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (previous != null) {
            for (int i = 0; i < previous.length(); i++) {
                JSONObject row = previous.getJSONObject(i);
                if (ids.add(row.getString("id"))) expected.add(row);
            }
        }
        JSONObject pending = before.optJSONObject("checkpoint");
        if (pending != null && ids.add(pending.getString("id"))) expected.add(pending);
        expected.sort((a, b) -> {
            int score = Integer.compare(b.optInt("score"), a.optInt("score"));
            if (score != 0) return score;
            int time = Long.compare(b.optLong("timestamp"), a.optLong("timestamp"));
            return time != 0 ? time : a.optString("id").compareTo(b.optString("id"));
        });
        List<HistoryStore.Record> actual = read(() -> view.history.records());
        expect(actual.size() == Math.min(10, expected.size()), "Existing phone records migrate to at most ten rounds");
        for (int i = 0; i < actual.size(); i++) {
            expect(actual.get(i).id.equals(expected.get(i).getString("id"))
                    && actual.get(i).score == expected.get(i).getInt("score"),
                    "Phone rank " + (i + 1) + " keeps the correct original high-score round");
        }
        expect(read(() -> view.history.bestScore()) >= before.optInt("best"), "Upgrade preserves lifetime best");
        HistoryStore reloaded = new HistoryStore(getTargetContext());
        expect(reloaded.records().size() == actual.size(), "Ranked history persists in device storage");
        for (int i = 0; i < actual.size(); i++) {
            expect(actual.get(i).id.equals(reloaded.records().get(i).id), "Persisted rank " + (i + 1) + " survives reload");
        }
        JSONObject persisted = new JSONObject(production.getString("payload_v1", ""));
        expect(persisted.getInt("version") == 2 && persisted.getJSONArray("records").length() <= 10,
                "On-disk payload is upgraded and physically contains no more than ten results");
        tap(890, 46);
        expect(read(() -> view.overlay == GameView.Overlay.HISTORY), "Real touch opens the ranked history screen");
        float remaining = read(() -> view.engine.remaining);
        SystemClock.sleep(300);
        expect(Math.abs(read(() -> view.engine.remaining) - remaining) < .001f, "Browsing ranked history freezes the round");
        screenshot("08-top-ten-history.png");
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
        HistoryStore cleaned = new HistoryStore(getTargetContext());
        cleaned.clearCheckpoint(); // Do not leave this acceptance screen's empty round in user data.
        expect(cleaned.getCheckpoint() == null && cleaned.records().size() == actual.size(),
                "Acceptance leaves no test-created checkpoint or extra completed round");
        JSONObject report = new JSONObject().put("passed", true).put("checks", checks).put("results", results)
                .put("screenshots", new JSONArray().put("08-top-ten-history.png"))
                .put("method", "Isolated ranking/migration checks plus existing phone records and real touchscreen history navigation. No synthetic game results added to user history.");
        write("device-checks.json", report.toString(2));
    }

    private void restoreHistory() {
        if (activity != null) { runOnMainSync(() -> activity.finish()); waitForIdleSync(); }
        if (production != null) {
            SharedPreferences.Editor editor = production.edit().remove("payload_v1");
            if (originalPayload != null) editor.putString("payload_v1", originalPayload);
            if (!editor.commit()) throw new AssertionError("Could not restore original history after device tests");
            new HistoryStore(getTargetContext()); // Apply the current top-ten policy to restored legacy data.
        }
    }

    private void naturalSwipe() throws Exception {
        // Start a fresh natural wave: taking a full-resolution screenshot can outlast a fruit's apex.
        fixture(() -> view.engine.restart());
        for (int attempt = 0; attempt < 80; attempt++) {
            float[] target = read(() -> {
                for (GameEngine.Fruit f : view.engine.fruits) if (f.type < 6 && f.y > 150
                        && f.y < view.getHeight() / screenScale() - 90 && Math.abs(f.vy) < 180)
                    return new float[]{f.x + f.vx * .08f, f.y};
                return null;
            });
            if (target != null) {
                int before = read(() -> view.engine.sliced);
                swipe(target[0], target[1] - 80, target[0], target[1] + 80, 65);
                if (read(() -> view.engine.sliced) > before) { expect(true, "Natural moving fruit sliced through touchscreen input"); return; }
            }
            SystemClock.sleep(40);
        }
        throw new AssertionError("Natural moving fruit was not sliced");
    }

    private void fixture(Runnable runnable) { runOnMainSync(runnable); }
    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor.AutoCloseInputStream stream =
                     new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) {
            while (stream.read() != -1) { /* Consume command output so the shell command completes. */ }
        }
    }
    private interface Read<T> { T get(); }
    private <T> T read(Read<T> source) {
        AtomicReference<T> value = new AtomicReference<>();
        runOnMainSync(() -> value.set(source.get())); return value.get();
    }
    private float screenScale() { return Math.min(view.getWidth() / 1000f, view.getHeight() / 420f); }
    private void swipe(float x1, float y1, float x2, float y2, int ms) {
        float scale = read(this::screenScale);
        float left = read(() -> (view.getWidth() - 1000 * scale) * .5f);
        long down = SystemClock.uptimeMillis();
        pointer(down, MotionEvent.ACTION_DOWN, left + x1 * scale, y1 * scale);
        for (int i = 1; i <= 5; i++) {
            SystemClock.sleep(ms / 5);
            pointer(down, MotionEvent.ACTION_MOVE, left + (x1 + (x2 - x1) * i / 5f) * scale,
                    (y1 + (y2 - y1) * i / 5f) * scale);
        }
        pointer(down, MotionEvent.ACTION_UP, left + x2 * scale, y2 * scale);
        SystemClock.sleep(30);
    }
    private void tap(float x, float y) { swipe(x, y, x, y, 30); }
    private void pointer(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        sendPointerSync(event); event.recycle();
    }
    private void expect(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++; results.put(label);
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable");
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle();
        screenshots.put(name);
    }
    private void artPreview() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1000, 650, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        runOnMainSync(() -> {
            view.art.background(canvas, 1000, 650);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFD589); p.setTextSize(26); p.setTextAlign(android.graphics.Paint.Align.CENTER);
            canvas.drawText("果刃 · 写实水果与大小", 500, 54, p);
            String[] names = {"西瓜", "橙子", "猕猴桃", "苹果", "菠萝", "李子"};
            p.setTextSize(15);
            for (int i = 0; i < 6; i++) {
                float x = 80 + i * 168;
                view.art.fruit(canvas, i, x, 158, 44, 0, 1);
                canvas.drawText(names[i], x, 231, p);
                view.art.half(canvas, i, x - 8, 296, 36, 0, true, 1);
                view.art.half(canvas, i, x + 8, 296, 36, 0, false, 1);
            }
            p.setTextSize(21);
            canvas.drawText("最小直径 = 最大的 1/4 · 小果高分", 500, 395, p);
            float[] radii = {56, 42, 28, 14};
            int[] points = {5, 8, 12, 20};
            p.setTextSize(16);
            for (int i = 0; i < 4; i++) {
                float x = 170 + i * 220;
                view.art.fruit(canvas, 1, x, 490, radii[i], 0, 1);
                canvas.drawText("直径 " + (int)(radii[i] * 2) + "  ·  " + points[i] + " 分", x, 582, p);
            }
        });
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, "00-fruit-art.png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle();
        screenshots.put("00-fruit-art.png");
    }
    private void write(String name, String text) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, name))) { stream.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
}
