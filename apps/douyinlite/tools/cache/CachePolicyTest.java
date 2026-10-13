package com.zhou.douyinlite;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** 虚拟单调时间验证只读观察节流；策略不接收缓存大小，也不返回清理指令。 */
public final class CachePolicyTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        CachePolicy active = new CachePolicy();
        equal(CachePolicy.NONE, active.observationDelay(0), "startup is conservatively busy");
        active.updateUsage(true, true, 100);
        equal(CachePolicy.NONE, active.beginObservation(10_000_000), "long playback does not scan");
        CachePolicy idle = new CachePolicy();
        idle.updateUsage(true, false, 100);
        equal(15_000, idle.observationDelay(100), "foreground waits 15 seconds");
        idle.updateUsage(true, false, 14_900);
        equal(200, idle.observationDelay(14_900), "same-state polling retains quiet start");
        equal(CachePolicy.NONE, idle.beginObservation(15_099), "quiet boundary is strict");
        long ticket = idle.beginObservation(15_100);
        check(ticket != CachePolicy.NONE, "idle observation begins at boundary");
        equal(CachePolicy.NONE, idle.beginObservation(15_101), "one observation in flight");
        idle.finishObservation(ticket);
        equal(119_900, idle.observationDelay(15_200), "observation throttles for two minutes");
        long oldTicket = ticket;
        ticket = idle.beginObservation(135_100);
        check(ticket != oldTicket && ticket != CachePolicy.NONE, "scan tickets are unique");
        idle.finishObservation(oldTicket);
        equal(CachePolicy.NONE, idle.observationDelay(135_101), "old callback does not consume new scan");
        idle.finishObservation(ticket);
        equal(119_998, idle.observationDelay(135_102), "current callback releases scan");
        idle.manualClearRequested(135_102);
        equal(600_000, idle.observationDelay(135_102), "manual clear backs off periodic statistics");
        equal(CachePolicy.NONE, idle.beginObservation(735_101), "post-clear quiet boundary is strict");
        check(idle.beginObservation(735_102) != CachePolicy.NONE, "statistics resume after post-clear quiet period");

        CachePolicy background = new CachePolicy();
        background.updateUsage(false, false, 1_000);
        equal(8_000, background.observationDelay(1_000), "background waits eight seconds");
        background.updateUsage(false, true, 8_999);
        equal(1, background.observationDelay(8_999), "late media status does not restart background grace");
        ticket = background.beginObservation(9_000);
        background.updateUsage(true, true, 9_001);
        background.finishObservation(ticket);
        equal(CachePolicy.NONE, background.observationDelay(999_999), "foreground playback prevents rescheduling");

        CachePolicy closing = new CachePolicy();
        closing.updateUsage(false, false, 0);
        ticket = closing.beginObservation(8_000);
        Thread closeThread = new Thread(closing::close);
        closeThread.start(); closeThread.join();
        closing.finishObservation(ticket);
        closing.updateUsage(false, false, 10_000);
        closing.manualClearRequested(10_000);
        equal(CachePolicy.NONE, closing.observationDelay(10_000_000), "closed policy cannot be revived by late work");

        CachePolicy concurrent = new CachePolicy();
        concurrent.updateUsage(false, false, 0);
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] callers = new Thread[8];
        for (int index = 0; index < callers.length; index++) {
            callers[index] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                if (concurrent.beginObservation(8_000) != CachePolicy.NONE) accepted.incrementAndGet();
            });
            callers[index].start();
        }
        start.countDown();
        for (Thread caller : callers) caller.join();
        equal(1, accepted.get(), "concurrent hints do not duplicate observations");
        System.out.println("PASS: " + assertions + " cache observation policy checks");
    }

    private static void equal(long expected, long actual, String message) {
        check(expected == actual, message + ": expected " + expected + ", actual " + actual);
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
