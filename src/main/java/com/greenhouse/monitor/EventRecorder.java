package com.greenhouse.monitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Author: Role 5 - Timing & Integration Lead
 * Concepts: #8 synchronized methods (many threads record events at the same time)
 * Purpose: Remembers the important events (faults, detections, votes, safe state, actuator changes ...)
 *          since the current scenario started, so a clear numbered SUMMARY can be printed at the end
 *          of each scenario and returned to Postman.
 */
public final class EventRecorder {

    private static final int MAX_EVENTS = 300;

    private static final List<String> events = new ArrayList<>();
    private static String scenario = "(no scenario started)";
    private static String expected = "";
    private static long startedAtMs = System.currentTimeMillis();
    private static int droppedEvents;

    private EventRecorder() { }

    private static String lastKey = "";
    private static int repeats;

    public static synchronized void record(String line) {
        // repeated warnings (e.g. "SAFE STATE ACTIVE for 3 s" every second) are collapsed into one line
        String key = line.length() > 14 ? line.substring(14).replaceAll("[0-9]", "") : line;
        if (key.equals(lastKey) && !events.isEmpty()) {
            repeats++;
            String last = events.get(events.size() - 1).replaceAll("  \\(repeated x[0-9]+\\)$", "");
            events.set(events.size() - 1, last + "  (repeated x" + (repeats + 1) + ")");
            return;
        }
        lastKey = key;
        repeats = 0;
        if (events.size() < MAX_EVENTS) {
            events.add(line);
        } else {
            droppedEvents++;
        }
    }

    public static synchronized void start(String name, String expect) {
        events.clear();
        lastKey = "";
        repeats = 0;
        droppedEvents = 0;
        scenario = name;
        expected = expect == null ? "" : expect;
        startedAtMs = System.currentTimeMillis();
    }

    public static synchronized List<String> events() {
        return new ArrayList<>(events);
    }

    public static synchronized String scenario() { return scenario; }

    public static synchronized String expected() { return expected; }

    public static synchronized long startedAtMs() { return startedAtMs; }

    public static synchronized int droppedEvents() { return droppedEvents; }
}
