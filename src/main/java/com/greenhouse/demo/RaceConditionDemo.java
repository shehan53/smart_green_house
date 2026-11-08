package com.greenhouse.demo;

import com.greenhouse.monitor.Log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Author: Role 3 - Concurrency Engineer
 * Concepts: #7 Race condition on count++ and the AtomicInteger fix, #8 synchronized fix,
 *           #6 Threads (Runnable, start, join)
 * Purpose: Scenario F4. Two threads increment a counter 100 000 times each (expected 200 000).
 *          The unsafe counter loses updates, the synchronized and AtomicInteger counters are correct.
 */
public class RaceConditionDemo {

    public static final int INCREMENTS_PER_THREAD = 100_000;
    private static final int MAX_ATTEMPTS = 5;

    /** UNSAFE: count++ is read-modify-write (3 steps) -> two threads can overwrite each other. */
    static class UnsafeCounter {
        private int count;
        void increment() { count++; }
        int get() { return count; }
    }

    /** FIX 1: synchronized -> only one thread at a time executes count++ (mutual exclusion). */
    static class SynchronizedCounter {
        private int count;
        synchronized void increment() { count++; }
        synchronized int get() { return count; }
    }

    public synchronized Map<String, Object> run() throws InterruptedException {
        Log.info("RACE-DEMO", "F4 race-condition demo: 2 threads x " + INCREMENTS_PER_THREAD + " increments each");
        int expected = 2 * INCREMENTS_PER_THREAD;

        int unsafeResult = expected;
        int attempts = 0;
        // the race depends on timing: repeat (max 5x) until lost updates become visible
        while (attempts < MAX_ATTEMPTS && unsafeResult == expected) {
            attempts++;
            UnsafeCounter unsafe = new UnsafeCounter();
            runTwoThreads(unsafe::increment);
            unsafeResult = unsafe.get();
        }

        SynchronizedCounter sync = new SynchronizedCounter();
        runTwoThreads(sync::increment);

        AtomicInteger atomic = new AtomicInteger(); // FIX 2: CONCEPT 7 atomic compare-and-set, no lock
        runTwoThreads(atomic::incrementAndGet);

        Log.warn("RACE-DEMO", "Unsafe counter       = " + unsafeResult + " (expected " + expected + ", LOST "
                + (expected - unsafeResult) + " updates) after " + attempts + " attempt(s)");
        Log.info("RACE-DEMO", "Synchronized counter = " + sync.get() + (sync.get() == expected ? " CORRECT" : " WRONG"));
        Log.info("RACE-DEMO", "AtomicInteger        = " + atomic.get() + (atomic.get() == expected ? " CORRECT" : " WRONG"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expected", expected);
        result.put("unsafe", unsafeResult);
        result.put("lostUpdates", expected - unsafeResult);
        result.put("unsafeAttempts", attempts);
        result.put("synchronized", sync.get());
        result.put("atomic", atomic.get());
        return result;
    }

    // CONCEPT 6: two threads created from the same Runnable, started together and joined
    private void runTwoThreads(Runnable increment) throws InterruptedException {
        StartGate gate = new StartGate();
        Runnable work = () -> {
            while (!gate.open) Thread.onSpinWait(); // both threads start at the same moment
            for (int i = 0; i < INCREMENTS_PER_THREAD; i++) increment.run();
        };
        Thread a = new Thread(work, "race-A");
        Thread b = new Thread(work, "race-B");
        a.start();
        b.start();
        gate.open = true;
        a.join();
        b.join();
    }

    private static class StartGate {
        volatile boolean open; // CONCEPT 7: volatile -> the change is seen by both threads at once
    }
}
