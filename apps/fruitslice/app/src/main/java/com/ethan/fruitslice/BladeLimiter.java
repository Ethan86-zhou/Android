package com.ethan.fruitslice;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** One shared blade budget for all fingers until every finger leaves the screen. */
public final class BladeLimiter {
    public static final long MAX_DURATION_MS = 500L;
    public static final float MAX_PATH_WIDTHS = 1.2f;

    /** End of the permitted part of a movement, in the same coordinates as its input. */
    public static final class Cut {
        public final float x, y;

        private Cut(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private final Set<Integer> pointers = new HashSet<>();
    private final Map<Integer, Double> pendingTravel = new HashMap<>();
    private boolean started, exhausted;
    private long startedAt, lastEventTime = -1L;
    private double used, pathLimit;

    public void press(int pointerId) {
        if (pointerId >= 0) pointers.add(pointerId);
    }

    public void release(int pointerId) {
        pendingTravel.remove(pointerId);
        if (pointers.remove(pointerId) && pointers.isEmpty()) reset();
    }

    public void cancel() {
        pointers.clear();
        reset();
    }

    /**
     * Starts after one finger has moved a cumulative two logical units. The width at that
     * point fixes this blade's path allowance; later fingers cannot refresh it.
     * High-frequency touch samples may each be shorter than the starting threshold.
     * Invalid or out-of-order samples leave the budget unchanged.
     */
    public Cut move(int pointerId, float fromX, float fromY, float toX, float toY,
            long eventTime, float worldWidth) {
        if (!pointers.contains(pointerId) || exhausted || eventTime < 0L
                || eventTime < lastEventTime || !Float.isFinite(fromX) || !Float.isFinite(fromY)
                || !Float.isFinite(toX) || !Float.isFinite(toY)
                || !Float.isFinite(worldWidth) || worldWidth <= 0f) return null;
        float proposedLimit = worldWidth * MAX_PATH_WIDTHS;
        if (!Float.isFinite(proposedLimit) || proposedLimit <= 0f) return null;
        double dx = (double) toX - fromX, dy = (double) toY - fromY;
        double length = Math.hypot(dx, dy);
        if (expire(eventTime)) return null;
        lastEventTime = eventTime;
        if (!started) {
            double pending = pendingTravel.getOrDefault(pointerId, 0d);
            if (pending + length < 2d) {
                pendingTravel.put(pointerId, pending + length);
                return null;
            }
            started = true;
            startedAt = eventTime;
            pathLimit = proposedLimit;
            // Count the lead-in distance too, so denser sampling cannot extend the blade.
            used = Math.min(pending, pathLimit);
            pendingTravel.clear();
        }
        if (length == 0d) return null;
        double remaining = pathLimit - used;
        if (remaining <= 0d) { exhausted = true; return null; }
        if (length >= remaining) {
            double fraction = remaining / length;
            used = pathLimit;
            exhausted = true;
            return new Cut((float) (fromX + dx * fraction), (float) (fromY + dy * fraction));
        }
        used += length;
        return new Cut(toX, toY);
    }

    public boolean isExhausted() { return exhausted; }

    public boolean isStarted() { return started; }

    public float pathUsed() { return (float) used; }

    /** Returns true only for the transition caused by reaching this blade's deadline. */
    public boolean expire(long now) {
        if (!started || exhausted || now < startedAt || now - startedAt < MAX_DURATION_MS) return false;
        exhausted = true;
        return true;
    }

    private void reset() {
        started = exhausted = false;
        startedAt = 0L;
        lastEventTime = -1L;
        used = pathLimit = 0d;
        pendingTravel.clear();
    }
}
