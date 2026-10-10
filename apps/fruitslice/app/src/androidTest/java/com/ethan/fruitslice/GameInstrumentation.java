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
import java.util.HashSet;
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
    private final JSONArray results = new JSONArray();

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

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
            artPreview();
            SystemClock.sleep(750);
            expect(read(() -> view.overlay == GameView.Overlay.NONE), "Open starts a round immediately");
            expect(read(() -> view.engine.elapsed > .2f && view.engine.remaining < 60), "Timer advances in foreground");
            for (int i = 0; i < 24 && !read(() -> view.engine.fruits.stream().anyMatch(f -> f.y > 150 && f.y < 330)); i++) SystemClock.sleep(40);
            expect(read(() -> !view.engine.fruits.isEmpty()), "Natural opening waves launch fruit");
            screenshot("01-opening.png");
            naturalSwipe();
            fixture(() -> {
                view.engine.restart(); view.engine.fruits.clear();
                view.engine.spawnFruit(0, 430, 250, 0, 0, 29);
                view.engine.spawnFruit(1, 500, 250, 0, 0, 29);
                view.engine.spawnFruit(2, 570, 250, 0, 0, 29);
            });
            swipe(365, 250, 635, 250, 80);
            expect(read(() -> view.engine.sliced == 3), "Continuous swipe slices three fruits");
            expect(read(() -> view.engine.score == 21 && view.engine.maxCombo == 3), "Three-fruit combo adds six bonus points");
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
            fixture(() -> { view.engine.fruits.clear(); view.engine.remaining = .08f; view.engine.misses = 0;
                view.engine.score = 321; view.engine.maxCombo = 7; });
            SystemClock.sleep(300);
            expect(read(() -> view.overlay == GameView.Overlay.RESULT && view.engine.gameOver), "Timeout produces result card");
            expect(read(() -> view.history.records().size() == initialHistory + 1), "Result saved once");
            SystemClock.sleep(300);
            expect(read(() -> view.history.records().size() == initialHistory + 1), "Repeated frames cannot duplicate results");
            expect(new HistoryStore(getTargetContext()).records().get(0).score == 321, "Result reloads from actual device storage");
            screenshot("06-result.png");
            tap(500, cardTop + 330);
            expect(read(() -> view.overlay == GameView.Overlay.HISTORY), "History opens after result");
            screenshot("07-history.png");
            tap(725, cardTop + 33);
            tap(500, cardTop + 275);
            expect(read(() -> view.engine.score == 0 && !view.engine.gameOver && view.overlay == GameView.Overlay.NONE), "Play again resets round without deleting history");
            expect(read(() -> view.history.records().size() == initialHistory + 1), "New round retains history");

            JSONObject report = new JSONObject().put("passed", true).put("checks", checks)
                    .put("results", results).put("method", "Real touchscreen MotionEvents plus explicit test-only scene fixtures; storage uses isolated preferences. Score321 is a test fixture.");
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

    private void restoreHistory() {
        if (activity != null) { runOnMainSync(() -> activity.finish()); waitForIdleSync(); }
        if (production != null) {
            SharedPreferences.Editor editor = production.edit().remove("payload_v1");
            if (originalPayload != null) editor.putString("payload_v1", originalPayload);
            if (!editor.commit()) throw new AssertionError("Could not restore original history after device tests");
        }
    }

    private void naturalSwipe() throws Exception {
        // Start a fresh natural wave: taking a full-resolution screenshot can outlast a fruit's apex.
        fixture(() -> view.engine.restart());
        for (int attempt = 0; attempt < 80; attempt++) {
            float[] target = read(() -> {
                for (GameEngine.Fruit f : view.engine.fruits) if (f.type < 6 && f.y > 150 && f.y < 320 && Math.abs(f.vy) < 180)
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
    }
    private void artPreview() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1000, 466, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        runOnMainSync(() -> {
            view.art.background(canvas, 1000, 466);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFD589); p.setTextSize(26); p.setTextAlign(android.graphics.Paint.Align.CENTER);
            canvas.drawText("果刃 · 水果画面检查", 500, 100, p);
            String[] names = {"西瓜", "橙子", "猕猴桃", "苹果", "菠萝", "李子", "彩虹果实", "炸弹"};
            p.setTextSize(15);
            for (int i = 0; i < 8; i++) {
                view.art.fruit(canvas, i, 90 + i * 117, 235, 38, 0, 1);
                canvas.drawText(names[i], 90 + i * 117, 315, p);
            }
        });
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, "00-fruit-art.png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle();
    }
    private void write(String name, String text) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, name))) { stream.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
}
