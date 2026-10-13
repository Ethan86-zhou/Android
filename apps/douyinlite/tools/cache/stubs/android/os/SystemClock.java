package android.os;
public final class SystemClock {
    private static volatile long now;
    public static long uptimeMillis() { return now; }
    public static void advance(long millis) { now+=millis; }
}
