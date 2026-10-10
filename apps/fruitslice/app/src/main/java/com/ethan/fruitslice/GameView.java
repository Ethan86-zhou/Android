package com.ethan.fruitslice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** A single immersive play surface. All physics and touch coordinates share one world. */
@SuppressLint("ViewConstructor")
final class GameView extends View {
    enum Overlay { NONE, PAUSE, HISTORY, RESULT }
    final GameEngine engine = new GameEngine(System.nanoTime());
    final HistoryStore history;
    final GameArt art = new GameArt();
    final GameAudio audio;
    Overlay overlay = Overlay.NONE;
    private Overlay historyReturn = Overlay.PAUSE;
    private HistoryStore.Record round;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final SparseArray<PointF> touches = new SparseArray<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Half> halves = new ArrayList<>();
    private final ArrayList<Popup> popups = new ArrayList<>();
    private final ArrayList<Blade> blades = new ArrayList<>();
    private final RectF pauseButton = new RectF(), historyButton = new RectF();
    private final RectF mainButton = new RectF(), secondButton = new RectF(), closeButton = new RectF();
    private float scale = 1, worldHeight = 460, offsetX, safeLeft = 24, safeRight = 24;
    private int previousBest;
    private boolean active, disposed, saved;
    private long lastFrame, lastSnapshot, nextCheckpoint;
    private float realClock, flash, shake, bannerTime;
    private String banner = "", bannerSub = "";
    private int bannerColor = Color.rgb(255, 210, 113);
    private int pressedAction;
    private float downY, historyScroll, historyStartScroll;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MM/dd HH:mm", Locale.CHINA);
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    GameView(Context context) {
        super(context);
        setFocusable(true);
        setClickable(true);
        setContentDescription("果刃切水果游戏。滑动切水果，避开炸弹，切开彩虹果实随机换模式。右上角为历史与暂停。");
        history = new HistoryStore(context);
        audio = new GameAudio(context);
        HistoryStore.Record pending = history.getCheckpoint();
        if (pending != null) {
            pending.reason = "interrupted";
            history.save(pending);
        }
        previousBest = history.bestScore();
        round = history.draft(0, 0, 0, 0, 0, 0, 0, "playing");
        nextCheckpoint = SystemClock.uptimeMillis() + 5000;
        setOnApplyWindowInsetsListener((v, insets) -> {
            updateInsets(insets);
            return insets;
        });
    }

    private void updateInsets(WindowInsets insets) {
        android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
        int left = bars.left, right = bars.right;
        if (insets.getDisplayCutout() != null) {
            left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
            right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
        }
        safeLeft = Math.max(24, (left - offsetX) / scale + 12);
        safeRight = Math.max(24, (right - offsetX) / scale + 12);
        layoutButtons();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        scale = Math.min(w / 1000f, h / 420f);
        offsetX = (w - 1000 * scale) * .5f;
        worldHeight = h / scale;
        engine.setBounds(1000, worldHeight);
        if (getRootWindowInsets() != null) updateInsets(getRootWindowInsets());
        layoutButtons();
    }

    private void layoutButtons() {
        pauseButton.set(1000 - safeRight - 48, 22, 1000 - safeRight, 70);
        historyButton.set(pauseButton.left - 59, 22, pauseButton.left - 11, 70);
    }

    void foreground() {
        active = true;
        lastFrame = 0;
        invalidate();
    }

    void focusLost() {
        if (active && overlay == Overlay.NONE) {
            pause();
            invalidate();
        }
    }

    void background() {
        if (overlay == Overlay.NONE) overlay = Overlay.PAUSE;
        finishTouch();
        checkpoint();
        audio.pause();
        active = false;
        lastFrame = 0;
    }

    void dispose() {
        disposed = true;
        audio.release();
    }

    void back() {
        if (overlay == Overlay.HISTORY) overlay = historyReturn;
        else if (overlay == Overlay.NONE) pause();
        else if (overlay == Overlay.PAUSE) resume();
        invalidate();
    }

    private void pause() {
        finishTouch();
        overlay = Overlay.PAUSE;
        audio.pause();
        checkpoint();
    }

    private void resume() {
        overlay = Overlay.NONE;
        lastFrame = 0;
    }

