package com.ethan.fruitslice;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Offline game rules and physics. Coordinates are in the caller's logical play field. */
public final class GameEngine {
    public enum Mode { CLASSIC, FRENZY, FREEZE, DOUBLE, ZEN }
    public enum Kind { SLICE, BOMB, COMBO, MODE, MISS, OVER }

    public static final class Fruit {
        public final long id;
        public final int type;
        public float x, y, vx, vy, radius, angle, spin;
        public boolean active = true;
        public boolean sliced;
        // A fruit launched during a safe mode never becomes a penalty afterwards.
        final boolean countsMiss;

        Fruit(long id, int type, float x, float y, float vx, float vy,
                float radius, float angle, float spin, boolean countsMiss) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.radius = radius;
            this.angle = angle;
            this.spin = spin;
            this.countsMiss = countsMiss;
        }
    }

    public static final class Event {
        public final Kind kind;
        public final float x, y;
        public final int type, value;
        public final String text;

        Event(Kind kind, float x, float y, int type, int value, String text) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.type = type;
            this.value = value;
            this.text = text;
        }
    }

    public final List<Fruit> fruits = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    public int score, sliced, misses, maxCombo, bombsHit, switches;
    public float remaining = 60f, elapsed, modeRemaining;
    public boolean gameOver;
    public Mode mode = Mode.CLASSIC;

    private final Random random;
    private float width = 1000f, height = 450f;
    private float nextWave = .25f, nextSpecial = 2.2f;
    private long nextId;
    private int waveIndex;
    private boolean strokeOpen, strokeBomb;
    private int strokeCount, strokeWeight;
    private float strokeX, strokeY;

    public GameEngine(long seed) {
        random = new Random(seed);
    }

    public void setBounds(float w, float h) {
        if (!Float.isFinite(w) || !Float.isFinite(h) || w < 100f || h < 100f) {
            throw new IllegalArgumentException("Play field dimensions must be finite and >= 100");
        }
        float scaleX = w / width;
        float scaleY = h / height;
        float oldRadius = fruitRadius();
        width = w;
        height = h;
        float radiusScale = fruitRadius() / oldRadius;
        for (Fruit fruit : fruits) {
            fruit.x *= scaleX;
            fruit.y *= scaleY;
            fruit.vx *= scaleX;
            fruit.vy *= scaleY;
            fruit.radius *= radiusScale;
        }
    }

    public void restart() {
        fruits.clear();
        events.clear();
        score = sliced = misses = maxCombo = bombsHit = switches = 0;
        remaining = 60f;
        elapsed = modeRemaining = 0f;
        mode = Mode.CLASSIC;
        gameOver = false;
        nextWave = .25f;
        nextSpecial = 2.2f;
        nextId = 0;
        waveIndex = 0;
        strokeOpen = strokeBomb = false;
        strokeCount = strokeWeight = 0;
    }

    /** A stalled frame advances at most 100 ms; pausing is controlled by the caller. */
    public void update(float realDt) {
        if (gameOver || !Float.isFinite(realDt) || realDt <= 0f) return;
        float left = Math.min(.1f, realDt);
        // Small steps keep spawning, boundary checks and mode transitions consistent.
        while (left > .000001f && !gameOver) {
            float dt = Math.min(left, 1f / 120f);
            advance(dt);
            left -= dt;
        }
    }

    private void advance(float dt) {
        remaining = Math.max(0f, remaining - dt);
        elapsed += dt;
        if (remaining <= .0001f) {
            remaining = 0f;
            finishRound();
            return;
        }
        if (mode != Mode.CLASSIC) {
            modeRemaining = Math.max(0f, modeRemaining - dt);
            if (modeRemaining <= .0001f) {
                mode = Mode.CLASSIC;
                modeRemaining = 0f;
                nextWave = Math.max(nextWave, .5f);
                event(Kind.MODE, width * .5f, height * .35f, 6, 0, mode.name());
            }
        }

        nextWave -= dt;
        if (nextWave <= 0f) {
            launchWave();
            nextWave += waveInterval();
        }
        nextSpecial -= dt;
        if (nextSpecial <= 0f) {
            launch(6);
            nextSpecial += 9f + random.nextFloat() * 4f;
        }

        float physicsDt = mode == Mode.FREEZE ? dt * .52f : dt;
        float acceleration = gravity();
        for (int i = fruits.size() - 1; i >= 0; i--) {
            Fruit fruit = fruits.get(i);
            fruit.x += fruit.vx * physicsDt;
            fruit.y += fruit.vy * physicsDt + .5f * acceleration * physicsDt * physicsDt;
            fruit.vy += acceleration * physicsDt;
            fruit.angle += fruit.spin * physicsDt;
            if (fruit.vy > 0f && fruit.y - fruit.radius > height + 8f) {
                fruit.active = false;
                fruits.remove(i);
                if (fruit.type < 6 && fruit.countsMiss && !safeMode(mode)) {
                    misses++;
                    event(Kind.MISS, fruit.x, height - fruit.radius, fruit.type, misses, "");
                    if (misses >= 3) {
                        finishRound();
                        return;
                    }
                }
            }
        }
    }

    public void beginStroke() {
        if (gameOver) return;
        if (strokeOpen) endStroke();
        strokeOpen = true;
        strokeBomb = false;
        strokeCount = strokeWeight = 0;
    }

    /** Segment/circle collision catches fast swipes without requiring intermediate touch samples. */
    public void slice(float x1, float y1, float x2, float y2) {
        if (gameOver || !Float.isFinite(x1) || !Float.isFinite(y1)
                || !Float.isFinite(x2) || !Float.isFinite(y2)) return;
        float dx = x2 - x1, dy = y2 - y1;
        float lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 4f) return;
        if (!strokeOpen) beginStroke();
        Mode collisionMode = mode;
        List<Fruit> collisions = new ArrayList<>();
        for (Fruit fruit : fruits) {
            if (!fruit.active) continue;
            float t = ((fruit.x - x1) * dx + (fruit.y - y1) * dy) / lengthSquared;
            t = Math.max(0f, Math.min(1f, t));
            float distanceX = fruit.x - (x1 + dx * t);
            float distanceY = fruit.y - (y1 + dy * t);
            if (distanceX * distanceX + distanceY * distanceY <= fruit.radius * fruit.radius) {
                collisions.add(fruit);
            }
        }
        // Finish all scoring against the mode at the start of this touch segment.
        // A rainbow encountered earlier in the list cannot alter another collision's reward.
        for (Fruit fruit : collisions) {
            fruit.active = false;
            fruit.sliced = true;
            fruits.remove(fruit);
            strokeX = fruit.x;
            strokeY = fruit.y;
            if (fruit.type == 7) {
                bombsHit++;
                score -= 10;
                remaining = Math.max(0f, remaining - 3f);
                strokeBomb = true;
                event(Kind.BOMB, fruit.x, fruit.y, fruit.type, -10, "");
            } else {
                int multiplier = collisionMode == Mode.DOUBLE ? 2 : 1;
                int reward = 5 * multiplier;
                score += reward;
                sliced++;
                strokeCount++;
                strokeWeight += multiplier;
                event(Kind.SLICE, fruit.x, fruit.y, fruit.type, reward, "");
            }
        }
        // Clamp after all collisions so list order cannot change a mixed bomb/fruit score.
        score = Math.max(0, score);
        for (Fruit fruit : collisions) {
            if (fruit.type == 6 && remaining > 0f) switchMode(fruit.x, fruit.y);
        }
        if (remaining <= 0f) finishRound();
    }

    public void endStroke() {
        if (!strokeOpen) return;
        strokeOpen = false;
        maxCombo = Math.max(maxCombo, strokeCount);
        if (!strokeBomb && strokeCount >= 3) {
            int bonus = 2 * strokeWeight;
            score += bonus;
            event(Kind.COMBO, strokeX, strokeY, -1, strokeCount, "+" + bonus);
        }
        strokeCount = strokeWeight = 0;
    }

    public List<Event> drainEvents() {
        List<Event> result = new ArrayList<>(events);
        events.clear();
        return result;
    }

    private void switchMode(float x, float y) {
        Mode previous = mode;
        Mode[] choices = { Mode.FRENZY, Mode.FREEZE, Mode.DOUBLE, Mode.ZEN };
        Mode selected;
        do {
            selected = choices[random.nextInt(choices.length)];
        } while (selected == previous);
        mode = selected;
        modeRemaining = 8f + random.nextFloat() * 4f;
        switches++;
        nextWave = Math.min(nextWave, mode == Mode.FRENZY ? .18f : .45f);
        if (safeMode(mode)) {
            // Safe modes contain no bombs, including bombs already on screen.
            for (int i = fruits.size() - 1; i >= 0; i--) {
                Fruit fruit = fruits.get(i);
                if (fruit.type == 7) {
                    fruit.active = false;
                    fruits.remove(i);
                }
            }
        }
        event(Kind.MODE, x, y, 6, switches, mode.name());
    }

    private void launchWave() {
        int count;
        if (waveIndex == 0) count = 2;
        else if (mode == Mode.FRENZY) count = 3 + random.nextInt(2);
        else if (elapsed >= 12f && random.nextInt(4) == 0) count = 4;
        else count = 2 + random.nextInt(2);
        waveIndex++;
        for (int i = 0; i < count; i++) launch(random.nextInt(6));
        if (!safeMode(mode) && elapsed > 3f && random.nextFloat() < .22f) launch(7);
    }

    private void launch(int type) {
        float radius = fruitRadius() * (type == 6 ? 1.13f : type == 7 ? .94f : 1f);
        float startX = width * (.17f + random.nextFloat() * .66f);
        float startY = height + radius + 5f;
        // Keep the full arc below the HUD: 140..250 in the 1000 x 466 play field.
        float apexY = height * ((140f + random.nextFloat() * 110f) / 466f);
        float vy = -(float) Math.sqrt(2f * gravity() * (startY - apexY));
        float timeToApex = -vy / gravity();
        // Aim toward the center instead of letting an edge launch disappear sideways.
        float targetX = width * (.24f + random.nextFloat() * .52f);
        float vx = (targetX - startX) / (timeToApex * 2f);
        spawnFruit(type, startX, startY, vx, vy, radius);
    }

    Fruit spawnFruit(int type, float x, float y, float vx, float vy, float radius) {
        if (type < 0 || type > 7 || radius <= 0f) throw new IllegalArgumentException("Invalid fruit");
        Fruit fruit = new Fruit(++nextId, type, x, y, vx, vy, radius,
                random.nextFloat() * 360f, (random.nextFloat() - .5f) * 180f, !safeMode(mode));
        fruits.add(fruit);
        return fruit;
    }

    private float waveInterval() {
        if (mode == Mode.FRENZY) return .42f + random.nextFloat() * .18f;
        if (mode == Mode.FREEZE) return 2.1f + random.nextFloat() * .4f;
        if (waveIndex == 1) return 1.7f;
        if (elapsed < 12f) return 1.5f + random.nextFloat() * .4f;
        return Math.max(1.05f, 1.65f - elapsed * .005f) + random.nextFloat() * .25f;
    }

    private float fruitRadius() {
        return Math.min(width * .035f, height * .06f);
    }

    private float gravity() {
        return height * 2.6f;
    }

    private static boolean safeMode(Mode candidate) {
        return candidate == Mode.FRENZY || candidate == Mode.ZEN;
    }

    private void finishRound() {
        if (gameOver) return;
        endStroke();
        gameOver = true;
        event(Kind.OVER, width * .5f, height * .5f, -1, score,
                misses >= 3 ? "MISSES" : "TIME");
    }

    private void event(Kind kind, float x, float y, int type, int value, String text) {
        events.add(new Event(kind, x, y, type, value, text));
    }
}
