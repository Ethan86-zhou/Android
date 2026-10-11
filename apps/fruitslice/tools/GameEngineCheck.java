package com.ethan.fruitslice;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Run with Java 17; no Android runtime or third-party test library is required. */
public final class GameEngineCheck {
    private static int checks;

    public static void main(String[] args) {
        collisionAndScoring();
        combosAndBombs();
        modesAndTransitions();
        clockAndGameOver();
        physicsAndDeterminism();
        playableLaunches();
        sizeScoringAndGeometry();
        naturalSizeVariety();
        bladeTimingBoundaries();
        bladePathAndPointers();
        bladeInvalidSamples();
        bladeScoringAcrossModes();
        System.out.println("GameEngineCheck: " + checks + " checks passed");
    }

    private static void collisionAndScoring() {
        GameEngine engine = new GameEngine(1);
        GameEngine.Fruit fruit = engine.spawnFruit(0, 200, 200, 0, 0, 20);
        engine.beginStroke();
        engine.slice(200, 200, 200, 200);
        equal(engine.score, 0, "Stationary tap does not slice");
        engine.slice(0, 200, 1000, 200);
        equal(engine.score, 20, "Fast swipe does not tunnel through a fruit");
        check(fruit.sliced && !fruit.active && engine.fruits.isEmpty(), "Sliced fruit removed once");
        engine.slice(0, 200, 1000, 200);
        engine.endStroke();
        equal(engine.score, 20, "Repeated collision cannot award twice");
        equal(engine.sliced, 1, "Slice count is exact");
        equal(count(engine.drainEvents(), GameEngine.Kind.SLICE), 1, "One slice event");
        check(engine.drainEvents().isEmpty(), "Event drain clears delivered events");

        engine.spawnFruit(1, 400, 220, 0, 0, 20);
        engine.slice(100, 200, 800, 200);
        equal(engine.sliced, 2, "Tangential contact at circle edge slices");
        engine.endStroke();
        engine.spawnFruit(2, 700, 250, 0, 0, 20);
        engine.slice(100, 229, 800, 229);
        equal(engine.sliced, 2, "A near miss outside circle does not slice");
        engine.slice(100, 250, 500, 250);
        equal(engine.sliced, 2, "Infinite line beyond segment endpoint does not slice");
        engine.slice(Float.NaN, 0, 700, 250);
        equal(engine.sliced, 2, "Non-finite touch ignored");
    }

    private static void combosAndBombs() {
        GameEngine engine = new GameEngine(2);
        engine.spawnFruit(0, 200, 200, 0, 0, 20);
        engine.spawnFruit(1, 400, 200, 0, 0, 20);
        engine.spawnFruit(2, 600, 200, 0, 0, 20);
        engine.beginStroke();
        engine.slice(0, 200, 1000, 200);
        equal(engine.score, 60, "Combo bonus waits for stroke completion");
        engine.endStroke();
        equal(engine.score, 66, "Three fruit stroke awards six bonus points");
        equal(engine.maxCombo, 3, "Largest stroke recorded");
        engine.endStroke();
        equal(engine.score, 66, "Ending same stroke twice does not repeat bonus");
        equal(count(engine.drainEvents(), GameEngine.Kind.COMBO), 1, "One combo per stroke");

        engine.spawnFruit(7, 400, 200, 0, 0, 20);
        engine.slice(0, 200, 1000, 200);
        engine.endStroke();
        equal(engine.score, 56, "Bomb subtracts ten points");
        near(engine.remaining, 57f, .001f, "Bomb costs three seconds");
        check(!engine.gameOver, "Bomb does not end round immediately");
        equal(engine.bombsHit, 1, "Bomb count recorded");

        engine.restart();
        engine.mode = GameEngine.Mode.DOUBLE;
        engine.modeRemaining = 10;
        for (int i = 0; i < 4; i++) engine.spawnFruit(i, 100 + i * 100, 200, 0, 0, 20);
        engine.slice(0, 200, 1000, 200);
        engine.endStroke();
        equal(engine.score, 176, "Double mode doubles fruit and combo points");
        engine.restart();
        for (int i = 0; i < 3; i++) engine.spawnFruit(i, 100 + i * 100, 200, 0, 0, 20);
        engine.spawnFruit(7, 500, 200, 0, 0, 20);
        engine.slice(0, 200, 1000, 200);
        engine.endStroke();
        equal(engine.score, 50, "Bomb in same stroke suppresses combo");
        engine.restart();
        engine.spawnFruit(7, 500, 200, 0, 0, 20);
        for (int i = 0; i < 3; i++) engine.spawnFruit(i, 100 + i * 100, 200, 0, 0, 20);
        engine.slice(0, 200, 1000, 200);
        engine.endStroke();
        equal(engine.score, 50, "Mixed collision reward is independent of fruit list order");
    }

