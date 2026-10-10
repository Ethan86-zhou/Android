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
        near(GameEngine.MIN_FRUIT_RADIUS, 14f, 0f, "Smallest logical radius is fourteen");
        near(GameEngine.MAX_FRUIT_RADIUS, 56f, 0f, "Largest logical radius is fifty-six");
        near((2f * GameEngine.MAX_FRUIT_RADIUS) / (2f * GameEngine.MIN_FRUIT_RADIUS),
                4f, 0f, "Largest diameter is exactly four times the smallest diameter");
        int[] expectedPoints = {20, 12, 8, 5};
        float[] expectedRadii = {14, 28, 42, 56};
        for (int tier = 0; tier < expectedRadii.length; tier++) {
            near(GameEngine.SIZE_RADII.get(tier), expectedRadii[tier], 0f,
                    "Public radius tier matches the game rule");
            equal(GameEngine.SIZE_POINTS.get(tier), expectedPoints[tier],
                    "Public score tier matches the game rule");
            equal(GameEngine.pointsForRadius(expectedRadii[tier]), expectedPoints[tier],
                    "Smaller fruit earns its stated higher score");
        }
        equal(GameEngine.pointsForRadius(29), 12, "Fixture radius twenty-nine uses the nearest medium tier");
        equal(GameEngine.pointsForRadius(20.99f), 20, "Radius below first midpoint remains a small fruit");
        equal(GameEngine.pointsForRadius(21), 12, "Midpoint tie uses the larger tier");
        equal(GameEngine.pointsForRadius(34.99f), 12, "Radius below second midpoint remains medium");
        equal(GameEngine.pointsForRadius(35), 8, "Second midpoint chooses large score");
        equal(GameEngine.pointsForRadius(48.99f), 8, "Radius below last midpoint remains large");
        equal(GameEngine.pointsForRadius(49), 5, "Last midpoint chooses giant score");
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
            engine.spawnFruit(0, 200, 200, 0, 0, 14);
            engine.spawnFruit(1, 500, 200, 0, 0, 28);
            engine.spawnFruit(2, 800, 200, 0, 0, 56);
            engine.slice(0, 200, 1000, 200);
            equal(engine.score, doubled ? 74 : 37, "Mixed sizes sum their own rewards before combo");
            engine.endStroke();
            equal(engine.score, doubled ? 86 : 43, "Mixed sizes retain two-point-per-fruit combo and double scaling");
            equal(engine.maxCombo, 3, "Different-sized fruits count equally toward a combo");
        }

        engine.restart();
        GameEngine.Fruit tiny = engine.spawnFruit(0, 200, 200, 0, 0, 14);
        engine.slice(0, 214.01f, 1000, 214.01f);
        equal(engine.sliced, 0, "Swipe just outside a tiny fruit is a true miss without extra hit padding");
        engine.slice(0, 200, 185, 200);
        equal(engine.sliced, 0, "Segment ending one pixel before tiny fruit does not hit");
        engine.slice(0, 214, 1000, 214);
        equal(engine.sliced, 1, "Exact tangent to a tiny fruit hits");
        equal(engine.score, 20, "Tiny edge hit earns twenty points");
        check(tiny.sliced, "Tiny hit removes the correct fruit");
        engine.endStroke();

        engine.restart();
        engine.spawnFruit(0, 200, 200, 0, 0, 14);
        engine.spawnFruit(1, 600, 200, 0, 0, 56);
        engine.slice(0, 215, 1000, 215);
        equal(engine.sliced, 1, "Same off-center swipe hits the giant and misses the tiny fruit");
        equal(engine.score, 5, "Giant-only hit earns five points");
        equal(engine.fruits.size(), 1, "Tiny fruit remains available after a nearby giant is cut");
        near(engine.fruits.get(0).radius, 14, 0f, "Missed tiny fruit retains exact geometry");
        engine.endStroke();

        engine.restart();
        GameEngine.Fruit resized = engine.spawnFruit(0, 500, 200, 0, 0, 14);
        engine.setBounds(500, 225);
        near(resized.radius, 7, 0f, "World resize scales a tiny fruit proportionally");
        equal(resized.basePoints, 20, "Resize preserves an already launched fruit reward");
        engine.fruits.clear();
        GameEngine.Fruit newTiny = engine.spawnFruit(1, 250, 100, 0, 0, 7);
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
                        check(thisWave.contains(14f) && thisWave.contains(56f),
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
