package com.greenhouse.monitor;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Author: Role 5 - Timing & Integration Lead
 * Concepts: #1 Real-time system (every event time-stamped to the millisecond),
 *           #8 synchronized methods (many threads log, only one writes at a time)
 * Purpose: Prints every event with a millisecond timestamp. One folder per date, two files per run:
 *            logs/YYYY-MM-DD/greenhouse_HH-mm-ss.log  - EXACT copy of the CLI window (see ConsoleCopy)
 *            logs/YYYY-MM-DD/events_HH-mm-ss.log      - only inputs, important events, scenario boxes, summaries
 *          Important lines get a marker so they stand out:
 *            >>> fault / action      !!! safe state, alarm, error      +++ recovery
 */
public final class Log {

    public enum Level { INFO, WARN, ERROR, CRITICAL }

    /** Tags whose INFO lines are also important (WARN / ERROR / CRITICAL lines always are). */
    private static final Set<String> IMPORTANT_TAGS = Set.of(
            "ACTUATOR", "VOTER", "WATCHDOG", "SAFE-STATE", "ALARM", "RT2", "RT3",
            "FAULT", "OBSERVE", "RACE-DEMO", "SYSTEM", "VALIDATION", "SIMULATOR");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final Path LOG_DIR = Paths.get("logs");
    private static final String HASH_LINE = "#".repeat(92);
    private static final String BOX_LINE = "-".repeat(92);
    private static final String EQ_LINE = "=".repeat(92);
    private static final String INPUT_TITLE = "=".repeat(36) + " NEW INPUT RECEIVED " + "=".repeat(36);

    /** The moment this run (program start) began - used in the file names. */
    static final LocalDateTime RUN_START = LocalDateTime.now();
    static final String RUN_NAME = RUN_START.format(DateTimeFormatter.ofPattern("HH-mm-ss"));

    private static final String[] LEGEND = {
            "=================================== HOW TO READ THIS SCREEN ==================================",
            " Every line:  time(ms) [thread] LEVEL [TAG] message",
            "",
            " REPEATING LINES - normal, they only show the system is alive and working:",
            "   [STATUS]  every 2 s  : temperature, vote, humidity, soil, actuators, safe state, sensors",
            "   [TIMING]  every 60 s : execution-time table (max C, U = C/T, RM test)",
            "   [CHECK]              : Postman reads the status to test the result (no change)",
            "",
            " EVENT LINES - something happened:",
            "   ===== NEW INPUT RECEIVED =====  a request from Postman or a menu key arrived;",
            "                                   the lines BELOW it are the system's reaction",
            "   >>> fault / action    !!! safe state, alarm, error    +++ recovery / back to normal",
            "   [FAULT] sensor broken or repaired     [VOTER] voting result changed (OK/MASKED/...)",
            "   [WATCHDOG] sensor dead / alive again  [ACTUATOR] heater, fan, pump or alarm switched",
            "   [SAFE-STATE] safe state entered/left  [RT2] [RT3] a hard deadline was measured",
            "   [VALIDATION] impossible reading thrown away   [OBSERVE] immediate alarm reaction",
            "   ##### SCENARIO #####  a Postman folder starts;  ---- SUMMARY ----  what it did",
            "",
            " Keys: 1-5 scenarios F1-F5   6 repair all   r reset   7 status   8 timing",
            "       s summary   h this help   9 menu   0 exit",
            "==============================================================================================",
    };

    private static LocalDate currentDay;
    private static PrintWriter eventsFile;
    private static Path eventsPath;
    private static boolean closed;

    private Log() { }

    public static void info(String tag, String msg)     { write(Level.INFO, tag, msg); }
    public static void warn(String tag, String msg)     { write(Level.WARN, tag, msg); }
    public static void error(String tag, String msg)    { write(Level.ERROR, tag, msg); }
    public static void critical(String tag, String msg) { write(Level.CRITICAL, tag, msg); }