    private static void modesAndTransitions() {
        EnumSet<GameEngine.Mode> selected = EnumSet.noneOf(GameEngine.Mode.class);
        for (int seed = 0; seed < 32; seed++) {
            GameEngine engine = new GameEngine(seed);
            GameEngine.Mode previous = engine.mode;
            for (int i = 0; i < 6; i++) {
                engine.spawnFruit(6, 400, 200, 0, 0, 20);
                engine.beginStroke();
                engine.slice(0, 200, 1000, 200);
                engine.endStroke();
                check(engine.mode != previous && engine.mode != GameEngine.Mode.CLASSIC,
                        "Rainbow always selects a different special mode");
                check(engine.modeRemaining >= 8 && engine.modeRemaining <= 12,
                        "Special mode has bounded duration");
                selected.add(engine.mode);
                previous = engine.mode;
            }
            equal(engine.switches, 6, "Every rainbow is counted");
        }
        equal(selected.size(), 4, "Seeded choices cover all four special modes");

        GameEngine engine = new GameEngine(10);
        engine.mode = GameEngine.Mode.ZEN;
        engine.modeRemaining = .05f;
        engine.spawnFruit(0, 400, 480, 0, 50, 20);
        engine.update(.1f);
        check(engine.mode == GameEngine.Mode.CLASSIC, "Mode expiration returns to classic");
        equal(engine.misses, 0, "Fruit launched in safe mode does not penalize after expiration");
        check(count(engine.drainEvents(), GameEngine.Kind.MODE) == 1, "Expiration emits one mode event");
        for (GameEngine.Mode safe : new GameEngine.Mode[] {GameEngine.Mode.FRENZY, GameEngine.Mode.ZEN}) {
            engine.restart();
            engine.mode = safe;
            engine.modeRemaining = 10;
            for (int i = 0; i < 6; i++) engine.spawnFruit(0, 300, 480, 0, 50, 20);
            engine.update(.1f);
            equal(engine.misses, 0, "Safe mode ignores missed fruits");
            check(!engine.gameOver, "Safe mode keeps round running");
        }

        engine.restart();
        engine.mode = GameEngine.Mode.DOUBLE;
        engine.modeRemaining = 5;
        engine.spawnFruit(6, 200, 200, 0, 0, 20);
        engine.spawnFruit(0, 400, 200, 0, 0, 20);
        engine.slice(0, 200, 1000, 200);
        equal(engine.score, 50, "Same segment scores using pre-rainbow mode snapshot");
    }

    private static void clockAndGameOver() {
        GameEngine engine = new GameEngine(4);
        engine.update(8);
        near(engine.elapsed, .1f, .001f, "Stalled frame time is clamped");
        near(engine.remaining, 59.9f, .001f, "Countdown uses clamped active time");
        engine.update(-1);
        engine.update(Float.NaN);
        engine.update(Float.POSITIVE_INFINITY);
        near(engine.elapsed, .1f, .001f, "Invalid delta ignored");
        for (int i = 0; i < 3; i++) engine.spawnFruit(i, 300, 480, 0, 50, 20);
        engine.update(.1f);
        equal(engine.misses, 3, "Three missed fruits counted");
        check(engine.gameOver, "Three misses end classic round");
        equal(count(engine.drainEvents(), GameEngine.Kind.OVER), 1, "Round ends exactly once");
        engine.update(1);
        engine.slice(0, 0, 1000, 1000);
        check(engine.drainEvents().isEmpty(), "Game over ignores later updates and input");
        engine.restart();
        check(!engine.gameOver && engine.score == 0 && engine.fruits.isEmpty(), "Restart resets game");
        near(engine.remaining, 60, .001f, "Restart restores one minute");
        engine.remaining = .02f;
        engine.update(.03f);
        check(engine.gameOver && engine.remaining == 0, "Timer ends round without negative time");
        equal(count(engine.drainEvents(), GameEngine.Kind.OVER), 1, "Time end emits one result");
    }

