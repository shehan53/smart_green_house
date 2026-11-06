package com.greenhouse.monitor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Author: Role 5 - Timing & Integration Lead
 * Concepts: #11 Execution-time measurement (System.nanoTime) and Rate-Monotonic utilisation test,
 *           #1 deadline monitoring (hard / firm / soft), #8 synchronized, #7 AtomicLong
 * Purpose: Every periodic task reports its execution time C. The monitor keeps the maximum C
 *          (estimate of the WCET), computes U = C / T per task, the total U and compares it with the
 *          RM bound n(2^(1/n) - 1) = 0.757 for n = 4. Late releases and overruns are logged as deadline misses.
 */
public class TimingMonitor {

    public enum Task {
        SENSOR("Sensor sampling", 500, "RT1", "FIRM"),
        CONTROL("Control cycle", 1000, "RT4", "FIRM"),
        WATCHDOG("Watchdog check", 1000, "RT3", "HARD"),
        DISPLAY("Status display", 2000, "RT5", "SOFT");

        final String label;
        final long periodMs;
        final String requirement;
        final String deadlineType;

        Task(String label, long periodMs, String requirement, String deadlineType) {
            this.label = label;
            this.periodMs = periodMs;
            this.requirement = requirement;
            this.deadlineType = deadlineType;
        }
    }

    public static final int N_TASKS = Task.values().length;
    public static final double RM_BOUND = N_TASKS * (Math.pow(2, 1.0 / N_TASKS) - 1); // 0.757 for n = 4

    private static class Stats {
        long runs;
        long totalNanos;
        long maxNanos;
    }

    private final Map<Task, Stats> stats = new EnumMap<>(Task.class);
    private final Map<String, Long> lastReleaseNanos = new HashMap<>();
    private final AtomicLong deadlineMisses = new AtomicLong(); // CONCEPT 7: lock-free counter
    private final long startedAtMs = System.currentTimeMillis();

    public TimingMonitor() {
        for (Task t : Task.values()) stats.put(t, new Stats());
    }

    /**
     * CONCEPT 11: C = end - start measured with System.nanoTime() around the task body.
     * @param instance e.g. "sensor-T1" - used to check the time between two releases of the same task
     */
    public void record(Task task, String instance, long startNanos, long endNanos) {
        long c = endNanos - startNanos;
        long periodNanos = task.periodMs * 1_000_000L;
        Long previousRelease;
        synchronized (this) { // CONCEPT 8: several task threads update the statistics at the same time
            Stats s = stats.get(task);
            s.runs++;
            s.totalNanos += c;
            if (c > s.maxNanos) s.maxNanos = c;
            previousRelease = lastReleaseNanos.put(instance, startNanos);
        }
        if (c > periodNanos) {
            deadlineMiss(task, instance + " execution time " + Log.f3(c / 1e6) + " ms > period " + task.periodMs + " ms");
        }
        if (previousRelease != null) {
            long gap = startNanos - previousRelease;
            if (gap > periodNanos * 3 / 2) {
                deadlineMiss(task, instance + " released " + Log.f1(gap / 1e6) + " ms after the previous release (period "
                        + task.periodMs + " ms) - late (GC pause / OS scheduling?)");
            }
        }
    }

    private void deadlineMiss(Task task, String msg) {
        deadlineMisses.incrementAndGet();
        Log.warn("DEADLINE", "[" + task.requirement + " " + task.deadlineType + "] DEADLINE MISS: " + msg);
    }

    /** Timing table as data (for GET /api/timing). */
    public synchronized Map<String, Object> snapshot() {
        List<Map<String, Object>> rows = new ArrayList<>();
        double totalU = 0;
        for (Task t : Task.values()) {
            Stats s = stats.get(t);
            double maxCms = s.maxNanos / 1e6;
            double avgCms = s.runs == 0 ? 0 : (s.totalNanos / (double) s.runs) / 1e6;
            double u = maxCms / t.periodMs;
            totalU += u;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", t.label);
            row.put("periodMs", t.periodMs);
            row.put("runs", s.runs);
            row.put("avgCms", round(avgCms, 4));
            row.put("maxCms", round(maxCms, 4));
            row.put("utilisation", round(u, 6));
            rows.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("measuredForSeconds", (System.currentTimeMillis() - startedAtMs) / 1000);
        m.put("tasks", rows);
        m.put("totalUtilisation", round(totalU, 6));
        m.put("rmBound", round(RM_BOUND, 3));
        m.put("schedulable", totalU <= RM_BOUND);
        m.put("deadlineMisses", deadlineMisses.get());
        return m;
    }

    /** CONCEPT 11: prints the timing analysis table (Section 4 of the assignment). */
    public void printReport(String title) {
        Map<String, Object> snap = snapshot();
        Log.info("TIMING", "================ TIMING REPORT (" + title + ", " + snap.get("measuredForSeconds") + " s) ================");
        Log.info("TIMING", String.format("%-16s %6s %7s %10s %10s %10s", "Task", "T(ms)", "runs", "avgC(ms)", "maxC(ms)", "U=C/T"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) snap.get("tasks");
        for (Map<String, Object> r : rows) {
            Log.info("TIMING", String.format(Locale.US, "%-16s %6d %7d %10.4f %10.4f %10.6f",
                    r.get("task"), (Long) r.get("periodMs"), (Long) r.get("runs"),
                    (Double) r.get("avgCms"), (Double) r.get("maxCms"), (Double) r.get("utilisation")));
        }
        double totalU = (Double) snap.get("totalUtilisation");
        boolean ok = (Boolean) snap.get("schedulable");
        Log.info("TIMING", String.format(Locale.US, "Total U = %.6f   RM bound n(2^(1/n)-1), n=%d = %.3f   -> %s",
                totalU, N_TASKS, RM_BOUND, ok ? "SCHEDULABLE (U <= bound)" : "NOT GUARANTEED (U > bound)"));
        Log.info("TIMING", "Deadline misses so far: " + snap.get("deadlineMisses")
                + "   (max C = measured estimate of the WCET; sensor row = max over T1,T2,T3,H1,S1)");
    }

    public long getDeadlineMisses() { return deadlineMisses.get(); }

    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