    /** Logs an error together with where it happened (first lines of the stack trace, CLI + full log). */
    public static synchronized void error(String tag, String msg, Throwable t) {
        write(Level.ERROR, tag, msg + " -> " + t);
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(3, st.length); i++) {
            System.out.println("                 at " + st[i]);
        }
    }

    // CONCEPT 8: synchronized - log lines from different threads never get mixed up
    public static synchronized void write(Level level, String tag, String msg) {
        LocalDateTime now = LocalDateTime.now();
        String thread = Thread.currentThread().getName();
        if (thread.length() > 11) thread = thread.substring(0, 11);
        boolean important = level != Level.INFO || IMPORTANT_TAGS.contains(tag);
        String text = important ? marker(level, tag, msg) + msg : msg;
        String time = now.format(TIME);
        String line = String.format("%s [%-11s] %-5s [%-10s] %s",
                time, thread, level == Level.CRITICAL ? "CRIT" : level.name(), tag, text);

        System.out.println(line); // CLI + full log (ConsoleCopy)
        if (important) {
            openEventsFileIfNeeded(now.toLocalDate());
            println(eventsFile, line);
            EventRecorder.record(time + "  " + String.format("%-10s", tag) + " " + text);
        }
    }

    private static String marker(Level level, String tag, String msg) {
        if (msg.contains("RECOVERED") || msg.contains("EXIT SAFE STATE") || msg.contains("repaired")
                || msg.contains("-> OK") || msg.contains("CORRECT")) {
            return "+++ ";
        }
        if (level == Level.ERROR || level == Level.CRITICAL || tag.equals("SAFE-STATE") || tag.equals("ALARM")) {
            return "!!! ";
        }
        return ">>> ";
    }

    /** First lines of every session: when it started and where its two log files are. */
    public static synchronized void sessionStart() {
        block(EQ_LINE,
                "  NEW SESSION  started " + RUN_START.format(DATE_TIME),
                "  Full log   : " + ConsoleCopy.path() + "   (exact copy of this window)",
                "  Events log : " + eventsLogPath() + "   (only inputs, events and summaries)",
                EQ_LINE);
    }

    /**
     * Box printed every time an input arrives (Postman request or menu key), BEFORE the system reacts:
     * what arrived, what it means and what should happen. The reaction lines follow below the box.
     */
    public static synchronized void input(String source, String request, String meaning, String expected) {
        String time = LocalDateTime.now().format(TIME);
        block("", INPUT_TITLE,
                "  " + time + "  from " + source + ":  " + request,
                "  WHAT IT MEANS : " + meaning,
                "  EXPECTED      : " + expected,
                "  SYSTEM REACTION (lines below):",
                BOX_LINE);
        EventRecorder.record(time + "  INPUT      " + request + "  -> " + meaning);
    }

    /** Box for an input that was refused (invalid data). Nothing in the system changes. */
    public static synchronized void inputRejected(String source, String request, String reason) {
        String time = LocalDateTime.now().format(TIME);
        block("", INPUT_TITLE,
                "  " + time + "  from " + source + ":  " + request,
                "  RESULT        : REJECTED - " + reason,
                "  SYSTEM REACTION: none - invalid input is refused, the greenhouse keeps running unchanged",
                EQ_LINE);
        EventRecorder.record(time + "  INPUT      " + request + "  -> REJECTED: " + reason);
    }

    /** Legend printed at start-up (and with menu key h): how to read the CLI. */
    public static synchronized void howToRead() {
        System.out.println();
        for (String l : LEGEND) System.out.println(l);
        System.out.println();
    }

    /** Big banner printed when a scenario starts (first request of every Postman folder). */
    public static synchronized void banner(String title, String expected, String source) {
        String time = LocalDateTime.now().format(TIME);
        block("", HASH_LINE,
                "##  SCENARIO: " + title + "   (start of a new test - the inputs of this scenario follow below)",
                "##  " + time + "  from " + source,
                "##  EXPECTED: " + expected,
                HASH_LINE);
    }

    /** Numbered summary of everything important that happened in the scenario (last request of every folder). */
    public static synchronized void summary(String title, long durationMs, List<String> events, int dropped,
                                            String source) {
        String time = LocalDateTime.now().format(TIME);
        block("", BOX_LINE,
                "  SUMMARY: " + title + "   (" + Log.f1(durationMs / 1000.0) + " s, " + events.size()
                        + " events) - everything important that happened in this scenario:",
                "  " + time + "  from " + source,
                BOX_LINE);
        if (events.isEmpty()) {
            block("   (no important events - the system stayed in normal operation)");
        }
        for (int i = 0; i < events.size(); i++) {
            block(String.format("  %2d. %s", i + 1, events.get(i)));
        }
        if (dropped > 0) block("  ... " + dropped + " more events (see the events log file)");
        block(BOX_LINE, "");
    }

    /** Lines that go to the CLI (+ full log) and to the events log. */
    private static void block(String... lines) {
        openEventsFileIfNeeded(LocalDate.now());
        for (String l : lines) {
            System.out.println(l);
            println(eventsFile, l);
        }
    }

    /** Plain console text without timestamp (used only for the interactive menu). */
    public static synchronized void console(String text) {
        System.out.println(text);
    }

    private static void println(PrintWriter w, String line) {
        if (w != null) {
            w.println(line);
            w.flush();
        }
    }

    // ---------------------------------------------------------------- events file

    /** Opens this run's events file; after midnight the run continues in the new date folder. */
    private static void openEventsFileIfNeeded(LocalDate day) {
        if (closed || day.equals(currentDay)) return;
        boolean continued = currentDay != null;
        if (eventsFile != null) {
            println(eventsFile, "(continued in the folder logs/" + day + ")");
            eventsFile.close();
        }
        currentDay = day;
        eventsPath = LOG_DIR.resolve(day.toString()).resolve("events_" + RUN_NAME + ".log");
        try {
            Files.createDirectories(eventsPath.getParent());
            eventsFile = new PrintWriter(new FileWriter(eventsPath.toFile(), true));
        } catch (IOException e) {
            eventsFile = null; // logging to file failed -> keep running, console logging still works
            System.out.println("WARNING: cannot write log file " + eventsPath + ": " + e.getMessage());
            return;
        }
        println(eventsFile, HASH_LINE);
        println(eventsFile, "##  SMART GREENHOUSE CONTROLLER - EVENTS LOG" + (continued ? "  (continued after midnight)" : ""));
        println(eventsFile, "##  Run started : " + RUN_START.format(DATE_TIME));
        println(eventsFile, "##  This file   : only inputs, important events, scenario boxes and summaries"
                + " (no STATUS / TIMING / CHECK lines)");
        println(eventsFile, "##  Full log    : " + ConsoleCopy.path() + "  (exact copy of the CLI window)");
        println(eventsFile, HASH_LINE);
        println(eventsFile, "");
        for (String l : LEGEND) println(eventsFile, l.replace("HOW TO READ THIS SCREEN", "HOW TO READ THIS LOG   "));
        println(eventsFile, "");
    }

    /** Where this run's events log is written, e.g. logs/2026-11-05/events_16-30-02.log */
    public static synchronized String eventsLogPath() {
        openEventsFileIfNeeded(LocalDate.now());
        return eventsPath == null ? "-" : eventsPath.toString().replace('\\', '/');
    }

    /** Called at the end of the clean shutdown: prints RUN ENDED (CLI + both files) and closes the files. */
    public static synchronized void close() {
        block("", HASH_LINE,
                "##  RUN ENDED " + LocalDateTime.now().format(DATE_TIME) + " - clean shutdown, log files closed",
                HASH_LINE);
        closed = true;
        if (eventsFile != null) eventsFile.close();
        eventsFile = null;
        ConsoleCopy.closeFile();
    }

    /** Number with 1 decimal, always with a dot (independent of the PC language settings). */
    public static String f1(double v) { return String.format(Locale.US, "%.1f", v); }

    public static String f3(double v) { return String.format(Locale.US, "%.3f", v); }
}
