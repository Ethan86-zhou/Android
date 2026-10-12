package com.zhou.douyinlite;

/** 无 Android/JUnit 依赖，可直接用 JDK 17 执行。 */
public final class EdgeGestureStateTest {
    private static int checks;

    public static void main(String[] args) {
        normalSwipeAndShortPress();
        holdAndRelease();
        downSlideLocksThenNextSwipePasses();
        cancellationAndMultiplePointers();
        unavailableVideoAndExternalReset();
        thresholdBoundaries();
        System.out.println("PASS: " + checks + " edge gesture state checks");
    }

    private static EdgeGestureState ready() {
        EdgeGestureState s = new EdgeGestureState(8, 72);
        s.setAllowed(true);
        return s;
    }

    private static void normalSwipeAndShortPress() {
        EdgeGestureState s = ready();
        s.down(150, 300, false, 1);
        check(!s.isCandidate() && !s.longPress(), "center never captures");
        s.move(150, 150, 1);
        check(!s.isCaptured(), "center swipe passes");
        s.up();
        s.down(5, 300, true, 1);
        s.move(5, 280, 1);
        check(!s.isCandidate() && !s.longPress(), "edge short swipe passes");
        s.up();
        s.down(5, 300, true, 1);
        s.up();
        check(!s.longPress() && !s.isBoosted(), "short tap does not boost");
    }

    private static void holdAndRelease() {
        EdgeGestureState s = ready();
        s.down(5, 300, true, 1);
        s.move(8, 303, 1);
        check(s.isCandidate(), "minor finger jitter remains candidate");
        check(s.longPress(), "stationary long hold activates");
        check(s.isBoosted() && s.isCaptured() && !s.isLocked(), "hold state");
        check(!s.longPress(), "timer cannot activate twice");
        s.move(5, 200, 1);
        check(!s.isLocked(), "upward movement cannot lock");
        s.up();
        check(!s.isBoosted() && !s.isCaptured(), "release restores normal");
    }

    private static void downSlideLocksThenNextSwipePasses() {
        EdgeGestureState s = ready();
        s.down(355, 300, true, 1);
        s.longPress();
        s.move(355, 371, 1);
        check(!s.isLocked(), "below lock distance");
        s.move(355, 372, 1);
        check(s.isBoosted() && s.isLocked() && s.isCaptured(), "down slide locks");
        s.up();
        check(s.isBoosted() && s.isLocked() && !s.isCaptured(), "locked release");
        s.down(355, 300, true, 1);
        check(!s.isCandidate() && !s.isCaptured(), "locked next edge swipe passes");
        s.move(355, 100, 1);
        s.up();
        check(s.isLocked() && s.isBoosted(), "next swipe preserves lock");
        s.resetBoost();
        check(!s.isLocked() && !s.isBoosted(), "unlock restores normal");
    }

    private static void cancellationAndMultiplePointers() {
        EdgeGestureState s = ready();
        s.down(5, 300, true, 1);
        s.multiplePointers();
        check(!s.isCandidate() && !s.longPress() && !s.isCaptured(), "second finger before hold passes");
        s.up();
        s.down(5, 300, true, 1);
        s.longPress();
        s.multiplePointers();
        check(!s.isBoosted() && s.isCaptured(), "second finger restores, keeps sequence ownership");
        s.move(5, 450, 1);
        check(!s.isBoosted() && !s.isLocked(), "removed finger cannot relock");
        s.up();
        check(!s.isCaptured(), "captured sequence ends on final up");
        s.down(5, 300, true, 1);
        s.longPress();
        s.move(5, 400, 1);
        s.cancel();
        check(!s.isBoosted() && !s.isLocked() && !s.isCaptured(), "cancel clears held and locked");
        s.down(5, 300, true, 2);
        check(!s.isCandidate(), "multi pointer down never candidate");
        s.down(5, 300, true, 1);
        s.move(5, 300, 2);
        check(!s.isCandidate(), "multi pointer move cancels candidate");
    }

    private static void unavailableVideoAndExternalReset() {
        EdgeGestureState s = new EdgeGestureState(8, 72);
        s.down(5, 300, true, 1);
        check(!s.longPress() && !s.isCaptured(), "missing video leaves webpage touch intact");
        s.setAllowed(true);
        check(!s.longPress(), "late availability cannot revive expired hold");
        s.down(5, 300, true, 1);
        s.longPress();
        s.setAllowed(false);
        check(!s.isBoosted() && s.isCaptured(), "lost video restores without leaking move");
        s.up();
        s.setAllowed(true);
        s.down(5, 300, true, 1);
        s.longPress();
        s.move(5, 400, 1);
        s.resetBoost();
        check(!s.isBoosted() && !s.isLocked() && s.isCaptured(), "background/unlock clears speed");
        s.up();
        s.down(5, 300, true, 1);
        s.resetBoost();
        check(!s.longPress(), "background cancels pending timer");
    }

    private static void thresholdBoundaries() {
        EdgeGestureState s = ready();
        s.down(5, 300, true, 1);
        s.move(13, 300, 1);
        check(s.isCandidate(), "exact slop allowed");
        s.move(13.01f, 300, 1);
        check(!s.isCandidate(), "beyond slop cancels");
        s.down(5, 300, true, 1);
        s.move(11, 306, 1);
        check(!s.isCandidate(), "diagonal uses distance instead of per axis tolerance");
        boolean rejected = false;
        try { new EdgeGestureState(-1, 72); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid slop rejected");
        rejected = false;
        try { new EdgeGestureState(8, 0); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid lock distance rejected");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