    private void openHistory() {
        finishTouch();
        historyReturn = overlay == Overlay.NONE ? Overlay.PAUSE : overlay;
        overlay = Overlay.HISTORY;
        historyScroll = 0;
        audio.pause();
        checkpoint();
    }

    private void restart() {
        engine.restart();
        engine.setBounds(1000, worldHeight);
        previousBest = history.bestScore();
        round = history.draft(0, 0, 0, 0, 0, 0, 0, "playing");
        saved = false;
        particles.clear(); halves.clear(); popups.clear(); blades.clear();
        bannerTime = flash = shake = 0;
        overlay = Overlay.NONE;
        lastFrame = 0;
        nextCheckpoint = SystemClock.uptimeMillis() + 5000;
        history.clearCheckpoint();
    }

    private void updateRecord(String reason) {
        round.score = engine.score;
        round.sliced = engine.sliced;
        round.maxCombo = engine.maxCombo;
        round.misses = engine.misses;
        round.bombs = engine.bombsHit;
        round.switches = engine.switches;
        round.duration = engine.elapsed;
        round.reason = reason;
    }

    private void checkpoint() {
        if (!saved && engine.elapsed > 0.1f) {
            updateRecord("playing");
            history.checkpoint(round);
        }
    }

    private void complete() {
        if (saved) return;
        finishTouch();
        updateRecord(engine.misses >= 3 ? "misses" : "time");
        history.save(round);
        saved = true;
        overlay = Overlay.RESULT;
        audio.pause();
        announceForAccessibility("本局结束，得分 " + engine.score + "，最高连击 " + engine.maxCombo);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.05f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        realClock += dt;
        if (active && overlay == Overlay.NONE) {
            engine.update(dt);
            processEvents();
            if (engine.gameOver) complete();
            if (SystemClock.uptimeMillis() > nextCheckpoint) {
                checkpoint();
                nextCheckpoint = SystemClock.uptimeMillis() + 5000;
            }
        }
        updateEffects(dt);
        canvas.drawColor(0xFF160F0A);
        canvas.save();
        canvas.translate(offsetX, 0);
        canvas.scale(scale, scale);
        if (shake > 0 && overlay == Overlay.NONE) canvas.translate(
                (random.nextFloat() - .5f) * shake * 12, (random.nextFloat() - .5f) * shake * 9);
        art.background(canvas, 1000, worldHeight);
        drawModeAtmosphere(canvas);
        drawFruit(canvas);
        drawEffects(canvas);
        drawBlades(canvas);
        drawHud(canvas);
        if (engine.elapsed < 4.8f && engine.sliced == 0 && overlay == Overlay.NONE) drawIntro(canvas);
        if (bannerTime > 0) drawBanner(canvas);
        if (flash > 0) {
            paint.setColor(Color.argb((int) (flash * 100), 255, 65, 40));
            canvas.drawRect(0, 0, 1000, worldHeight, paint);
        }
        if (overlay != Overlay.NONE) drawOverlay(canvas);
        canvas.restore();
        if (BuildConfig.DEBUG && SystemClock.uptimeMillis() - lastSnapshot > 300) {
            lastSnapshot = SystemClock.uptimeMillis();
            writeSnapshot();
        }
        if (active && !disposed) postInvalidateOnAnimation();
    }

