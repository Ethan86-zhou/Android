package com.zhou.douyinlite;

/** 纯 Java 的只读观察时序。阈值仅供提示，不作清理决策；时间来自单调时钟。 */
final class CachePolicy {
    static final long SOFT_LIMIT_BYTES = 1024L * 1024L * 1024L;
    static final long SCAN_INTERVAL_MS = 2 * 60_000L;
    static final long POST_MANUAL_CLEAR_QUIET_MS = 10 * 60_000L;
    static final long BACKGROUND_QUIET_MS = 8_000L;
    static final long FOREGROUND_QUIET_MS = 15_000L;
    static final long NONE = -1L;

    private boolean foreground = true;
    private boolean busy = true;
    private boolean closed;
    private long safeSince = NONE;
    private long lastScanAt = NONE;
    private long lastManualClearAt = NONE;
    private long nextTicket;
    private long activeScan = NONE;

    /** 同一状态的媒体轮询不重置安静期；播放时暂停自动观察。 */
    synchronized void updateUsage(boolean inForeground, boolean playbackBusy, long now) {
        if (closed) return;
        boolean nextBusy = inForeground && playbackBusy;
        if (foreground == inForeground && busy == nextBusy) return;
        foreground = inForeground;
        busy = nextBusy;
        safeSince = busy ? NONE : now;
    }

    /** -1 表示暂不安排；否则是距离下一次可开始只读观察的毫秒数。 */
    synchronized long observationDelay(long now) {
        if (closed || busy || safeSince == NONE || activeScan != NONE) return NONE;
        long delay = remaining(now, safeSince,
                foreground ? FOREGROUND_QUIET_MS : BACKGROUND_QUIET_MS);
        if (lastScanAt != NONE) delay = Math.max(delay, remaining(now, lastScanAt, SCAN_INTERVAL_MS));
        if (lastManualClearAt != NONE) delay = Math.max(delay, remaining(now, lastManualClearAt, POST_MANUAL_CLEAR_QUIET_MS));
        return delay;
    }

    synchronized long beginObservation(long now) {
        if (observationDelay(now) != 0) return NONE;
        lastScanAt = now;
        activeScan = ++nextTicket;
        return activeScan;
    }

    /** 完成只读扫描，不接受缓存大小参数，也不会生成清理指令。 */
    synchronized void finishObservation(long ticket) {
        if (ticket != NONE && ticket == activeScan) activeScan = NONE;
    }

    synchronized void manualClearRequested(long now) {
        if (closed) return;
        lastManualClearAt = now;
    }

    synchronized void close() {
        closed = true;
        activeScan = NONE;
    }

    private static long remaining(long now, long since, long period) {
        return Math.max(0L, period - Math.max(0L, now - since));
    }
}