    private static void physicsAndDeterminism() {
        GameEngine first = new GameEngine(42);
        GameEngine second = new GameEngine(42);
        for (int i = 0; i < 60; i++) {
            first.update(1f / 60);
            second.update(1f / 60);
        }
        equal(first.fruits.size(), second.fruits.size(), "Seeded launch count deterministic");
        check(!first.fruits.isEmpty(), "First fruit launches promptly");
        for (int i = 0; i < first.fruits.size(); i++) {
            GameEngine.Fruit a = first.fruits.get(i), b = second.fruits.get(i);
            near(a.x, b.x, .0001f, "Seeded trajectory x deterministic");
            near(a.y, b.y, .0001f, "Seeded trajectory y deterministic");
            check(a.x > a.radius && a.x < 1000 - a.radius, "Fruit reachable horizontally");
            check(a.y >= 450 * .12f, "Fruit does not fly beyond top controls");
        }
        GameEngine.Fruit tracked = first.fruits.get(0);
        float oldX = tracked.x, oldY = tracked.y, oldRadius = tracked.radius;
        first.setBounds(500, 225);
        near(tracked.x, oldX * .5f, .001f, "Resize scales x");
        near(tracked.y, oldY * .5f, .001f, "Resize scales y");
        near(tracked.radius, oldRadius * .5f, .001f, "Resize scales fruit radius");
        boolean rejected = false;
        try { first.setBounds(0, 450); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Invalid play field rejected");

        GameEngine slow = new GameEngine(8), normal = new GameEngine(8);
        slow.mode = GameEngine.Mode.FREEZE;
        slow.modeRemaining = 10;
        GameEngine.Fruit slowFruit = slow.spawnFruit(0, 500, 300, 0, -200, 20);
        GameEngine.Fruit normalFruit = normal.spawnFruit(0, 500, 300, 0, -200, 20);
        slow.update(.1f);
        normal.update(.1f);
        check(slowFruit.y > normalFruit.y, "Freeze slows the moving fruit");
        near(slow.remaining, normal.remaining, .001f, "Freeze keeps fair real-time countdown");

        GameEngine auto = new GameEngine(13);
        boolean rainbow = false;
        for (int frame = 0; frame < 180; frame++) {
            auto.update(1f / 60);
            for (GameEngine.Fruit fruit : List.copyOf(auto.fruits)) {
                if (fruit.type == 6) rainbow = true;
                if (fruit.type < 6 && fruit.y < 400) {
                    auto.beginStroke();
                    auto.slice(fruit.x - fruit.radius - 2, fruit.y,
                            fruit.x + fruit.radius + 2, fruit.y);
                    auto.endStroke();
                }
            }
            auto.drainEvents();
        }
        check(rainbow, "Rainbow launches within first three seconds");
        check(!auto.gameOver, "Normal accurate slicing can keep round alive");
    }

    private static void playableLaunches() {
        for (int seed = 0; seed < 20; seed++) {
            GameEngine engine = new GameEngine(seed);
            engine.setBounds(1000, 466);
            int waves = 0;
            float previousWave = 0;
            boolean earlySpecial = false;
            while (engine.elapsed < 7f) {
                engine.update(.01f);
                int normalCount = 0;
                for (GameEngine.Fruit fruit : engine.fruits) {
                    // Closed-form apex stays invariant while a ballistic fruit moves.
                    float apex = fruit.y - fruit.vy * fruit.vy / (2f * 466f * 2.6f);
                    if (fruit.type < 6) {
                        float visualRadius = fruit.radius * (fruit.type == 4 ? 2.25f : 1.30f);
                        check(apex - visualRadius >= 109.9f,
                                "Rotating ordinary fruit and crowns remain below the HUD");
                        check(apex + visualRadius <= 456.1f,
                                "Whole rotating ordinary fruit fits above the screen bottom at apex");
                        check(apex >= 139.9f && apex <= 386.1f,
                                "Ordinary fruit apex remains within a reachable play field");
                    } else {
                        check(apex >= 139.9f && apex <= 250.1f,
                                "Special and bomb arcs retain their 140..250 play field");
                    }
                    check(fruit.x >= fruit.radius && fruit.x <= 1000 - fruit.radius,
                            "Launch starts away from horizontal screen edges");
                    if (fruit.type < 6) normalCount++;
                    if (fruit.type == 6 && engine.elapsed < 2.3f) earlySpecial = true;
                    if (engine.elapsed <= 3f) check(fruit.type != 7, "First three seconds contain no bombs");
                }
                if (normalCount > 0) {
                    waves++;
                    if (waves == 1) {
                        equal(normalCount, 2, "First launch always contains two fruits");
                        near(engine.elapsed, .25f, .02f, "First wave starts promptly");
                    } else {
                        check(normalCount >= 2 && normalCount <= 3,
                                "Early waves provide two or three fruits for a swipe");
                        if (waves == 2) near(engine.elapsed - previousWave, 1.7f, .02f,
                                "First two waves have a readable 1.7 second interval");
                        else check(engine.elapsed - previousWave >= 1.48f
                                        && engine.elapsed - previousWave <= 1.92f,
                                "Early wave intervals stay between 1.5 and 1.9 seconds");
                    }
                    previousWave = engine.elapsed;
                }
                // Observe each launch once while preventing unattended miss penalties.
                engine.fruits.clear();
                engine.drainEvents();
            }
            check(earlySpecial, "Rainbow appears at 2.2 seconds before an idle round ends");
            check(waves >= 4, "Opening has repeated groups rather than sporadic singles");
        }
    }

    private static void sizeScoringAndGeometry() {
        near(GameEngine.MIN_FRUIT_RADIUS, 28f, 0f, "Smallest logical radius is twenty-eight");
        near(GameEngine.MAX_FRUIT_RADIUS, 56f, 0f, "Largest logical radius is fifty-six");
        near((2f * GameEngine.MAX_FRUIT_RADIUS) / (2f * GameEngine.MIN_FRUIT_RADIUS),
                2f, 0f, "Largest diameter is exactly twice the smallest diameter");
        int[] expectedPoints = {20, 12, 8, 5};
        float[] expectedRadii = {28, 35, 42, 56};
        for (int tier = 0; tier < expectedRadii.length; tier++) {
            near(GameEngine.SIZE_RADII.get(tier), expectedRadii[tier], 0f,
                    "Public radius tier matches the game rule");
            equal(GameEngine.SIZE_POINTS.get(tier), expectedPoints[tier],
                    "Public score tier matches the game rule");
            equal(GameEngine.pointsForRadius(expectedRadii[tier]), expectedPoints[tier],
                    "Smaller fruit earns its stated higher score");
        }
        equal(GameEngine.pointsForRadius(29), 20, "Fixture radius twenty-nine uses the nearest small tier");
        equal(GameEngine.pointsForRadius(31.49f), 20, "Radius below first midpoint remains a small fruit");
        equal(GameEngine.pointsForRadius(31.5f), 12, "Midpoint tie uses the larger tier");
        equal(GameEngine.pointsForRadius(38.49f), 12, "Radius below second midpoint remains medium");
        equal(GameEngine.pointsForRadius(38.5f), 8, "Second midpoint chooses large score");
        equal(GameEngine.pointsForRadius(48.99f), 8, "Radius below last midpoint remains large");
        equal(GameEngine.pointsForRadius(49), 5, "Last midpoint chooses giant score");
        equal(GameEngine.pointsForRadius(1), 20, "Positive radii below all tiers use the smallest tier reward");
        equal(GameEngine.pointsForRadius(100), 5, "Radii above all tiers use the largest tier reward");
        for (float invalid : new float[] {0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            boolean rejected = false;
            try { GameEngine.pointsForRadius(invalid); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid scoring radius is rejected");
        }

        GameEngine engine = new GameEngine(101);
        int expectedTotal = 0;
        for (int type = 0; type < 6; type++) {
            for (int tier = 0; tier < expectedRadii.length; tier++) {
                GameEngine.Fruit fruit = engine.spawnFruit(type, 500, 200, 0, 0, expectedRadii[tier]);
                equal(fruit.basePoints, expectedPoints[tier], "Each fruit species uses the same size scoring rule");
                engine.beginStroke();
                engine.slice(0, 200, 1000, 200);
                engine.endStroke();
                expectedTotal += expectedPoints[tier];
                equal(engine.score, expectedTotal, "Single fruit awards the correct size-based score");
                List<GameEngine.Event> events = engine.drainEvents();
                equal(events.size(), 1, "Single slice emits exactly one event");
                equal(events.get(0).value, expectedPoints[tier], "Slice event carries the actual point reward");
                near(events.get(0).radius, expectedRadii[tier], 0f,
                        "Slice event preserves the cut fruit radius for rendering");
            }
        }

        for (boolean doubled : new boolean[] {false, true}) {
            engine.restart();
            if (doubled) { engine.mode = GameEngine.Mode.DOUBLE; engine.modeRemaining = 10; }
            engine.spawnFruit(0, 200, 200, 0, 0, 28);
            engine.spawnFruit(1, 500, 200, 0, 0, 35);
            engine.spawnFruit(2, 800, 200, 0, 0, 56);
            engine.slice(0, 200, 1000, 200);
            equal(engine.score, doubled ? 74 : 37, "Mixed sizes sum their own rewards before combo");
            engine.endStroke();
            equal(engine.score, doubled ? 86 : 43, "Mixed sizes retain two-point-per-fruit combo and double scaling");
            equal(engine.maxCombo, 3, "Different-sized fruits count equally toward a combo");
        }

        engine.restart();
        GameEngine.Fruit tiny = engine.spawnFruit(0, 200, 200, 0, 0, 28);
        engine.slice(0, 228.01f, 1000, 228.01f);
        equal(engine.sliced, 0, "Swipe just outside a tiny fruit is a true miss without extra hit padding");
        engine.slice(0, 200, 171, 200);
        equal(engine.sliced, 0, "Segment ending one pixel before tiny fruit does not hit");
        engine.slice(0, 228, 1000, 228);
        equal(engine.sliced, 1, "Exact tangent to a tiny fruit hits");
        equal(engine.score, 20, "Tiny edge hit earns twenty points");
        check(tiny.sliced, "Tiny hit removes the correct fruit");
        engine.endStroke();

        engine.restart();
        engine.spawnFruit(0, 200, 200, 0, 0, 28);
        engine.spawnFruit(1, 600, 200, 0, 0, 56);
        engine.slice(0, 229, 1000, 229);
        equal(engine.sliced, 1, "Same off-center swipe hits the giant and misses the tiny fruit");
        equal(engine.score, 5, "Giant-only hit earns five points");
        equal(engine.fruits.size(), 1, "Tiny fruit remains available after a nearby giant is cut");
        near(engine.fruits.get(0).radius, 28, 0f, "Missed tiny fruit retains exact geometry");
        engine.endStroke();

        engine.restart();
        GameEngine.Fruit resized = engine.spawnFruit(0, 500, 200, 0, 0, 28);
        GameEngine.Fruit resizedGiant = engine.spawnFruit(1, 800, 200, 0, 0, 56);
        engine.setBounds(500, 225);
        near(resized.radius, 14, 0f, "World resize scales a tiny fruit proportionally");
        near(resizedGiant.radius, 28, 0f, "World resize scales a giant fruit proportionally");
        near(resized.radius / resizedGiant.radius, .5f, 0f,
                "World resize preserves the exact one-half diameter ratio");
        equal(resized.basePoints, 20, "Resize preserves an already launched fruit reward");
        equal(resizedGiant.basePoints, 5, "Resize preserves an already launched giant reward");
        engine.fruits.clear();
        GameEngine.Fruit newTiny = engine.spawnFruit(1, 250, 100, 0, 0, 14);
        equal(newTiny.basePoints, 20, "New fruit in a resized field scores by its logical size");
    }

    private static void naturalSizeVariety() {
        for (int seed = 0; seed < 32; seed++) {
            GameEngine engine = new GameEngine(seed + 500);
            engine.setBounds(1000, 466);
            Set<Float> radiiSeen = new HashSet<>();
            int waves = 0;
            boolean rainbowSeen = false;
            while (engine.elapsed < 4.5f) {
                engine.update(.01f);
                Set<Float> thisWave = new HashSet<>();
                for (GameEngine.Fruit fruit : engine.fruits) {
                    if (fruit.type < 6) {
                        check(GameEngine.SIZE_RADII.contains(fruit.radius), "Natural fruit uses an exact size tier");
                        equal(fruit.basePoints, GameEngine.pointsForRadius(fruit.radius),
                                "Natural fruit stores the reward matching its visible size");
                        thisWave.add(fruit.radius);
                        radiiSeen.add(fruit.radius);
                    } else if (fruit.type == 6) {
                        rainbowSeen = true;
                        near(fruit.radius, 31.5948f, .001f, "Rainbow keeps its original size");
                        equal(fruit.basePoints, 5, "Rainbow keeps its original five-point reward");
                    } else {
                        near(fruit.radius, 26.2824f, .001f, "Bomb keeps its original size");
                        equal(fruit.basePoints, 0, "Bomb does not gain a size-based fruit reward");
                    }
                }
                if (!thisWave.isEmpty()) {
                    waves++;
                    check(thisWave.size() >= 2, "Every natural wave visibly mixes at least two different sizes");
                    if (waves == 1) {
                        check(thisWave.contains(28f) && thisWave.contains(56f),
                                "First wave immediately shows the smallest and largest fruit together");
                    }
                    if (waves == 2) equal(radiiSeen.size(), 4, "All four sizes appear within the first two waves");
                }
                engine.fruits.clear();
                engine.drainEvents();
            }
            equal(radiiSeen.size(), 4, "Natural opening covers every size tier");
            check(rainbowSeen, "Size variety preserves the early rainbow switch opportunity");
        }
    }

    private static void bladeTimingBoundaries() {
        BladeLimiter limiter = new BladeLimiter();
        check(BladeLimiter.MAX_DURATION_MS == 500L, "Blade duration is five hundred milliseconds");
        near(BladeLimiter.MAX_PATH_WIDTHS, 1.2f, 0f, "Blade path is one point two screen widths");
        check(limiter.move(7, 0, 0, 100, 0, 0, 1000) == null,
                "Movement requires a pressed pointer");
        limiter.press(7);
        check(limiter.move(7, 0, 0, 1.99f, 0, 10000, 1000) == null,
                "Movement below two units does not start a blade");
        check(!limiter.isStarted() && !limiter.isExhausted(), "Waiting finger has no running deadline");
        check(!limiter.expire(Long.MAX_VALUE), "Long press without a real swipe never expires");
        near(limiter.pathUsed(), 0, 0, "Pre-swipe jitter does not spend the path budget");
        check(limiter.move(7, 0, 0, 2, 0, 20000, 1000) != null,
                "First movement of exactly two units starts the blade after a long press");
        check(limiter.isStarted(), "Real movement starts the deadline");
        check(limiter.move(7, 2, 0, 2.25f, 0, 20001, 1000) != null,
                "Sub-two-unit movement after starting still spends path");
        near(limiter.pathUsed(), 2.25f, 0, "Every moving sample after the start contributes its distance");
        check(!limiter.expire(20499), "Blade remains usable one millisecond before deadline");
        check(limiter.move(7, 2.25f, 0, 3.25f, 0, 20002, 1000) != null,
                "Frame expiration checks do not discard older valid historical touch samples");
        check(limiter.move(7, 3.25f, 0, 100, 0, 20500, 1000) == null,
                "Movement at exactly five hundred milliseconds is rejected");
        check(limiter.isExhausted() && limiter.isStarted(), "Timed-out blade stays locked until release");
        near(limiter.pathUsed(), 3.25f, 0, "Rejected timed-out segment consumes no extra path");
        check(!limiter.expire(20501), "Already exhausted blade does not announce expiration twice");

        limiter.cancel();
        limiter.press(1);
        check(limiter.move(1, 0, 0, 10, 0, 0, 1000) != null, "Uptime zero is a valid blade start");
        check(limiter.move(1, 10, 0, 20, 0, 499, 1000) != null,
                "Movement at four hundred ninety-nine milliseconds is still valid");
        check(limiter.expire(500), "Frame timer expires at the exact deadline without another move");
        check(!limiter.expire(500) && !limiter.expire(700), "Expiration transition is reported only once");
        limiter.cancel();
        limiter.press(2);
        check(limiter.move(2, 0, 0, 10, 0, Long.MAX_VALUE - 500, 1000) != null,
                "Very large nonnegative touch timestamps are supported");
        check(!limiter.expire(Long.MAX_VALUE - 1), "Large timestamp deadline comparison does not overflow");
        check(limiter.expire(Long.MAX_VALUE), "Large timestamp expires at the correct boundary");
        limiter.cancel();
        limiter.press(1);
        check(limiter.move(1, 0, 0, 10, 0, 100, 1000) != null, "First finger starts the shared duration");
        limiter.press(2);
        limiter.release(1);
        check(limiter.move(2, 0, 0, 10, 0, 599, 1000) != null,
                "Relay finger can use the original blade just before its deadline");
        check(limiter.move(2, 10, 0, 20, 0, 600, 1000) == null,
                "Relay finger cannot postpone the original blade deadline");
        check(limiter.isExhausted(), "Time allowance is shared even when the original finger has lifted");
    }

    private static void bladePathAndPointers() {
        BladeLimiter limiter = new BladeLimiter();
        limiter.press(1);
        limiter.press(1);
        BladeLimiter.Cut exact = limiter.move(1, 0, 0, 1200, 0, 10, 1000);
        check(exact != null && limiter.isExhausted(), "Exact path boundary returns the final cut and locks");
        near(exact.x, 1200, 0, "Exact boundary preserves the requested endpoint");
        near(limiter.pathUsed(), 1200, 0, "Exact path boundary uses the complete budget");
        check(!limiter.expire(1000), "Path exhaustion is not a second time expiration");
        limiter.release(1);
        check(!limiter.isStarted() && !limiter.isExhausted(), "Duplicate press does not require duplicate releases");

        limiter.press(1);
        BladeLimiter.Cut diagonal = limiter.move(1, 0, 0, 1500, 2000, 20, 1000);
        check(diagonal != null && limiter.isExhausted(), "Overlong diagonal is clipped and exhausts the blade");
        near(diagonal.x, 720, .0001f, "Diagonal clipping preserves direction in x");
        near(diagonal.y, 960, .0001f, "Diagonal clipping preserves direction in y");
        near(limiter.pathUsed(), 1200, 0, "Diagonal clipping cannot overspend the budget");
        limiter.cancel();
        limiter.press(1);
        check(limiter.move(1, 0, 0, 900, 0, 100, 1000) != null, "First finger spends part of the shared path");
        limiter.press(2);
        limiter.release(1);
        limiter.press(3);
        BladeLimiter.Cut shared = limiter.move(2, 0, 0, 0, 600, 101, 1000);
        check(shared != null && limiter.isExhausted(), "Second finger inherits the first finger's remaining budget");
        near(shared.x, 0, 0, "Shared budget vertical clipping preserves x");
        near(shared.y, 300, 0, "Shared budget clips exactly to the remaining three hundred units");
        near(limiter.pathUsed(), 1200, 0, "All fingers spend one shared path allowance");
        limiter.release(2);
        limiter.press(4);
        limiter.release(3);
        limiter.release(999);
        check(limiter.isExhausted(), "Finger relay, new pointer and unknown release cannot recharge a blade");
        check(limiter.move(4, 0, 0, 100, 0, 102, 1000) == null, "Relay finger cannot slice while another remains down");
        limiter.release(4);
        near(limiter.pathUsed(), 0, 0, "Last finger release resets path allowance");
        check(!limiter.isStarted() && !limiter.isExhausted(), "Last finger release resets both blade flags");
        check(limiter.move(4, 0, 0, 100, 0, 103, 1000) == null, "Released pointer cannot move without a new press");
        limiter.press(5);
        check(limiter.move(5, 10, 20, 610, 20, 200, 1000) != null, "New complete touch gets a fresh blade");
        BladeLimiter.Cut resized = limiter.move(5, 610, 20, 2000, 20, 201, 2000);
        check(resized != null && limiter.isExhausted(), "Changing width mid-blade cannot refill its path");
        near(resized.x, 1210, 0, "Mid-blade width change retains the starting width allowance");

        limiter.cancel();
        limiter.press(6);
        check(limiter.move(6, 0, 0, 100, 0, 1000, 500) != null, "Smaller field permits normal movement");
        BladeLimiter.Cut smaller = limiter.move(6, 100, 0, 1000, 0, 1001, 500);
        check(smaller != null && limiter.isExhausted(), "Path allowance scales with logical field width");
        near(smaller.x, 600, 0, "Half-width field gets a six hundred unit allowance");
    }

    private static void bladeInvalidSamples() {
        BladeLimiter limiter = new BladeLimiter();
        limiter.press(-1);
        check(limiter.move(-1, 0, 0, 10, 0, 0, 1000) == null, "Invalid pointer ID cannot begin a blade");
        limiter.press(1);
        for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            check(limiter.move(1, value, 0, 10, 0, 0, 1000) == null, "Nonfinite start x is rejected");
            check(limiter.move(1, 0, value, 10, 0, 0, 1000) == null, "Nonfinite start y is rejected");
            check(limiter.move(1, 0, 0, value, 0, 0, 1000) == null, "Nonfinite end x is rejected");
            check(limiter.move(1, 0, 0, 10, value, 0, 1000) == null, "Nonfinite end y is rejected");
            check(limiter.move(1, 0, 0, 10, 0, 0, value) == null, "Nonfinite field width is rejected");
        }
        for (float value : new float[] {0, -1, Float.MAX_VALUE}) {
            check(limiter.move(1, 0, 0, 10, 0, 0, value) == null, "Invalid or overflowing path limit is rejected");
        }
        check(limiter.move(1, 0, 0, 10, 0, -1, 1000) == null, "Negative touch timestamp is rejected");
        check(!limiter.isStarted() && !limiter.isExhausted(), "Invalid samples do not poison a waiting blade");
        near(limiter.pathUsed(), 0, 0, "Invalid samples do not spend path");
        check(limiter.move(1, 0, 0, 100, 0, 100, 1000) != null, "Valid sample works after rejected samples");
        check(limiter.move(1, 100, 0, 200, 0, 99, 1000) == null, "Out-of-order touch timestamp is rejected");
        check(!limiter.expire(-1) && !limiter.expire(99), "Clock values before start cannot exhaust a blade");
        near(limiter.pathUsed(), 100, 0, "Out-of-order timestamp cannot modify the allowance");
        check(limiter.move(1, 100, 0, 100, 0, 101, 1000) == null, "Stationary sample returns no cut");
        near(limiter.pathUsed(), 100, 0, "Stationary sample consumes no path");
        limiter.cancel();
        check(!limiter.isStarted() && !limiter.isExhausted(), "Cancel resets blade state");
        check(limiter.move(1, 0, 0, 10, 0, 102, 1000) == null, "Cancel removes all active pointers");
        limiter.press(2);
        BladeLimiter.Cut extreme = limiter.move(2, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, 200, 1000);
        check(extreme != null && Float.isFinite(extreme.x) && Float.isFinite(extreme.y),
                "Extreme finite endpoints clip without NaN or infinity");
        check(limiter.isExhausted() && Float.isFinite(limiter.pathUsed()),
                "Extreme finite coordinates cannot corrupt the shared budget");
        limiter.cancel();
        limiter.cancel();
        limiter.press(3);
        check(limiter.move(3, 0, 0, 10, 0, 0, 1000) != null, "Repeated cancellation still permits a fresh gesture");
    }

    private static void bladeScoringAcrossModes() {
        for (GameEngine.Mode mode : GameEngine.Mode.values()) {
            GameEngine engine = new GameEngine(901);
            engine.mode = mode;
            engine.modeRemaining = 10;
            int multiplier = mode == GameEngine.Mode.DOUBLE ? 2 : 1;
            engine.spawnFruit(0, 200, 200, 0, 0, 28);
            engine.spawnFruit(1, 500, 200, 0, 0, 35);
            engine.spawnFruit(2, 800, 200, 0, 0, 56);
            BladeLimiter limiter = new BladeLimiter();
            limiter.press(11);
            limitedSlice(engine, limiter, 11, 0, 200, 1000, 200, 10);
            equal(engine.sliced, 3, "Every mode preserves genuine multi-fruit slicing within one blade");
            equal(engine.score, 37 * multiplier, "Valid blade retains size-based and double-mode scoring");
            limitedSlice(engine, limiter, 11, 1000, 200, 0, 200, 510);
            equal(engine.score, 43 * multiplier, "Deadline settles the existing combo exactly once");
            equal(engine.maxCombo, 3, "Finite blade retains the normal combo count");
            near(engine.elapsed, 0, 0, "Blade deadline uses touch time independently of engine frame time");
            engine.spawnFruit(3, 500, 200, 0, 0, 28);
            limitedSlice(engine, limiter, 11, 0, 200, 1000, 200, 511);
            equal(engine.sliced, 3, "Later fruit cannot be cut by an exhausted held finger in any mode");
            equal(engine.score, 43 * multiplier, "Held movement earns no further base points or combo bonus");
            limiter.press(12);
            limiter.release(11);
            limitedSlice(engine, limiter, 12, 1000, 200, 0, 200, 512);
            equal(engine.score, 43 * multiplier, "Finger relay cannot restore scoring after deadline");
            limiter.release(12);
            limiter.press(13);
            limitedSlice(engine, limiter, 13, 0, 200, 1000, 200, 513);
            limiter.release(13);
            engine.endStroke();
            equal(engine.score, 63 * multiplier, "Fully lifting and swiping again legitimately cuts the later fruit");
            equal(engine.maxCombo, 3, "New gesture does not merge into the previous combo");

            engine.restart();
            engine.mode = mode;
            engine.modeRemaining = 10;
            engine.spawnFruit(0, 1100, 200, 0, 0, 28);
            engine.spawnFruit(1, 1300, 200, 0, 0, 28);
            limiter.press(21);
            limitedSlice(engine, limiter, 21, 0, 200, 3000, 200, 1000);
            equal(engine.sliced, 1, "Clipped final segment cuts before its endpoint and misses fruit beyond it");
            equal(engine.score, 20 * multiplier, "Clipped blade never awards points outside the permitted path");
            limitedSlice(engine, limiter, 21, 1200, 200, 2000, 200, 1001);
            equal(engine.score, 20 * multiplier, "Path-exhausted finger cannot resume slicing even before deadline");
            limiter.release(21);
            limiter.press(22);
            limitedSlice(engine, limiter, 22, 1200, 200, 1450, 200, 1002);
            limiter.release(22);
            engine.endStroke();
            equal(engine.score, 40 * multiplier, "A fresh gesture reaches fruit beyond the old blade boundary");
        }
    }

    /** Mirrors the view's single permitted entry point without Android dependencies. */
    private static void limitedSlice(GameEngine engine, BladeLimiter limiter, int pointerId,
            float fromX, float fromY, float toX, float toY, long eventTime) {
        BladeLimiter.Cut cut = limiter.move(pointerId, fromX, fromY, toX, toY, eventTime, 1000);
        if (cut != null) engine.slice(fromX, fromY, cut.x, cut.y);
        if (limiter.isExhausted()) engine.endStroke();
    }

    private static int count(List<GameEngine.Event> events, GameEngine.Kind kind) {
        int total = 0;
        for (GameEngine.Event event : events) if (event.kind == kind) total++;
        return total;
    }

    private static void equal(int actual, int expected, String message) {
        check(actual == expected, message + " (actual=" + actual + ", expected=" + expected + ")");
    }

    private static void near(float actual, float expected, float tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance,
                message + " (actual=" + actual + ", expected=" + expected + ")");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