    private void processEvents() {
        for (GameEngine.Event event : engine.drainEvents()) {
            switch (event.kind) {
                case SLICE -> {
                    addSlice(event);
                    audio.slice(engine.maxCombo);
                    if (SystemClock.uptimeMillis() % 3 == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                case BOMB -> {
                    flash = 1; shake = 1;
                    burst(event.x, event.y, Color.rgb(255, 176, 61), 35);
                    popup("−10  /  −3秒", event.x, event.y - 35, 0xFFFF8070, 30);
                    audio.bomb();
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
                case COMBO -> {
                    popup(event.value + " 连击", event.x, event.y - 45, 0xFFFFDD8A, 44);
                    audio.combo();
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                }
                case MODE -> {
                    banner = modeName(engine.mode);
                    bannerSub = modeDescription(engine.mode);
                    bannerTime = 2.1f;
                    bannerColor = modeColor(engine.mode);
                    if (engine.mode != GameEngine.Mode.CLASSIC) {
                        burst(event.x, event.y, bannerColor, 42);
                        audio.mode();
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                    announceForAccessibility(banner + "，" + bannerSub);
                }
                case MISS -> popup("漏掉了", event.x, worldHeight - 56, 0xFFF69A7F, 21);
                case OVER -> { /* Saving happens once after all events have been handled. */ }
            }
        }
    }

    private void addSlice(GameEngine.Event event) {
        if (event.type == 6) { burst(event.x, event.y, 0xFFECC1FF, 28); return; }
        for (int side = 0; side < 2; side++) {
            Half h = new Half();
            h.x = event.x; h.y = event.y; h.type = event.type;
            h.left = side == 0; h.r = event.type == 4 ? 29 : 28;
            h.vx = (side == 0 ? -1 : 1) * (65 + random.nextFloat() * 80);
            h.vy = -75 - random.nextFloat() * 75;
            h.spin = (side == 0 ? -1 : 1) * (100 + random.nextFloat() * 160);
            h.life = 1.15f;
            halves.add(h);
        }
        burst(event.x, event.y, art.juiceColor(event.type), 13);
        popup("+" + event.value, event.x, event.y - 20, 0xFFFFE3AB, 25);
    }

    private void burst(float x, float y, int color, int count) {
        for (int i = 0; i < count && particles.size() < 240; i++) {
            Particle p = new Particle(); p.x = x; p.y = y;
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = 55 + random.nextFloat() * 240;
            p.vx = (float) Math.cos(angle) * speed; p.vy = (float) Math.sin(angle) * speed;
            p.r = 1.6f + random.nextFloat() * 5; p.color = color;
            p.life = p.total = .4f + random.nextFloat() * .5f;
            particles.add(p);
        }
    }

    private void popup(String text, float x, float y, int color, int size) {
        Popup p = new Popup(); p.text = text; p.x = Math.max(75, Math.min(925, x));
        p.y = Math.max(115, y); p.color = color; p.size = size; p.life = p.total = .9f;
        if (popups.size() >= 18) popups.remove(0);
        popups.add(p);
    }

    private void updateEffects(float dt) {
        // Frozen play also freezes its visual debris; the menu remains stable and readable.
        if (overlay != Overlay.NONE) return;
        flash = Math.max(0, flash - dt * 3.2f); shake = Math.max(0, shake - dt * 2.6f);
        bannerTime = Math.max(0, bannerTime - dt);
        for (Iterator<Particle> it = particles.iterator(); it.hasNext();) {
            Particle p = it.next(); p.life -= dt;
            if (p.life <= 0) { it.remove(); continue; }
            p.x += p.vx * dt; p.y += p.vy * dt; p.vy += 320 * dt;
        }
        for (Iterator<Half> it = halves.iterator(); it.hasNext();) {
            Half h = it.next(); h.life -= dt;
            if (h.life <= 0) { it.remove(); continue; }
            h.x += h.vx * dt; h.y += h.vy * dt; h.vy += 390 * dt; h.angle += h.spin * dt;
        }
        for (Iterator<Popup> it = popups.iterator(); it.hasNext();) { Popup p = it.next(); p.life -= dt; p.y -= dt * 48; if (p.life < 0) it.remove(); }
        for (Iterator<Blade> it = blades.iterator(); it.hasNext();) { Blade b = it.next(); b.life -= dt; if (b.life < 0) it.remove(); }
    }

    private void drawModeAtmosphere(Canvas c) {
        if (engine.mode == GameEngine.Mode.CLASSIC) return;
        int color = modeColor(engine.mode);
        paint.setShader(new LinearGradient(0, worldHeight, 0, worldHeight - 110,
                new int[]{(color & 0xFFFFFF) | 0x35000000, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, worldHeight - 110, 1000, worldHeight, paint); paint.setShader(null);
        paint.setColor(color); paint.setAlpha(70);
        for (int i = 0; i < 14; i++) {
            float x = (i * 83 + realClock * (i % 2 == 0 ? 9 : -9) + 1000) % 1000;
            float y = worldHeight - ((realClock * 30 + i * 31) % worldHeight);
            c.drawCircle(x, y, 1 + i % 3, paint);
        }
        paint.setAlpha(255);
    }

    private void drawFruit(Canvas c) {
        for (GameEngine.Fruit f : engine.fruits) if (f.active) art.fruit(c, f.type, f.x, f.y, f.radius, f.angle, 1);
        for (Half h : halves) art.half(c, h.type, h.x, h.y, h.r, h.angle, h.left, Math.min(1, h.life * 2));
    }

    private void drawEffects(Canvas c) {
        for (Particle p : particles) {
            paint.setColor(p.color); paint.setAlpha((int) (255 * p.life / p.total));
            c.drawCircle(p.x, p.y, p.r * (.5f + .5f * p.life / p.total), paint);
        }
        paint.setAlpha(255);
        for (Popup p : popups) text(c, p.text, p.x, p.y, p.size,
                (p.color & 0xFFFFFF) | ((int) (Math.min(1, p.life * 3) * 255) << 24), Paint.Align.CENTER, true);
    }

    private void drawBlades(Canvas c) {
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (Blade b : blades) {
            float alpha = b.life / .18f;
            paint.setColor(Color.argb((int) (alpha * 80), 122, 220, 255)); paint.setStrokeWidth(14 * alpha);
            c.drawLine(b.x1, b.y1, b.x2, b.y2, paint);
            paint.setColor(Color.argb((int) (alpha * 255), 232, 252, 255)); paint.setStrokeWidth(5.5f * alpha);
            c.drawLine(b.x1, b.y1, b.x2, b.y2, paint);
        }
    }

    private void drawHud(Canvas c) {
        paint.setShader(new LinearGradient(0, 0, 0, 115, 0xC4190F0A, 0x00190F0A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1000, 115, paint); paint.setShader(null);
        text(c, "本 局", safeLeft + 5, 30, 14, 0xFFCFB18B, Paint.Align.LEFT, false);
        text(c, String.format(Locale.ROOT, "%03d", engine.score), safeLeft, 81, 56, 0xFFFFD589, Paint.Align.LEFT, true);
        text(c, "最高纪录", safeLeft + 130, 34, 13, 0xFFBFA484, Paint.Align.LEFT, false);
        text(c, Integer.toString(history.bestScore()), safeLeft + 130, 65, 26, 0xFFF4DCC0, Paint.Align.LEFT, true);
        for (int i = 0; i < 3; i++) {
            float x = safeLeft + 247 + i * 22;
            paint.setColor(i < engine.misses ? 0xFFFF785D : 0xFF76614D); paint.setStrokeWidth(3.5f);
            c.drawLine(x - 4, 44, x + 4, 52, paint); c.drawLine(x + 4, 44, x - 4, 52, paint);
        }
        boolean safe = engine.mode == GameEngine.Mode.ZEN || engine.mode == GameEngine.Mode.FRENZY;
        text(c, safe ? "漏果无惩罚" : "漏果三次结束", safeLeft + 268, 77, 11,
                safe ? 0xFFBDE39B : 0xFFAF9578, Paint.Align.CENTER, false);

        RectF chip = new RectF(433, 23, 621, 62);
        fill(c, chip, 20, 0x8423150E);
        stroke(c, chip, 20, (modeColor(engine.mode) & 0xFFFFFF) | 0x70000000, 1);
        text(c, "✦  " + modeName(engine.mode), 527, 49, 19, modeColor(engine.mode), Paint.Align.CENTER, true);
        if (engine.mode != GameEngine.Mode.CLASSIC) {
            text(c, modeDescription(engine.mode) + " · " + (int) Math.ceil(engine.modeRemaining) + "s", 527, 82,
                    12, 0xFFE8D6BE, Paint.Align.CENTER, false);
        } else text(c, "彩虹果实 · 随机换玩法", 527, 82, 12, 0xFFB79A7B, Paint.Align.CENTER, false);

        float timerX = historyButton.left - 61;
        text(c, "剩余", timerX - 37, 39, 12, 0xFFBBA184, Paint.Align.CENTER, false);
        text(c, String.format(Locale.ROOT, "%02d", (int) Math.ceil(engine.remaining)), timerX, 69, 42,
                engine.remaining <= 10 ? 0xFFFF8B6A : 0xFFF5E4C6, Paint.Align.CENTER, true);
        text(c, "秒", timerX + 29, 66, 12, 0xFFBBA184, Paint.Align.CENTER, false);
        drawIconButton(c, historyButton, false);
        drawIconButton(c, pauseButton, true);

        paint.setColor(0x3FCDAB7C); paint.setStrokeWidth(1);
        c.drawLine(safeLeft, worldHeight - 43, 1000 - safeRight, worldHeight - 43, paint);
        text(c, "果 刃", safeLeft, worldHeight - 19, 16, 0xFFE5C39B, Paint.Align.LEFT, true);
        text(c, "滑动切水果   /   避开炸弹   /   切开彩虹果实", 500, worldHeight - 20, 12, 0xFFB79B7B, Paint.Align.CENTER, false);
        text(c, "OFFLINE", 1000 - safeRight, worldHeight - 20, 10, 0xFF9D8265, Paint.Align.RIGHT, true);
        paint.setColor(modeColor(engine.mode));
        c.drawRect(safeLeft, worldHeight - 4, safeLeft + (1000 - safeLeft - safeRight) * engine.remaining / 60f, worldHeight - 2, paint);
    }

    private void drawIconButton(Canvas c, RectF rect, boolean pause) {
        fill(c, rect, 14, 0x4825150D); stroke(c, rect, 14, 0x507F6243, 1);
        float x = rect.centerX(), y = rect.centerY();
        paint.setColor(0xFFE4CBAB); paint.setStrokeWidth(2.2f);
        if (pause) {
            c.drawRoundRect(x - 7, y - 8, x - 3, y + 8, 1, 1, paint);
            c.drawRoundRect(x + 3, y - 8, x + 7, y + 8, 1, 1, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE); c.drawCircle(x, y, 9, paint); paint.setStyle(Paint.Style.FILL);
            c.drawLine(x, y, x, y - 5, paint); c.drawLine(x, y, x + 4, y + 2, paint);
        }
    }

    private void drawIntro(Canvas c) {
        float alpha = Math.min(1, (4.8f - engine.elapsed) * 1.5f);
        text(c, "一 刀 入 魂", 500, 133, 30,
                Color.argb((int) (alpha * 235), 255, 228, 182), Paint.Align.CENTER, true);
        text(c, "手指划过水果，即刻开切", 500, 159, 13,
                Color.argb((int) (alpha * 220), 202, 175, 139), Paint.Align.CENTER, false);
        float x = 405 + (realClock * 90 % 180);
        paint.setColor(Color.argb((int) (alpha * 115), 255, 230, 185)); paint.setStrokeWidth(2);
        c.drawLine(400, 177, 600, 177, paint);
        c.drawCircle(x, 177, 4, paint);
    }

    private void drawBanner(Canvas c) {
        float a = Math.min(1, bannerTime * 2.5f);
        fill(c, new RectF(344, 94, 656, 147), 13, Color.argb((int) (a * 175), 27, 19, 21));
        text(c, "✦  " + banner + "  ✦", 500, 117, 21,
                (bannerColor & 0xFFFFFF) | ((int) (a * 255) << 24), Paint.Align.CENTER, true);
        text(c, bannerSub, 500, 136, 12, Color.argb((int) (a * 235), 236, 219, 197), Paint.Align.CENTER, false);
    }

    private void drawOverlay(Canvas c) {
        paint.setColor(0xCB130E0B); c.drawRect(0, 0, 1000, worldHeight, paint);
        float top = Math.max(12, (worldHeight - 360) / 2);
        RectF card = new RectF(242, top, 758, Math.min(worldHeight - 12, top + 360));
        fill(c, card, 24, 0xFF2C2018); stroke(c, card, 24, 0xFF806241, 1.2f);
        paint.setColor(0xFFFFD18A); c.drawRoundRect(467, card.top, 533, card.top + 3, 2, 2, paint);
        closeButton.set(card.right - 54, card.top + 12, card.right - 12, card.top + 54);
        if (overlay == Overlay.HISTORY) {
            drawHistory(c, card);
            return;
        }
        if (overlay == Overlay.PAUSE) {
            text(c, "歇 一 刀", 500, card.top + 61, 31, 0xFFF9DCA7, Paint.Align.CENTER, true);
            text(c, "时间与水果都已暂停", 500, card.top + 94, 14, 0xFFBBA187, Paint.Align.CENTER, false);
            text(c, Integer.toString(engine.score), 500, card.top + 166, 61, 0xFFFFD485, Paint.Align.CENTER, true);
            text(c, "本局得分", 500, card.top + 190, 13, 0xFFC8AC8D, Paint.Align.CENTER, false);
            mainButton.set(318, card.bottom - 130, 682, card.bottom - 77);
            secondButton.set(318, card.bottom - 64, 682, card.bottom - 17);
            drawButton(c, mainButton, "继续开切", true); drawButton(c, secondButton, "历史记录", false);
        } else {
            boolean best = engine.score > previousBest;
            text(c, best ? "新 的 刀 锋 纪 录" : "这 一 刀，痛 快", 500, card.top + 49,
                    25, 0xFFF9DCA7, Paint.Align.CENTER, true);
            text(c, Integer.toString(engine.score), 500, card.top + 122, 69, 0xFFFFD485, Paint.Align.CENTER, true);
            text(c, engine.misses >= 3 ? "漏掉 3 个水果 · 成绩已保存" : "时间到 · 成绩已保存", 500, card.top + 148,
                    13, 0xFFBDA286, Paint.Align.CENTER, false);
            float y = card.top + 192;
            text(c, "切中水果", 363, y, 12, 0xFFBDA286, Paint.Align.CENTER, false);
            text(c, "最高连击", 500, y, 12, 0xFFBDA286, Paint.Align.CENTER, false);
            text(c, "模式切换", 637, y, 12, 0xFFBDA286, Paint.Align.CENTER, false);
            text(c, Integer.toString(engine.sliced), 363, y + 29, 25, 0xFFF4DAB7, Paint.Align.CENTER, true);
            text(c, Integer.toString(engine.maxCombo), 500, y + 29, 25, 0xFFF4DAB7, Paint.Align.CENTER, true);
            text(c, Integer.toString(engine.switches), 637, y + 29, 25, 0xFFF4DAB7, Paint.Align.CENTER, true);
            mainButton.set(318, card.bottom - 111, 682, card.bottom - 59);
            secondButton.set(318, card.bottom - 51, 682, card.bottom - 7);
            drawButton(c, mainButton, "再来一局", true); drawButton(c, secondButton, "查看历史", false);
        }
    }

    private void drawHistory(Canvas c, RectF card) {
        text(c, "刀 锋 履 历", 500, card.top + 44, 25, 0xFFF9DCA7, Paint.Align.CENTER, true);
        text(c, "本机最高  " + history.bestScore() + "    ·    最近 " + history.records().size() + " 局", 500,
                card.top + 73, 13, 0xFFC1A385, Paint.Align.CENTER, false);
        float x = closeButton.centerX(), y = closeButton.centerY();
        paint.setColor(0xFFE1C5A2); paint.setStrokeWidth(2);
        c.drawLine(x - 5, y - 5, x + 5, y + 5, paint); c.drawLine(x + 5, y - 5, x - 5, y + 5, paint);
        List<HistoryStore.Record> rows = history.records();
        float listTop = card.top + 95, listBottom = card.bottom - 24;
        if (rows.isEmpty()) {
            text(c, "第一份纪录，等你落刀", 500, card.centerY() + 10, 19, 0xFFC6AA8B, Paint.Align.CENTER, false);
            text(c, "完成一局后自动保存在这里", 500, card.centerY() + 40, 13, 0xFF927B64, Paint.Align.CENTER, false);
            return;
        }
        historyScroll = Math.max(0, Math.min(historyScroll, Math.max(0, rows.size() * 61 - (listBottom - listTop))));
        c.save(); c.clipRect(card.left + 20, listTop, card.right - 20, listBottom);
        for (int i = 0; i < rows.size(); i++) {
            float rowY = listTop + i * 61 - historyScroll;
            if (rowY + 61 < listTop || rowY > listBottom) continue;
            HistoryStore.Record r = rows.get(i);
            text(c, String.format(Locale.ROOT, "%02d", i + 1), card.left + 35, rowY + 32, 19, 0xFF917553, Paint.Align.LEFT, true);
            text(c, Integer.toString(r.score), card.left + 78, rowY + 31, 30, 0xFFFFD38A, Paint.Align.LEFT, true);
            text(c, dateFormat.format(r.timestamp), card.right - 34, rowY + 22, 13, 0xFFE2CAAA, Paint.Align.RIGHT, false);
            String end = "interrupted".equals(r.reason) ? "中断保存" : "misses".equals(r.reason) ? "漏果结束" : "时间到";
            text(c, r.sliced + " 果  ·  " + r.maxCombo + " 连击  ·  " + r.switches + " 换模式", card.left + 79,
                    rowY + 51, 11, 0xFFBBA084, Paint.Align.LEFT, false);
            text(c, end, card.right - 34, rowY + 48, 11, 0xFF947E66, Paint.Align.RIGHT, false);
            paint.setColor(0x306F553B); c.drawRect(card.left + 33, rowY + 59, card.right - 33, rowY + 60, paint);
        }
        c.restore();
        if (rows.size() > 4) text(c, "上下滑动查看更多", 500, card.bottom - 8, 10, 0xFF917B63, Paint.Align.CENTER, false);
    }

    private void drawButton(Canvas c, RectF rect, String label, boolean primary) {
        fill(c, rect, 13, primary ? 0xFFF5C77C : 0x002C2018);
        if (!primary) stroke(c, rect, 13, 0x806C543C, 1);
        text(c, label, rect.centerX(), rect.centerY() + 7, 20,
                primary ? 0xFF382415 : 0xFFE0C6A5, Paint.Align.CENTER, true);
    }

    private void fill(Canvas c, RectF rect, float radius, int color) {
        paint.setShader(null); paint.setColor(color); paint.setStyle(Paint.Style.FILL); c.drawRoundRect(rect, radius, radius, paint);
    }

    private void stroke(Canvas c, RectF rect, float radius, int color, float width) {
        paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setStrokeWidth(width);
        c.drawRoundRect(rect, radius, radius, paint); paint.setStyle(Paint.Style.FILL);
    }

    private void text(Canvas c, String text, float x, float y, float size, int color, Paint.Align align, boolean strong) {
        paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(color);
        paint.setTextSize(size); paint.setTextAlign(align); paint.setTypeface(strong ? bold : Typeface.DEFAULT);
        paint.setShadowLayer(2, 0, 2, 0x6A0B0805); c.drawText(text, x, y, paint); paint.clearShadowLayer();
    }

    static String modeName(GameEngine.Mode mode) {
        return switch (mode) {
            case CLASSIC -> "经典切台";
            case FRENZY -> "水果狂热";
            case FREEZE -> "冰封时刻";
            case DOUBLE -> "双倍刀锋";
            case ZEN -> "禅意切台";
        };
    }

    static String modeDescription(GameEngine.Mode mode) {
        return switch (mode) {
            case CLASSIC -> "留意炸弹，漏果三次结束";
            case FRENZY -> "水果成群涌来 · 漏果无惩罚";
            case FREEZE -> "水果慢动作 · 从容出刀";
            case DOUBLE -> "每一刀，双倍得分";
            case ZEN -> "没有炸弹 · 漏果无惩罚";
        };
    }

    static int modeColor(GameEngine.Mode mode) {
        return switch (mode) {
            case CLASSIC -> 0xFFFFD18A;
            case FRENZY -> 0xFFFFA76A;
            case FREEZE -> 0xFF9EE9FA;
            case DOUBLE -> 0xFFFFDA6B;
            case ZEN -> 0xFFBAE89C;
        };
    }

    @SuppressLint("ClickableViewAccessibility") @Override public boolean onTouchEvent(MotionEvent event) {
        float x = (event.getX() - offsetX) / scale, y = event.getY() / scale;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downY = y; historyStartScroll = historyScroll;
            pressedAction = actionAt(x, y);
            if (pressedAction > 0 || overlay != Overlay.NONE) return true;
            engine.beginStroke();
        }
        if (overlay == Overlay.HISTORY && action == MotionEvent.ACTION_MOVE) {
            historyScroll = historyStartScroll + downY - y; invalidate(); return true;
        }
        if (pressedAction > 0 || overlay != Overlay.NONE) {
            if (action == MotionEvent.ACTION_UP) {
                if (Math.abs(y - downY) < 18 && actionAt(x, y) == pressedAction) {
                    performClick(); handleAction(pressedAction);
                }
                pressedAction = 0;
            } else if (action == MotionEvent.ACTION_CANCEL) pressedAction = 0;
            return true;
        }
        int index = event.getActionIndex();
        int id = event.getPointerId(index);
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            touches.put(id, new PointF((event.getX(index) - offsetX) / scale, event.getY(index) / scale));
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int h = 0; h < event.getHistorySize(); h++) {
                for (int p = 0; p < event.getPointerCount(); p++) cutTo(event.getPointerId(p),
                        (event.getHistoricalX(p, h) - offsetX) / scale, event.getHistoricalY(p, h) / scale);
            }
            for (int p = 0; p < event.getPointerCount(); p++) cutTo(event.getPointerId(p), (event.getX(p) - offsetX) / scale, event.getY(p) / scale);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            cutTo(id, (event.getX(index) - offsetX) / scale, event.getY(index) / scale);
            touches.remove(id);
            if (touches.size() == 0) { engine.endStroke(); processEvents(); }
        } else if (action == MotionEvent.ACTION_CANCEL) finishTouch();
        invalidate();
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private void cutTo(int id, float x, float y) {
        PointF old = touches.get(id);
        if (old == null) { touches.put(id, new PointF(x, y)); return; }
        engine.slice(old.x, old.y, x, y);
        if (blades.size() > 96) blades.remove(0);
        Blade b = new Blade(); b.x1 = old.x; b.y1 = old.y; b.x2 = x; b.y2 = y; b.life = .18f; blades.add(b);
        old.set(x, y);
        processEvents();
    }

    private void finishTouch() {
        if (touches.size() > 0) { engine.endStroke(); processEvents(); }
        touches.clear(); blades.clear(); pressedAction = 0;
    }

    private int actionAt(float x, float y) {
        if (overlay == Overlay.NONE) {
            if (pauseButton.contains(x, y)) return 1;
            if (historyButton.contains(x, y)) return 2;
        } else if (overlay == Overlay.HISTORY) {
            if (closeButton.contains(x, y)) return 5;
        } else {
            if (mainButton.contains(x, y)) return 3;
            if (secondButton.contains(x, y)) return 4;
        }
        return 0;
    }

    private void handleAction(int action) {
        switch (action) {
            case 1 -> pause();
            case 2, 4 -> openHistory();
            case 3 -> { if (overlay == Overlay.RESULT) restart(); else resume(); }
            case 5 -> overlay = historyReturn;
            default -> { }
        }
        invalidate();
    }

    private void writeSnapshot() {
        try {
            JSONObject state = new JSONObject();
            state.put("score", engine.score).put("sliced", engine.sliced).put("remaining", engine.remaining)
                    .put("elapsed", engine.elapsed).put("misses", engine.misses).put("mode", engine.mode.name())
                    .put("switches", engine.switches).put("modeRemaining", engine.modeRemaining)
                    .put("maxCombo", engine.maxCombo).put("bombsHit", engine.bombsHit)
                    .put("overlay", overlay.name()).put("historyCount", history.records().size())
                    .put("best", history.bestScore()).put("worldHeight", worldHeight).put("scale", scale).put("offsetX", offsetX);
            JSONArray fruit = new JSONArray();
            for (GameEngine.Fruit f : engine.fruits) if (f.active) fruit.put(new JSONObject()
                    .put("id", f.id).put("type", f.type).put("x", f.x).put("y", f.y).put("vx", f.vx).put("vy", f.vy).put("r", f.radius));
            state.put("fruits", fruit);
            File file = new File(getContext().getFilesDir(), "debug_state.json");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(state.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { /* Optional debug diagnostics must never affect play. */ }
    }

    private static class Particle { float x, y, vx, vy, r, life, total; int color; }
    private static final class Half extends Particle { float angle, spin; int type; boolean left; }
    private static final class Popup { String text; float x, y, life, total; int size, color; }
    private static final class Blade { float x1, y1, x2, y2, life; }
}
