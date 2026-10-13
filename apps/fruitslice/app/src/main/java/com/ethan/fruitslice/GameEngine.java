package com.ethan.fruitslice;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Offline game rules and physics. Coordinates are in the caller's logical play field. */
public final class GameEngine {
    public enum Mode { CLASSIC, FRENZY, FREEZE, DOUBLE, ZEN }
    public enum Kind { SLICE, BOMB, COMBO, MODE, MISS, OVER }

    public static final float MIN_FRUIT_RADIUS = 28f;
    public static final float MAX_FRUIT_RADIUS = 56f;
    public static final List<Float> SIZE_RADII = List.of(28f, 35f, 42f, 56f);
    public static final List<Integer> SIZE_POINTS = List.of(20, 12, 8, 5);

    public static final class Fruit {
        public final long id;
        public final int type;
        public final int basePoints;
        public float x, y, vx, vy, radius, angle, spin;
        public boolean active = true;
        public boolean sliced;
        // A fruit launched during a safe mode never becomes a penalty afterwards.
        final boolean countsMiss;

        Fruit(long id, int type, float x, float y, float vx, float vy,
                float radius, float angle, float spin, boolean countsMiss, int basePoints) {
            this.id = id;
            this.type = type;
            this.basePoints = basePoints;
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
        public final float radius;
        public final int type, value;
        public final String text;

        Event(Kind kind, float x, float y, int type, int value, String text, float radius) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.radius = radius;
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
        float oldSizeScale = sizeScale();
        width = w;
        height = h;
        float radiusScale = fruitRadius() / oldRadius;
        float ordinaryRadiusScale = sizeScale() / oldSizeScale;
        for (Fruit fruit : fruits) {
            fruit.x *= scaleX;
            fruit.y *= scaleY;
            fruit.vx *= scaleX;
            fruit.vy *= scaleY;
            fruit.radius *= fruit.type < 6 ? ordinaryRadiusScale : radiusScale;
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
        double dx = (double) x2 - x1, dy = (double) y2 - y1;
        double lengthSquared = dx * dx + dy * dy;
        // BladeLimiter owns the starting threshold. Once active, every nonzero sample
        // must collide, including sub-two-unit segments from high-rate touch hardware.
        if (lengthSquared == 0d) return;
        if (!strokeOpen) beginStroke();
        Mode collisionMode = mode;
        List<Fruit> collisions = new ArrayList<>();
        for (Fruit fruit : fruits) {
            if (!fruit.active) continue;
            double t = (((double) fruit.x - x1) * dx + ((double) fruit.y - y1) * dy) / lengthSquared;
            t = Math.max(0d, Math.min(1d, t));
            double distanceX = fruit.x - (x1 + dx * t);
            double distanceY = fruit.y - (y1 + dy * t);
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
                int reward = fruit.basePoints * multiplier;
                score += reward;
                sliced++;
                strokeCount++;
                strokeWeight += multiplier;
                event(Kind.SLICE, fruit.x, fruit.y, fruit.type, reward, "", fruit.radius);
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
        int launchingWave = waveIndex++;
        int firstTier = -1;
        for (int i = 0; i < count; i++) {
            int tier;
            if (launchingWave == 0) tier = i == 0 ? 0 : 3;
            else if (launchingWave == 1 && i < 2) tier = i + 1;
            else {
                do {
                    tier = randomSizeTier();
                } while (i == 1 && tier == firstTier);
            }
            if (i == 0) firstTier = tier;
            float slot = count == 1 ? .5f : .22f + .56f * i / (count - 1f);
            float startX = width * (slot + (random.nextFloat() - .5f) * .06f);
            launch(random.nextInt(6), SIZE_RADII.get(tier) * sizeScale(), startX);
        }
        if (!safeMode(mode) && elapsed > 3f && random.nextFloat() < .22f) launch(7);
    }

    private void launch(int type) {
        float radius = fruitRadius() * (type == 6 ? 1.13f : type == 7 ? .94f : 1f);
        float startX = width * (.17f + random.nextFloat() * .66f);
        launch(type, radius, startX);
    }

    private void launch(int type, float radius, float startX) {
        float startY = height + radius + 5f;
        float apexY;
        if (type < 6) {
            // Leave room for the rotating silhouette, including the pineapple's tall crown.
            float fieldScale = height / 466f;
            float visualRadius = radius * (type == 4 ? 2.25f : 1.30f);
            float lowestApex = height - Math.max(80f * fieldScale, visualRadius + 10f * fieldScale);
            float highestApex = Math.min(lowestApex,
                    Math.max(140f * fieldScale, 110f * fieldScale + visualRadius));
            float maximumApex = Math.min(lowestApex, highestApex + 90f * fieldScale);
            apexY = highestApex + random.nextFloat() * (maximumApex - highestApex);
        } else {
            // Special fruit and bombs retain their familiar 140..250 arcs.
            apexY = height * ((140f + random.nextFloat() * 110f) / 466f);
        }
        float vy = -(float) Math.sqrt(2f * gravity() * (startY - apexY));
        float timeToApex = -vy / gravity();
        // Aim toward the center instead of letting an edge launch disappear sideways.
        float targetX = Math.max(width * .20f, Math.min(width * .80f,
                startX + (random.nextFloat() - .5f) * width * .22f));
        float vx = (targetX - startX) / (timeToApex * 2f);
        spawnFruit(type, startX, startY, vx, vy, radius);
    }

    Fruit spawnFruit(int type, float x, float y, float vx, float vy, float radius) {
        if (type < 0 || type > 7 || !Float.isFinite(radius) || radius <= 0f) {
            throw new IllegalArgumentException("Invalid fruit");
        }
        int basePoints = type < 6 ? pointsForRadius(radius / sizeScale()) : type == 6 ? 5 : 0;
        Fruit fruit = new Fruit(++nextId, type, x, y, vx, vy, radius,
                random.nextFloat() * 360f, (random.nextFloat() - .5f) * 180f,
                !safeMode(mode), basePoints);
        fruits.add(fruit);
        return fruit;
    }

    /** Nearest logical size tier; midpoint ties use the larger, lower-scoring tier. */
    public static int pointsForRadius(float radius) {
        if (!Float.isFinite(radius) || radius <= 0f) {
            throw new IllegalArgumentException("Radius must be finite and positive");
        }
        for (int tier = 0; tier < SIZE_RADII.size() - 1; tier++) {
            float midpoint = (SIZE_RADII.get(tier) + SIZE_RADII.get(tier + 1)) * .5f;
            if (radius < midpoint) return SIZE_POINTS.get(tier);
        }
        return SIZE_POINTS.get(SIZE_POINTS.size() - 1);
    }

    private int randomSizeTier() {
        int choice = random.nextInt(10);
        return choice < 2 ? 0 : choice < 5 ? 1 : choice < 8 ? 2 : 3;
    }

    private float sizeScale() {
        return Math.min(width / 1000f, height / 450f);
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
        event(kind, x, y, type, value, text, 0f);
    }

    private void event(Kind kind, float x, float y, int type, int value, String text, float radius) {
        events.add(new Event(kind, x, y, type, value, text, radius));
    }
}
