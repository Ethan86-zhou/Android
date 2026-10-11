package com.ethan.fruitslice;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private float bladeTouchScale, bladeTouchLeft;
    private java.lang.reflect.Field bladeStartedAt, bladeLastEventTime;
    private JSONObject viewportDiagnostics;
    private int audioEndIndex;
    private final JSONArray results = new JSONArray();
    private final JSONArray screenshots = new JSONArray();
    private final JSONArray bladeDiagnostics = new JSONArray();
    private final JSONArray audioDiagnostics = new JSONArray();

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
            viewportAndLargeScreenChecks();
            sizeAndScoreChecks();
            antiScribbleChecks();
            fixture(() -> {
                view.engine.restart(); view.engine.fruits.clear();
                view.engine.spawnFruit(0, 430, 250, 0, 0, 28);
                view.engine.spawnFruit(1, 500, 250, 0, 0, 35);
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

            long notificationDown = SystemClock.uptimeMillis();
            bladeTouch(notificationDown, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{100}, new float[]{250});
            bladeTouch(notificationDown, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{110}, new float[]{250});
            expect(read(() -> view.bladeLimiter.isStarted()), "Notification test begins with a real active blade");
            shell("cmd statusbar expand-notifications");
            for (int i = 0; i < 30 && !read(() -> view.overlay == GameView.Overlay.PAUSE); i++) SystemClock.sleep(50);
            expect(read(() -> view.overlay == GameView.Overlay.PAUSE), "Notification shade pauses covered gameplay");
            expect(read(() -> !view.bladeLimiter.isStarted() && !view.bladeLimiter.isExhausted()),
                    "Notification pause cancels held-finger blade state before another DOWN");
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
            endAudioChecks();

            JSONObject report = new JSONObject().put("passed", true).put("checks", checks)
                    .put("results", results).put("screenshots", screenshots).put("artwork", artwork)
                    .put("viewport", viewportDiagnostics)
                    .put("audio", audioDiagnostics)
                    .put("method", "Real touchscreen MotionEvents plus explicit test-only scene fixtures; storage uses isolated preferences. The result score is a test fixture.");
            write("device-checks.json", report.toString(2));
            write("blade-diagnostics.json", bladeDiagnosticText());
            write("audio-diagnostics.json", audioDiagnostics.toString(2));
            result.putString("stream", "PASS: " + checks + " device checks\n");
            restoreHistory();
            result.putInt("checks", checks);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            try { write("blade-diagnostics.json", bladeDiagnosticText()); } catch (Exception ignored) { }
            try { write("audio-diagnostics.json", audioDiagnostics.toString(2)); } catch (Exception ignored) { }
            try { if (viewportDiagnostics != null) write("viewport-diagnostics.json", viewportDiagnostics.toString(2)); }
            catch (Exception ignored) { }
            try { write("device-failure.txt", android.util.Log.getStackTraceString(failure)); } catch (Exception ignored) { }
            try { restoreHistory(); } catch (Exception ignored) { }
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void endAudioChecks() throws Exception {
        audioEndIndex = (Integer) audioValue("END");
        for (int i = 0; i < 100 && !readAudioState().getBoolean("endLoaded"); i++) SystemClock.sleep(50);
        expect(audioState("end-sample-ready").getBoolean("endLoaded"),
                "The distinct round-ending sample loads into the real device SoundPool");

        prepareEndAudioScene();
        fixture(() -> view.engine.remaining = .02f);
        awaitAudioResult("timeout");
        JSONObject timeout = audioState("timeout-play");
        expectEndStream(timeout, "Timeout");
        expectNoEndReplay(timeout, "timeout");
        tapViewButton("secondButton");
        expect(read(() -> view.overlay == GameView.Overlay.HISTORY)
                        && audioStopped(audioState("timeout-history-stop")),
                "Opening history from results stops retained effect streams and abandons audio focus");
        awaitScreenshotFrame("audio-history-close-layout");
        tapViewButton("closeButton");
        awaitScreenshotFrame("audio-return-to-result");
        expect(read(() -> view.overlay == GameView.Overlay.RESULT)
                        && audioStopped(audioState("history-return-no-replay")),
                "Returning from history to the result card does not replay the ending sound");
        restartAudioRound("after history");

        prepareEndAudioScene();
        fixture(() -> {
            view.engine.misses = 2;
            view.engine.spawnFruit(0, 500, privateFloat(GameEngine.class, view.engine, "height") + 100,
                    0, 10, 28);
        });
        awaitAudioResult("third missed fruit");
        JSONObject misses = audioState("third-miss-play");
        expect(read(() -> view.engine.misses == 3 && view.engine.remaining > 40),
                "A third falling fruit ends the round independently of timeout");
        expectEndStream(misses, "Third missed fruit");
        expectNoEndReplay(misses, "third missed fruit");
        shell("input keyevent KEYCODE_HOME");
        for (int i = 0; i < 40 && !audioStopped(readAudioState()); i++) SystemClock.sleep(25);
        expect(audioStopped(audioState("result-background-stop")),
                "Sending the result screen to the real background stops its ending stream and focus");
        getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for (int i = 0; i < 60 && !read(() -> activity.hasWindowFocus()); i++) SystemClock.sleep(25);
        awaitScreenshotFrame("audio-result-foreground");
        expect(read(() -> view.overlay == GameView.Overlay.RESULT)
                        && audioStopped(audioState("result-foreground-no-replay")),
                "Returning from background preserves the result without replaying its sound");
        restartAudioRound("after background");

        prepareEndAudioScene();
        fixture(() -> {
            view.engine.remaining = 2;
            view.engine.spawnFruit(7, 500, 250, 0, 0, 28);
        });
        swipe(445, 250, 555, 250, 60);
        awaitAudioResult("bomb time deduction");
        JSONObject bomb = audioState("bomb-ending-play");
        expect(read(() -> view.engine.bombsHit == 1 && view.engine.remaining == 0 && view.engine.misses == 0),
                "A real bomb cut can end a round by deducting its remaining time");
        expectEndStream(bomb, "Bomb time deduction");
        expectNoEndReplay(bomb, "bomb time deduction");
        restartAudioRound("during the final ending sound");
    }

    /** This method keeps GameView.saved intact; every preceding round restarts through its real button. */
    private void prepareEndAudioScene() throws Exception {
        java.lang.reflect.Field wave = GameEngine.class.getDeclaredField("nextWave");
        java.lang.reflect.Field special = GameEngine.class.getDeclaredField("nextSpecial");
        wave.setAccessible(true); special.setAccessible(true);
        fixture(() -> {
            view.audio.pause(); view.engine.fruits.clear(); view.engine.mode = GameEngine.Mode.CLASSIC;
            view.engine.modeRemaining = 0; view.engine.remaining = 50;
            try { wave.setFloat(view.engine, 60); special.setFloat(view.engine, 60); }
            catch (IllegalAccessException failure) { throw new AssertionError(failure); }
        });
        expect(read(() -> view.overlay == GameView.Overlay.NONE && !view.engine.gameOver)
                        && audioStopped(audioState("new-round-before-ending")),
                "A fresh round starts without a preceding ending stream");
    }

    private void awaitAudioResult(String cause) {
        for (int i = 0; i < 100 && !read(() -> view.overlay == GameView.Overlay.RESULT); i++) SystemClock.sleep(20);
        expect(read(() -> view.overlay == GameView.Overlay.RESULT && view.engine.gameOver),
                cause + " reaches the real result transition");
    }

    private void expectEndStream(JSONObject state, String cause) throws Exception {
        expect(state.getBoolean("endLoaded") && state.getBoolean("focused")
                        && state.getInt("nextStream") == 1 && state.getInt("streamCount") == 1
                        && state.getJSONArray("streamIds").getInt(0) != 0,
                cause + " submits one nonzero ending stream accepted by SoundPool. Actual=" + state);
    }

    private void expectNoEndReplay(JSONObject initial, String cause) throws Exception {
        for (int i = 0; i < 3; i++) awaitScreenshotFrame("audio-once-" + cause);
        JSONObject later = audioState(cause + "-after-result-frames");
        expect(initial.getJSONArray("streamIds").toString().equals(later.getJSONArray("streamIds").toString())
                        && initial.getInt("nextStream") == later.getInt("nextStream"),
                "Repeated committed result frames do not submit another ending stream for " + cause);
    }

    private void restartAudioRound(String phase) throws Exception {
        awaitScreenshotFrame("audio-restart-layout");
        tapViewButton("mainButton");
        expect(read(() -> view.overlay == GameView.Overlay.NONE && !view.engine.gameOver && view.engine.score == 0)
                        && audioStopped(audioState("restart-" + phase)),
                "Real Play again stops previous streams and starts a fresh round " + phase);
    }

    private Object audioValue(String name) {
        try {
            java.lang.reflect.Field field = GameAudio.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(view.audio);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private JSONObject readAudioState() {
        return read(() -> {
            synchronized (audioValue("lock")) {
                int[] sounds = ((int[]) audioValue("soundIds")).clone();
                int[] streams = ((int[]) audioValue("streamIds")).clone();
                int count = 0;
                for (int stream : streams) if (stream != 0) count++;
                try {
                    return new JSONObject().put("observedAt", SystemClock.uptimeMillis())
                            .put("endSampleIndex", audioEndIndex).put("soundIds", new JSONArray(sounds))
                            .put("endLoaded", sounds[audioEndIndex] != 0
                                    && ((Set<?>) audioValue("loaded")).contains(sounds[audioEndIndex]))
                            .put("streamIds", new JSONArray(streams)).put("streamCount", count)
                            .put("nextStream", (Integer) audioValue("nextStream"))
                            .put("focused", (Boolean) audioValue("focused")).put("overlay", view.overlay.name());
                } catch (org.json.JSONException failure) { throw new AssertionError(failure); }
            }
        });
    }

    private JSONObject audioState(String phase) throws Exception {
        JSONObject state = readAudioState().put("phase", phase);
        audioDiagnostics.put(state);
        return state;
    }

    private boolean audioStopped(JSONObject state) throws org.json.JSONException {
        return state.getInt("streamCount") == 0 && state.getInt("nextStream") == 0 && !state.getBoolean("focused");
    }

    /** Exercise actual window dimensions and touch mapping without depending on a device model. */
    private void viewportAndLargeScreenChecks() throws Exception {
        int[] dimensions = read(() -> {
            android.graphics.Rect window = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
            int[] position = new int[2];
            view.getLocationOnScreen(position);
            android.content.res.Configuration configuration = view.getResources().getConfiguration();
            return new int[]{view.getWidth(), view.getHeight(),
                    activity.getWindow().getDecorView().getWidth(), activity.getWindow().getDecorView().getHeight(),
                    window.left, window.top, window.width(), window.height(), position[0], position[1],
                    configuration.orientation, configuration.smallestScreenWidthDp,
                    view.getResources().getDisplayMetrics().densityDpi};
        });
        float[] world = read(() -> new float[]{privateFloat(GameView.class, view, "scale"),
                privateFloat(GameView.class, view, "offsetX"), privateFloat(GameView.class, view, "worldHeight"),
                privateFloat(GameEngine.class, view.engine, "width"), privateFloat(GameEngine.class, view.engine, "height")});
        viewportDiagnostics = new JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("viewWidth", dimensions[0]).put("viewHeight", dimensions[1])
                .put("decorWidth", dimensions[2]).put("decorHeight", dimensions[3])
                .put("windowLeft", dimensions[4]).put("windowTop", dimensions[5])
                .put("windowWidth", dimensions[6]).put("windowHeight", dimensions[7])
                .put("viewLeft", dimensions[8]).put("viewTop", dimensions[9])
                .put("smallestScreenWidthDp", dimensions[11]).put("densityDpi", dimensions[12])
                .put("scale", world[0]).put("offsetX", world[1]).put("worldWidth", world[3])
                .put("worldHeight", world[2]).put("engineHeight", world[4]);
        write("viewport-diagnostics.json", viewportDiagnostics.toString(2));
        expect(dimensions[0] > dimensions[1] && dimensions[1] > 0
                        && dimensions[10] == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                "Actual play surface and configuration are landscape");
        expect(dimensions[0] == dimensions[2] && dimensions[1] == dimensions[3]
                        && dimensions[0] == dimensions[6] && dimensions[1] == dimensions[7]
                        && dimensions[8] == dimensions[4] && dimensions[9] == dimensions[5],
                "Immersive play surface fills its actual window without a smaller phone-sized canvas");
        float expectedScale = Math.min(dimensions[0] / 1000f, dimensions[1] / 420f);
        expect(Math.abs(world[0] - expectedScale) < .001f
                        && Math.abs(world[1] - (dimensions[0] - 1000 * expectedScale) * .5f) < .1f
                        && Math.abs(world[2] * world[0] - dimensions[1]) < 1,
                "Rendered canvas scale, centering, and full height match the measured window");
        expect(world[3] == 1000 && Math.abs(world[4] - world[2]) < .01f,
                "Physics uses the full visible canvas height on both tall tablets and wide phones");

        float middleY = world[2] * .5f;
        float lowerY = world[2] - 84;
        float[][] targets = {{48, middleY, 28, 20}, {952, middleY, 35, 12}, {500, lowerY, 42, 8}};
        String[] locations = {"left edge", "right edge", "lower play field"};
        for (int i = 0; i < targets.length; i++) {
            final int type = i;
            final float[] target = targets[i];
            quietBladeScene(GameEngine.Mode.CLASSIC);
            fixture(() -> view.engine.spawnFruit(type, target[0], target[1], 0, 0, target[2]));
            if (i < 2) swipe(target[0], target[1] - 60, target[0], target[1] + 60, 45);
            else swipe(target[0] - 70, target[1], target[0] + 70, target[1], 45);
            expect(read(() -> view.engine.sliced == 1 && view.engine.score == (int) target[3]),
                    "Real touchscreen hits fruit at the " + locations[i] + " with its correct size reward");
        }
        screenshot("11-viewport-low-cut.png");

        tapViewButton("pauseButton");
        expect(read(() -> view.overlay == GameView.Overlay.PAUSE),
                "Actual inset-aware pause button responds on this window size");
        screenshot("12-large-screen-pause.png");
        float paused = read(() -> view.engine.remaining);
        tapViewButton("mainButton");
        expect(read(() -> view.overlay == GameView.Overlay.NONE && view.engine.sliced == 1 && view.engine.score == 8),
                "Actual centered Continue button resumes the same large-screen round");
        tapViewButton("historyButton");
        expect(read(() -> view.overlay == GameView.Overlay.HISTORY),
                "Actual inset-aware history button opens local records on this window size");
        float historyTime = read(() -> view.engine.remaining);
        SystemClock.sleep(250);
        expect(Math.abs(read(() -> view.engine.remaining) - historyTime) < .001f && historyTime <= paused,
                "Large-screen history navigation freezes the round timer");
        screenshot("13-large-screen-history.png");
        tapViewButton("closeButton");
        expect(read(() -> view.overlay == GameView.Overlay.PAUSE),
                "Actual history close button returns to the paused round");
        awaitScreenshotFrame("large-screen-continue-layout");
        tapViewButton("mainButton");
        expect(read(() -> view.overlay == GameView.Overlay.NONE && view.engine.score == 8),
                "Large-screen history closes and resumes without starting or scoring another round");
    }

    private float privateFloat(Class<?> type, Object target, String name) {
        try {
            java.lang.reflect.Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.getFloat(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    /** Read the button actually drawn, rather than assuming one phone's insets or card position. */
    private void tapViewButton(String name) throws Exception {
        java.lang.reflect.Field field = GameView.class.getDeclaredField(name);
        field.setAccessible(true);
        RectF bounds = read(() -> {
            try { return new RectF((RectF) field.get(view)); }
            catch (IllegalAccessException failure) { throw new AssertionError(failure); }
        });
        float height = read(() -> view.getHeight() / screenScale());
        if (bounds.isEmpty() || bounds.left < 0 || bounds.right > 1000 || bounds.top < 0 || bounds.bottom > height) {
            throw new AssertionError("Button must fit the visible canvas: " + name + " " + bounds);
        }
        tap(bounds.centerX(), bounds.centerY());
    }

    private void sizeAndScoreChecks() throws Exception {
        expect(GameEngine.MIN_FRUIT_RADIUS * 2 == GameEngine.MAX_FRUIT_RADIUS,
                "Smallest fruit diameter is exactly one half of largest");
        int[] points = {20, 12, 8, 5};
        float[] radii = {28, 35, 42, 56};
        for (int i = 0; i < radii.length; i++) {
            final float radius = radii[i];
            final int type = i;
            fixture(() -> {
                view.engine.restart();
                view.engine.spawnFruit(type, 500, 250, 0, 0, radius);
            });
            if (i == 0) {
                swipe(430, 216, 570, 216, 30);
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
            view.engine.spawnFruit(0, 430, 250, 0, 0, 28);
            view.engine.spawnFruit(3, 590, 250, 0, 0, 56);
        });
        swipe(365, 250, 655, 250, 60);
        expect(read(() -> view.engine.score == 50 && view.engine.sliced == 2),
                "Double mode doubles different size rewards to 40 and 10");
    }

    private void antiScribbleChecks() throws Exception {
        bladeTouchScale = read(this::screenScale);
        bladeTouchLeft = read(() -> (view.getWidth() - 1000 * bladeTouchScale) * .5f);
        bladeStartedAt = BladeLimiter.class.getDeclaredField("startedAt");
        bladeLastEventTime = BladeLimiter.class.getDeclaredField("lastEventTime");
        bladeStartedAt.setAccessible(true); bladeLastEventTime.setAccessible(true);
        runOnMainSync(() -> view.setOnTouchListener((target, event) -> {
            recordBladeReceived(event);
            return false; // Observe the real delivered samples without consuming gameplay input.
        }));
        try { antiScribbleScenes(); }
        finally { runOnMainSync(() -> view.setOnTouchListener(null)); }
    }

    private void antiScribbleScenes() throws Exception {
        quietBladeScene(GameEngine.Mode.CLASSIC);
        long down = SystemClock.uptimeMillis();
        bladeTouch(down, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{100}, new float[]{250});
        SystemClock.sleep(650);
        expect(read(() -> !view.bladeLimiter.isStarted() && !view.bladeLimiter.isExhausted()),
                "Holding still longer than 500 ms does not consume or start the blade");
        fixture(() -> view.engine.spawnFruit(0, 500, 250, 0, 0, 28));
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{600}, new float[]{250});
        bladeTouch(down, MotionEvent.ACTION_UP, new int[]{0}, new float[]{600}, new float[]{250});
        expect(read(() -> view.engine.sliced == 1 && view.engine.score == 20),
                "A normal short cut still works after waiting with a finger held down");

        for (GameEngine.Mode mode : GameEngine.Mode.values()) {
            quietBladeScene(mode);
            down = SystemClock.uptimeMillis();
            bladeTouch(down, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{100}, new float[]{250});
            bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{110}, new float[]{250});
            SystemClock.sleep(560);
            expect(read(() -> view.bladeLimiter.isExhausted()),
                    "500 ms closes a held blade in " + mode.name());
            fixture(() -> {
                view.engine.fruits.clear(); view.engine.score = 100;
                view.engine.spawnFruit(0, 300, 250, 0, 0, 28);
                view.engine.spawnFruit(6, 500, 250, 0, 0, 31);
                if (mode != GameEngine.Mode.FRENZY && mode != GameEngine.Mode.ZEN) {
                    view.engine.spawnFruit(7, 700, 250, 0, 0, 28);
                }
            });
            bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{900}, new float[]{250});
            expect(read(() -> view.engine.score == 100 && view.engine.sliced == 0
                            && view.engine.switches == 0 && view.engine.bombsHit == 0 && view.engine.mode == mode),
                    "An exhausted blade cannot cut new fruit, switch modes, or hit bombs in " + mode.name());
            if (mode == GameEngine.Mode.CLASSIC) screenshot("09-blade-limit.png");
            bladeTouch(down, MotionEvent.ACTION_UP, new int[]{0}, new float[]{900}, new float[]{250});
            expect(read(() -> !view.bladeLimiter.isStarted() && !view.bladeLimiter.isExhausted()),
                    "Lifting every finger restores the next blade in " + mode.name());
        }

        quietBladeScene(GameEngine.Mode.CLASSIC);
        down = SystemClock.uptimeMillis();
        bladeTouch(down, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{100}, new float[]{250});
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{900}, new float[]{250});
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{700}, new float[]{250});
        JSONObject pathActual = bladeState("path-before-clipped-segment");
        expect(!pathActual.getBoolean("exhausted") && Math.abs(pathActual.getDouble("pathUsed") - 1000) < .5f,
                "A fast blade can use 1000 logical units before reaching its 1200-unit limit. Actual="
                        + pathActual + "; recent injection times=" + recentBladeEvents());
        fixture(() -> {
            view.engine.spawnFruit(0, 550, 250, 0, 0, 28);
            view.engine.spawnFruit(1, 450, 250, 0, 0, 28);
        });
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{100}, new float[]{250});
        JSONObject clippedActual = bladeState("path-after-clipped-segment");
        expect(clippedActual.getBoolean("exhausted") && Math.abs(clippedActual.getDouble("pathUsed") - 1200) < .5f,
                "Fast scribbling exhausts the shared path allowance before the time deadline. Actual="
                        + clippedActual + "; recent injection times=" + recentBladeEvents());
        expect(read(() -> view.engine.sliced == 1 && view.engine.score == 20
                        && view.engine.fruits.stream().anyMatch(f -> f.type == 1 && f.active)),
                "The final over-budget segment cuts its allowed target and clips before the farther fruit");
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{800}, new float[]{250});
        expect(read(() -> view.engine.sliced == 1 && view.engine.score == 20),
                "Continuing the same finger after path exhaustion cannot cut the excluded fruit");
        bladeTouch(down, MotionEvent.ACTION_UP, new int[]{0}, new float[]{800}, new float[]{250});
        fixture(() -> { view.engine.fruits.clear(); view.engine.spawnFruit(2, 500, 250, 0, 0, 28); });
        swipe(450, 250, 550, 250, 45);
        expect(read(() -> view.engine.sliced == 2 && view.engine.score == 40),
                "A new DOWN after complete lift cuts normally after path exhaustion");

        quietBladeScene(GameEngine.Mode.ZEN);
        down = SystemClock.uptimeMillis();
        bladeTouch(down, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{100}, new float[]{250});
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{500}, new float[]{250});
        bladeTouch(down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{0, 1}, new float[]{500, 100}, new float[]{250, 250});
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0, 1}, new float[]{500, 500}, new float[]{250, 250});
        JSONObject sharedActual = bladeState("two-pointer-path");
        expect(Math.abs(sharedActual.getDouble("pathUsed") - 800) < .5f,
                "Two real touchscreen pointers share one cumulative path budget. Actual="
                        + sharedActual + "; recent injection times=" + recentBladeEvents());
        bladeTouch(down, MotionEvent.ACTION_POINTER_UP, new int[]{0, 1}, new float[]{500, 500}, new float[]{250, 250});
        bladeTouch(down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{1, 2}, new float[]{500, 100}, new float[]{250, 250});
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{1, 2}, new float[]{500, 500}, new float[]{250, 250});
        JSONObject rotatedActual = bladeState("rotated-pointer-path");
        expect(rotatedActual.getBoolean("exhausted") && Math.abs(rotatedActual.getDouble("pathUsed") - 1200) < .5f,
                "Replacing one finger while another stays down cannot refresh the path allowance. Actual="
                        + rotatedActual + "; recent injection times=" + recentBladeEvents());
        bladeTouch(down, MotionEvent.ACTION_POINTER_UP, new int[]{1, 2}, new float[]{500, 500}, new float[]{250, 250});
        bladeTouch(down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{2, 3}, new float[]{500, 100}, new float[]{250, 250});
        fixture(() -> view.engine.spawnFruit(3, 700, 250, 0, 0, 28));
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{2, 3}, new float[]{500, 800}, new float[]{250, 250});
        expect(read(() -> view.bladeLimiter.isExhausted() && view.engine.sliced == 0),
                "Adding and rotating fingers after exhaustion cannot resume cutting");
        bladeTouch(down, MotionEvent.ACTION_POINTER_UP, new int[]{2, 3}, new float[]{500, 800}, new float[]{250, 250});
        bladeTouch(down, MotionEvent.ACTION_UP, new int[]{3}, new float[]{800}, new float[]{250});
        expect(read(() -> !view.bladeLimiter.isStarted() && view.bladeLimiter.pathUsed() == 0),
                "Only the final pointer leaving clears the shared exhausted gesture");

        quietBladeScene(GameEngine.Mode.CLASSIC);
        down = SystemClock.uptimeMillis();
        bladeTouch(down, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{100}, new float[]{250});
        SystemClock.sleep(620);
        fixture(() -> view.engine.spawnFruit(0, 650, 250, 0, 0, 28));
        long current = SystemClock.uptimeMillis();
        MotionEvent batch = bladeEvent(down, current - 550, MotionEvent.ACTION_MOVE,
                new int[]{0}, new float[]{110}, new float[]{250});
        batch.addBatch(current - 530, bladeCoordinates(new float[]{120}, new float[]{250}), 0);
        batch.addBatch(current, bladeCoordinates(new float[]{900}, new float[]{250}), 0);
        sendPointerSync(batch); batch.recycle();
        expect(read(() -> view.bladeLimiter.isExhausted() && view.engine.sliced == 0
                        && view.bladeLimiter.pathUsed() < 25),
                "Historical MotionEvent sample times prevent a delayed batched MOVE from extending blade life");
        bladeTouch(down, MotionEvent.ACTION_UP, new int[]{0}, new float[]{900}, new float[]{250});

        quietBladeScene(GameEngine.Mode.CLASSIC);
        down = SystemClock.uptimeMillis();
        bladeTouch(down, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{100}, new float[]{250});
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{110}, new float[]{250});
        bladeTouch(down, MotionEvent.ACTION_CANCEL, new int[]{0}, new float[]{110}, new float[]{250});
        expect(read(() -> !view.bladeLimiter.isStarted() && !view.bladeLimiter.isExhausted()
                        && view.bladeLimiter.pathUsed() == 0),
                "A real ACTION_CANCEL discards every pointer and its blade budget");
        fixture(() -> view.engine.spawnFruit(0, 500, 250, 0, 0, 28));
        swipe(450, 250, 550, 250, 45);
        expect(read(() -> view.engine.sliced == 1 && view.engine.score == 20),
                "A fresh DOWN works normally after cancellation");

        quietBladeScene(GameEngine.Mode.CLASSIC);
        down = SystemClock.uptimeMillis();
        bladeTouch(down, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{100}, new float[]{250});
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{110}, new float[]{250});
        fixture(() -> { view.engine.spawnFruit(0, 500, 250, 0, 0, 56); view.background(); view.foreground(); });
        expect(read(() -> view.overlay == GameView.Overlay.PAUSE && !view.bladeLimiter.isStarted()),
                "Background and foreground cancel an active blade and retain the pause card");
        float cardTop = read(() -> Math.max(12, (view.getHeight() / screenScale() - 360) / 2));
        bladeTouch(down, MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{500}, new float[]{cardTop + 256});
        bladeTouch(down, MotionEvent.ACTION_UP, new int[]{0}, new float[]{500}, new float[]{cardTop + 256});
        expect(read(() -> view.overlay == GameView.Overlay.PAUSE && view.engine.sliced == 0
                        && !view.bladeLimiter.isStarted()),
                "A held gameplay drag cannot cut through the pause card or activate its Continue button");
        screenshot("10-paused-touch-lock.png");
        tap(500, cardTop + 256);
        expect(read(() -> view.overlay == GameView.Overlay.NONE && !view.bladeLimiter.isStarted()),
                "Continue consumes its own fresh touch without starting a gameplay blade");
        swipe(420, 250, 580, 250, 45);
        expect(read(() -> view.engine.sliced == 1 && view.engine.score == 5),
                "Gameplay after background pause requires and accepts a new DOWN");
        quietBladeScene(GameEngine.Mode.CLASSIC);
    }

    /** Avoid random launches during timing-sensitive held-finger scenes, only in this test APK. */
    private void quietBladeScene(GameEngine.Mode mode) throws Exception {
        java.lang.reflect.Field wave = GameEngine.class.getDeclaredField("nextWave");
        java.lang.reflect.Field special = GameEngine.class.getDeclaredField("nextSpecial");
        wave.setAccessible(true); special.setAccessible(true);
        fixture(() -> {
            view.engine.restart(); view.engine.mode = mode; view.engine.modeRemaining = 30;
            try { wave.setFloat(view.engine, 60); special.setFloat(view.engine, 60); }
            catch (IllegalAccessException failure) { throw new AssertionError(failure); }
        });
    }

    private MotionEvent.PointerCoords[] bladeCoordinates(float[] xs, float[] ys) {
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[xs.length];
        for (int i = 0; i < xs.length; i++) {
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = bladeTouchLeft + xs[i] * bladeTouchScale;
            coordinates[i].y = ys[i] * bladeTouchScale;
            coordinates[i].pressure = 1; coordinates[i].size = .1f;
        }
        return coordinates;
    }

    private MotionEvent bladeEvent(long down, long time, int action, int[] ids, float[] xs, float[] ys) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i]; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
        }
        return MotionEvent.obtain(down, time, action, ids.length, properties, bladeCoordinates(xs, ys),
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private void bladeTouch(long down, int action, int[] ids, float[] xs, float[] ys) {
        long sent = SystemClock.uptimeMillis();
        MotionEvent event = bladeEvent(down, sent, action, ids, xs, ys);
        sendPointerSync(event); event.recycle();
        try {
            recordBladeDiagnostic(new JSONObject().put("kind", "injected-touch").put("action", action)
                    .put("downTime", down).put("eventTime", sent).put("returnedAt", SystemClock.uptimeMillis())
                    .put("ids", new JSONArray(ids)).put("logicalX", new JSONArray(xs)).put("logicalY", new JSONArray(ys)));
        } catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
    }

    private JSONObject bladeState(String phase) throws Exception {
        double[] values = read(() -> new double[]{view.bladeLimiter.pathUsed(), view.bladeLimiter.isStarted() ? 1 : 0,
                view.bladeLimiter.isExhausted() ? 1 : 0, view.engine.elapsed, SystemClock.uptimeMillis(),
                bladePrivateTime(bladeStartedAt), bladePrivateTime(bladeLastEventTime)});
        JSONObject state = new JSONObject().put("kind", "observed-state").put("phase", phase)
                .put("pathUsed", values[0]).put("started", values[1] == 1).put("exhausted", values[2] == 1)
                .put("engineElapsed", values[3]).put("observedAt", (long) values[4])
                .put("startedAt", (long) values[5]).put("lastEventTime", (long) values[6])
                .put("wallSinceStarted", values[1] == 1 ? (long) (values[4] - values[5]) : -1);
        recordBladeDiagnostic(state);
        return state;
    }

    /** This listener records delivered coordinates/times, including Android-added history samples. */
    private void recordBladeReceived(MotionEvent event) {
        try {
            JSONArray samples = new JSONArray();
            for (int sample = 0; sample <= event.getHistorySize(); sample++) {
                boolean current = sample == event.getHistorySize();
                JSONArray pointers = new JSONArray();
                for (int pointer = 0; pointer < event.getPointerCount(); pointer++) {
                    MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
                    if (current) event.getPointerCoords(pointer, coordinates);
                    else event.getHistoricalPointerCoords(pointer, sample, coordinates);
                    float x = coordinates.x, y = coordinates.y;
                    pointers.put(new JSONObject().put("id", event.getPointerId(pointer))
                            .put("x", (x - bladeTouchLeft) / bladeTouchScale).put("y", y / bladeTouchScale)
                            .put("pixelX", x).put("pixelY", y)
                            .put("isResampled", android.os.Build.VERSION.SDK_INT >= 35 && coordinates.isResampled()));
                }
                samples.put(new JSONObject().put("time", current ? event.getEventTime() : event.getHistoricalEventTime(sample))
                        .put("current", current).put("pointers", pointers));
            }
            recordBladeDiagnostic(new JSONObject().put("kind", "received-touch").put("action", event.getAction())
                    .put("downTime", event.getDownTime()).put("eventTime", event.getEventTime())
                    .put("receivedAt", SystemClock.uptimeMillis()).put("samples", samples)
                    .put("resampleFlagAvailable", android.os.Build.VERSION.SDK_INT >= 35)
                    .put("beforePathUsed", view.bladeLimiter.pathUsed()).put("beforeStarted", view.bladeLimiter.isStarted())
                    .put("beforeExhausted", view.bladeLimiter.isExhausted()).put("beforeEngineElapsed", view.engine.elapsed)
                    .put("beforeStartedAt", bladePrivateTime(bladeStartedAt))
                    .put("beforeLastEventTime", bladePrivateTime(bladeLastEventTime)));
        } catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
    }

    private long bladePrivateTime(java.lang.reflect.Field field) {
        try { return field == null ? -1 : field.getLong(view.bladeLimiter); }
        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    }

    private void recordBladeDiagnostic(JSONObject event) {
        synchronized (bladeDiagnostics) { bladeDiagnostics.put(event); }
    }

    private String bladeDiagnosticText() throws org.json.JSONException {
        synchronized (bladeDiagnostics) { return bladeDiagnostics.toString(2); }
    }

    private JSONArray recentBladeEvents() {
        JSONArray recent = new JSONArray();
        synchronized (bladeDiagnostics) {
            for (int i = Math.max(0, bladeDiagnostics.length() - 10); i < bladeDiagnostics.length(); i++) {
                recent.put(bladeDiagnostics.opt(i));
            }
        }
        return recent;
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
        awaitScreenshotFrame(name);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable");
        try (FileOutputStream stream = new FileOutputStream(new File(evidence, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        bitmap.recycle();
        screenshots.put(name);
    }

    /** State may change before RenderThread submits its matching buffer; wait for that frame. */
    private void awaitScreenshotFrame(String name) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Runnable frameReady = ready::countDown;
        // Submission can precede display composition, so capture after the following vsync too.
        Runnable frameCommitted = () -> view.postOnAnimation(frameReady);
        ViewTreeObserver.OnDrawListener softwareDrawn = () -> view.postOnAnimation(frameReady);
        AtomicReference<ViewTreeObserver> observer = new AtomicReference<>();
        boolean hardwareCommit = read(() -> android.os.Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated());
        runOnMainSync(() -> {
            ViewTreeObserver tree = view.getViewTreeObserver();
            observer.set(tree);
            if (!tree.isAlive() || !view.isAttachedToWindow()) {
                throw new AssertionError("Screenshot View is detached: " + name);
            }
            if (hardwareCommit) tree.registerFrameCommitCallback(frameCommitted);
            else tree.addOnDrawListener(softwareDrawn);
            view.invalidate();
        });
        try {
            if (!ready.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("Screenshot frame did not complete within 3 seconds: " + name
                        + "; hardwareCommit=" + hardwareCommit);
            }
        } finally {
            runOnMainSync(() -> {
                ViewTreeObserver tree = observer.get();
                if (tree != null && tree.isAlive()) {
                    if (hardwareCommit) tree.unregisterFrameCommitCallback(frameCommitted);
                    else tree.removeOnDrawListener(softwareDrawn);
                }
                view.removeCallbacks(frameReady);
            });
        }
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
            canvas.drawText("最小直径 = 最大的 1/2 · 小果高分", 500, 395, p);
            float[] radii = {56, 42, 35, 28};
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
